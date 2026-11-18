#pragma once
// One ONNX model loaded in ONNX Runtime, shared by every detector type.
//
// Owns what all detectors need and must get right the same way:
//   - loading (CPU, N intra-op threads, full graph optimisation)
//   - input/output names and shapes, custom metadata (class names, preprocessing settings)
//   - running with ABORT support: abort() from another thread stops the run in progress,
//     and run() then throws ar::Aborted (used by the worker's deadline watcher)
//
// ONNX Runtime types stay out of this header.

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace ar {

class OrtModel {
public:
    OrtModel(const std::string& path, int threads);
    ~OrtModel();
    OrtModel(const OrtModel&) = delete;
    OrtModel& operator=(const OrtModel&) = delete;

    // Shape of input 0 / output i as stored in the model (-1 = dynamic dimension).
    std::vector<std::int64_t> input_shape() const;
    std::vector<std::int64_t> output_shape(std::size_t i) const;
    std::size_t output_count() const;

    // A custom metadata value (e.g. "names"), if the model has it.
    std::optional<std::string> metadata(const std::string& key) const;

    struct Output {
        std::vector<std::int64_t> shape;
        const float* data = nullptr;  // valid until the next run() on this model
    };

    // Run one float input of the given shape. Throws ar::Aborted if abort() stopped it.
    std::vector<Output> run(const float* input, const std::vector<std::int64_t>& shape);

    // Stop the run in progress (if any) as soon as possible. Thread-safe.
    void abort();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// Ultralytics-style class names: "{0: 'person', 1: 'bicycle', ...}" -> vector by id.
std::vector<std::string> parse_names(const std::string& text);

}  // namespace ar
