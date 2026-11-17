#pragma once
// The Detector interface.
//
// Every model the C++ worker can run sits behind this one interface and returns
// detections in ONE shape - the Detection message from proto/ar/v1/detection.proto:
//   {label, confidence, x1, y1, x2, y2, model}, boxes normalized to 0..1.
//
// Swapping YOLOv8 for another model later (e.g. an Apache-licensed one) means
// writing a new class that implements this interface - nothing else changes.
//
// Thread safety: a Detector instance is NOT thread-safe (it reuses its buffers).
// Use one instance per thread.

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

#include <opencv2/core.hpp>

namespace ar {

struct Detection {
    std::string label;
    float confidence = 0.f;
    float x1 = 0.f, y1 = 0.f, x2 = 0.f, y2 = 0.f;  // normalized 0..1, top-left / bottom-right
    std::string model;
};

// Time spent in each stage of one detect() call, in milliseconds.
struct StageTiming {
    double decode_ms = 0;       // JPEG/PNG bytes -> BGR image
    double preprocess_ms = 0;   // letterbox + HWC->CHW float tensor
    double infer_ms = 0;        // ONNX Runtime forward pass
    double postprocess_ms = 0;  // decode output + NMS + map boxes back
    double total() const { return decode_ms + preprocess_ms + infer_ms + postprocess_ms; }
};

// Thrown when the input bytes are not a decodable image (-> BAD_FRAME status).
struct BadFrame : std::runtime_error {
    using std::runtime_error::runtime_error;
};

// Thrown when abort_current() stopped an inference halfway (e.g. its deadline passed).
struct Aborted : std::runtime_error {
    using std::runtime_error::runtime_error;
};

class Detector {
public:
    virtual ~Detector() = default;

    // Run the model over one BGR image.
    virtual std::vector<Detection> detect(const cv::Mat& bgr, StageTiming* timing = nullptr) = 0;

    // Decode encoded image bytes (JPEG, PNG...) and run the model. Throws BadFrame.
    std::vector<Detection> detect_encoded(const std::uint8_t* data, std::size_t size,
                                          StageTiming* timing = nullptr);

    virtual const std::string& name() const = 0;
    virtual const std::vector<std::string>& class_names() const = 0;

    // Ask the inference running right now (if any) to stop as soon as possible; that detect()
    // call then throws Aborted. Safe to call from ANOTHER thread - that is its purpose.
    // Calling it when nothing is running does nothing.
    virtual void abort_current() {}
};

}  // namespace ar
