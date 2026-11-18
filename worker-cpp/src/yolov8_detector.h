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
#include "ort_model.h"

namespace ar {

class YoloV8Detector final : public Detector {
public:
    explicit YoloV8Detector(DetectorOptions options);

    std::vector<Detection> detect(const cv::Mat& bgr, StageTiming* timing = nullptr) override;
    void abort_current() override { model_.abort(); }

    const std::string& name() const override { return opt_.name; }
    const std::vector<std::string>& class_names() const override { return names_; }

private:
    DetectorOptions opt_;
    OrtModel model_;
    std::vector<std::string> names_;
    int input_w_ = 0, input_h_ = 0;
    int num_classes_ = 0, num_candidates_ = 0;
    std::vector<float> input_;  // reused [1,3,H,W] tensor buffer
};

}  // namespace ar
