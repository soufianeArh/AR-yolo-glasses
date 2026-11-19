#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <iomanip>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>

#include <grpcpp/grpcpp.h>

#include "ar/v1/detection.grpc.pb.h"
#include "detector.h"

namespace pb = ar::v1;
using Clock = std::chrono::steady_clock;

namespace {

std::atomic<bool> g_stop{false};
void on_signal(int) { g_stop = true; }

std::string now_text() {
    const auto t = std::chrono::system_clock::now();
    const auto secs = std::chrono::system_clock::to_time_t(t);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t.time_since_epoch()).count() % 1000;
    std::tm tm{};
#ifdef _WIN32
    localtime_s(&tm, &secs);
#else
    localtime_r(&secs, &tm);
#endif
    std::ostringstream os;
    os << std::put_time(&tm, "%H:%M:%S") << '.' << std::setw(3) << std::setfill('0') << ms;
    return os.str();
}

std::mutex g_log_mu;
void log(const std::string& worker_id, const std::string& msg) {
    std::lock_guard<std::mutex> lk(g_log_mu);
    std::cerr << now_text() << " [" << worker_id << "] " << msg << std::endl;
}

struct Options {
    std::string host = "0.0.0.0";
    int port = 50061;
    std::string worker_id = "worker-1";
    std::string scheduler = "127.0.0.1:50051";
    std::string advertise;
    int max_queue = 2;
    ar::DetectorOptions model;
};

// everything the grpc threads share so counters are atomics and the model sits behind a mutex
struct WorkerState {
    Options opt;
    std::unique_ptr<ar::Detector> detector;
    // one inference at a time and the onnx threads do the parallel work inside it
    std::mutex detector_mu;
    std::atomic<int> in_flight{0};
    std::atomic<double> avg_infer_ms{0};
    std::atomic<unsigned long long> served{0}, bad_frames{0}, rejected{0}, skipped_deadline{0}, aborted{0};

    std::mutex active_mu;
    bool active = false;
    std::chrono::system_clock::time_point active_deadline;
};

// aborts the running inference once its deadline is gone
void deadline_watcher(WorkerState& s) {
    while (!g_stop) {
        {
            std::lock_guard<std::mutex> lk(s.active_mu);
            if (s.active && std::chrono::system_clock::now() > s.active_deadline) {
                s.detector->abort_current();
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}

class InferenceService final : public pb::InferenceWorker::Service {
public:
    explicit InferenceService(WorkerState& s) : s_(s) {}

    // called by the scheduler for every frame on a grpc thread
    grpc::Status Detect(grpc::ServerContext* ctx, const pb::Frame* frame, pb::FrameResult* result) override {
        result->set_session_id(frame->session_id());
        result->set_frame_id(frame->frame_id());
        result->set_worker_id(s_.opt.worker_id);

        // a frame asking for another model is refused instead of guessed
        for (const auto& m : frame->models()) {
            if (m != s_.detector->name()) {
                ++s_.bad_frames;
                result->set_status(pb::BAD_FRAME);
                result->set_error("model '" + m + "' is not loaded on " + s_.opt.worker_id + " (has: " +
                                  s_.detector->name() + ")");
                return grpc::Status::OK;
            }
        }

        const int now_in_flight = ++s_.in_flight;
        struct Release {
            std::atomic<int>& n;
            ~Release() { --n; }
        } release{s_.in_flight};
        if(now_in_flight > 1 + s_.opt.max_queue) {
            ++s_.rejected;
            // answer right away so the scheduler can try another worker
            return grpc::Status(grpc::StatusCode::RESOURCE_EXHAUSTED, "worker queue full");
        }

        // admitted frames wait here untill the one before is done
        std::lock_guard<std::mutex> lk(s_.detector_mu);

        // the deadline passed while waiting so skip it and save the cpu
        if (ctx->IsCancelled()) {
            ++s_.skipped_deadline;
            return grpc::Status(grpc::StatusCode::DEADLINE_EXCEEDED, "deadline passed while queued");
        }

        {
            std::lock_guard<std::mutex> alk(s_.active_mu);
            s_.active = true;
            s_.active_deadline = ctx->deadline();
        }
        struct Inactive {
            WorkerState& s;
            ~Inactive() {
                std::lock_guard<std::mutex> alk(s.active_mu);
                s.active = false;
            }
        } inactive{s_};

        try {
            ar::StageTiming t;
            const auto& jpeg = frame->jpeg();
            const auto dets = s_.detector->detect_encoded(reinterpret_cast<const std::uint8_t*>(jpeg.data()),
                                                          jpeg.size(), &t);
            for (const auto& d : dets) {
                pb::Detection* out = result->add_detections();
                out->set_label(d.label);
                out->set_confidence(d.confidence);
                out->set_x1(d.x1);
                out->set_y1(d.y1);
                out->set_x2(d.x2);
                out->set_y2(d.y2);
                out->set_model(d.model);
            }
            const double total = t.total();
            result->set_infer_ms(static_cast<std::uint32_t>(total + 0.5));
            result->set_status(pb::OK);

            const double prev = s_.avg_infer_ms.load();
            // moving average that goes out in the heartbeat
            s_.avg_infer_ms.store(prev == 0 ? total : prev * 0.8 + total * 0.2);
            ++s_.served;
        } catch (const ar::BadFrame& e) {
            ++s_.bad_frames;
            result->set_status(pb::BAD_FRAME);
            result->set_error(e.what());
        } catch (const ar::Aborted&) {
            ++s_.aborted;
            return grpc::Status(grpc::StatusCode::DEADLINE_EXCEEDED, "inference aborted: deadline passed");
        } catch (const std::exception& e) {
            log(s_.opt.worker_id, std::string("inference failed: ") + e.what());
            return grpc::Status(grpc::StatusCode::INTERNAL, e.what());
        }
        return grpc::Status::OK;
    }

private:
    WorkerState& s_;
};

// tells the scheduler every second that this worker is alive and how busy it is
void heartbeat_loop(WorkerState& s) {
    const auto& o = s.opt;
    // default backoff grows to two minutes so after a long scheduler outage
    // the worker came back way too late
    grpc::ChannelArguments args;
    args.SetInt(GRPC_ARG_INITIAL_RECONNECT_BACKOFF_MS, 500);
    args.SetInt(GRPC_ARG_MIN_RECONNECT_BACKOFF_MS, 500);
    args.SetInt(GRPC_ARG_MAX_RECONNECT_BACKOFF_MS, 1000);
    auto stub = pb::WorkerControl::NewStub(
        grpc::CreateCustomChannel(o.scheduler, grpc::InsecureChannelCredentials(), args));

    bool connected = false, first = true, warned_rejected = false;
    while (!g_stop) {
        pb::WorkerHeartbeat hb;
        hb.set_worker_id(o.worker_id);
        hb.set_address(o.advertise);
        hb.add_models(s.detector->name());
        const int n = s.in_flight.load();
        hb.set_busy(n > 0);
        // frames waiting behind the one that runs
        hb.set_queue_depth(static_cast<std::uint32_t>(n > 1 ? n - 1 : 0));
        hb.set_avg_infer_ms(static_cast<float>(s.avg_infer_ms.load()));
        hb.set_threads(static_cast<std::uint32_t>(o.model.threads));

        grpc::ClientContext ctx;
        ctx.set_deadline(std::chrono::system_clock::now() + std::chrono::milliseconds(500));
        pb::HeartbeatAck ack;
        const grpc::Status st = stub->Heartbeat(&ctx, hb, &ack);

        if (st.ok()) {
            if (!connected) log(o.worker_id, "scheduler " + o.scheduler + " reachable, heartbeats flowing");
            connected = true;
            if (!ack.accepted() && !warned_rejected) {
                log(o.worker_id, "WARNING: scheduler rejected this worker (duplicate worker id?)");
                warned_rejected = true;
            }
        } else {
            if (connected || first) {
                log(o.worker_id, "scheduler " + o.scheduler + " unreachable (" + st.error_message() +
                                     "), still serving, retrying every second");
            }
            connected = false;
        }
        first = false;

        for (int i = 0; i < 10 && !g_stop; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

int usage() {
    std::cerr << "usage: worker-server --model <file.onnx> [--port 50061] [--host 0.0.0.0] [--worker-id worker-1]\n"
                 "                     [--threads 2] [--scheduler 127.0.0.1:50051 | --scheduler none]\n"
                 "                     [--advertise 127.0.0.1:<port>] [--max-queue 2]\n"
                 "                     [--conf 0.4] [--iou 0.5] [--name default]\n"
                 "                     [--model-type auto|yolov8|yolos]   (auto = read from the model file)\n";
    return 2;
}

}

int main(int argc, char** argv) {
    Options o;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (i + 1 >= argc) return usage();
        const std::string v = argv[++i];
        try {
            if (a == "--model") o.model.model_path = v;
            else if (a == "--name") o.model.name = v;
            else if (a == "--threads") o.model.threads = std::stoi(v);
            else if (a == "--model-type") o.model.model_type = v;
            else if (a == "--conf") o.model.conf_threshold = std::stof(v);
            else if (a == "--iou") o.model.iou_threshold = std::stof(v);
            else if (a == "--port") o.port = std::stoi(v);
            else if (a == "--host") o.host = v;
            else if (a == "--worker-id") o.worker_id = v;
            else if (a == "--scheduler") o.scheduler = (v == "none") ? "" : v;
            else if (a == "--advertise") o.advertise = v;
            else if (a == "--max-queue") o.max_queue = std::stoi(v);
            else return usage();
        } catch (const std::exception&) {
            return usage();
        }
    }
    if (o.model.model_path.empty()) return usage();
    if (o.advertise.empty()) o.advertise = "127.0.0.1:" + std::to_string(o.port);

    WorkerState state;
    state.opt = o;
    try {
        const auto t0 = Clock::now();
        state.detector = ar::load_detector(o.model);
        // one warmup inference on a gray image so the first real frame is not slow
        state.detector->detect(cv::Mat(480, 640, CV_8UC3, cv::Scalar(114, 114, 114)));
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count();
        log(o.worker_id, "model '" + o.model.name + "' loaded + warmed up in " + std::to_string(ms) + " ms (" +
                             std::to_string(o.model.threads) + " threads, " +
                             std::to_string(state.detector->class_names().size()) + " classes)");
    } catch (const std::exception& e) {
        log(o.worker_id, std::string("cannot load model: ") + e.what());
        return 1;
    }

    InferenceService service(state);
    grpc::ServerBuilder builder;
    int bound_port = 0;
    builder.AddListeningPort(o.host + ":" + std::to_string(o.port), grpc::InsecureServerCredentials(), &bound_port);
    builder.SetMaxReceiveMessageSize(8 * 1024 * 1024);
    builder.RegisterService(&service);
    std::unique_ptr<grpc::Server> server = builder.BuildAndStart();
    if (!server || bound_port == 0) {
        log(o.worker_id, "cannot listen on " + o.host + ":" + std::to_string(o.port) + " (port in use?)");
        return 1;
    }
    log(o.worker_id, "READY listening on " + o.host + ":" + std::to_string(bound_port) + ", advertised as " +
                         o.advertise + ", max queue " + std::to_string(o.max_queue));

    std::signal(SIGINT, on_signal);
    std::signal(SIGTERM, on_signal);
#ifdef SIGBREAK
    std::signal(SIGBREAK, on_signal);
#endif

    // the watcher and the heartbeats run next to the grpc threads
    std::thread watcher(deadline_watcher, std::ref(state));
    std::thread hb;
    if (!o.scheduler.empty()) hb = std::thread(heartbeat_loop, std::ref(state));
    else log(o.worker_id, "no scheduler configured, heartbeats off");

    while (!g_stop) std::this_thread::sleep_for(std::chrono::milliseconds(100));

    log(o.worker_id, "shutting down");
    server->Shutdown(std::chrono::system_clock::now() + std::chrono::seconds(2));
    if (hb.joinable()) hb.join();
    watcher.join();
    log(o.worker_id, "stopped. served=" + std::to_string(state.served) + " bad_frames=" +
                         std::to_string(state.bad_frames) + " rejected=" + std::to_string(state.rejected) +
                         " skipped_deadline=" + std::to_string(state.skipped_deadline) +
                         " aborted=" + std::to_string(state.aborted));
    return 0;
}
