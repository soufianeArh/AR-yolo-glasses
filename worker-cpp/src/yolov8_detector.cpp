#include "yolov8_detector.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <numeric>
#include <stdexcept>
#include <utility>

#include <opencv2/imgproc.hpp>

namespace ar {
namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

int py_round(double v) { return static_cast<int>(std::nearbyint(v)); }

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

// greedy nms per class nothing fancy
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

}

YoloV8Detector::YoloV8Detector(DetectorOptions options)
    : opt_(std::move(options)), model_(opt_.model_path, opt_.threads) {
    const auto in_shape = model_.input_shape();
    if (in_shape.size() != 4 || in_shape[0] != 1 || in_shape[1] != 3 || in_shape[2] <= 0 || in_shape[3] <= 0) {
        throw std::runtime_error("expected a fixed [1,3,H,W] model input");
    }
    input_h_ = static_cast<int>(in_shape[2]);
    input_w_ = static_cast<int>(in_shape[3]);

    const auto out_shape = model_.output_shape(0);
    if (out_shape.size() != 3 || out_shape[0] != 1 || out_shape[1] <= 4 || out_shape[2] <= 0) {
        throw std::runtime_error("expected a [1, 4+classes, candidates] model output");
    }
    num_classes_ = static_cast<int>(out_shape[1]) - 4;
    num_candidates_ = static_cast<int>(out_shape[2]);

    const auto names = model_.metadata("names");
    if (!names) throw std::runtime_error("model has no 'names' metadata (not an Ultralytics export?)");
    names_ = parse_names(*names);
    if (static_cast<int>(names_.size()) != num_classes_) {
        throw std::runtime_error("metadata has " + std::to_string(names_.size()) + " class names but the output has " +
                                 std::to_string(num_classes_) + " classes");
    }
    input_.resize(static_cast<std::size_t>(3) * input_h_ * input_w_);
}

// letterbox keeps the aspect ratio and pads with gray
std::vector<Detection> YoloV8Detector::detect(const cv::Mat& bgr, StageTiming* timing) {
    if (bgr.empty() || bgr.type() != CV_8UC3) throw BadFrame("expected a non-empty 8-bit BGR image");
    const int img_w = bgr.cols, img_h = bgr.rows;

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

    t0 = Clock::now();
    const auto outputs = model_.run(input_.data(), {1, 3, input_h_, input_w_});
    const float* out = outputs[0].data;
    if (timing) timing->infer_ms = ms_since(t0);

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
        if (!(best_score > opt_.conf_threshold)) continue;
        const float cx = out[0 * n + a], cy = out[1 * n + a];
        const float half_w = out[2 * n + a] / 2.f, half_h = out[3 * n + a] / 2.f;
        cands.push_back({cx - half_w, cy - half_h, cx + half_w, cy + half_h, best_score, best});
    }

    const auto keep = nms(cands, opt_.iou_threshold, opt_.max_detections);

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
                          to_y(c.y2), opt_.name});
    }
    if (timing) timing->postprocess_ms = ms_since(t0);
    return result;
}

}
