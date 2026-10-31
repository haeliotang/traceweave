#include <jni.h>
#include "llama.h"
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <memory>
#include <stdexcept>

// Experimental isolated runtime. A model response is text only and grants no capability.
namespace {
std::atomic<bool> cancelled{false};
std::atomic<bool> prepared{false};
std::mutex run_mutex;
std::once_flag backend_once;

bool continue_loading(float, void *) { return !cancelled.load(); }
bool abort_decoding(void *) { return cancelled.load(); }

void replace_all(std::string &value, const std::string &needle) {
    size_t pos = 0;
    while ((pos = value.find(needle, pos)) != std::string::npos) {
        value.replace(pos, needle.size(), "[reserved token]");
        pos += 16;
    }
}
void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_traceform_agentos_LocalModelProbe_prepare(JNIEnv *env, jclass) {
    std::unique_lock<std::mutex> lock(run_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Another local model request is running");
        return;
    }
    cancelled.store(false);
    prepared.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_org_traceform_agentos_LocalModelProbe_cancel(JNIEnv *, jclass) {
    cancelled.store(true);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_traceform_agentos_LocalModelProbe_generate(JNIEnv *env, jclass, jstring model_path,
                                                    jbyteArray prompt_bytes, jint max_tokens,
                                                    jstring grammar_text) {
    std::unique_lock<std::mutex> lock(run_mutex, std::try_to_lock);
    try {
        require(lock.owns_lock(), "Another local model request is running");
        require(prepared.exchange(false), "Prepare the local model request first");
        require(!cancelled.load(), "Local model request cancelled");
        require(model_path && prompt_bytes && max_tokens > 0 && max_tokens <= 128, "Invalid local model request");
        jsize length = env->GetArrayLength(prompt_bytes);
        require(length > 0 && length <= 4096, "Local model input exceeds the limit");
        std::vector<jbyte> input(static_cast<size_t>(length));
        env->GetByteArrayRegion(prompt_bytes, 0, length, input.data());
        require(!env->ExceptionCheck(), "Cannot read local model input");
        std::string text(input.begin(), input.end());
        require(text.find('\0') == std::string::npos, "NUL is not allowed in local model input");
        for (const char *token : {"<|im_start|>", "<|im_end|>", "<|endoftext|>"}) replace_all(text, token);
        std::string prompt = "<|im_start|>system\nYou are a local drafting assistant. "
            "Give a concise draft. The user will review it; do not execute actions.<|im_end|>\n"
            "<|im_start|>user\n" + text + "<|im_end|>\n<|im_start|>assistant\n";
        const char *path = env->GetStringUTFChars(model_path, nullptr);
        require(path != nullptr, "Cannot open local model path");
        std::string path_copy(path);
        env->ReleaseStringUTFChars(model_path, path);
        std::call_once(backend_once, [] { llama_backend_init(); });
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.progress_callback = continue_loading;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(path_copy.c_str(), model_params), llama_model_free);
        require(model != nullptr, cancelled.load() ? "Local model request cancelled" : "Local model could not be loaded");
        const llama_vocab *vocab = llama_model_get_vocab(model.get());
        int count = -llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
        require(count > 0 && count <= 768 && count + max_tokens <= 1024, "Local model prompt exceeds the context limit");
        std::vector<llama_token> tokens(static_cast<size_t>(count));
        require(llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), count, true, true) == count,
                "Local model tokenization failed");
        llama_context_params params = llama_context_default_params();
        params.n_ctx = 1024; params.n_batch = static_cast<uint32_t>(count); params.n_ubatch = 128;
        params.n_threads = 4; params.n_threads_batch = 4;
        params.abort_callback = abort_decoding;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), params), llama_free);
        require(context != nullptr, "Local model context could not be created");
        std::string grammar;
        if (grammar_text != nullptr) {
            const char *raw = env->GetStringUTFChars(grammar_text, nullptr);
            require(raw != nullptr, "Cannot read grammar");
            grammar.assign(raw);
            env->ReleaseStringUTFChars(grammar_text, raw);
        }
        require(grammar.size() <= 16384, "Local model grammar exceeds the limit");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        require(sampler != nullptr, "Local model sampler could not be created");
        if (!grammar.empty()) {
            llama_sampler *grammar_sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
            require(grammar_sampler != nullptr, "Local model grammar could not be applied");
            llama_sampler_chain_add(sampler.get(), grammar_sampler);
        }
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        llama_batch batch = llama_batch_get_one(tokens.data(), count);
        std::string result;
        for (int i = 0; i < max_tokens; ++i) {
            require(!cancelled.load(), "Local model request cancelled");
            require(llama_decode(context.get(), batch) == 0, cancelled.load() ? "Local model request cancelled" : "Local model evaluation failed");
            llama_token next = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, next)) break;
            char buffer[256];
            int bytes = llama_token_to_piece(vocab, next, buffer, sizeof(buffer), 0, false);
            require(bytes >= 0 && result.size() + static_cast<size_t>(bytes) <= 32768, "Local model output exceeds the limit");
            result.append(buffer, static_cast<size_t>(bytes));
            tokens[0] = next;
            batch = llama_batch_get_one(tokens.data(), 1);
        }
        require(!cancelled.load(), "Local model request cancelled");
        jbyteArray output = env->NewByteArray(static_cast<jsize>(result.size()));
        require(output != nullptr, "Cannot allocate local model output");
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte *>(result.data()));
        return output;
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_traceform_agentos_LocalModelRuntime_prepare(JNIEnv *env, jclass type) {
    Java_org_traceform_agentos_LocalModelProbe_prepare(env, type);
}

extern "C" JNIEXPORT void JNICALL
Java_org_traceform_agentos_LocalModelRuntime_cancel(JNIEnv *env, jclass type) {
    Java_org_traceform_agentos_LocalModelProbe_cancel(env, type);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_traceform_agentos_LocalModelRuntime_generate(JNIEnv *env, jclass type, jstring path,
                                                    jbyteArray prompt, jint max_tokens, jstring grammar) {
    return Java_org_traceform_agentos_LocalModelProbe_generate(env, type, path, prompt, max_tokens, grammar);
}
