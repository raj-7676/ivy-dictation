// Ivy phone engine: Qwen3-ASR-0.6B (s114) through llama.cpp + mtmd, with live reading.
// Port of the desktop beta (src-tauri/src/lite.rs + live.rs):
//  - the system prompt stays in memory between dictations (prefix cache);
//  - while the speaker talks, every finished 8 s audio window is encoded and fed to the model (step());
//  - between windows the model drafts the text so far in a scratch copy of the memory (sequence 1);
//  - after release only the last partial window is read, then the draft is checked several tokens per pass and the
//    text written greedily from wherever the model disagrees (same text as plain greedy decoding).
// Not thread-safe by itself: the caller serialises calls (the Android side uses one worker thread).
#pragma once
#include <string>
#include <vector>

struct IvyReport {
    int windows_reused = 0;   // 8 s windows read during speech and kept
    int windows_at_end = 0;   // windows read after release
    int out_tokens = 0;
    int drafts = 0;           // drafts written while talking
    int draft_tokens = 0;     // length of the last draft
    int proposed = 0;         // draft tokens offered / kept by the final check
    int accepted = 0;
    int passes = 0;           // model passes used to write the text
    double read_ms = 0;       // reading the rest of the audio after release
    double write_ms = 0;      // writing the text
    double total_ms = 0;      // finish() wall time
    std::string note;         // why live windows were not used (empty = they were)
};

struct IvyEngine;

// Load the compute backends once. lib_dir = where libggml-cpu-*.so live (Android: nativeLibraryDir); nullptr = next to
// the program. The fastest CPU code version for this chip is measured once and remembered in cache_dir/cpu_code.txt
// (Android: filesDir; nullptr = measure every start).
void ivy_backend_init(const char * lib_dir, const char * cache_dir = nullptr);
// Which CPU code version was loaded and, when it was just measured, the timings: for the log.
std::string ivy_cpu_choice();

// n_threads <= 0: pick automatically. n_gpu_layers 0 = CPU only.
IvyEngine * ivy_load(const char * model_path, const char * mmproj_path, int n_threads, int n_gpu_layers,
                     std::string & err);
void ivy_free(IvyEngine * e);
int ivy_threads(const IvyEngine * e);  // threads actually used

// Start a new dictation (keeps the system prompt in memory).
void ivy_live_reset(IvyEngine * e, const std::vector<std::string> & dictionary);
// The recording so far (16 kHz mono float). Does at most one unit of work; returns true if it did any.
bool ivy_live_step(IvyEngine * e, const float * pcm, size_t n);
// The final recording. Returns the text; fills the report.
// Any thread: the speaker let go; a running draft gives way at once (call before joining the worker).
void ivy_live_interrupt(IvyEngine * e);
std::string ivy_live_finish(IvyEngine * e, const float * pcm, size_t n, IvyReport & rep);
// One-shot (no live reading): what the desktop app does without the beta.
std::string ivy_transcribe(IvyEngine * e, const float * pcm, size_t n, const std::vector<std::string> & dictionary,
                           IvyReport & rep);

// "Make it professional": a small text-only model (Gemma 3 270M fine-tune) rewrites text that was already typed.
struct IvyRewriter;
IvyRewriter * ivy_rewrite_load(const char * gguf_path, int n_threads, std::string & err);
void ivy_rewrite_free(IvyRewriter * r);
// Returns the professional version (never with an em or en dash), or the input unchanged if the model fails.
// instruction = what the model was trained to be asked: "Make this professional:\n" (default) or "Fix the grammar:\n"
std::string ivy_rewrite(IvyRewriter * r, const std::string & text, double & ms, const std::string & instruction = "Make this professional:\n");
