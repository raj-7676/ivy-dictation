// See ivy_engine.h. Ported from the desktop beta (lite.rs + live.rs); comments there explain the why.
#include "ivy_engine.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <optional>
#include <thread>
#if defined(__linux__)
#include <cerrno>
#include <dirent.h>
#include <dlfcn.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

namespace {

constexpr size_t SR = 16000;
constexpr size_t WINDOW = 800 * 160;          // one encoder window = 8 s
constexpr size_t LOOKAHEAD = 200 + 1600;      // last mel frame reaches half an FFT past the window, + 0.1 s
constexpr size_t CHUNK_SECS = 120;            // longest piece per model pass (context 4096)
// Long dictations are cut into pieces while the speaker talks: after PIECE_WINDOWS windows (88 s) no further window
// is read; once PIECE_CUT_SECS of the piece exist, it is closed at its quietest moment after the last read window and
// a new piece starts there. Before this, live reading simply stopped past ~112 s and a 4.4 min dictation took 108 s
// after release (CMF 2026-11-09). Cost: a spoken correction cannot reach back across a cut (cuts sit in pauses).
constexpr size_t PIECE_WINDOWS = 11;
constexpr size_t PIECE_CUT_SECS = 100;
// Later pieces start with this much silence: when speech begins at the very first sample the model skips the first
// sentence (PC 2026-11-09: "The trail was muddy..." lost at a cut; with 0.5 s of silence in front it is kept).
constexpr size_t PIECE_PAD = SR / 2;
constexpr int N_BATCH = 1024;
constexpr float CLIP_GUARD = 0.95f;

double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

std::string instruction(const std::vector<std::string> & dict) {
    std::string base = "Write what the speaker means, ready to paste: apply their own corrections, keep every other word.";
    if (dict.empty()) return base;
    base += " Words that may appear: ";
    for (size_t i = 0; i < dict.size(); i++) base += (i ? ", " : "") + dict[i];
    return base + ".";
}

std::string format_prompt(const std::vector<std::string> & dict) {
    return "<|im_start|>system\n" + instruction(dict) + "<|im_end|>\n<|im_start|>user\n" + mtmd_default_marker() +
           "<|im_end|>\n<|im_start|>assistant\nlanguage English<asr_text>";
}

// desktop audio::normalize_gain: peak-normalise quiet speech to 0.7 (max 20x); leave loud, silent or flat audio alone
std::optional<float> normalize_gain(const float * s, size_t n) {
    if (n == 0) return std::nullopt;
    float peak = 0;
    double sq = 0;
    for (size_t i = 0; i < n; i++) {
        peak = std::max(peak, std::fabs(s[i]));
        sq += (double) s[i] * s[i];
    }
    if (peak < 0.01f || peak >= 0.70f) return std::nullopt;
    float rms = (float) std::sqrt(sq / n);
    if (rms <= 0 || peak / rms < 1.6f) return std::nullopt;
    return std::min(0.7f / peak, 20.0f);
}

// live.rs window_gains ("step-soft"): earlier windows keep their gain; a louder later window gets a lower one
std::vector<std::optional<float>> window_gains(const float * s, size_t n, const std::vector<std::optional<float>> & fixed) {
    size_t nw = std::max<size_t>(1, (n + WINDOW - 1) / WINDOW);
    if (const char * m = std::getenv("IVY_LIVE_GAIN"); m && std::string(m) == "none")  // test switch
        return std::vector<std::optional<float>>(nw);
    std::vector<std::optional<float>> g = fixed;
    if (g.empty()) g.push_back(normalize_gain(s, n));
    while (g.size() < nw) {
        size_t k = g.size();
        std::optional<float> next = g[k - 1];
        if (next) {
            float peak = 0;
            for (size_t i = k * WINDOW; i < std::min(n, (k + 1) * WINDOW); i++) peak = std::max(peak, std::fabs(s[i]));
            if (peak * *next > CLIP_GUARD) next = 0.7f / peak;
        }
        g.push_back(next);
    }
    return g;
}

float soft_limit(float x) {
    float a = std::fabs(x);
    return a <= 0.7f ? x : std::copysign(0.7f + 0.3f * std::tanh((a - 0.7f) / 0.3f), x);
}

std::vector<float> apply_gains(const float * s, size_t n, const std::vector<std::optional<float>> & g) {
    std::vector<float> out(n);
    for (size_t i = 0; i < n; i++) {
        const auto & w = g[std::min(i / WINDOW, g.size() - 1)];
        out[i] = w ? soft_limit(s[i] * *w) : s[i];
    }
    return out;
}

size_t complete_windows(size_t n) { return n < LOOKAHEAD ? 0 : (n - LOOKAHEAD) / WINDOW; }

struct Chunks {
    mtmd_input_chunks * p = mtmd_input_chunks_init();
    ~Chunks() { mtmd_input_chunks_free(p); }
    size_t size() const { return mtmd_input_chunks_size(p); }
    const mtmd_input_chunk * get(size_t i) const { return mtmd_input_chunks_get(p, i); }
    bool is_audio(size_t i) const { return mtmd_input_chunk_get_type(get(i)) == MTMD_INPUT_CHUNK_TYPE_AUDIO; }
    std::vector<llama_token> text_tokens(size_t i) const {
        size_t k = 0;
        const llama_token * t = mtmd_input_chunk_get_tokens_text(get(i), &k);
        return std::vector<llama_token>(t, t + k);
    }
    // layout: text, audio windows..., text
    int audio_count() const {
        size_t n = size();
        if (n < 3 || is_audio(0) || is_audio(n - 1)) return -1;
        for (size_t i = 1; i + 1 < n; i++) if (!is_audio(i)) return -1;
        return (int) n - 2;
    }
};

struct PinMel {
    explicit PinMel(std::optional<double> v) {
        // Phone default: no pin. Each window read while talking uses the loudness reference of the audio so far
        // (pinning it to the first window lost Yash-test line 15 on the PC: 32 vs 33/38, same WER).
        // IVY_LIVE_PIN=on brings the desktop behaviour back for tests.
        if (const char * m = std::getenv("IVY_LIVE_PIN"); !(m && std::string(m) == "on")) v.reset();
        mtmd_ivy_pin_mel_max(v.has_value(), v.value_or(0.0));
    }
    ~PinMel() { mtmd_ivy_pin_mel_max(false, 0.0); }
};

// Proposed draft tokens per check (desktop live.rs MAX_PROPOSE; IVY_LIVE_K overrides, 0 = plain greedy).
constexpr int MAX_PROPOSE = 64;

int max_propose() {
    static int k = [] {
        const char * v = std::getenv("IVY_LIVE_K");
        return v ? std::clamp(std::atoi(v), 0, MAX_PROPOSE) : MAX_PROPOSE;
    }();
    return k;
}

bool drafts_off() {
    const char * v = std::getenv("IVY_LIVE_DRAFT");
    return v && std::string(v) == "off";
}

// Draft tokens that should follow `out`, keeping a cursor into the draft (desktop live.rs propose).
std::vector<llama_token> propose(const std::vector<llama_token> & out, const std::vector<llama_token> & draft,
                                 size_t & cursor, size_t k) {
    if (draft.empty() || k == 0 || out.empty()) return {};
    llama_token last = out.back();
    if (cursor < draft.size() && draft[cursor] == last) {
        cursor++;
    } else {
        // The model wrote something other than the draft here: find where the draft continues. Short matches (" the",
        // ",") also appear far away, and the old whole-draft search jumped there and offered whole wrong chunks (PC +
        // CMF 2026-11-09: ~140 offered tokens per minute of speech thrown away). So: a run of 2-4 written tokens
        // ending NEAR the cursor ([cursor-4, cursor+8]); else a run of 3-4 anywhere (a longer insertion or deletion;
        // 3 tokens rarely repeat by chance); else the model dropped one draft token; else it replaced one. Nearest wins.
        // Past the end of the draft nothing is offered (new speech is written plainly).
        std::optional<size_t> found;
        auto dist = [&](size_t x) { return x > cursor ? x - cursor : cursor - x; };
        auto search = [&](size_t lo, size_t hi, size_t n_min) {
            for (size_t n = std::min<size_t>(out.size(), 4); n >= n_min && !found; n--) {
                for (size_t end = std::max(lo, n); end <= hi; end++) {
                    if (!std::equal(out.end() - n, out.end(), draft.begin() + (end - n))) continue;
                    if (!found || dist(end) < dist(*found)) found = end;
                }
            }
        };
        search(cursor > 4 ? cursor - 4 : 0, std::min(cursor + 8, draft.size()), 2);
        if (!found) search(0, draft.size(), 3);
        if (found) cursor = *found;
        else if (cursor + 1 < draft.size() && draft[cursor + 1] == last) cursor += 2;
        else cursor += 1;
    }
    if (cursor >= draft.size()) return {};
    size_t stop = std::min(cursor + k, draft.size());
    return std::vector<llama_token>(draft.begin() + cursor, draft.begin() + stop);
}

// An interrupted draft (fresher, cut short) followed by the rest of the previous draft (desktop merge_draft).
std::vector<llama_token> merge_draft(std::vector<llama_token> fresh, const std::vector<llama_token> & old) {
    constexpr size_t N = 3;
    if (fresh.size() < N) return old.size() > fresh.size() ? old : fresh;
    std::optional<size_t> best;
    auto dist = [&](size_t j) { return j > fresh.size() ? j - fresh.size() : fresh.size() - j; };
    for (size_t j = 0; j + N <= old.size(); j++)
        if (std::equal(fresh.end() - N, fresh.end(), old.begin() + j) && (!best || dist(j) < dist(*best))) best = j;
    if (best) {
        fresh.insert(fresh.end(), old.begin() + *best + N, old.end());
        return fresh;
    }
    // No join point (the fresh draft changed course): keep the longer one. A draft is only offered to the final
    // check, which re-finds its place after a mismatch, so a short fresh piece alone would leave most of the text to
    // be written after release.
    if (old.size() > fresh.size() && std::getenv("IVY_DEBUG"))
        std::fprintf(stderr, "draft merge: no join point, kept the old draft (%zu tok, fresh %zu)\n", old.size(), fresh.size());
    return old.size() > fresh.size() ? old : fresh;
}

// Fast cores: big.LITTLE phones run slower when llama.cpp threads land on little cores, so count only cores with at
// least half the top capacity (Android/Linux sysfs; empty = unknown, use all cores).
std::vector<int> fast_cores() {
    unsigned hw = std::max(1u, std::thread::hardware_concurrency());
    std::vector<long> cap;
    for (unsigned i = 0; i < hw; i++) {
        long v = -1;
        for (const char * f : {"cpu_capacity", "cpufreq/cpuinfo_max_freq"}) {
            std::string p = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/" + f;
            if (FILE * fp = std::fopen(p.c_str(), "r")) {
                if (std::fscanf(fp, "%ld", &v) != 1) v = -1;
                std::fclose(fp);
            }
            if (v > 0) break;
        }
        cap.push_back(v);
    }
    std::vector<int> out;
    long top = *std::max_element(cap.begin(), cap.end());
    if (top <= 0) return out;
    for (unsigned i = 0; i < hw; i++) if (cap[i] * 2 >= top) out.push_back((int) i);
    return out;
}

// Cores this process may run on right now; 0 = unknown. Android moves an app between core sets: behind another app
// Ivy gets cores 0-5 of 8 on the S26, and background apps share cores 0-1 and 4-5 of those.
int allowed_cores() {
#if defined(__linux__)
    cpu_set_t s;
    CPU_ZERO(&s);
    if (sched_getaffinity(0, sizeof(s), &s) == 0) return CPU_COUNT(&s);
#endif
    return 0;
}

// Nanoseconds a thread of this process has spent ready to run but waiting for a core (Linux schedstat, 2nd field;
// kernel CONFIG_SCHED_INFO, set in Android's GKI kernels). 0 = unknown: the busy-core guard then never acts.
uint64_t wait_ns(const char * schedstat) {
    unsigned long long run = 0, wait = 0;
    FILE * f = std::fopen(schedstat, "r");
    if (!f) return 0;
    const bool ok = std::fscanf(f, "%llu %llu", &run, &wait) == 2;
    std::fclose(f);
    return ok ? wait : 0;
}
uint64_t thread_wait_ns(int tid) {
    char p[64];
    std::snprintf(p, sizeof(p), "/proc/self/task/%d/schedstat", tid);
    return wait_ns(p);
}

// While in scope, this thread runs at normal priority, and so do the threads it creates (they inherit it). A load
// nobody waits for yet runs below normal priority so typing never stutters; compute threads must not, or every
// normal app thread on their core takes it from them.
struct NormalPriority {
#if defined(__linux__)
    int tid = (int) syscall(SYS_gettid), was = 0;
    bool raised = false;
    NormalPriority() {
        errno = 0;
        was = getpriority(PRIO_PROCESS, tid);
        raised = errno == 0 && was > 0 && setpriority(PRIO_PROCESS, tid, 0) == 0;
    }
    ~NormalPriority() {
        if (raised) setpriority(PRIO_PROCESS, tid, was);
    }
#endif
};

// Thread ids of this process, sorted.
std::vector<int> thread_ids() {
    std::vector<int> out;
#if defined(__linux__)
    if (DIR * d = opendir("/proc/self/task")) {
        while (dirent * t = readdir(d))
            if (t->d_name[0] != '.') out.push_back(std::atoi(t->d_name));
        closedir(d);
    }
    std::sort(out.begin(), out.end());
#endif
    return out;
}

// Busy-core sensor: how long this thread and the watched worker threads were ready to run but waited for a core
// while fn ran. Only during fn: after a run the workers keep spinning a while for more work, and waiting behind
// another app then costs nothing (counting it made the guard drop to 1 thread with one busy core, S26 2026-11-10).
struct CoreWait {
    std::vector<int> tids;
    std::vector<uint64_t> seen;
    // watch the threads that appeared since `before` (a threadpool's workers; a stray app thread among them does
    // no harm: its waiting for a core also means the cores are short)
    void watch_new(const std::vector<int> & before) {
        for (int tid : thread_ids())
            if (!std::binary_search(before.begin(), before.end(), tid)) {
                tids.push_back(tid);
                seen.push_back(thread_wait_ns(tid));
            }
    }
    template <class F> auto run(F fn, double & ms, double & waited_ms) {
        for (size_t i = 0; i < tids.size(); i++) seen[i] = thread_wait_ns(tids[i]);
        const uint64_t self0 = wait_ns("/proc/thread-self/schedstat");
        const double t0 = now_ms();
        auto r = fn();
        ms = now_ms() - t0;
        const uint64_t self1 = wait_ns("/proc/thread-self/schedstat");
        uint64_t w = self1 > self0 ? self1 - self0 : 0;
        for (size_t i = 0; i < tids.size(); i++) {
            const uint64_t now = thread_wait_ns(tids[i]);
            if (now > seen[i]) w += now - seen[i];
        }
        waited_ms = w / 1e6;
        return r;
    }
};

// Busy-core guard. ggml's compute threads wait for each other after every step, so one thread whose core another
// busy program shares stalls all of them. S26, 60 s dictation, 6 cores allowed: ~2 s with all free; one busy core:
// 97-133 s with 6 threads, 2 s with 5; two busy cores: over 300 s with 6, 22-59 s with 5, 3.3 s with 4 (2026-11-10).
// The kernel counts how long each thread was ready but waiting for a core (CoreWait). Per 300 ms of compute the
// guard takes the threads' total wait / compute time, smoothed (each new window counts half). Above 0.6: one thread
// fewer, down to half the count it started with. S26 smoothed values: up to 0.43 in normal use, 0.85-1.05 with one
// busy core, 1.25-2.35 with two or a busy video app in front. Apps come and go, so after a calm while (under 0.3) it
// tries one thread more, up to the start count; a try pushed back down within 5 s doubles the wait before the next
// one (3 s up to 60 s). With it, two busy cores: 4.4-6.4 s instead of 13-118 s.
struct BusyGuard {
    CoreWait waits;  // the threadpool's workers (and the thread running the pass)
    int n_load = 0;  // thread count at load
    int fewest = 0;  // fewest in this dictation (for the report)
    double ms = 0, waited_ms = 0, level = 0, changed_ms = 0, backoff_ms = 3000;
    bool up = false;  // the last change added a thread
    void start(int n) { n_load = fewest = n; }
    // After a pass of pass_ms on n threads that waited pass_waited_ms for cores: the count for the next passes.
    int after(int n, double pass_ms, double pass_waited_ms) {
        ms += pass_ms;
        waited_ms += pass_waited_ms;
        if (ms < 300) return n;
        level = 0.5 * level + 0.5 * waited_ms / ms;
        ms = waited_ms = 0;
        if (std::getenv("IVY_NOGUARD")) return n;  // test switch: watch only
        const double now = now_ms();
        int to = n;
        if (level > 0.6 && n > (n_load + 1) / 2) {
            backoff_ms = up && now - changed_ms < 5000 ? std::min(60000.0, 2 * backoff_ms) : 3000;
            to = n - 1;
        } else if (level < 0.3 && n < n_load && now - changed_ms > backoff_ms) {
            to = n + 1;
        }
        if (to != n) {
            if (std::getenv("IVY_DEBUG")) std::fprintf(stderr, "busy cores: %d -> %d threads (level %.2f)\n", n, to, level);
            up = to > n;
            changed_ms = now;
            level = 0;
            fewest = std::min(fewest, to);
        }
        return to;
    }
};

// Compute threads kept alive between passes (a llama.cpp threadpool). Without one every pass starts fresh threads,
// which Android places on slow cores first and whose clocks ramp up late.
struct Pool {
    ggml_threadpool_t tp = nullptr;
    void (*free_fn)(ggml_threadpool_t) = nullptr;
    void free() {
        if (tp && free_fn) free_fn(tp);
        tp = nullptr;
    }
};

// A pool of n threads; its workers are added to `watch`. Made at normal priority: the threads keep the priority of
// the thread that creates them. Empty when the CPU code has no threadpools.
Pool new_pool(int n, CoreWait & watch) {
    Pool p;
    ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    ggml_backend_reg_t reg = cpu ? ggml_backend_dev_backend_reg(cpu) : nullptr;
    auto * pool_new = reg ? (decltype(ggml_threadpool_new) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new") : nullptr;
    p.free_fn = reg ? (decltype(ggml_threadpool_free) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free") : nullptr;
    if (!pool_new || !p.free_fn) return p;
    NormalPriority normal;
    ggml_threadpool_params tpp = ggml_threadpool_params_default(n);
    if (const char * v = std::getenv("IVY_POLL")) tpp.poll = (uint32_t) std::atoi(v);  // test switch (default 50)
    const std::vector<int> before = thread_ids();
    p.tp = pool_new(&tpp);
    watch.watch_new(before);
    return p;
}

int auto_threads() {
    const int fast = (int) fast_cores().size();
    // ponytail: cap 6 = best of 4/6/8 on the S26 speed test (2026-11-05)
    int n = fast == 0 ? (int) std::min(8u, std::max(1u, std::thread::hardware_concurrency())) : std::min(fast, 6);
    // Never take every core the process may use. ggml's threads wait for each other after every step, so when one of
    // them shares its core with another app (a background sync, the bubble being drawn) all of them wait.
    // Tried and dropped (CMF, 4 big + 4 little, 2026-11-10): the big cores only, minus one, was 40% faster with a big
    // core fully busy but 28% slower in normal use (60 s dictation 5.8 vs 4.6 s).
    const int allowed = allowed_cores();
    if (allowed > 1) n = std::min(n, allowed - 1);
    return std::max(n, 1);
}

}  // namespace

struct IvyEngine {
    llama_model * model = nullptr;
    llama_context * lctx = nullptr;
    mtmd_context * mctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<int> n_threads{0};  // now; the busy-core guard may change it
    BusyGuard guard;
    Pool pool;
    Pool pool_batch;  // test switch IVY_TB: batches (draft checks, audio windows) on more threads
    // prefix cache (system prompt in memory)
    std::vector<llama_token> prefix_tokens;
    int prefix_n_past = 0;
    // live state
    std::vector<std::string> dict;
    int n_past = 0;
    size_t committed = 0;
    std::vector<std::optional<float>> gains;
    std::optional<double> mel_max;
    std::vector<float> read_from;
    std::string disabled;
    // drafts written while talking (sequence 1), checked at the end
    std::atomic<bool> stop{false};
    size_t piece_start = 0;      // samples of this dictation already written as closed pieces
    std::string pieces_text;     // their text
    int pieces = 0;
    std::vector<float> piece_buf;  // a later piece's audio with PIECE_PAD of silence in front
    std::atomic<bool> in_draft{false};  // a draft is running: the speaker letting go aborts it mid-pass
    std::vector<llama_token> draft;
    size_t last_draft_samples = 0;
    int drafts = 0;
};

static llama_memory_t mem(IvyEngine * e) { return llama_get_memory(e->lctx); }
static void seq_rm(IvyEngine * e, int seq, int from) { llama_memory_seq_rm(mem(e), seq, from, -1); }
// keep only the system prompt in memory
static void keep_prefix(IvyEngine * e) {
    seq_rm(e, 0, e->prefix_n_past);
    seq_rm(e, 1, 0);
}

// One model pass (fn) under the busy-core guard (see BusyGuard): this thread is compute thread 0, the pool's
// workers the rest; the encoder runs on the same pool.
template <class F> static auto guarded(IvyEngine * e, F fn) {
    double ms = 0, waited = 0;
    auto r = e->guard.waits.run(fn, ms, waited);
    const int n = e->n_threads, to = e->guard.after(n, ms, waited);
    if (to != n) {
        e->n_threads = to;
        llama_set_n_threads(e->lctx, to, to);
        mtmd_ivy_set_n_threads(e->mctx, to);
    }
    return r;
}

static bool tokenize(IvyEngine * e, const std::vector<float> & audio, Chunks & out) {
    mtmd_bitmap * bm = mtmd_bitmap_init_from_audio(audio.size(), audio.data());
    if (!bm) return false;
    std::string prompt = format_prompt(e->dict);
    mtmd_input_text txt{prompt.c_str(), prompt.size(), false, true};
    const mtmd_bitmap * bms[1] = {bm};
    int r = mtmd_tokenize(e->mctx, out.p, &txt, bms, 1);
    mtmd_bitmap_free(bm);
    return r == 0;
}

static bool eval_chunk(IvyEngine * e, const Chunks & c, size_t i, int n_past, int seq, bool logits_last, int & out) {
    llama_pos np = n_past;
    if (std::getenv("IVY_DEBUG"))
        std::fprintf(stderr, "chunk %zu: %zu tokens (seq %d)\n", i, mtmd_input_chunk_get_n_tokens(c.get(i)), seq);
    if (guarded(e, [&] { return mtmd_helper_eval_chunk_single(e->mctx, e->lctx, c.get(i), n_past, seq, N_BATCH, logits_last, &np); }) != 0)
        return false;
    out = np;
    return true;
}

// system prompt into memory, or reuse it from the last dictation
static bool ensure_prefix(IvyEngine * e, const Chunks & c) {
    auto toks = c.text_tokens(0);
    if (e->prefix_n_past > 0 && toks == e->prefix_tokens) {
        keep_prefix(e);
        return true;
    }
    // forget the cells only (data=false): rewriting the whole cache with zeros would make all of it resident again;
    // stale values sit behind the attention mask and never count
    llama_memory_clear(mem(e), false);
    int np = 0;
    if (!eval_chunk(e, c, 0, 0, 0, false, np)) return false;
    e->prefix_tokens = toks;
    e->prefix_n_past = np;
    return true;
}

static bool is_stop_token(IvyEngine * e, llama_token t) {
    return llama_vocab_is_eog(e->vocab, t) || t == 151645 || t == 151643;
}

// same rule as desktop argmax: strictly greater, so the first of equal logits wins
static std::optional<llama_token> argmax(IvyEngine * e, int i) {
    const float * lg = llama_get_logits_ith(e->lctx, i);
    if (!lg) return std::nullopt;
    const int n_vocab = llama_vocab_n_tokens(e->vocab);
    return (llama_token) (std::max_element(lg, lg + n_vocab) - lg);
}

struct Decoded {
    std::vector<llama_token> tokens;
    int proposed = 0, accepted = 0, passes = 0;
    bool interrupted = false;
};

// Greedy decoding that checks draft tokens several at a time (desktop decode_with_draft). The model's own choice
// decides every token, so the text equals plain greedy decoding (up to batched-vs-single rounding). Logits for the
// first token must be ready. An empty draft is plain greedy, one token per pass.
static Decoded decode_with_draft(IvyEngine * e, int seq, int n_past, const std::vector<llama_token> & draft,
                                 size_t max_tokens, const std::atomic<bool> * stop, double yield_at_ms) {
    Decoded d;
    auto first = argmax(e, -1);
    if (!first) return d;
    llama_token cur = *first;
    const int max_k = max_propose();
    int k = max_k;
    size_t cursor = 0;
    size_t fresh = 0;  // tokens written past the end of the old draft
    static const size_t yield_min = [] { const char * v = std::getenv("IVY_YIELD_MIN"); return v ? (size_t) std::atoi(v) : 0; }();
    llama_batch b = llama_batch_init(MAX_PROPOSE + 1, 0, 1);
    while (true) {
        if (is_stop_token(e, cur) || d.tokens.size() >= max_tokens) break;
        d.tokens.push_back(cur);
        if (d.tokens.size() >= max_tokens) break;
        if (cursor >= draft.size()) fresh++;
        // yield only once the old draft is used up: what was written so far is then all fresh, nothing is lost
        if ((stop && stop->load()) ||
            (yield_at_ms > 0 && cursor >= draft.size() && fresh >= yield_min && now_ms() > yield_at_ms)) {
            d.interrupted = true;
            break;
        }
        auto props = propose(d.tokens, draft, cursor, std::min<size_t>(k, max_tokens - d.tokens.size()));
        b.n_tokens = 1 + (int) props.size();
        for (int i = 0; i < b.n_tokens; i++) {
            b.token[i] = i == 0 ? cur : props[i - 1];
            b.pos[i] = n_past + i;
            b.n_seq_id[i] = 1;
            b.seq_id[i][0] = seq;
            b.logits[i] = 1;
        }
        if (guarded(e, [&] { return llama_decode(e->lctx, b); }) != 0) {
            d.interrupted = stop && stop->load();  // the speaker let go mid-pass (draft_abort)
            break;
        }
        d.passes++;
        d.proposed += (int) props.size();
        // the model's choice after `cur`, then after each kept draft token
        size_t kept = 0;
        auto next = argmax(e, 0);
        if (!next) break;
        for (size_t i = 0; i < props.size(); i++) {
            if (*next != props[i]) break;
            kept++;
            d.tokens.push_back(props[i]);
            next = argmax(e, (int) i + 1);
            if (!next || d.tokens.size() >= max_tokens) break;
        }
        if (!next) break;
        if (seq == 0 && std::getenv("IVY_DEBUG"))
            std::fprintf(stderr, "pass: draft %zu/%zu offered %zu kept %zu\n", cursor, draft.size(), props.size(), kept);
        d.accepted += (int) kept;
        n_past += 1 + (int) kept;
        cursor += kept;
        if (kept < props.size()) {
            seq_rm(e, seq, n_past);  // forget the rejected draft tokens
            // next offer after a rejection: 8 (a pass costs about the same for 1-8 tokens on phone CPUs; "kept + 2"
            // either gambled big or crawled back up from 2). CMF 2026-11-09: 8.5/8.3 s -> 5.4/6.5 s after release.
            static const int k_rej = [] { const char * v = std::getenv("IVY_KREJ"); return v ? std::atoi(v) : 8; }();
            k = std::clamp(k_rej, 1, max_k);
        } else {
            k = std::min(k * 2, max_k);
        }
        if (d.tokens.size() >= max_tokens) break;
        cur = *next;
    }
    llama_batch_free(b);
    return d;
}

static std::string to_text(IvyEngine * e, const std::vector<llama_token> & toks) {
    std::string text;
    char buf[256];
    for (llama_token t : toks) {
        int nc = llama_token_to_piece(e->vocab, t, buf, sizeof(buf), 0, false);
        if (nc > 0) text.append(buf, nc);
    }
    auto ws = [](char ch) { return ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r'; };
    while (!text.empty() && ws(text.back())) text.pop_back();
    size_t s = 0;
    while (s < text.size() && ws(text[s])) s++;
    return text.substr(s);
}

static size_t max_new_tokens(size_t n) { return (size_t) (n / (float) SR * 6.0f) + 64; }

// ------------------------------------------------------------------------------------------------
// Which CPU code to load. llama.cpp builds its CPU code once per ARM instruction-set level (libggml-cpu-android_
// armv8.0_1 ... armv9.2_2) and by default loads the newest level the chip supports. Newest is not always fastest:
// on the S26 (Snapdragon 8 Elite Gen 5) the SVE/SME build read audio 2.5x slower than the plain fp16 build, same
// text (2026-11-09). A list of phones would never be complete, so Ivy times every level the chip supports on a small
// slice of its own work and loads the fastest. The choice is kept in a file until the app is updated. A hot phone
// (CPU speed capped) is not measured: llama.cpp's pick is used until a later start, when it has cooled down.

#if defined(__linux__)
namespace {

std::string g_cpu_choice = "llama.cpp default";  // for the test log

// Timings (ms) of one CPU code version on a slice of Ivy's work; inf = cannot run it / skipped. stalled = a part
// never ran without one of its threads waiting for a core (another app busy): no valid timing.
struct CpuTimes {
    double ear = INFINITY, window = INFINITY, steps = INFINITY;
    bool stalled = false;
    double total() const { return ear + window + steps; }
};

// Text model shapes (Qwen3-0.6B layer): q, k, v, o, gate, up, down.
constexpr int64_t TXT_W[7][2] = {{1024, 2048}, {1024, 1024}, {1024, 1024}, {2048, 1024}, {1024, 3072}, {1024, 3072}, {3072, 1024}};

// Bench inputs, made once for all versions: pseudo-random numbers (no denormals/NaN, which change the speed), the
// same as f16 and as Q8_0 rows.
struct BenchData {
    std::vector<float> f32;
    std::vector<ggml_fp16_t> f16;
    std::vector<uint8_t> q8[7];
};
const BenchData & bench_data() {
    static BenchData d = [] {
        BenchData b;
        b.f32.resize(3584 * 896);
        uint32_t s = 12345;
        for (float & x : b.f32) { s = s * 1664525u + 1013904223u; x = ((s >> 9) / 8388608.0f - 0.5f) * 0.2f; }
        b.f16.resize(b.f32.size());
        ggml_fp32_to_fp16_row(b.f32.data(), b.f16.data(), (int64_t) b.f32.size());
        for (int i = 0; i < 7; i++) {
            b.q8[i].resize(ggml_row_size(GGML_TYPE_Q8_0, TXT_W[i][0]) * TXT_W[i][1]);
            ggml_quantize_chunk(GGML_TYPE_Q8_0, b.f32.data(), b.q8[i].data(), 0, TXT_W[i][1], TXT_W[i][0], nullptr);
        }
        return b;
    }();
    return d;
}

// Can this extra buffer type (the CPU's repacked weight layout) take a Q8_0 matrix multiply? Same test llama.cpp
// uses when it places the model's weights, so the bench uses the layout the real model will get.
bool q8_repack_ok(ggml_backend_dev_t dev, ggml_backend_buffer_type_t buft) {
    ggml_init_params p = {ggml_tensor_overhead() * 4, nullptr, true};
    ggml_context * ctx = ggml_init(p);
    ggml_tensor * w = ggml_new_tensor_2d(ctx, GGML_TYPE_Q8_0, 1024, 2048);
    ggml_tensor * op = ggml_mul_mat(ctx, w, ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 1024, 104));
    w->buffer = ggml_backend_buft_alloc_buffer(buft, 0);
    const bool ok = w->buffer && ggml_backend_dev_supports_op(dev, op);
    if (w->buffer) ggml_backend_buffer_free(w->buffer);
    w->buffer = nullptr;
    ggml_free(ctx);
    return ok;
}

// One CPU code version, timed on: the audio encoder's front end (3x3 conv, 400 positions) and one encoder block on
// an 8 s window (f16 weights, flash attention); one text-model layer (Q8_0, repacked when the CPU has a layout for it)
// on a 104-token audio window; the same layer 24 times on 4 tokens (checking a draft / writing). Fastest single runs.
// The three parts take about the shares of a real dictation (S26: 10 + 6 + 12 ms; real 60 s: ~30/25/45 %).
// give_up_ms: stop after the encoder part if it alone is already slower than that.
CpuTimes time_cpu_code(ggml_backend_reg_t reg, int n_threads, double give_up_ms) {
    CpuTimes t;
    ggml_backend_dev_t dev = ggml_backend_reg_dev_count(reg) ? ggml_backend_reg_dev_get(reg, 0) : nullptr;
    ggml_backend_t be = dev ? ggml_backend_dev_init(dev, nullptr) : nullptr;
    if (!be) return t;
    if (auto f = (ggml_backend_set_n_threads_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads")) f(be, n_threads);
    // workers kept for all runs (this version's own threadpool), so the busy-core sensor can watch them
    auto * pool_new = (decltype(ggml_threadpool_new) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
    auto * pool_free = (decltype(ggml_threadpool_free) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
    auto * pool_set = (decltype(ggml_backend_cpu_set_threadpool) *) ggml_backend_reg_get_proc_address(reg, "ggml_backend_cpu_set_threadpool");
    ggml_threadpool_t pool = nullptr;
    CoreWait cw;
    if (pool_new && pool_free && pool_set) {
        ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads);
        const std::vector<int> before = thread_ids();
        pool = pool_new(&tpp);
        cw.watch_new(before);
        if (pool) pool_set(be, pool);
    }
    ggml_backend_buffer_type_t buft = ggml_backend_dev_buffer_type(dev), qbuft = buft;
    if (auto f = (ggml_backend_dev_get_extra_bufts_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_dev_get_extra_bufts"))
        for (auto * p = f(dev); p && *p; ++p)
            if (q8_repack_ok(dev, *p)) { qbuft = *p; break; }

    const BenchData & D = bench_data();
    ggml_init_params wp = {ggml_tensor_overhead() * 16, nullptr, true};
    ggml_context * cf = ggml_init(wp), * cq = ggml_init(wp);
    ggml_tensor * conv = ggml_new_tensor_4d(cf, GGML_TYPE_F16, 3, 3, 480, 480);
    ggml_tensor * ew[6];
    for (int i = 0; i < 4; i++) ew[i] = ggml_new_tensor_2d(cf, GGML_TYPE_F16, 896, 896);
    ew[4] = ggml_new_tensor_2d(cf, GGML_TYPE_F16, 896, 3584);
    ew[5] = ggml_new_tensor_2d(cf, GGML_TYPE_F16, 3584, 896);
    ggml_tensor * tw[7];
    for (int i = 0; i < 7; i++) tw[i] = ggml_new_tensor_2d(cq, GGML_TYPE_Q8_0, TXT_W[i][0], TXT_W[i][1]);
    ggml_backend_buffer_t bf = ggml_backend_alloc_ctx_tensors_from_buft(cf, buft);
    ggml_backend_buffer_t bq = ggml_backend_alloc_ctx_tensors_from_buft(cq, qbuft);
    ggml_gallocr_t ga = ggml_gallocr_new(buft);
    ggml_init_params gp = {ggml_tensor_overhead() * 256 + 3 * ggml_graph_overhead(), nullptr, true};
    ggml_context * cg = ggml_init(gp);
    if (bf && bq && ga && cg) {
        ggml_backend_tensor_set(conv, D.f16.data(), 0, ggml_nbytes(conv));
        for (ggml_tensor * w : ew) ggml_backend_tensor_set(w, D.f16.data(), 0, ggml_nbytes(w));
        // through set_tensor, so a repacking buffer converts them like the real weights
        for (int i = 0; i < 7; i++) ggml_backend_tensor_set(tw[i], D.q8[i].data(), 0, D.q8[i].size());

        // audio encoder slice
        ggml_tensor * mel = ggml_new_tensor_4d(cg, GGML_TYPE_F32, 50, 32, 480, 1);
        ggml_tensor * x0 = ggml_new_tensor_2d(cg, GGML_TYPE_F32, 896, 104);
        ggml_set_input(mel);
        ggml_set_input(x0);
        ggml_tensor * front = ggml_gelu_erf(cg, ggml_conv_2d(cg, conv, mel, 2, 2, 1, 1, 1, 1));
        ggml_tensor * h = ggml_norm(cg, x0, 1e-5f);
        auto heads = [&](ggml_tensor * w) { return ggml_permute(cg, ggml_reshape_3d(cg, ggml_mul_mat(cg, w, h), 64, 14, 104), 0, 2, 1, 3); };
        ggml_tensor * att = ggml_flash_attn_ext(cg, heads(ew[0]), ggml_cast(cg, heads(ew[1]), GGML_TYPE_F16),
                                                ggml_cast(cg, heads(ew[2]), GGML_TYPE_F16), nullptr, 0.125f, 0.0f, 0.0f);
        ggml_prec_set_acc(att, GGML_PREC_F32);
        ggml_tensor * y = ggml_add(cg, x0, ggml_mul_mat(cg, ew[3], ggml_reshape_2d(cg, att, 896, 104)));
        y = ggml_add(cg, y, ggml_mul_mat(cg, ew[5], ggml_gelu(cg, ggml_mul_mat(cg, ew[4], ggml_norm(cg, y, 1e-5f)))));
        ggml_cgraph * g_ear = ggml_new_graph(cg);
        ggml_build_forward_expand(g_ear, front);
        ggml_build_forward_expand(g_ear, y);

        // text model layer (attention itself left out: weights dominate)
        auto text_layer = [&](int T, ggml_tensor ** in) {
            ggml_tensor * x = *in = ggml_new_tensor_2d(cg, GGML_TYPE_F32, 1024, T);
            ggml_set_input(x);
            ggml_tensor * n = ggml_rms_norm(cg, x, 1e-6f);
            ggml_tensor * q = ggml_mul_mat(cg, tw[0], n);
            ggml_tensor * kv = ggml_add(cg, ggml_mul_mat(cg, tw[1], n), ggml_mul_mat(cg, tw[2], n));
            ggml_tensor * z = ggml_add(cg, ggml_add(cg, x, ggml_mul_mat(cg, tw[3], q)), kv);
            n = ggml_rms_norm(cg, z, 1e-6f);
            z = ggml_add(cg, z, ggml_mul_mat(cg, tw[6], ggml_swiglu_split(cg, ggml_mul_mat(cg, tw[4], n), ggml_mul_mat(cg, tw[5], n))));
            ggml_cgraph * g = ggml_new_graph(cg);
            ggml_build_forward_expand(g, z);
            return g;
        };
        ggml_tensor * xw = nullptr, * xs = nullptr;
        ggml_cgraph * g_win = text_layer(104, &xw);
        ggml_cgraph * g_step = text_layer(4, &xs);

        // One untimed run first (first-touch page faults; wakes the cores up to speed), then the FASTEST of n clean
        // single runs. A run in which a thread waited for a core is the other app's time, not the code's: one busy
        // core turned a block of 24 steps from 10 ms into 1600 ms and the pick went wrong (S26 2026-11-10). Up to
        // 3n tries; none clean = stalled.
        auto run = [&](ggml_cgraph * g, std::vector<ggml_tensor *> ins, int n) {
            if (!ggml_gallocr_alloc_graph(ga, g)) return (double) INFINITY;
            for (ggml_tensor * in : ins) ggml_backend_tensor_set(in, D.f32.data(), 0, ggml_nbytes(in));
            if (ggml_backend_graph_compute(be, g) != GGML_STATUS_SUCCESS) return (double) INFINITY;
            double best = INFINITY;
            int clean = 0;
            for (int k = 0; k < 3 * n && clean < n; k++) {
                double ms = 0, waited = 0;
                if (cw.run([&] { return ggml_backend_graph_compute(be, g); }, ms, waited) != GGML_STATUS_SUCCESS)
                    return (double) INFINITY;
                // waits from the phone's normal background work stay under a quarter of a run (S26 idle: up to
                // 23%); a thread sharing its core with a busy app waits about half the run or more
                if (waited > 0.25 * ms) continue;
                clean++;
                best = std::min(best, ms);
            }
            if (clean == 0) t.stalled = true;
            return best;
        };
        t.ear = run(g_ear, {mel, x0}, 4);
        if (t.ear < give_up_ms) {  // already far slower: skip the rest
            t.window = run(g_win, {xw}, 4);
            t.steps = 24 * run(g_step, {xs}, 24);  // writing = many small steps: 24 x the fastest one
        }
    }
    if (cg) ggml_free(cg);
    if (ga) ggml_gallocr_free(ga);
    if (bq) ggml_backend_buffer_free(bq);
    if (bf) ggml_backend_buffer_free(bf);
    ggml_free(cq);
    ggml_free(cf);
    ggml_backend_free(be);
    if (pool) pool_free(pool);  // after the backend that points to it
    return t;
}

std::string exe_dir() {
    char buf[4096];
    const ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n <= 0) return ".";
    std::string p(buf, (size_t) n);
    const size_t s = p.rfind('/');
    return s == std::string::npos ? "." : p.substr(0, s);
}

// Android's thermal status (0 none, 1 light, 2 moderate = CPU speed capped, ...); -1 = unknown. Looked up at run
// time: the call exists from Android 11 and the app supports older phones.
int thermal_status() {
#if defined(__ANDROID__)
    static void * lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return -1;
    auto acquire = (void * (*)()) dlsym(lib, "AThermal_acquireManager");
    auto status = (int (*)(void *)) dlsym(lib, "AThermal_getCurrentThermalStatus");
    auto release = (void (*)(void *)) dlsym(lib, "AThermal_releaseManager");
    if (!acquire || !status || !release) return -1;
    void * m = acquire();
    if (!m) return -1;
    const int s = status(m);
    release(m);
    return s;
#else
    return -1;
#endif
}

struct CpuLib {
    std::string file;
    int score = 0;
    ggml_backend_reg_t reg = nullptr;
    CpuTimes t;
};

std::string short_name(const std::string & file) { return file.substr(12, file.size() - 15); }  // android_armv8.2_2

// Loads the fastest CPU code in lib_dir (see above). False = nothing loaded (caller falls back to llama.cpp's pick).
bool load_fastest_cpu(const std::string & lib_dir, const char * cache_dir) {
    const char * force = std::getenv("IVY_CPU");  // test switch: a file name, or "default" for llama.cpp's own pick
    if (force && std::string(force) == "default") return false;
    std::vector<std::string> files;
    if (force) files.push_back(force);
    else if (DIR * d = opendir(lib_dir.c_str())) {
        while (dirent * f = readdir(d)) {
            const std::string n = f->d_name;
            if (n.rfind("libggml-cpu-", 0) == 0 && n.size() > 15 && n.compare(n.size() - 3, 3, ".so") == 0) files.push_back(n);
        }
        closedir(d);
    }
    if (files.empty()) return false;
    // Timed with the engine's thread count, at normal priority (a background load runs below it, and then every
    // app in use takes the cores from the measuring). Timed on 2 threads instead it was robust but took 6-10 s.
    const int nt = auto_threads();
    NormalPriority normal;
    // the choice holds for this install of the app (the folder changes on every update)
    const std::string key = lib_dir;
    const std::string cache = cache_dir ? std::string(cache_dir) + "/cpu_code.txt" : "";
    std::string pick, report;
    if (files.size() == 1) pick = files[0];
    if (pick.empty() && !cache.empty())
        if (FILE * f = std::fopen(cache.c_str(), "r")) {
            char k[4096] = {0}, v[256] = {0};
            if (std::fscanf(f, "%4095[^\n]\n%255s", k, v) == 2 && key == k) { pick = v; report = " (kept)"; }
            std::fclose(f);
        }
    // 3 = severe (CPU clearly throttled). A phone in normal use is warm (1-2) and still measures fine with the
    // fastest-run timing above; skipping at 2 would leave most phones on llama.cpp's pick.
    const int hot = pick.empty() ? thermal_status() : -1;
    if (pick.empty() && hot >= 3) {
        if (std::getenv("IVY_DEBUG")) std::fprintf(stderr, "cpu code: phone hot (thermal %d), not measured\n", hot);
        return false;
    }
    if (pick.empty()) {
        std::vector<CpuLib> libs;
        for (const std::string & n : files) {
            void * h = dlopen((lib_dir + "/" + n).c_str(), RTLD_NOW | RTLD_LOCAL);
            if (!h) continue;
            auto score = (int (*)()) dlsym(h, "ggml_backend_score");
            auto init = (ggml_backend_reg_t (*)()) dlsym(h, "ggml_backend_init");
            // score 0 = this chip lacks an instruction the version needs (running it would crash)
            const int s = score && init ? score() : 0;
            if (s == 0) { dlclose(h); continue; }
            // kept loaded: the CPU code may hold thread-local state; a few MB of mapped code, nothing more
            if (ggml_backend_reg_t reg = init()) libs.push_back({n, s, reg, {}});
        }
        if (libs.empty()) return false;
        // llama.cpp's own pick (highest score) first; the second pass runs the closest ones again in reverse order,
        // so a phone slowly warming up does not favour whichever went first
        std::sort(libs.begin(), libs.end(), [](const CpuLib & a, const CpuLib & b) { return a.score > b.score; });
        // all versions timed on t threads; false = a timing stalled (a thread kept sharing its core with a busy app)
        auto measure = [&](int t) {
            double best = INFINITY;
            for (CpuLib & c : libs) {
                c.t = time_cpu_code(c.reg, t, 1.5 * best);
                if (c.t.stalled) return false;
                best = std::min(best, c.t.total());
            }
            for (auto it = libs.rbegin(); it != libs.rend(); ++it)
                if (it->t.total() < 2 * best) {
                    const CpuTimes t2 = time_cpu_code(it->reg, t, INFINITY);  // each part keeps its faster pass
                    it->t.ear = std::min(it->t.ear, t2.ear);
                    it->t.window = std::min(it->t.window, t2.window);
                    it->t.steps = std::min(it->t.steps, t2.steps);
                }
            return true;
        };
        // Busy cores: one thread fewer per try (which version is fastest does not depend on the thread count; a
        // stalled try stops at its first stall, well under a second). All cores busy: nothing is kept, llama.cpp's
        // pick runs now and the next start measures again.
        int t = nt;
        while (!measure(t) && --t >= 1) {}
        if (t < 1) {
            g_cpu_choice = "llama.cpp default (cores busy while measuring; next start measures again)";
            if (std::getenv("IVY_DEBUG")) std::fprintf(stderr, "cpu code: %s\n", g_cpu_choice.c_str());
            return false;
        }
        const std::string head = t < nt ? " (measured on " + std::to_string(t) + " threads, cores busy, ms: " : " (measured, ms: ";
        const CpuLib * win = &libs[0];
        for (const CpuLib & c : libs)
            if (c.t.total() < win->t.total()) win = &c;
        // move away from llama.cpp's pick only when clearly faster: a near tie is noise
        if (!(win->t.total() < 0.9 * libs[0].t.total())) win = &libs[0];
        pick = win->file;
        for (const CpuLib & c : libs) {
            char line[160];
            std::snprintf(line, sizeof(line), "%s%s %.0f+%.0f+%.0f", report.empty() ? head.c_str() : ", ",
                          short_name(c.file).c_str(), c.t.ear, c.t.window, c.t.steps);
            report += line;
        }
        report += ")";
        if (!cache.empty())
            if (FILE * f = std::fopen(cache.c_str(), "w")) { std::fprintf(f, "%s\n%s\n", key.c_str(), pick.c_str()); std::fclose(f); }
    }
    if (!ggml_backend_load((lib_dir + "/" + pick).c_str())) return false;
    g_cpu_choice = short_name(pick) + report;
    if (std::getenv("IVY_DEBUG")) std::fprintf(stderr, "cpu code: %s\n", g_cpu_choice.c_str());
    return true;
}

}  // namespace
#endif

std::string ivy_cpu_choice() {
#if defined(__linux__)
    return g_cpu_choice;
#else
    return "llama.cpp default";
#endif
}

// once, even when the speech engine and the rewrite model load on two threads at the same time
static std::once_flag g_backend;

void ivy_backend_init(const char * lib_dir, const char * cache_dir) {
    std::call_once(g_backend, [lib_dir, cache_dir] {
        bool done = false;
#if defined(__linux__)
        // the test program keeps its choice next to itself
        const std::string dir = lib_dir ? lib_dir : exe_dir();
        done = load_fastest_cpu(dir, lib_dir ? cache_dir : dir.c_str());
#endif
        if (!done) {
            if (lib_dir) ggml_backend_load_all_from_path(lib_dir);
            else ggml_backend_load_all();
        }
        llama_backend_init();
    });
}

static bool draft_abort(void * data);

IvyEngine * ivy_load(const char * model_path, const char * mmproj_path, int n_threads, int n_gpu_layers, std::string & err) {
    ivy_backend_init(nullptr);
    if (n_threads <= 0) n_threads = auto_threads();
    auto * e = new IvyEngine();
    e->n_threads = n_threads;
    e->guard.start(n_threads);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    e->model = llama_model_load_from_file(model_path, mp);
    if (!e->model) { err = "model load failed"; delete e; return nullptr; }
    e->vocab = llama_model_get_vocab(e->model);
    mtmd_context_params tp = mtmd_context_params_default();
    tp.use_gpu = n_gpu_layers > 0;
    tp.print_timings = false;
    const int n_threads_batch = std::getenv("IVY_TB") ? std::max(1, std::atoi(std::getenv("IVY_TB"))) : n_threads;
    // fast cores only, like the text model: giving the encoder all 8 cores left none for drawing the bubble
    // (CMF 2026-11-09) and its gain was within noise
    tp.n_threads = std::getenv("IVY_ET") ? std::max(1, std::atoi(std::getenv("IVY_ET"))) : n_threads;
    tp.warmup = true;  // first encode is cold (3-6 s on the S26): pay it at load, not after the first dictation
    if (const char * fa = std::getenv("IVY_EAR_FA"))  // test switch for the ear: on / off (default auto)
        tp.flash_attn_type = std::string(fa) == "off" ? LLAMA_FLASH_ATTN_TYPE_DISABLED : LLAMA_FLASH_ATTN_TYPE_ENABLED;
    e->mctx = mtmd_init_from_file(mmproj_path, e->model, tp);
    if (!e->mctx) { err = "ear (mmproj) load failed"; ivy_free(e); return nullptr; }
    mtmd_ivy_set_abort_callback(e->mctx, draft_abort, e);  // a draft still encoding audio gives way on release
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = N_BATCH;
    // Largest batch Ivy ever sends is one 8 s audio window (104 tokens); 512 reserved a 303 MB compute buffer for
    // attention over 512 tokens at once. 128 keeps every batch whole (same results) at about a quarter of the memory.
    cp.n_ubatch = 128;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads_batch;
    cp.n_seq_max = 2;      // sequence 1 = scratch copy for drafts
    // Flash attention off: on phone CPUs it made reading each 8 s window grow from 0.9 s to 4.3 s over a minute
    // (S26, 2026-11-05); off = 0.2-0.5 s, same text. IVY_FA=on brings it back for tests.
    const char * fa = std::getenv("IVY_FA");
    cp.flash_attn_type = fa && std::string(fa) == "on" ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.kv_unified = true;  // both sequences share the audio already in memory
    e->lctx = llama_init_from_model(e->model, cp);
    if (!e->lctx) { err = "context init failed"; ivy_free(e); return nullptr; }
    llama_set_abort_callback(e->lctx, draft_abort, e);
    if (!std::getenv("IVY_NOPOOL")) {
        e->pool = new_pool(n_threads, e->guard.waits);
        if (e->pool.tp && n_threads_batch != n_threads) e->pool_batch = new_pool(n_threads_batch, e->guard.waits);
        if (e->pool.tp) {
            llama_attach_threadpool(e->lctx, e->pool.tp, e->pool_batch.tp ? e->pool_batch.tp : e->pool.tp);
            // the ear on the same threads: with threads of its own it competed with the pool's workers, which keep
            // spinning a while after each text pass (S26 2026-11-10: an 8 s window encoded while the pool's workers
            // waited 231 ms for cores). Same speed in an A/B, and the busy-core guard sees the ear too.
            mtmd_ivy_set_threadpool(e->mctx, e->pool.tp);
        }
    }
    return e;
}

int ivy_threads(const IvyEngine * e) { return e->n_threads; }

void ivy_free(IvyEngine * e) {
    if (!e) return;
    if (e->lctx) llama_free(e->lctx);
    e->pool.free();  // after the context that uses it
    e->pool_batch.free();
    if (e->mctx) mtmd_free(e->mctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

// Live state of the current piece (the system prompt stays in memory).
static void piece_reset(IvyEngine * e) {
    e->n_past = 0;
    e->committed = 0;
    e->gains.clear();
    e->mel_max.reset();
    std::fill(e->read_from.begin(), e->read_from.end(), 0.0f);
    e->read_from.clear();
    e->disabled.clear();
    e->stop = false;
    e->draft.clear();
    e->last_draft_samples = 0;
    e->drafts = 0;
}

void ivy_live_reset(IvyEngine * e, const std::vector<std::string> & dictionary) {
    if (dictionary != e->dict) e->prefix_n_past = 0;  // new system prompt
    e->dict = dictionary;
    piece_reset(e);
    e->piece_start = 0;
    e->pieces_text.clear();
    e->pieces = 0;
    e->guard.fewest = e->n_threads;
}

void ivy_live_interrupt(IvyEngine * e) { e->stop = true; }

// llama.cpp abort hook: only a running draft gives way when the speaker lets go (window reads and finish() never do)
static bool draft_abort(void * data) {
    auto * e = static_cast<IvyEngine *>(data);
    return e->in_draft.load() && e->stop.load();
}

// Draft of everything heard so far, in a scratch copy of the memory (sequence 1). Runs to the end and only stops
// when the speaker lets go.
static void write_draft(IvyEngine * e, const float * pcm, size_t n) {
    double t0 = now_ms();
    struct InDraft {  // set while this draft runs, cleared on every return path
        IvyEngine * e;
        explicit InDraft(IvyEngine * e) : e(e) { e->in_draft = true; }
        ~InDraft() { e->in_draft = false; }
    } in_draft(e);
    seq_rm(e, 1, 0);
    llama_memory_seq_cp(mem(e), 0, 1, -1, -1);
    std::vector<float> scaled = apply_gains(pcm, n, window_gains(pcm, n, e->gains));
    Chunks c;
    bool ok;
    {
        PinMel pin(e->mel_max);
        ok = tokenize(e, scaled, c);
    }
    int n_audio = ok ? c.audio_count() : -1;
    int np = e->n_past;
    ok = n_audio >= (int) e->committed;
    for (int i = 1 + (int) e->committed; ok && i <= n_audio && !e->stop; i++) ok = eval_chunk(e, c, i, np, 1, false, np);
    ok = ok && !e->stop && eval_chunk(e, c, n_audio + 1, np, 1, true, np);
    if (ok) {
        // Gives way to the next 8 s window only after the old draft is checked (decode_with_draft): cut short while
        // checking, a draft is half old, the next check rejects most of it and is cut short again (CMF Phone 1,
        // 2026-11-09: last whole draft at 34 s of 59 s, 9.9-17 s after release); never giving way left windows
        // unread at release (+1.5 s).
        size_t next_window = (e->committed + 1) * WINDOW + LOOKAHEAD;
        double yield_at = now_ms() + (next_window > n ? (next_window - n) * 1000.0 / SR : 0.0);
        Decoded d = decode_with_draft(e, 1, np, e->draft, max_new_tokens(n), &e->stop, yield_at);
        if (std::getenv("IVY_DEBUG"))
            std::fprintf(stderr, "draft %d at %.1f s: %zu tok%s, kept %d/%d in %d passes, %.0f ms (old draft %zu tok)\n",
                         e->drafts + 1, n / (double) SR, d.tokens.size(), d.interrupted ? " INTERRUPTED" : "", d.accepted,
                         d.proposed, d.passes, now_ms() - t0, e->draft.size());
        e->draft = d.interrupted ? merge_draft(std::move(d.tokens), e->draft) : std::move(d.tokens);
        e->drafts++;
    }
    seq_rm(e, 1, 0);
    e->last_draft_samples = n;
}

static void close_piece(IvyEngine * e, const float * pcm, size_t n);

// The current piece's audio (everything below works on the piece): the recording from piece_start, with PIECE_PAD
// of silence in front for every piece after the first.
static const float * piece_audio(IvyEngine * e, const float * pcm_all, size_t n_all, size_t & n) {
    const float * p = pcm_all + std::min(e->piece_start, n_all);
    n = n_all - std::min(e->piece_start, n_all);
    if (e->pieces == 0) return p;
    e->piece_buf.assign(PIECE_PAD, 0.0f);
    e->piece_buf.insert(e->piece_buf.end(), p, p + n);
    n = e->piece_buf.size();
    return e->piece_buf.data();
}

bool ivy_live_step(IvyEngine * e, const float * pcm_all, size_t n_all) {
    if (n_all <= e->piece_start) return false;
    size_t n = 0;
    const float * pcm = piece_audio(e, pcm_all, n_all, n);
    if (!e->disabled.empty() || e->stop || n < SR / 2) return false;
    if (n + WINDOW > CHUNK_SECS * SR) { e->disabled = "recording too long for live reading"; return false; }
    if (e->n_past > 0 && e->committed >= PIECE_WINDOWS && n >= PIECE_CUT_SECS * SR) {
        close_piece(e, pcm, n);
        return true;
    }
    if (e->n_past > 0 && (e->committed >= complete_windows(n) || e->committed >= PIECE_WINDOWS)) {
        // no window due: a fresher draft as soon as 2 s more were spoken, so the one checked after release is recent
        // (back to back: CMF 59 s clip 6.2-7.7 s -> 3.5-3.7 s after release, same text)
        if (drafts_off() || n < 3 * SR || n < e->last_draft_samples + 2 * SR) return false;
        write_draft(e, pcm, n);
        return true;
    }
    auto g = window_gains(pcm, n, e->gains);
    std::vector<float> scaled = apply_gains(pcm, n, g);
    Chunks c;
    {
        PinMel pin(e->mel_max);
        if (!tokenize(e, scaled, c)) { e->disabled = "tokenize failed"; return false; }
    }
    if (!e->mel_max) e->mel_max = mtmd_ivy_last_mel_max();
    int n_audio = c.audio_count();
    if (n_audio < 0) { e->disabled = "unexpected prompt layout"; return false; }
    if (e->n_past == 0) {  // first step of this dictation: system prompt
        if (!ensure_prefix(e, c)) { e->disabled = "prefix eval failed"; return false; }
        e->n_past = e->prefix_n_past;
        return true;
    }
    if (e->committed >= complete_windows(n) || (int) e->committed >= n_audio) return false;
    int np = 0;
    if (!eval_chunk(e, c, 1 + e->committed, e->n_past, 0, false, np)) { e->disabled = "window eval failed"; return false; }
    e->n_past = np;
    e->gains.push_back(g[e->committed]);
    e->committed++;
    e->read_from.assign(pcm, pcm + n);
    return true;
}

static void write_text(IvyEngine * e, int np, size_t n, const std::vector<llama_token> & draft, IvyReport & rep,
                       std::string & text) {
    double t1 = now_ms();
    Decoded d = decode_with_draft(e, 0, np, draft, max_new_tokens(n), nullptr, 0);
    rep.out_tokens += (int) d.tokens.size();
    rep.proposed += d.proposed;
    rep.accepted += d.accepted;
    rep.passes += d.passes;
    rep.write_ms += now_ms() - t1;
    std::string t = to_text(e, d.tokens);
    if (!t.empty()) text += (text.empty() ? "" : " ") + t;
}

static void read_all_and_write(IvyEngine * e, const float * pcm, size_t n, const std::vector<llama_token> & draft,
                               IvyReport & rep, std::string & text) {
    double t0 = now_ms();
    std::vector<float> audio(pcm, pcm + n);
    if (auto g = normalize_gain(pcm, n)) for (auto & x : audio) x = std::clamp(x * *g, -1.0f, 1.0f);
    Chunks c;
    if (!tokenize(e, audio, c)) return;
    int n_audio = c.audio_count();
    if (n_audio < 0 || !ensure_prefix(e, c)) return;
    int np = e->prefix_n_past;
    for (int i = 1; i <= n_audio; i++) if (!eval_chunk(e, c, i, np, 0, false, np)) return;
    if (!eval_chunk(e, c, n_audio + 1, np, 0, true, np)) return;
    rep.windows_at_end += n_audio;
    rep.read_ms += now_ms() - t0;
    write_text(e, np, n, draft, rep, text);
    keep_prefix(e);
}

// longer than one model pass: cut at the quietest 100 ms in the last 20 s before each 120 s limit (lite.rs)
static std::vector<std::pair<size_t, size_t>> split_long(size_t n, const float * s) {
    const size_t win = SR / 10, limit = CHUNK_SECS * SR;
    std::vector<std::pair<size_t, size_t>> out;
    size_t start = 0;
    while (n - start > limit) {
        size_t best = limit;
        float be = INFINITY;
        for (size_t i = limit - 20 * SR; i + win <= limit; i += win / 2) {
            float en = 0;
            for (size_t j = 0; j < win; j++) en += s[start + i + j] * s[start + i + j];
            if (en < be) { be = en; best = i + win / 2; }
        }
        out.push_back({start, best});
        start += best;
    }
    out.push_back({start, n - start});
    return out;
}

static std::string transcribe_with(IvyEngine * e, const float * pcm, size_t n, const std::vector<llama_token> & draft,
                                   IvyReport & rep) {
    std::string all;
    for (auto [s, len] : split_long(n, pcm)) read_all_and_write(e, pcm + s, len, draft, rep, all);
    return all;
}

// in the report when the busy-core guard dropped threads during this dictation (see guarded)
static void note_busy_cores(IvyEngine * e, IvyReport & rep) {
    if (e->guard.fewest >= e->guard.n_load) return;
    rep.note += (rep.note.empty() ? "" : "; ") + std::string("busy cores: down to ") + std::to_string(e->guard.fewest) +
                " of " + std::to_string(e->guard.n_load) + " threads, ended on " + std::to_string(e->n_threads.load());
}

std::string ivy_transcribe(IvyEngine * e, const float * pcm, size_t n, const std::vector<std::string> & dictionary,
                           IvyReport & rep) {
    double t0 = now_ms();
    ivy_live_reset(e, dictionary);
    std::string all = transcribe_with(e, pcm, n, {}, rep);
    note_busy_cores(e, rep);
    rep.total_ms = now_ms() - t0;
    return all;
}

// Reads what is left of one piece and writes its text (live windows reused when possible).
static std::string finish_piece(IvyEngine * e, const float * pcm, size_t n, IvyReport & rep) {
    double t0 = now_ms();
    std::string text;
    rep.drafts = e->drafts;
    rep.draft_tokens = (int) e->draft.size();
    if (n == 0) { piece_reset(e); return text; }
    seq_rm(e, 1, 0);
    bool reuse = e->disabled.empty() && e->committed > 0 && n <= CHUNK_SECS * SR;
    if (!reuse) rep.note = !e->disabled.empty() ? e->disabled : (n > CHUNK_SECS * SR ? "too long" : "no window read during speech");
    if (reuse) {
        size_t need = std::min(e->committed * WINDOW + LOOKAHEAD, e->read_from.size());
        if (n < need || std::memcmp(pcm, e->read_from.data(), need * sizeof(float)) != 0) {
            reuse = false;
            rep.note = "final audio differs from the live audio";
        }
    }
    if (reuse) {
        double tr = now_ms();
        auto g = window_gains(pcm, n, e->gains);
        std::vector<float> scaled = apply_gains(pcm, n, g);
        Chunks c;
        bool ok;
        {
            PinMel pin(e->mel_max);
            ok = tokenize(e, scaled, c);
        }
        int n_audio = ok ? c.audio_count() : -1;
        int np = e->n_past;
        ok = n_audio >= (int) e->committed;
        if (ok) {
            seq_rm(e, 0, np);
            for (int i = 1 + (int) e->committed; ok && i <= n_audio; i++) ok = eval_chunk(e, c, i, np, 0, false, np);
            ok = ok && eval_chunk(e, c, n_audio + 1, np, 0, true, np);
        }
        if (ok) {
            rep.windows_reused = (int) e->committed;
            rep.windows_at_end = n_audio - (int) e->committed;
            rep.read_ms = now_ms() - tr;
            write_text(e, np, n, e->draft, rep, text);
            keep_prefix(e);
            rep.total_ms = now_ms() - t0;
            piece_reset(e);
            return text;
        }
        rep.note = "live read failed at the end";
    }
    // read the whole recording the normal way (the draft still speeds up writing)
    auto draft = e->draft;
    piece_reset(e);
    text = transcribe_with(e, pcm, n, draft, rep);
    rep.total_ms = now_ms() - t0;
    return text;
}

// A long dictation's piece is done (called while the speaker keeps talking): close it at its quietest 0.1 s after
// the last read window, keep its text, and start the next piece there.
static void close_piece(IvyEngine * e, const float * pcm, size_t n) {
    const size_t win = SR / 10, from = e->committed * WINDOW + LOOKAHEAD, to = std::min(n, PIECE_CUT_SECS * SR);
    size_t cut = to;
    float best = INFINITY;
    for (size_t i = from; i + win <= to; i += win / 2) {
        float en = 0;
        for (size_t j = 0; j < win; j++) en += pcm[i + j] * pcm[i + j];
        if (en < best) { best = en; cut = i + win / 2; }
    }
    IvyReport rep;
    std::string text = finish_piece(e, pcm, cut, rep);
    if (std::getenv("IVY_DEBUG"))
        std::fprintf(stderr, "piece %d closed at %.1f s (+%.1f s): %.0f ms, %d tok\n", e->pieces + 1,
                     (e->piece_start + cut - (e->pieces > 0 ? PIECE_PAD : 0)) / (double) SR, cut / (double) SR, rep.total_ms, rep.out_tokens);
    if (!text.empty()) e->pieces_text += (e->pieces_text.empty() ? "" : " ") + text;
    e->piece_start += cut - (e->pieces > 0 ? PIECE_PAD : 0);  // the cut is counted in the padded piece
    e->pieces++;
}

std::string ivy_live_finish(IvyEngine * e, const float * pcm, size_t n, IvyReport & rep) {
    double t0 = now_ms();
    e->stop = false;
    size_t m = 0;
    const float * piece = piece_audio(e, pcm, n, m);
    std::string text = finish_piece(e, piece, m, rep);
    std::string all = e->pieces_text;
    if (!text.empty()) all += (all.empty() ? "" : " ") + text;
    if (e->pieces > 0) rep.note = std::to_string(e->pieces + 1) + " pieces" + (rep.note.empty() ? "" : "; " + rep.note);
    note_busy_cores(e, rep);
    ivy_live_reset(e, e->dict);
    rep.total_ms = now_ms() - t0;
    return all;
}

// ------------------------------------------------------------------------------------------------
// "Make it professional": a small text model (Qwen2.5 0.5B + LoRA, merged; Gemma 3 270M also works) rewrites text
// already typed.
// No audio. Greedy, em/en dash tokens banned outright, any dash that still appears replaced afterwards.

struct IvyRewriter {
    llama_model * model = nullptr;
    llama_context * lctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> banned;  // tokens that contain an em or en dash
    int n_threads = 0;  // now; the busy-core guard may change it
    BusyGuard guard;    // the same guard as the speech engine: busy apps stalled a rewrite to 24 s (S26 2026-11-10)
    Pool pool;
};

// one rewrite pass under the busy-core guard (see BusyGuard)
template <class F> static auto guarded(IvyRewriter * r, F fn) {
    double ms = 0, waited = 0;
    auto res = r->guard.waits.run(fn, ms, waited);
    const int to = r->guard.after(r->n_threads, ms, waited);
    if (to != r->n_threads) {
        r->n_threads = to;
        llama_set_n_threads(r->lctx, to, to);
    }
    return res;
}

IvyRewriter * ivy_rewrite_load(const char * path, int n_threads, std::string & err) {
    ivy_backend_init(nullptr);
    if (n_threads <= 0) n_threads = auto_threads();
    auto * r = new IvyRewriter();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    r->model = llama_model_load_from_file(path, mp);
    if (!r->model) { err = "rewrite model load failed"; delete r; return nullptr; }
    r->vocab = llama_model_get_vocab(r->model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024;
    cp.n_batch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;  // slow on phone CPUs (see ivy_load)
    r->lctx = llama_init_from_model(r->model, cp);
    if (!r->lctx) { err = "rewrite context failed"; ivy_rewrite_free(r); return nullptr; }
    r->n_threads = n_threads;
    r->guard.start(n_threads);
    if (!std::getenv("IVY_NOPOOL")) {
        r->pool = new_pool(n_threads, r->guard.waits);
        if (r->pool.tp) llama_attach_threadpool(r->lctx, r->pool.tp, r->pool.tp);
    }
    const int n = llama_vocab_n_tokens(r->vocab);
    for (int t = 0; t < n; t++) {
        const char * s = llama_vocab_get_text(r->vocab, t);
        if (s && (std::strstr(s, "\xE2\x80\x94") || std::strstr(s, "\xE2\x80\x93"))) r->banned.push_back(t);
    }
    return r;
}

void ivy_rewrite_free(IvyRewriter * r) {
    if (!r) return;
    if (r->lctx) llama_free(r->lctx);
    r->pool.free();  // after the context that uses it
    if (r->model) llama_model_free(r->model);
    delete r;
}

static void replace_all(std::string & s, const std::string & a, const std::string & b) {
    for (size_t p = s.find(a); p != std::string::npos; p = s.find(a, p + b.size())) s.replace(p, a.size(), b);
}

std::string ivy_rewrite(IvyRewriter * r, const std::string & text, double & ms, const std::string & instruction) {
    double t0 = now_ms();
    llama_memory_clear(llama_get_memory(r->lctx), true);
    // Exactly the chat format the model was trained on: Qwen2.5 (rewrite/train_lora.py, incl. its default system
    // line) or Gemma 3 (rewrite/train.py). Qwen is recognised by its <|im_start|> token.
    llama_token probe[4];
    const bool qwen = llama_tokenize(r->vocab, "<|im_start|>", 12, probe, 4, false, true) == 1;
    std::string prompt = qwen
        ? "<|im_start|>system\nYou are Qwen, created by Alibaba Cloud. You are a helpful assistant.<|im_end|>\n"
          "<|im_start|>user\n" + instruction + text + "<|im_end|>\n<|im_start|>assistant\n"
        : "<start_of_turn>user\n" + instruction + text + "<end_of_turn>\n<start_of_turn>model\n";
    std::vector<llama_token> toks(prompt.size() + 8);
    int n = llama_tokenize(r->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true);
    if (n <= 0) return text;
    toks.resize(n);
    if (guarded(r, [&] { return llama_decode(r->lctx, llama_batch_get_one(toks.data(), n)); }) != 0) return text;
    const int n_vocab = llama_vocab_n_tokens(r->vocab);
    std::string out;
    size_t limit = text.size() / 2 + 96;  // tokens; a rewrite is at most about twice as long
    for (size_t k = 0; k < limit; k++) {
        float * lg = llama_get_logits_ith(r->lctx, -1);
        if (!lg) break;
        for (llama_token b : r->banned) lg[b] = -INFINITY;
        llama_token best = (llama_token) (std::max_element(lg, lg + n_vocab) - lg);
        if (llama_vocab_is_eog(r->vocab, best)) break;
        char buf[256];
        int nc = llama_token_to_piece(r->vocab, best, buf, sizeof(buf), 0, false);
        if (nc > 0) out.append(buf, nc);
        if (out.find("<end_of_turn>") != std::string::npos) { out.erase(out.find("<end_of_turn>")); break; }
        if (guarded(r, [&] { return llama_decode(r->lctx, llama_batch_get_one(&best, 1)); }) != 0) break;
    }
    // belt and braces: no em/en dash ever reaches the user
    replace_all(out, " \xE2\x80\x94 ", ", ");
    replace_all(out, "\xE2\x80\x94", ", ");
    replace_all(out, "\xE2\x80\x93", "-");
    while (!out.empty() && std::isspace((unsigned char) out.back())) out.pop_back();
    size_t s = 0;
    while (s < out.size() && std::isspace((unsigned char) out[s])) s++;
    ms = now_ms() - t0;
    return s < out.size() ? out.substr(s) : text;
}
