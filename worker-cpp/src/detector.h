#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include <opencv2/core.hpp>

namespace ar {

// coords are 0..1 of the original image
struct Detection {
    std::string label;
    float confidence = 0.f;
    float x1 = 0.f, y1 = 0.f, x2 = 0.f, y2 = 0.f;
    std::string model;
};

struct StageTiming {
    double decode_ms = 0;
    double preprocess_ms = 0;
    double infer_ms = 0;
    double postprocess_ms = 0;
    double total() const { return decode_ms + preprocess_ms + infer_ms + postprocess_ms; }
};

struct BadFrame : std::runtime_error {
    using std::runtime_error::runtime_error;
};

// thrown when the scheduler gave up on the frame in the middle of the inference
struct Aborted : std::runtime_error {
    using std::runtime_error::runtime_error;
};

class Detector {
public:
    virtual ~Detector() = default;

    virtual std::vector<Detection> detect(const cv::Mat& bgr, StageTiming* timing = nullptr) = 0;

    std::vector<Detection> detect_encoded(const std::uint8_t* data, std::size_t size,
                                          StageTiming* timing = nullptr);

    virtual const std::string& name() const = 0;
    virtual const std::vector<std::string>& class_names() const = 0;

    virtual void abort_current() {}
};

struct DetectorOptions {
    std::string model_path;
    std::string name = "default";
    std::string model_type = "auto";
    float conf_threshold = 0.40f;
    float iou_threshold = 0.50f;
    int max_detections = 300;
    int threads = 2;
};

std::unique_ptr<Detector> load_detector(const DetectorOptions& options);

std::string detect_model_type(const std::string& model_path);

}
