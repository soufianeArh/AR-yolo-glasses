#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <sstream>
#include <string>
#include <vector>

#include "detector.h"

namespace {

// just enough escaping for labels and error messages
std::string json_escape(const std::string& s) {
    std::string out;
    for (const char ch : s) {
        switch (ch) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            default: out += ch;
        }
    }
    return out;
}

std::vector<std::uint8_t> read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot open " + path);
    return {std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()};
}

int usage() {
    std::cerr << "usage: worker-cli --model <file.onnx> [--name default] [--conf 0.4] [--iou 0.5] "
                 "[--threads 2] [--model-type auto|yolov8|yolos] <image> [<image>...]\n";
    return 2;
}

}

int main(int argc, char** argv) {
    ar::DetectorOptions cfg;
    std::vector<std::string> images;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= argc) throw std::runtime_error("missing value after " + a);
            return argv[++i];
        };
        try {
            if (a == "--model") cfg.model_path = next();
            else if (a == "--name") cfg.name = next();
            else if (a == "--conf") cfg.conf_threshold = std::stof(next());
            else if (a == "--iou") cfg.iou_threshold = std::stof(next());
            else if (a == "--threads") cfg.threads = std::stoi(next());
            else if (a == "--model-type") cfg.model_type = next();
            else if (a.rfind("--", 0) == 0) return usage();
            else images.push_back(a);
        } catch (const std::exception& e) {
            std::cerr << e.what() << "\n";
            return usage();
        }
    }
    if (cfg.model_path.empty() || images.empty()) return usage();

    try {
        const auto det = ar::load_detector(cfg);
        ar::Detector& detector = *det;
        for (const auto& path : images) {
            std::ostringstream js;
            js << "{\"file\":\"" << json_escape(path) << "\"";
            try {
                const auto bytes = read_file(path);
                ar::StageTiming t;
                const auto dets = detector.detect_encoded(bytes.data(), bytes.size(), &t);
                js << ",\"status\":\"OK\",\"timing_ms\":{\"decode\":" << t.decode_ms
                   << ",\"preprocess\":" << t.preprocess_ms << ",\"infer\":" << t.infer_ms
                   << ",\"postprocess\":" << t.postprocess_ms << "},\"detections\":[";
                for (std::size_t k = 0; k < dets.size(); ++k) {
                    const auto& d = dets[k];
                    js << (k ? "," : "") << "{\"label\":\"" << json_escape(d.label) << "\",\"confidence\":"
                       << d.confidence << ",\"x1\":" << d.x1 << ",\"y1\":" << d.y1 << ",\"x2\":" << d.x2
                       << ",\"y2\":" << d.y2 << ",\"model\":\"" << json_escape(d.model) << "\"}";
                }
                js << "]";
            } catch (const ar::BadFrame& e) {
                js << ",\"status\":\"BAD_FRAME\",\"error\":\"" << json_escape(e.what()) << "\"";
            }
            js << "}";
            std::cout << js.str() << "\n";
        }
    } catch (const std::exception& e) {
        std::cerr << "error: " << e.what() << "\n";
        return 1;
    }
    return 0;
}
