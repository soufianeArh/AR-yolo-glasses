#pragma once

#include <string>
#include <vector>

#include "detector.h"
#include "ort_model.h"

namespace ar {

// the hugging face plant model with its own preprocessing
class YolosDetector final : public Detector {
public:
    explicit YolosDetector(DetectorOptions options);

    std::vector<Detection> detect(const cv::Mat& bgr, StageTiming* timing = nullptr) override;
    void abort_current() override { model_.abort(); }

    const std::string& name() const override { return opt_.name; }
    const std::vector<std::string>& class_names() const override { return names_; }

    static std::pair<int, int> input_size(int height, int width, int shortest, int longest, int divisor);

private:
    DetectorOptions opt_;
    OrtModel model_;
    std::vector<std::string> names_;
    int shortest_ = 512, longest_ = 864, divisor_ = 16;
    float mean_[3] = {0.485f, 0.456f, 0.406f};
    float std_[3] = {0.229f, 0.224f, 0.225f};
    std::vector<float> input_;
};

}
