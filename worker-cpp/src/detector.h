#pragma once
// The Detector interface.
//
// Every model the C++ worker can run sits behind this one interface and returns
// detections in ONE shape - the Detection message from proto/ar/v1/detection.proto:
//   {label, confidence, x1, y1, x2, y2, model}, boxes normalized to 0..1.
//
// Two implementations today, chosen from the model file by load_detector():
//   YoloV8Detector  Ultralytics YOLOv8 exports (COCO yolov8n: the pipeline placeholder)
//   YolosDetector   YOLOS transformer exports (the plant-disease model: the core model)
// Adding another model family means writing a new class here - nothing else changes.
//
// Thread safety: a Detector instance is NOT thread-safe (it reuses its buffers).
// Use one instance per thread.

#include <cstddef>
#include <cstdint>
#include <memory>
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

// What every detector is configured with.
struct DetectorOptions {
    std::string model_path;
    std::string name = "default";      // model name in the contract (Frame.models / Detection.model)
    std::string model_type = "auto";   // "auto" (from the model file), "yolov8" or "yolos"
    float conf_threshold = 0.40f;      // keep a detection only if score > this
    float iou_threshold = 0.50f;       // NMS IoU (YOLOv8 only; YOLOS has no NMS)
    int max_detections = 300;
    int threads = 2;                   // ONNX Runtime intra-op threads
};

// Opens the model and returns the right detector for it. With model_type "auto", the type comes
// from the ONNX metadata key "model_type" (our YOLOS export writes "yolos"); a model without it
// is treated as an Ultralytics YOLOv8 export.
std::unique_ptr<Detector> load_detector(const DetectorOptions& options);

// The type load_detector() would pick for this file ("yolov8" or "yolos").
std::string detect_model_type(const std::string& model_path);

}  // namespace ar
