#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace ar {

// shared by both detectors for the session setup and the abort
class OrtModel {
public:
    // threads is how many cores one inference may use
    OrtModel(const std::string& path, int threads);
    ~OrtModel();
    // not copyable because it owns the onnx session
    OrtModel(const OrtModel&) = delete;
    OrtModel& operator=(const OrtModel&) = delete;

    std::vector<std::int64_t> input_shape() const;
    std::vector<std::int64_t> output_shape(std::size_t i) const;
    std::size_t output_count() const;

    // reads a custom key written at export like names or model_type
    std::optional<std::string> metadata(const std::string& key) const;

    // points into the last outputs so it is only valid untill the next run
    struct Output {
        std::vector<std::int64_t> shape;
        const float* data = nullptr;
    };

    // one inference on a float tensor in nchw order
    std::vector<Output> run(const float* input, const std::vector<std::int64_t>& shape);

    // safe to call from another thread while run is busy
    void abort();

private:
    // keeps the onnx runtime headers out of every file that includes this one
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

std::vector<std::string> parse_names(const std::string& text);

}
