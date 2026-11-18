#include "yolos_detector.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <sstream>
#include <stdexcept>
#include <utility>

namespace ar {
namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// ------------------------------------------------------------------ antialiased resize
// A port of torchvision's uint8 bilinear resize with antialias=True (the path the Python
// processor takes on a CPU): a separable triangle filter whose width grows with the shrink
// factor (every input pixel contributes when shrinking), weights turned into 16-bit FIXED-POINT
// integers, horizontal pass first, uint8 image in between, then the vertical pass.
// Checked against torchvision pixel by pixel: identical on 14/15 test images; on an extreme 5x
// shrink, 0.25% of pixels differ by 1 level (out of 255).

struct Taps {
    std::vector<int> start;        // first input index for each output index
    std::vector<int> count;        // number of taps
    std::vector<std::int32_t> w;   // fixed-point weights, count[i] of them at i * max_taps
    int max_taps = 0;
    int precision = 0;             // weights are real_weight * 2^precision
};

Taps make_taps(int in_size, int out_size) {
    const double scale = static_cast<double>(in_size) / out_size;
    const double support = scale >= 1.0 ? scale : 1.0;     // bilinear: half-width 1, scaled when shrinking
    const double invscale = scale >= 1.0 ? 1.0 / scale : 1.0;
    Taps t;
    t.max_taps = static_cast<int>(std::ceil(support)) * 2 + 1;
    t.start.resize(out_size);
    t.count.resize(out_size);
    std::vector<double> real(static_cast<std::size_t>(out_size) * t.max_taps, 0.0);
    double wmax = 0;
    for (int i = 0; i < out_size; ++i) {
        const double center = scale * (i + 0.5);
        const int xmin = std::max(static_cast<int>(center - support + 0.5), 0);
        const int n = std::min(static_cast<int>(center + support + 0.5), in_size) - xmin;
        double total = 0;
        for (int k = 0; k < n; ++k) {
            const double v = std::max(0.0, 1.0 - std::abs((k + xmin - center + 0.5) * invscale));  // triangle
            real[static_cast<std::size_t>(i) * t.max_taps + k] = v;
            total += v;
        }
        for (int k = 0; k < n; ++k) {
            double& v = real[static_cast<std::size_t>(i) * t.max_taps + k];
            v = total > 0 ? v / total : 0;
            wmax = std::max(wmax, v);
        }
        t.start[i] = xmin;
        t.count[i] = n;
    }
    // Largest precision whose biggest weight still fits a signed 16-bit integer.
    while (t.precision < 15 && static_cast<int>(0.5 + wmax * (1 << (t.precision + 1))) < (1 << 15)) ++t.precision;
    t.w.resize(real.size());
    for (std::size_t j = 0; j < real.size(); ++j) {
        t.w[j] = static_cast<std::int32_t>(std::floor(0.5 + real[j] * (1 << t.precision)));
    }
    return t;
}

std::uint8_t to_u8(std::int32_t acc, int precision) {
    const std::int32_t v = acc >> precision;
    return static_cast<std::uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

cv::Mat resize_antialias(const cv::Mat& src, int out_w, int out_h) {
    cv::Mat cur = src;
    if (out_w != src.cols) {                               // horizontal pass first
        const Taps th = make_taps(src.cols, out_w);
        const std::int32_t half = 1 << (th.precision - 1);
        cv::Mat mid(src.rows, out_w, CV_8UC3);
        for (int y = 0; y < src.rows; ++y) {
            const std::uint8_t* s = src.ptr<std::uint8_t>(y);
            std::uint8_t* d = mid.ptr<std::uint8_t>(y);
            for (int x = 0; x < out_w; ++x) {
                const std::int32_t* w = &th.w[static_cast<std::size_t>(x) * th.max_taps];
                std::int32_t a = half, b = half, c = half;
                for (int k = 0; k < th.count[x]; ++k) {
                    const std::uint8_t* p = s + 3 * (th.start[x] + k);
                    a += w[k] * p[0];
                    b += w[k] * p[1];
                    c += w[k] * p[2];
                }
                d[3 * x] = to_u8(a, th.precision);
                d[3 * x + 1] = to_u8(b, th.precision);
                d[3 * x + 2] = to_u8(c, th.precision);
            }
        }
        cur = mid;
    }
    if (out_h != cur.rows) {                               // then vertical
        const Taps tv = make_taps(cur.rows, out_h);
        const std::int32_t half = 1 << (tv.precision - 1);
        cv::Mat dst(out_h, cur.cols, CV_8UC3);
        for (int y = 0; y < out_h; ++y) {
            const std::int32_t* w = &tv.w[static_cast<std::size_t>(y) * tv.max_taps];
            std::uint8_t* d = dst.ptr<std::uint8_t>(y);
            for (int x = 0; x < 3 * cur.cols; ++x) {
                std::int32_t acc = half;
                for (int k = 0; k < tv.count[y]; ++k) acc += w[k] * cur.ptr<std::uint8_t>(tv.start[y] + k)[x];
                d[x] = to_u8(acc, tv.precision);
            }
        }
        cur = dst;
    }
    return cur;
}

std::vector<float> parse_floats(const std::string& csv) {
    std::vector<float> out;
    std::stringstream ss(csv);
    std::string item;
    while (std::getline(ss, item, ',')) out.push_back(std::stof(item));
    return out;
}

int int_after(const std::string& text, const std::string& key, int fallback) {
    const auto p = text.find(key + "=");
    return p == std::string::npos ? fallback : std::stoi(text.substr(p + key.size() + 1));
}

}  // namespace

// Port of transformers' get_size_with_aspect_ratio(image_size, size, max_size, mod_size).
// Python int() truncates toward zero, round() rounds half to even (std::nearbyint).
std::pair<int, int> YolosDetector::input_size(int height, int width, int shortest, int longest, int divisor) {
    double size = shortest;
    double raw_size = -1;
    const double min_orig = std::min(height, width), max_orig = std::max(height, width);
    if (max_orig / min_orig * size > longest) {
        raw_size = longest * min_orig / max_orig;
        size = std::nearbyint(raw_size);
    }
    int oh, ow;
    if (width < height) {
        ow = static_cast<int>(size);
        oh = static_cast<int>(raw_size >= 0 ? raw_size * height / width : size * height / width);
    } else if ((height <= width && height == size) || (width <= height && width == size)) {
        oh = height;
        ow = width;
    } else {
        oh = static_cast<int>(size);
        ow = static_cast<int>(raw_size >= 0 ? raw_size * width / height : size * width / height);
    }
    return {oh - oh % divisor, ow - ow % divisor};
}

YolosDetector::YolosDetector(DetectorOptions options)
    : opt_(std::move(options)), model_(opt_.model_path, opt_.threads) {
    const auto in_shape = model_.input_shape();
    if (in_shape.size() != 4 || in_shape[1] != 3) throw std::runtime_error("expected a [1,3,H,W] model input");
    if (model_.output_count() < 2) throw std::runtime_error("expected logits and pred_boxes outputs");

    const auto names = model_.metadata("names");
    if (!names) throw std::runtime_error("model has no 'names' metadata (export it with tools/export_plant_onnx.py)");
    names_ = parse_names(*names);
    if (const auto r = model_.metadata("resize")) {
        shortest_ = int_after(*r, "shortest_edge", shortest_);
        longest_ = int_after(*r, "longest_edge", longest_);
        divisor_ = int_after(*r, "divisor", divisor_);
    }
    if (const auto m = model_.metadata("image_mean")) {
        const auto v = parse_floats(*m);
        if (v.size() == 3) std::copy(v.begin(), v.end(), mean_);
    }
    if (const auto s = model_.metadata("image_std")) {
        const auto v = parse_floats(*s);
        if (v.size() == 3) std::copy(v.begin(), v.end(), std_);
    }
    const auto logits_shape = model_.output_shape(0);
    if (logits_shape.size() == 3 && logits_shape[2] > 0 &&
        static_cast<std::size_t>(logits_shape[2]) != names_.size() + 1) {
        throw std::runtime_error("metadata has " + std::to_string(names_.size()) + " class names but logits have " +
                                 std::to_string(logits_shape[2]) + " (expected names + 1 'no object')");
    }
}

std::vector<Detection> YolosDetector::detect(const cv::Mat& bgr, StageTiming* timing) {
    if (bgr.empty() || bgr.type() != CV_8UC3) throw BadFrame("expected a non-empty 8-bit BGR image");

    // ---------------------------------------------------------- 1. resize (antialiased) + normalise
    auto t0 = Clock::now();
    const auto [oh, ow] = input_size(bgr.rows, bgr.cols, shortest_, longest_, divisor_);
    if (oh <= 0 || ow <= 0) throw BadFrame("image too small");
    const cv::Mat resized = (oh == bgr.rows && ow == bgr.cols) ? bgr : resize_antialias(bgr, ow, oh);

    const int hw = oh * ow;
    input_.resize(static_cast<std::size_t>(3) * hw);
    float* out_r = input_.data();
    float* out_g = out_r + hw;
    float* out_b = out_g + hw;
    const float inv255 = 1.0f / 255.0f;
    for (int y = 0; y < oh; ++y) {
        const std::uint8_t* row = resized.ptr<std::uint8_t>(y);
        for (int x = 0; x < ow; ++x) {
            const int i = y * ow + x;
            out_r[i] = (row[3 * x + 2] * inv255 - mean_[0]) / std_[0];
            out_g[i] = (row[3 * x + 1] * inv255 - mean_[1]) / std_[1];
            out_b[i] = (row[3 * x + 0] * inv255 - mean_[2]) / std_[2];
        }
    }
    if (timing) timing->preprocess_ms = ms_since(t0);

    // ---------------------------------------------------------- 2. forward pass (abortable)
    t0 = Clock::now();
    const auto outputs = model_.run(input_.data(), {1, 3, oh, ow});
    if (timing) timing->infer_ms = ms_since(t0);

    // ---------------------------------------------------------- 3. softmax, best real class, threshold
    t0 = Clock::now();
    const auto& ls = outputs[0].shape;   // [1, Q, C+1]
    const int queries = static_cast<int>(ls[1]);
    const int classes = static_cast<int>(ls[2]);     // includes "no object" (the last one)
    const float* logits = outputs[0].data;
    const float* boxes = outputs[1].data;           // [1, Q, 4] cx, cy, w, h relative

    std::vector<Detection> result;
    for (int q = 0; q < queries; ++q) {
        const float* l = logits + static_cast<std::size_t>(q) * classes;
        const float mx = *std::max_element(l, l + classes);
        double sum = 0;
        for (int c = 0; c < classes; ++c) sum += std::exp(static_cast<double>(l[c] - mx));
        int best = 0;
        for (int c = 1; c < classes - 1; ++c) {
            if (l[c] > l[best]) best = c;
        }
        const float score = static_cast<float>(std::exp(static_cast<double>(l[best] - mx)) / sum);
        if (!(score > opt_.conf_threshold)) continue;      // strictly greater, like transformers
        const float* b = boxes + static_cast<std::size_t>(q) * 4;
        auto c01 = [](float v) { return std::clamp(v, 0.f, 1.f); };
        result.push_back({names_.at(static_cast<std::size_t>(best)), score, c01(b[0] - b[2] / 2), c01(b[1] - b[3] / 2),
                          c01(b[0] + b[2] / 2), c01(b[1] + b[3] / 2), opt_.name});
    }
    // Highest score first (transformers keeps query order; the order carries no meaning).
    std::stable_sort(result.begin(), result.end(),
                     [](const Detection& a, const Detection& b) { return a.confidence > b.confidence; });
    if (static_cast<int>(result.size()) > opt_.max_detections) result.resize(static_cast<std::size_t>(opt_.max_detections));
    if (timing) timing->postprocess_ms = ms_since(t0);
    return result;
}

}  // namespace ar
