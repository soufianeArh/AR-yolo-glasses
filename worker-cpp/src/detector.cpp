#include "detector.h"

#include <chrono>
#include <stdexcept>

#include <opencv2/imgcodecs.hpp>

#include "ort_model.h"
#include "yolos_detector.h"
#include "yolov8_detector.h"

namespace ar {

std::vector<Detection> Detector::detect_encoded(const std::uint8_t* data, std::size_t size,
                                                StageTiming* timing) {
    // decoding counts as part of the frame time
    const auto t0 = std::chrono::steady_clock::now();

    // empty bytes are a client mistake and not a crash
    if (data == nullptr || size == 0) throw BadFrame("empty image bytes");
    // wraps the bytes without copying them
    const cv::Mat raw(1, static_cast<int>(size), CV_8UC1, const_cast<std::uint8_t*>(data));
    // opencv decodes jpeg or png into a bgr image
    const cv::Mat bgr = cv::imdecode(raw, cv::IMREAD_COLOR);
    if (bgr.empty()) throw BadFrame("could not decode image bytes");

    if (timing) {
        timing->decode_ms =
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    }
    return detect(bgr, timing);
}

// the model_type metadata decides wich detector class is used
std::string detect_model_type(const std::string& model_path) {
    // opens the model with one thread only to read its metadata
    const OrtModel peek(model_path, 1);
    const auto type = peek.metadata("model_type");
    return type ? *type : "yolov8";
}

// no model_type in the metadata means a plain yolov8 export
std::unique_ptr<Detector> load_detector(const DetectorOptions& options) {
    const std::string type = options.model_type == "auto" ? detect_model_type(options.model_path) : options.model_type;
    // a new model family means one more line here and one more detector class
    if (type == "yolov8") return std::make_unique<YoloV8Detector>(options);
    if (type == "yolos") return std::make_unique<YolosDetector>(options);
    throw std::invalid_argument("unknown model type '" + type + "' (expected yolov8 or yolos)");
}

}
