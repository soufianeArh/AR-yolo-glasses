#include "yolov8_detector.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <numeric>
#include <regex>
#include <stdexcept>
#include <utility>

#include <onnxruntime_cxx_api.h>
#include <opencv2/imgproc.hpp>

namespace ar {
namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// Python's round() rounds half to even. std::nearbyint does the same under the
// default floating-point rounding mode. Used wherever Ultralytics calls round(),
// so the letterbox size and padding come out to exactly the same pixel.
int py_round(double v) { return static_cast<int>(std::nearbyint(v)); }

// One process-wide ONNX Runtime environment, shared by every detector instance.
Ort::Env& ort_env() {
    static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "ar-worker");
    return env;
}

// Ultralytics writes class names into the ONNX metadata as a Python dict literal:
//   "{0: 'person', 1: 'bicycle', ...}"
std::vector<std::string> parse_names(const std::string& text) {
    static const std::regex entry(R"((\d+)\s*:\s*['"]([^'"]*)['"])");
    std::vector<std::pair<int, std::string>> pairs;
    for (auto it = std::sregex_iterator(text.begin(), text.end(), entry); it != std::sregex_iterator(); ++it) {
        pairs.emplace_back(std::stoi((*it)[1].str()), (*it)[2].str());
    }
    if (pairs.empty()) throw std::runtime_error("model metadata 'names' is empty or unreadable");

    int max_id = 0;
    for (const auto& p : pairs) max_id = std::max(max_id, p.first);
    std::vector<std::string> names(static_cast<std::size_t>(max_id) + 1);
    for (auto& p : pairs) names[static_cast<std::size_t>(p.first)] = std::move(p.second);
    return names;
}

// A candidate box in letterboxed (model input) pixel coordinates.
struct Candidate {
    float x1, y1, x2, y2;
    float score;
    int cls;
};

float iou(const Candidate& a, const Candidate& b) {
    const float iw = std::max(0.f, std::min(a.x2, b.x2) - std::max(a.x1, b.x1));
    const float ih = std::max(0.f, std::min(a.y2, b.y2) - std::max(a.y1, b.y1));
    const float inter = iw * ih;
    const float area_a = (a.x2 - a.x1) * (a.y2 - a.y1);
    const float area_b = (b.x2 - b.x1) * (b.y2 - b.y1);
    const float uni = area_a + area_b - inter;
    return uni > 0.f ? inter / uni : 0.f;
}

// Greedy NMS, highest score first, per class (boxes of different classes never
// suppress each other - Ultralytics' default, agnostic_nms=False).
std::vector<std::size_t> nms(const std::vector<Candidate>& c, float iou_threshold, int max_keep) {
    std::vector<std::size_t> order(c.size());
    std::iota(order.begin(), order.end(), std::size_t{0});
    std::stable_sort(order.begin(), order.end(),
                     [&](std::size_t a, std::size_t b) { return c[a].score > c[b].score; });

    std::vector<char> removed(c.size(), 0);
    std::vector<std::size_t> keep;
    for (std::size_t oi = 0; oi < order.size(); ++oi) {
        const std::size_t i = order[oi];
        if (removed[i]) continue;
        keep.push_back(i);
        if (static_cast<int>(keep.size()) >= max_keep) break;
        for (std::size_t oj = oi + 1; oj < order.size(); ++oj) {
            const std::size_t j = order[oj];
            if (!removed[j] && c[j].cls == c[i].cls && iou(c[i], c[j]) > iou_threshold) removed[j] = 1;
        }
    }
    return keep;
}

}  // namespace

// ------------------------------------------------------------------ ONNX Runtime state

struct YoloV8Detector::Impl {
    std::unique_ptr<Ort::Session> session;
    std::string input_name, output_name;
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
};

YoloV8Detector::YoloV8Detector(YoloV8Config config) : impl_(std::make_unique<Impl>()), config_(std::move(config)) {
    if (config_.threads < 1) throw std::invalid_argument("threads must be >= 1");

    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(config_.threads);  // threads for one forward pass
    options.SetInterOpNumThreads(1);                // one graph branch at a time
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    // path::c_str() is wchar_t on Windows and char on Linux - exactly what ORTCHAR_T is.
    const std::filesystem::path path(config_.model_path);
    if (!std::filesystem::exists(path)) throw std::runtime_error("model file not found: " + config_.model_path);
    impl_->session = std::make_unique<Ort::Session>(ort_env(), path.c_str(), options);
    Ort::Session& s = *impl_->session;

    Ort::AllocatorWithDefaultOptions alloc;
    impl_->input_name = s.GetInputNameAllocated(0, alloc).get();
    impl_->output_name = s.GetOutputNameAllocated(0, alloc).get();

    // Input must be a FIXED [1, 3, H, W] - the model was exported that way (Step 0).
    const auto in_shape = s.GetInputTypeInfo(0).GetTensorTypeAndShapeInfo().GetShape();
    if (in_shape.size() != 4 || in_shape[0] != 1 || in_shape[1] != 3 || in_shape[2] <= 0 || in_shape[3] <= 0) {
        throw std::runtime_error("expected a fixed [1,3,H,W] model input");
    }
    input_h_ = static_cast<int>(in_shape[2]);
    input_w_ = static_cast<int>(in_shape[3]);

    // Output: [1, 4 + num_classes, num_candidates], e.g. [1, 84, 8400].
    const auto out_shape = s.GetOutputTypeInfo(0).GetTensorTypeAndShapeInfo().GetShape();
    if (out_shape.size() != 3 || out_shape[0] != 1 || out_shape[1] <= 4 || out_shape[2] <= 0) {
        throw std::runtime_error("expected a [1, 4+classes, candidates] model output");
    }
    num_classes_ = static_cast<int>(out_shape[1]) - 4;
    num_candidates_ = static_cast<int>(out_shape[2]);

    const auto meta = s.GetModelMetadata();
    const auto names = meta.LookupCustomMetadataMapAllocated("names", alloc);
    if (!names) throw std::runtime_error("model has no 'names' metadata (not an Ultralytics export?)");
    names_ = parse_names(names.get());
    if (static_cast<int>(names_.size()) != num_classes_) {
        throw std::runtime_error("metadata has " + std::to_string(names_.size()) + " class names but the output has " +
                                 std::to_string(num_classes_) + " classes");
    }

    input_.resize(static_cast<std::size_t>(3) * input_h_ * input_w_);
}

YoloV8Detector::~YoloV8Detector() = default;

// ------------------------------------------------------------------ detect

std::vector<Detection> YoloV8Detector::detect(const cv::Mat& bgr, StageTiming* timing) {
    if (bgr.empty() || bgr.type() != CV_8UC3) throw BadFrame("expected a non-empty 8-bit BGR image");
    const int img_w = bgr.cols, img_h = bgr.rows;

    // ---------------------------------------------------------- 1. letterbox
    // Same arithmetic as Ultralytics LetterBox(auto=False, center=True, scaleup=True).
    auto t0 = Clock::now();
    const double r = std::min(static_cast<double>(input_h_) / img_h, static_cast<double>(input_w_) / img_w);
    const int new_w = std::max(py_round(img_w * r), 1);
    const int new_h = std::max(py_round(img_h * r), 1);
    const double dw = (input_w_ - new_w) / 2.0;
    const double dh = (input_h_ - new_h) / 2.0;
    const int top = py_round(dh - 0.1), bottom = py_round(dh + 0.1);
    const int left = py_round(dw - 0.1), right = py_round(dw + 0.1);

    cv::Mat resized;
    if (new_w != img_w || new_h != img_h) {
        cv::resize(bgr, resized, cv::Size(new_w, new_h), 0, 0, cv::INTER_LINEAR);
    } else {
        resized = bgr;
    }
    cv::Mat boxed;
    cv::copyMakeBorder(resized, boxed, top, bottom, left, right, cv::BORDER_CONSTANT, cv::Scalar(114, 114, 114));
    if (boxed.cols != input_w_ || boxed.rows != input_h_) throw std::logic_error("letterbox produced the wrong size");

    // ---------------------------------------------------------- 2. HWC BGR uint8 -> CHW RGB float /255
    const int hw = input_w_ * input_h_;
    float* out_r = input_.data();
    float* out_g = out_r + hw;
    float* out_b = out_g + hw;
    for (int y = 0; y < input_h_; ++y) {
        const std::uint8_t* row = boxed.ptr<std::uint8_t>(y);
        const int base = y * input_w_;
        for (int x = 0; x < input_w_; ++x) {
            out_b[base + x] = row[3 * x + 0] / 255.f;
            out_g[base + x] = row[3 * x + 1] / 255.f;
            out_r[base + x] = row[3 * x + 2] / 255.f;
        }
    }
    if (timing) timing->preprocess_ms = ms_since(t0);

    // ---------------------------------------------------------- 3. forward pass
    t0 = Clock::now();
    const std::array<std::int64_t, 4> shape{1, 3, input_h_, input_w_};
    Ort::Value in = Ort::Value::CreateTensor<float>(impl_->memory, input_.data(), input_.size(), shape.data(),
                                                    shape.size());
    const char* in_names[] = {impl_->input_name.c_str()};
    const char* out_names[] = {impl_->output_name.c_str()};
    auto outputs = impl_->session->Run(Ort::RunOptions{nullptr}, in_names, &in, 1, out_names, 1);
    const float* out = outputs[0].GetTensorData<float>();
    if (timing) timing->infer_ms = ms_since(t0);

    // ---------------------------------------------------------- 4. decode candidates
    // Layout [1, 84, 8400]: row 0..3 = cx, cy, w, h; rows 4..83 = one score per class.
    // YOLOv8 has no separate objectness score - the best class score IS the confidence.
    t0 = Clock::now();
    const int n = num_candidates_;
    std::vector<Candidate> cands;
    for (int a = 0; a < n; ++a) {
        int best = 0;
        float best_score = out[4 * n + a];
        for (int c = 1; c < num_classes_; ++c) {
            const float s = out[(4 + c) * n + a];
            if (s > best_score) {
                best_score = s;
                best = c;
            }
        }
        if (!(best_score > config_.conf_threshold)) continue;  // strictly greater, like Ultralytics
        const float cx = out[0 * n + a], cy = out[1 * n + a];
        const float half_w = out[2 * n + a] / 2.f, half_h = out[3 * n + a] / 2.f;
        cands.push_back({cx - half_w, cy - half_h, cx + half_w, cy + half_h, best_score, best});
    }

    // ---------------------------------------------------------- 5. NMS
    const auto keep = nms(cands, config_.iou_threshold, config_.max_detections);

    // ---------------------------------------------------------- 6. back to the original image, normalized
    // Same as Ultralytics scale_boxes(): undo the padding, then the per-axis resize gain.
    const double gain_x = static_cast<double>(new_w) / img_w;
    const double gain_y = static_cast<double>(new_h) / img_h;
    const int pad_x = py_round((input_w_ - new_w) / 2.0 - 0.1);
    const int pad_y = py_round((input_h_ - new_h) / 2.0 - 0.1);
    auto to_x = [&](float v) {
        const double px = std::clamp((v - pad_x) / gain_x, 0.0, static_cast<double>(img_w));
        return static_cast<float>(px / img_w);
    };
    auto to_y = [&](float v) {
        const double py = std::clamp((v - pad_y) / gain_y, 0.0, static_cast<double>(img_h));
        return static_cast<float>(py / img_h);
    };

    std::vector<Detection> result;
    result.reserve(keep.size());
    for (const std::size_t i : keep) {
        const Candidate& c = cands[i];
        result.push_back({names_[static_cast<std::size_t>(c.cls)], c.score, to_x(c.x1), to_y(c.y1), to_x(c.x2),
                          to_y(c.y2), config_.name});
    }
    if (timing) timing->postprocess_ms = ms_since(t0);
    return result;
}

}  // namespace ar
