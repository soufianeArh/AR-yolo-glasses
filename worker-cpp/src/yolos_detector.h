#pragma once
// YOLOS (transformer detector, Hugging Face) exported to ONNX - the plant-disease model.
//
// Reproduces transformers' YolosImageProcessor(Fast) + post_process_object_detection:
//   1. resize: short side -> 512, long side <= 864, both rounded DOWN to a multiple of 16
//      (aspect kept; input size varies per image - the ONNX input is dynamic)
//   2. bilinear resize WITH antialiasing (like torchvision / PIL), uint8 in, uint8 out
//   3. /255, then ImageNet mean/std normalisation, RGB, CHW
//   4. outputs: logits [1, Q, C+1] and pred_boxes [1, Q, 4] (cx, cy, w, h, relative 0..1)
//   5. per query: softmax over C+1, best of the first C (the last one is "no object"),
//      keep if score > threshold. NO NMS (DETR-style models predict one box per object).
//
// Every setting is read from the ONNX metadata written by tools/export_plant_onnx.py.

#include <string>
#include <vector>

#include "detector.h"
#include "ort_model.h"

namespace ar {

class YolosDetector final : public Detector {
public:
    explicit YolosDetector(DetectorOptions options);

    std::vector<Detection> detect(const cv::Mat& bgr, StageTiming* timing = nullptr) override;
    void abort_current() override { model_.abort(); }

    const std::string& name() const override { return opt_.name; }
    const std::vector<std::string>& class_names() const override { return names_; }

    // The (height, width) the model input gets for an image of this size. Public for testing.
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

}  // namespace ar
