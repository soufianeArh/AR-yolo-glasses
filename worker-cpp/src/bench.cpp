#include <algorithm>
#include <cmath>
#include <cstdio>
#include <iostream>
#include <numeric>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include "detector.h"

namespace {

struct Stats {
    double avg = 0, p95 = 0;
};

// median and p95 over all the repeats
Stats stats(std::vector<double> v) {
    Stats s;
    if (v.empty()) return s;
    s.avg = std::accumulate(v.begin(), v.end(), 0.0) / static_cast<double>(v.size());
    std::sort(v.begin(), v.end());
    const auto idx = static_cast<std::size_t>(std::ceil(0.95 * static_cast<double>(v.size()))) - 1;
    s.p95 = v[std::min(idx, v.size() - 1)];
    return s;
}

// thread counts come as a comma separated list
std::vector<int> parse_list(const std::string& s) {
    std::vector<int> out;
    std::stringstream ss(s);
    std::string item;
    while (std::getline(ss, item, ',')) {
        if (!item.empty()) out.push_back(std::stoi(item));
    }
    return out;
}

}

int main(int argc, char** argv) {
    std::string model, image;
    std::vector<int> thread_counts{1, 2, 4, 8};
    int iters = 50, warmup = 5, width = 640, quality = 80;
    for (int i = 1; i + 1 < argc; i += 2) {
        const std::string a = argv[i], v = argv[i + 1];
        if (a == "--model") model = v;
        else if (a == "--image") image = v;
        else if (a == "--threads") thread_counts = parse_list(v);
        else if (a == "--iters") iters = std::stoi(v);
        else if (a == "--warmup") warmup = std::stoi(v);
        else if (a == "--width") width = std::stoi(v);
        else if (a == "--quality") quality = std::stoi(v);
        else { std::cerr << "unknown option " << a << "\n"; return 2; }
    }
    if (model.empty() || image.empty()) {
        std::cerr << "usage: worker-bench --model <file.onnx> --image <file> [--threads 1,2,4,8] [--iters 50]\n";
        return 2;
    }

    try {
        const cv::Mat original = cv::imread(image, cv::IMREAD_COLOR);
        if (original.empty()) throw std::runtime_error("cannot read " + image);
        cv::Mat sent = original;
        if (original.cols > width) {
            const int h = static_cast<int>(std::lround(original.rows * (static_cast<double>(width) / original.cols)));
            cv::resize(original, sent, cv::Size(width, h), 0, 0, cv::INTER_AREA);
        }
        std::vector<std::uint8_t> jpeg;
        cv::imencode(".jpg", sent, jpeg, {cv::IMWRITE_JPEG_QUALITY, quality});

        std::printf("image: %s -> sent as %dx%d JPEG q%d (%.1f KB)\n", image.c_str(), sent.cols, sent.rows, quality,
                    jpeg.size() / 1024.0);
        std::printf("hardware threads: %u | iterations: %d (+%d warm-up)\n\n", std::thread::hardware_concurrency(),
                    iters, warmup);
        std::printf("| threads | decode | preprocess | infer avg | infer p95 | postprocess | total avg | total p95 | FPS (1 worker) |\n");
        std::printf("|---|---|---|---|---|---|---|---|---|\n");

        for (const int threads : thread_counts) {
            ar::DetectorOptions cfg;
            cfg.model_path = model;
            cfg.threads = threads;
            const auto det_ptr = ar::load_detector(cfg);
            ar::Detector& det = *det_ptr;

            for (int i = 0; i < warmup; ++i) det.detect_encoded(jpeg.data(), jpeg.size());

            std::vector<double> dec, pre, inf, post, total;
            for (int i = 0; i < iters; ++i) {
                ar::StageTiming t;
                det.detect_encoded(jpeg.data(), jpeg.size(), &t);
                dec.push_back(t.decode_ms);
                pre.push_back(t.preprocess_ms);
                inf.push_back(t.infer_ms);
                post.push_back(t.postprocess_ms);
                total.push_back(t.total());
            }
            const Stats d = stats(dec), p = stats(pre), in = stats(inf), po = stats(post), to = stats(total);
            std::printf("| %d | %.1f ms | %.1f ms | %.1f ms | %.1f ms | %.2f ms | %.1f ms | %.1f ms | %.1f |\n",
                        threads, d.avg, p.avg, in.avg, in.p95, po.avg, to.avg, to.p95, 1000.0 / to.avg);
            std::fflush(stdout);
        }
    } catch (const std::exception& e) {
        std::cerr << "error: " << e.what() << "\n";
        return 1;
    }
    return 0;
}
