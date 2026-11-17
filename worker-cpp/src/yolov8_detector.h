#pragma once
// YOLOv8 (Ultralytics) exported to ONNX, run with ONNX Runtime on CPU.
//
// Reproduces Ultralytics' own pre- and post-processing so results match it:
//   letterbox to the model's fixed input (640x640, gray 114 padding, centered),
//   BGR->RGB, /255, HWC->CHW, forward pass, best class per candidate,
//   confidence filter, per-class NMS, boxes mapped back to the original image.
//
// Class names are read from the ONNX file's own metadata ("names", written by the
// Ultralytics exporter), so any YOLOv8 export works without code changes.

#include <memory>
#include <string>
#include <vector>

#include "detector.h"

namespace ar {

struct YoloV8Config {
    std::string model_path;
    std::string name = "default";  // model name used in the contract (Frame.models / Detection.model)
    float conf_threshold = 0.40f;  // keep a detection only if score > this (same rule as Ultralytics)
    float iou_threshold = 0.50f;   // NMS IoU threshold (Ultralytics predict default)
    int max_detections = 300;      // same as Ultralytics max_det
    int threads = 2;               // ONNX Runtime intra-op threads for this instance
};

class YoloV8Detector final : public Detector {
public:
    explicit YoloV8Detector(YoloV8Config config);
    ~YoloV8Detector() override;

    YoloV8Detector(const YoloV8Detector&) = delete;
    YoloV8Detector& operator=(const YoloV8Detector&) = delete;

    std::vector<Detection> detect(const cv::Mat& bgr, StageTiming* timing = nullptr) override;

    const std::string& name() const override { return config_.name; }
    const std::vector<std::string>& class_names() const override { return names_; }
    const YoloV8Config& config() const { return config_; }
    int input_width() const { return input_w_; }
    int input_height() const { return input_h_; }

private:
    struct Impl;  // keeps ONNX Runtime types out of this header
    std::unique_ptr<Impl> impl_;

    YoloV8Config config_;
    std::vector<std::string> names_;
    int input_w_ = 0, input_h_ = 0;
    int num_classes_ = 0, num_candidates_ = 0;
    std::vector<float> input_;  // reused [1,3,H,W] tensor buffer
};

}  // namespace ar
