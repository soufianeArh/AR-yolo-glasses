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
    OrtModel(const std::string& path, int threads);
    ~OrtModel();
    OrtModel(const OrtModel&) = delete;
    OrtModel& operator=(const OrtModel&) = delete;

    std::vector<std::int64_t> input_shape() const;
    std::vector<std::int64_t> output_shape(std::size_t i) const;
    std::size_t output_count() const;

    std::optional<std::string> metadata(const std::string& key) const;

    struct Output {
        std::vector<std::int64_t> shape;
        const float* data = nullptr;
    };

    std::vector<Output> run(const float* input, const std::vector<std::int64_t>& shape);

    void abort();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

std::vector<std::string> parse_names(const std::string& text);

}
