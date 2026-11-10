// PC test for the phone engine. Reads 16 kHz mono PCM16 WAVs listed in a JSONL manifest ({"id","wav"} per line),
// runs each through the engine and prints one JSON line per clip: {"id","text","total_ms","read_ms","write_ms",...}.
// mode "live": the recording is fed as if spoken (every 0.5 s the engine may read finished 8 s windows), then
//               finish() runs on the whole clip, like the phone after release.  mode "once": no live reading.
// Usage: ivy_test MODEL MMPROJ MANIFEST BASE_DIR live|rt|once [threads]
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "ivy_engine.h"

static std::vector<float> read_wav(const std::string & path) {
    std::ifstream f(path, std::ios::binary);
    std::vector<char> b((std::istreambuf_iterator<char>(f)), {});
    size_t p = 12;
    while (p + 8 <= b.size()) {
        uint32_t len;
        std::memcpy(&len, &b[p + 4], 4);
        if (std::memcmp(&b[p], "data", 4) == 0) {
            size_t n = std::min<size_t>(len, b.size() - p - 8) / 2;
            std::vector<float> out(n);
            const int16_t * s = reinterpret_cast<const int16_t *>(&b[p + 8]);
            for (size_t i = 0; i < n; i++) out[i] = s[i] / 32768.0f;
            return out;
        }
        p += 8 + len + (len & 1);
    }
    return {};
}

static std::string field(const std::string & line, const std::string & key) {
    auto k = line.find("\"" + key + "\"");
    if (k == std::string::npos) return "";
    auto q1 = line.find('"', line.find(':', k) + 1);
    auto q2 = line.find('"', q1 + 1);
    return line.substr(q1 + 1, q2 - q1 - 1);
}

static std::string esc(const std::string & s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if (c == '\n') o += "\\n";
        else o += c;
    }
    return o;
}

int main(int argc, char ** argv) {
    // ivy_test rewrite MODEL.gguf LINES.txt : "Make it professional" on each line (checks the phone prompt format)
    if (argc == 4 && std::string(argv[1]) == "rewrite") {
        std::string err;
        IvyRewriter * r = ivy_rewrite_load(argv[2], 0, err);
        if (!r) { std::fprintf(stderr, "rewrite load failed: %s\n", err.c_str()); return 1; }
        std::ifstream in(argv[3]);
        for (std::string line; std::getline(in, line);) {
            if (line.empty()) continue;
            double ms = 0;
            std::string out = ivy_rewrite(r, line, ms);
            std::printf("%s\n  -> %s  [%.0f ms]\n", line.c_str(), out.c_str(), ms);
        }
        ivy_rewrite_free(r);
        return 0;
    }
    if (argc < 6) { std::fprintf(stderr, "usage: ivy_test MODEL MMPROJ MANIFEST BASE_DIR live|rt|once [threads]\n"); return 2; }
    std::string err;
    int threads = argc > 6 ? std::atoi(argv[6]) : 0;
    auto tl = std::chrono::steady_clock::now();
    IvyEngine * e = ivy_load(argv[1], argv[2], threads, 0, err);
    std::fprintf(stderr, "load_ms %.0f\n", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tl).count());
    std::fprintf(stderr, "cpu_code %s\n", ivy_cpu_choice().c_str());
    if (!e) { std::fprintf(stderr, "load failed: %s\n", err.c_str()); return 1; }
    std::fprintf(stderr, "threads %d\n", ivy_threads(e));
    bool live = std::string(argv[5]) == "live", rt = std::string(argv[5]) == "rt";
    std::ifstream man(argv[3]);
    std::string line;
    while (std::getline(man, line)) {
        if (line.empty()) continue;
        std::string id = field(line, "id"), wav = field(line, "wav");
        std::vector<float> pcm = read_wav(std::string(argv[4]) + "/" + wav);
        IvyReport rep;
        std::string text;
        double wait_ms = 0, app_ms = 0;  // rt only: waiting for the worker after release / release -> text (what the app logs)
        if (rt) {  // like the app: a worker reads while the clip "arrives" at speaking speed; at the end of the clip the
                   // speaker lets go: interrupt, wait for the worker (as LiveDictation.finish does), then finish
            ivy_live_reset(e, {});
            std::atomic<bool> talking{true};
            auto t0 = std::chrono::steady_clock::now();
            std::thread worker([&] {
                while (talking) {
                    double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
                    size_t n = std::min(pcm.size(), (size_t) (secs * 16000));
                    if (n < 8000) { std::this_thread::sleep_for(std::chrono::milliseconds(50)); continue; }
                    if (!ivy_live_step(e, pcm.data(), n)) std::this_thread::sleep_for(std::chrono::milliseconds(150));
                }
            });
            std::this_thread::sleep_until(t0 + std::chrono::microseconds((long long) (pcm.size() * 1e6 / 16000)));
            auto released = std::chrono::steady_clock::now();
            talking = false;
            ivy_live_interrupt(e);
            worker.join();
            wait_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - released).count();
            text = ivy_live_finish(e, pcm.data(), pcm.size(), rep);
            app_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - released).count();
        } else if (live) {
            ivy_live_reset(e, {});
            // IVY_TEST_BLOCK=from:to (seconds): no step runs in between, as when one step blocks that long on a slow
            // or busy phone (99:113 jumps over a piece's whole cut range; the piece must still be cut)
            double b0 = 0, b1 = 0;
            if (const char * v = std::getenv("IVY_TEST_BLOCK")) std::sscanf(v, "%lf:%lf", &b0, &b1);
            for (size_t n = 8000; n < pcm.size(); n += 8000) {  // 0.5 s of new audio at a time
                if (n > b0 * 16000 && n < b1 * 16000) continue;
                while (ivy_live_step(e, pcm.data(), n)) {}
            }
            text = ivy_live_finish(e, pcm.data(), pcm.size(), rep);
        } else {
            text = ivy_transcribe(e, pcm.data(), pcm.size(), {}, rep);
        }
        std::printf("{\"id\": \"%s\", \"text\": \"%s\", \"secs\": %.1f, \"total_ms\": %.0f, \"read_ms\": %.0f, "
                    "\"write_ms\": %.0f, \"reused\": %d, \"at_end\": %d, \"tokens\": %d, \"drafts\": %d, \"proposed\": %d, "
                    "\"accepted\": %d, \"passes\": %d, \"wait_ms\": %.0f, \"app_ms\": %.0f, \"note\": \"%s\"}\n",
                    id.c_str(), esc(text).c_str(), pcm.size() / 16000.0, rep.total_ms, rep.read_ms, rep.write_ms,
                    rep.windows_reused, rep.windows_at_end, rep.out_tokens, rep.drafts, rep.proposed, rep.accepted, rep.passes,
                    wait_ms, app_ms, esc(rep.note).c_str());
        std::fflush(stdout);
    }
    if (FILE * f = std::fopen("/proc/self/status", "r")) {  // memory after the run (Linux/Android)
        char l[256];
        while (std::fgets(l, sizeof l, f)) if (!std::strncmp(l, "VmHWM", 5) || !std::strncmp(l, "Rss", 3)) std::fprintf(stderr, "%s", l);
        std::fclose(f);
    }
    ivy_free(e);
    return 0;
}
