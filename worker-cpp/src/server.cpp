// worker-server: the C++ inference worker as a gRPC service (Step 3).
//
//   worker-server --model models/yolov8n.onnx --port 50061 --worker-id worker-1
//                 [--threads 2] [--scheduler 127.0.0.1:50051] [--advertise 127.0.0.1:50061]
//                 [--max-queue 2] [--conf 0.4] [--iou 0.5] [--name default]
//
// Serves   ar.v1.InferenceWorker/Detect   (Java scheduler -> this worker, one call per frame)
// Calls    ar.v1.WorkerControl/Heartbeat  (this worker -> Java scheduler, every second)
//
// Concurrency model: ONE inference at a time per worker process.
//   - Extra calls wait in a small queue (--max-queue). Beyond that the call is rejected
//     with RESOURCE_EXHAUSTED, so the Java scheduler can send the frame to another worker.
//   - A call whose deadline passed while it was waiting is skipped, not computed:
//     nobody is waiting for that answer anymore, so the CPU goes to the next frame.
//   - Scaling = more worker processes (Step 5), not more threads fighting inside one.
//
// Two kinds of failure, kept separate on purpose:
//   - Frame-level outcome (bad image, unknown model) -> gRPC OK + FrameResult.status
//   - Transport/capacity (queue full, deadline, crash) -> a gRPC error status

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
    std::string scheduler = "127.0.0.1:50051";  // empty = no heartbeats
    std::string advertise;                      // address the scheduler should call; default 127.0.0.1:<port>
    int max_queue = 2;
    ar::DetectorOptions model;
};

// ------------------------------------------------------------------ shared worker state

struct WorkerState {
    Options opt;
    std::unique_ptr<ar::Detector> detector;
    std::mutex detector_mu;               // one inference at a time
    std::atomic<int> in_flight{0};        // running + waiting
    std::atomic<double> avg_infer_ms{0};  // moving average, read by the heartbeat thread
    std::atomic<unsigned long long> served{0}, bad_frames{0}, rejected{0}, skipped_deadline{0}, aborted{0};

    // The call whose inference is running right now, watched by the deadline watcher.
    std::mutex active_mu;
    bool active = false;
    std::chrono::system_clock::time_point active_deadline;
};

// Aborts the running inference once its call's deadline has passed.
//
// Without this, a frame nobody waits for anymore keeps the CPU busy until its inference ends,
// and every frame queued behind it is delayed too (measured in Step 4). Polls every 5 ms:
// cheap, and fine-grained enough next to ~100 ms inferences.
void deadline_watcher(WorkerState& s) {
    while (!g_stop) {
        {
            // Abort WHILE holding active_mu: the running call can only mark itself inactive (and
            // the next call can only start) after we release it, so we can never hit the wrong run.
            std::lock_guard<std::mutex> lk(s.active_mu);
            if (s.active && std::chrono::system_clock::now() > s.active_deadline) {
                s.detector->abort_current();  // repeated each poll until it lands: harmless
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}

// ------------------------------------------------------------------ InferenceWorker service

class InferenceService final : public pb::InferenceWorker::Service {
public:
    explicit InferenceService(WorkerState& s) : s_(s) {}

    grpc::Status Detect(grpc::ServerContext* ctx, const pb::Frame* frame, pb::FrameResult* result) override {
        result->set_session_id(frame->session_id());
        result->set_frame_id(frame->frame_id());
        result->set_worker_id(s_.opt.worker_id);

        // Frame-level check: this worker only has one model loaded.
        for (const auto& m : frame->models()) {
            if (m != s_.detector->name()) {
                ++s_.bad_frames;
                result->set_status(pb::BAD_FRAME);
                result->set_error("model '" + m + "' is not loaded on " + s_.opt.worker_id + " (has: " +
                                  s_.detector->name() + ")");
                return grpc::Status::OK;
            }
        }

        // Admission: 1 running + max_queue waiting. Beyond that, say so immediately.
        const int now_in_flight = ++s_.in_flight;
        struct Release {
            std::atomic<int>& n;
            ~Release() { --n; }
        } release{s_.in_flight};
        if (now_in_flight > 1 + s_.opt.max_queue) {
            ++s_.rejected;
            return grpc::Status(grpc::StatusCode::RESOURCE_EXHAUSTED, "worker queue full");
        }

        std::lock_guard<std::mutex> lk(s_.detector_mu);

        // The deadline may have passed while this call waited for the detector.
        if (ctx->IsCancelled()) {
            ++s_.skipped_deadline;
            return grpc::Status(grpc::StatusCode::DEADLINE_EXCEEDED, "deadline passed while queued");
        }

        // Let the watcher abort this inference if the deadline passes while it runs.
        {
            std::lock_guard<std::mutex> alk(s_.active_mu);
            s_.active = true;
            s_.active_deadline = ctx->deadline();  // "infinite" when the caller set none
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

// ------------------------------------------------------------------ heartbeats

void heartbeat_loop(WorkerState& s) {
    const auto& o = s.opt;
    // Reconnect backoff capped at 1 s: gRPC's default grows to 2 minutes, so after a long scheduler
    // outage (70 s in the 8.7 test) the worker came back 35 s after the scheduler - the app then got
    // "check failed" for every leaf meanwhile. Our own loop already paces the heartbeats (1/s).
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
        hb.set_queue_depth(static_cast<std::uint32_t>(n > 1 ? n - 1 : 0));
        hb.set_avg_infer_ms(static_cast<float>(s.avg_infer_ms.load()));
        hb.set_threads(static_cast<std::uint32_t>(o.model.threads));

        grpc::ClientContext ctx;
        ctx.set_deadline(std::chrono::system_clock::now() + std::chrono::milliseconds(500));
        pb::HeartbeatAck ack;
        const grpc::Status st = stub->Heartbeat(&ctx, hb, &ack);

        // Log only when the state changes, not every second.
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

}  // namespace

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
        // Warm-up: the first forward pass is much slower (allocations, kernel selection).
        // Pay it now, not on the first real frame.
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
    builder.SetMaxReceiveMessageSize(8 * 1024 * 1024);  // frames are ~50 KB; 8 MB leaves room for big test images
    builder.RegisterService(&service);
    std::unique_ptr<grpc::Server> server = builder.BuildAndStart();
    if (!server || bound_port == 0) {
        log(o.worker_id, "cannot listen on " + o.host + ":" + std::to_string(o.port) + " (port in use?)");
        return 1;
    }
    // Tests wait for this exact line.
    log(o.worker_id, "READY listening on " + o.host + ":" + std::to_string(bound_port) + ", advertised as " +
                         o.advertise + ", max queue " + std::to_string(o.max_queue));

    std::signal(SIGINT, on_signal);
    std::signal(SIGTERM, on_signal);
#ifdef SIGBREAK
    std::signal(SIGBREAK, on_signal);  // Windows: Ctrl+Break, which a parent process can send to stop us cleanly
#endif

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
