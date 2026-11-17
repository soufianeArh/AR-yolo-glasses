#include "detector.h"

#include <chrono>

#include <opencv2/imgcodecs.hpp>

namespace ar {

std::vector<Detection> Detector::detect_encoded(const std::uint8_t* data, std::size_t size,
                                                StageTiming* timing) {
    const auto t0 = std::chrono::steady_clock::now();

    if (data == nullptr || size == 0) throw BadFrame("empty image bytes");
    const cv::Mat raw(1, static_cast<int>(size), CV_8UC1, const_cast<std::uint8_t*>(data));
    // IMREAD_COLOR: always 3-channel BGR (drops alpha, expands grayscale) - same as Ultralytics.
    const cv::Mat bgr = cv::imdecode(raw, cv::IMREAD_COLOR);
    if (bgr.empty()) throw BadFrame("could not decode image bytes");

    if (timing) {
        timing->decode_ms =
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    }
    return detect(bgr, timing);
}

}  // namespace ar
