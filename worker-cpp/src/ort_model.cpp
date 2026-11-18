#include "ort_model.h"

#include <algorithm>
#include <filesystem>
#include <mutex>
#include <regex>
#include <stdexcept>
#include <utility>

#include <onnxruntime_cxx_api.h>

#include "detector.h"

namespace ar {
namespace {

// One process-wide ONNX Runtime environment, shared by every model.
Ort::Env& ort_env() {
    static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "ar-worker");
    return env;
}

}  // namespace

std::vector<std::string> parse_names(const std::string& text) {
    // Matches  0: 'person'  and  12: "Corn leaf blight"  (names may contain spaces)
    static const std::regex entry(R"((\d+)\s*:\s*(['"])(.*?)\2)");
    std::vector<std::pair<int, std::string>> pairs;
    for (auto it = std::sregex_iterator(text.begin(), text.end(), entry); it != std::sregex_iterator(); ++it) {
        pairs.emplace_back(std::stoi((*it)[1].str()), (*it)[3].str());
    }
    if (pairs.empty()) throw std::runtime_error("model metadata 'names' is empty or unreadable");
    int max_id = 0;
    for (const auto& p : pairs) max_id = std::max(max_id, p.first);
    std::vector<std::string> names(static_cast<std::size_t>(max_id) + 1);
    for (auto& p : pairs) names[static_cast<std::size_t>(p.first)] = std::move(p.second);
    return names;
}

struct OrtModel::Impl {
    std::unique_ptr<Ort::Session> session;
    std::string input_name;
    std::vector<std::string> output_names;
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::vector<Ort::Value> last_outputs;   // keeps the data of the last run alive

    std::mutex run_mu;                      // guards the two fields below
    Ort::RunOptions* current_run = nullptr;
    bool abort_requested = false;
};

OrtModel::OrtModel(const std::string& path, int threads) : impl_(std::make_unique<Impl>()) {
    if (threads < 1) throw std::invalid_argument("threads must be >= 1");
    const std::filesystem::path p(path);
    if (!std::filesystem::exists(p)) throw std::runtime_error("model file not found: " + path);

    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(threads);  // threads for one forward pass
    options.SetInterOpNumThreads(1);
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    // path::c_str() is wchar_t on Windows and char on Linux - exactly what ORTCHAR_T is.
    impl_->session = std::make_unique<Ort::Session>(ort_env(), p.c_str(), options);

    Ort::AllocatorWithDefaultOptions alloc;
    impl_->input_name = impl_->session->GetInputNameAllocated(0, alloc).get();
    for (std::size_t i = 0; i < impl_->session->GetOutputCount(); ++i) {
        impl_->output_names.emplace_back(impl_->session->GetOutputNameAllocated(i, alloc).get());
    }
}

OrtModel::~OrtModel() = default;

std::vector<std::int64_t> OrtModel::input_shape() const {
    return impl_->session->GetInputTypeInfo(0).GetTensorTypeAndShapeInfo().GetShape();
}

std::vector<std::int64_t> OrtModel::output_shape(std::size_t i) const {
    return impl_->session->GetOutputTypeInfo(i).GetTensorTypeAndShapeInfo().GetShape();
}

std::size_t OrtModel::output_count() const { return impl_->output_names.size(); }

std::optional<std::string> OrtModel::metadata(const std::string& key) const {
    Ort::AllocatorWithDefaultOptions alloc;
    const auto meta = impl_->session->GetModelMetadata();
    const auto value = meta.LookupCustomMetadataMapAllocated(key.c_str(), alloc);
    if (!value) return std::nullopt;
    return std::string(value.get());
}

std::vector<OrtModel::Output> OrtModel::run(const float* input, const std::vector<std::int64_t>& shape) {
    std::size_t count = 1;
    for (const auto d : shape) count *= static_cast<std::size_t>(d);
    Ort::Value in = Ort::Value::CreateTensor<float>(impl_->memory, const_cast<float*>(input), count, shape.data(),
                                                    shape.size());
    const char* in_names[] = {impl_->input_name.c_str()};
    std::vector<const char*> out_names;
    for (const auto& n : impl_->output_names) out_names.push_back(n.c_str());

    // A fresh RunOptions per run, published so abort() can terminate THIS run only.
    Ort::RunOptions run_options;
    {
        std::lock_guard<std::mutex> lk(impl_->run_mu);
        impl_->current_run = &run_options;
        impl_->abort_requested = false;
    }
    struct Unpublish {
        Impl& impl;
        ~Unpublish() {
            std::lock_guard<std::mutex> lk(impl.run_mu);
            impl.current_run = nullptr;
        }
    } unpublish{*impl_};

    try {
        impl_->last_outputs = impl_->session->Run(run_options, in_names, &in, 1, out_names.data(), out_names.size());
    } catch (const Ort::Exception&) {
        bool aborted;
        {
            std::lock_guard<std::mutex> lk(impl_->run_mu);
            aborted = impl_->abort_requested;
        }
        if (aborted) throw Aborted("inference aborted");
        throw;
    }

    std::vector<Output> outs;
    for (auto& v : impl_->last_outputs) {
        outs.push_back({v.GetTensorTypeAndShapeInfo().GetShape(), v.GetTensorData<float>()});
    }
    return outs;
}

void OrtModel::abort() {
    std::lock_guard<std::mutex> lk(impl_->run_mu);
    if (impl_->current_run != nullptr) {
        impl_->current_run->SetTerminate();  // ONNX Runtime checks this flag between graph nodes
        impl_->abort_requested = true;
    }
}

}  // namespace ar
