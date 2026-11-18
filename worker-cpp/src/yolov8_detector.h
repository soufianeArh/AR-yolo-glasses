#pragma once

#include <memory>
#include <string>
#include <vector>

#include "detector.h"
#include "ort_model.h"

namespace ar {

// coco and any other ultralytics yolov8 export
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
    std::vector<float> input_;
};

}
