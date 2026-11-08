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
#include <optional>
#include <thread>

#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

namespace {

constexpr size_t SR = 16000;
constexpr size_t WINDOW = 800 * 160;          // one encoder window = 8 s
constexpr size_t LOOKAHEAD = 200 + 1600;      // last mel frame reaches half an FFT past the window, + 0.1 s
constexpr size_t CHUNK_SECS = 120;            // longest piece per model pass (context 4096)
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
constexpr int MAX_PROPOSE = 10;

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
        // re-align: the longest recent run of what was written (up to 4 tokens) found in the draft, nearest the cursor
        std::optional<size_t> found;
        for (size_t n = std::min<size_t>(out.size(), 4); n >= 1 && !found; n--) {
            std::optional<size_t> best;
            for (size_t j = 0; j + n <= draft.size(); j++) {
                if (!std::equal(out.end() - n, out.end(), draft.begin() + j)) continue;
                size_t end = j + n;
                auto dist = [&](size_t x) { return x > cursor ? x - cursor : cursor - x; };
                if (!best || dist(end) < dist(*best)) best = end;
            }
            found = best;
        }
        if (!found) return {};
        cursor = *found;
    }
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
    if (best) fresh.insert(fresh.end(), old.begin() + *best + N, old.end());
    return fresh;
}

// Fast cores: big.LITTLE phones run slower when llama.cpp threads land on little cores, so count only cores with at
// least half the top capacity (Android/Linux sysfs; anything else falls back to all cores).
int auto_threads() {
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
    long top = *std::max_element(cap.begin(), cap.end());
    if (top <= 0) return (int) std::min(8u, hw);
    int fast = (int) std::count_if(cap.begin(), cap.end(), [&](long c) { return c * 2 >= top; });
    // ponytail: cap 6 = best of 4/6/8 on the S26 speed test (2026-11-05); re-measure on a budget phone
    return std::clamp(fast, 1, 6);
}

}  // namespace

struct IvyEngine {
    llama_model * model = nullptr;
    llama_context * lctx = nullptr;
    mtmd_context * mctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_threads = 0;
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
    std::vector<llama_token> draft;
    double last_draft_at = -1;
    size_t last_draft_samples = 0;
    double last_draft_ms = 0;
    int drafts = 0;
};

static llama_memory_t mem(IvyEngine * e) { return llama_get_memory(e->lctx); }
static void seq_rm(IvyEngine * e, int seq, int from) { llama_memory_seq_rm(mem(e), seq, from, -1); }
// keep only the system prompt in memory
static void keep_prefix(IvyEngine * e) {
    seq_rm(e, 0, e->prefix_n_past);
    seq_rm(e, 1, 0);
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
    if (mtmd_helper_eval_chunk_single(e->mctx, e->lctx, c.get(i), n_past, seq, N_BATCH, logits_last, &np) != 0)
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
    llama_memory_clear(mem(e), true);
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
    llama_batch b = llama_batch_init(MAX_PROPOSE + 1, 0, 1);
    while (true) {
        if (is_stop_token(e, cur) || d.tokens.size() >= max_tokens) break;
        d.tokens.push_back(cur);
        if (d.tokens.size() >= max_tokens) break;
        if ((stop && stop->load()) || (yield_at_ms > 0 && now_ms() > yield_at_ms)) {
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
        if (llama_decode(e->lctx, b) != 0) break;
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
        d.accepted += (int) kept;
        n_past += 1 + (int) kept;
        cursor += kept;
        if (kept < props.size()) {
            seq_rm(e, seq, n_past);  // forget the rejected draft tokens
            k = std::clamp((int) kept + 2, std::min(2, max_k), max_k);
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

static bool g_backend = false;

void ivy_backend_init(const char * lib_dir) {
    if (g_backend) return;
    if (lib_dir) ggml_backend_load_all_from_path(lib_dir);
    else ggml_backend_load_all();
    llama_backend_init();
    g_backend = true;
}

IvyEngine * ivy_load(const char * model_path, const char * mmproj_path, int n_threads, int n_gpu_layers, std::string & err) {
    ivy_backend_init(nullptr);
    if (n_threads <= 0) n_threads = auto_threads();
    auto * e = new IvyEngine();
    e->n_threads = n_threads;
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    e->model = llama_model_load_from_file(model_path, mp);
    if (!e->model) { err = "model load failed"; delete e; return nullptr; }
    e->vocab = llama_model_get_vocab(e->model);
    mtmd_context_params tp = mtmd_context_params_default();
    tp.use_gpu = n_gpu_layers > 0;
    tp.print_timings = false;
    tp.n_threads = n_threads;
    tp.warmup = true;  // first encode is cold (3-6 s on the S26): pay it at load, not after the first dictation
    if (const char * fa = std::getenv("IVY_EAR_FA"))  // test switch for the ear: on / off (default auto)
        tp.flash_attn_type = std::string(fa) == "off" ? LLAMA_FLASH_ATTN_TYPE_DISABLED : LLAMA_FLASH_ATTN_TYPE_ENABLED;
    e->mctx = mtmd_init_from_file(mmproj_path, e->model, tp);
    if (!e->mctx) { err = "ear (mmproj) load failed"; ivy_free(e); return nullptr; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = N_BATCH;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.n_seq_max = 2;      // sequence 1 = scratch copy for drafts
    // Flash attention off: on phone CPUs it made reading each 8 s window grow from 0.9 s to 4.3 s over a minute
    // (S26, 2026-11-05); off = 0.2-0.5 s, same text. IVY_FA=on brings it back for tests.
    const char * fa = std::getenv("IVY_FA");
    cp.flash_attn_type = fa && std::string(fa) == "on" ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.kv_unified = true;  // both sequences share the audio already in memory
    e->lctx = llama_init_from_model(e->model, cp);
    if (!e->lctx) { err = "context init failed"; ivy_free(e); return nullptr; }
    return e;
}

int ivy_threads(const IvyEngine * e) { return e->n_threads; }

void ivy_free(IvyEngine * e) {
    if (!e) return;
    if (e->lctx) llama_free(e->lctx);
    if (e->mctx) mtmd_free(e->mctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

void ivy_live_reset(IvyEngine * e, const std::vector<std::string> & dictionary) {
    if (dictionary != e->dict) e->prefix_n_past = 0;  // new system prompt
    e->dict = dictionary;
    e->n_past = 0;
    e->committed = 0;
    e->gains.clear();
    e->mel_max.reset();
    std::fill(e->read_from.begin(), e->read_from.end(), 0.0f);
    e->read_from.clear();
    e->disabled.clear();
    e->stop = false;
    e->draft.clear();
    e->last_draft_at = -1;
    e->last_draft_samples = 0;
    e->last_draft_ms = 0;
    e->drafts = 0;
}

void ivy_live_interrupt(IvyEngine * e) { e->stop = true; }

// Draft of everything heard so far, in a scratch copy of the memory (sequence 1). Gives way when the next 8 s
// window is ready (reading it has priority) or when the speaker lets go.
static void write_draft(IvyEngine * e, const float * pcm, size_t n) {
    double t0 = now_ms();
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
        size_t next_window = (e->committed + 1) * WINDOW + LOOKAHEAD;
        double yield_at = now_ms() + (next_window > n ? (next_window - n) * 1000.0 / SR : 0.0);
        Decoded d = decode_with_draft(e, 1, np, e->draft, max_new_tokens(n), &e->stop, yield_at);
        e->draft = d.interrupted ? merge_draft(std::move(d.tokens), e->draft) : std::move(d.tokens);
        e->drafts++;
    }
    seq_rm(e, 1, 0);
    e->last_draft_at = now_ms();
    e->last_draft_samples = n;
    e->last_draft_ms = e->last_draft_at - t0;
}

bool ivy_live_step(IvyEngine * e, const float * pcm, size_t n) {
    if (!e->disabled.empty() || e->stop || n < SR / 2) return false;
    if (n + WINDOW > CHUNK_SECS * SR) { e->disabled = "recording too long for live reading"; return false; }
    if (e->n_past > 0 && e->committed >= complete_windows(n)) {
        // no window due: maybe a fresher draft (desktop live.rs step timing)
        double wait = std::max(2500.0, e->last_draft_ms * 1.5);
        if (drafts_off() || n < 3 * SR || n < e->last_draft_samples + 2 * SR ||
            (e->last_draft_at >= 0 && now_ms() - e->last_draft_at < wait))
            return false;
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

std::string ivy_transcribe(IvyEngine * e, const float * pcm, size_t n, const std::vector<std::string> & dictionary,
                           IvyReport & rep) {
    double t0 = now_ms();
    ivy_live_reset(e, dictionary);
    std::string all = transcribe_with(e, pcm, n, {}, rep);
    rep.total_ms = now_ms() - t0;
    return all;
}

std::string ivy_live_finish(IvyEngine * e, const float * pcm, size_t n, IvyReport & rep) {
    double t0 = now_ms();
    std::string text;
    e->stop = false;
    rep.drafts = e->drafts;
    rep.draft_tokens = (int) e->draft.size();
    if (n == 0) return text;
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
            ivy_live_reset(e, e->dict);
            return text;
        }
        rep.note = "live read failed at the end";
    }
    // read the whole recording the normal way (the draft still speeds up writing)
    auto draft = e->draft;
    ivy_live_reset(e, e->dict);
    text = transcribe_with(e, pcm, n, draft, rep);
    rep.total_ms = now_ms() - t0;
    return text;
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
};

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
    if (llama_decode(r->lctx, llama_batch_get_one(toks.data(), n)) != 0) return text;
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
        if (llama_decode(r->lctx, llama_batch_get_one(&best, 1)) != 0) break;
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
