// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

#include "periodic_spectrum.h"
#include "traceform_core.h"
#include <jni.h>
#include <algorithm>
#include <map>
#include <memory>
#include <mutex>
#include <limits>
#include <chrono>
#include <time.h>

struct FittedSpectrum {
    int states;
    PeriodicSpectrum summary;
    double timings[6]{};
    explicit FittedSpectrum(int count) : states(count), summary(count) {}
};
static std::mutex spectrum_mutex;
static std::map<jlong, std::unique_ptr<FittedSpectrum>> spectra;
static unsigned long long next_spectrum = 1;
static void reject(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_fitSpectrum(JNIEnv* env, jclass, jint states,
        jdoubleArray phases, jintArray sources, jintArray targets) {
    if (!phases || !sources || !targets || states < 1 || states > 16) {
        reject(env, "spectrum arrays and 1..16 states required"); return 0;
    }
    jsize pairs = env->GetArrayLength(sources);
    if (pairs > 1000000 || env->GetArrayLength(targets) != pairs || env->GetArrayLength(phases) != pairs * 2) {
        reject(env, "inconsistent or excessive spectrum history"); return 0;
    }
    try {
        auto fitted = std::make_unique<FittedSpectrum>(states);
        // Bounded copying avoids retaining a second full history during fitting.
        double observed[512]; jint origins[256], destinations[256];
        for (jsize start = 0; start < pairs; start += 256) {
            jsize count = std::min(jsize(256), pairs - start);
            env->GetDoubleArrayRegion(phases, start * 2, count * 2, observed);
            env->GetIntArrayRegion(sources, start, count, origins);
            env->GetIntArrayRegion(targets, start, count, destinations);
            if (env->ExceptionCheck()) return 0;
            for (jsize index = 0; index < count; ++index)
                if (fitted->summary.apply_delta(observed[index * 2], observed[index * 2 + 1], origins[index], destinations[index], 1)) {
                    reject(env, "invalid canonical spectrum history"); return 0;
                }
        }
        std::lock_guard<std::mutex> lock(spectrum_mutex);
        if (next_spectrum > static_cast<unsigned long long>(std::numeric_limits<jlong>::max())) {
            reject(env, "spectrum handle capacity exhausted"); return 0;
        }
        jlong handle = next_spectrum++;
        spectra.emplace(handle, std::move(fitted)); return handle;
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "spectrum allocation failed"); return 0;
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_spectrumCdf(JNIEnv* env, jclass, jlong handle,
        jdoubleArray prior, jdoubleArray phases) {
    auto entered = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(spectrum_mutex);
    auto locked = std::chrono::steady_clock::now();
    auto found = spectra.find(handle);
    if (found == spectra.end()) { reject(env, "unknown or closed spectrum handle"); return nullptr; }
    int states = found->second->states;
    if (!prior || !phases || env->GetArrayLength(prior) != states * states) {
        reject(env, "spectrum prior and phases required"); return nullptr;
    }
    jsize length = env->GetArrayLength(phases);
    if (length < 2 || length % 2 || length / 2 > 2880) {
        reject(env, "bounded spectrum queries required"); return nullptr;
    }
    try {
        std::vector<double> probabilities(states * states), queries(length), output(length / 2 * states * states);
        env->GetDoubleArrayRegion(prior, 0, probabilities.size(), probabilities.data());
        env->GetDoubleArrayRegion(phases, 0, length, queries.data());
        if (env->ExceptionCheck()) return nullptr;
        auto copied = std::chrono::steady_clock::now();
        timespec cpu_start{}, cpu_end{};
        bool cpu_available = clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start) == 0;
        if (found->second->summary.cdf(probabilities.data(), queries.data(), length / 2, output.data())) {
            reject(env, "invalid spectrum query or prior"); return nullptr;
        }
        if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end) != 0) cpu_available = false;
        auto calculated = std::chrono::steady_clock::now();
        auto result = env->NewDoubleArray(output.size());
        if (result) env->SetDoubleArrayRegion(result, 0, output.size(), output.data());
        auto finished = std::chrono::steady_clock::now();
        auto ms = [](auto first, auto last) { return std::chrono::duration<double, std::milli>(last - first).count(); };
        double* timing = found->second->timings;
        timing[0] = ms(entered, locked); timing[1] = ms(locked, copied);
        timing[2] = ms(copied, calculated); timing[3] = ms(calculated, finished);
        timing[4] = ms(entered, finished);
        timing[5] = cpu_available ? (cpu_end.tv_sec - cpu_start.tv_sec) * 1000.0 + (cpu_end.tv_nsec - cpu_start.tv_nsec) / 1e6 : -1;
        return result;
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "spectrum query allocation failed"); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_closeSpectrum(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(spectrum_mutex); spectra.erase(handle);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_spectrumRollouts(JNIEnv* env, jclass, jlong handle,
        jdoubleArray prior, jdoubleArray phases, jint runs, jint initial, jdoubleArray draws) {
    std::lock_guard<std::mutex> lock(spectrum_mutex);
    auto found = spectra.find(handle);
    if (found == spectra.end()) { reject(env, "unknown or closed spectrum handle"); return nullptr; }
    int states = found->second->states;
    if (!prior || !phases || !draws || runs < 1 || runs > 10000 || initial < 0 || initial >= states
            || env->GetArrayLength(prior) != states * states) {
        reject(env, "bounded spectrum rollout inputs required"); return nullptr;
    }
    jsize length = env->GetArrayLength(phases);
    if (length < 2 || length % 2 || length / 2 > 2880
            || env->GetArrayLength(draws) != runs * (length / 2 - 1)) {
        reject(env, "inconsistent spectrum rollout shape"); return nullptr;
    }
    try {
        int horizon = length / 2;
        std::vector<double> probabilities(states * states), queries(length),
            random(std::max(1, runs * (horizon - 1))), cdf(horizon * states * states);
        env->GetDoubleArrayRegion(prior, 0, probabilities.size(), probabilities.data());
        env->GetDoubleArrayRegion(phases, 0, length, queries.data());
        if (horizon > 1) env->GetDoubleArrayRegion(draws, 0, runs * (horizon - 1), random.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<unsigned long long> occupancy(horizon * states);
        if (found->second->summary.cdf(probabilities.data(), queries.data(), horizon, cdf.data())
                || traceform_rollouts(cdf.data(), states, horizon, runs, initial, random.data(), occupancy.data())) {
            reject(env, "invalid spectrum prior, phases or draws"); return nullptr;
        }
        std::vector<jlong> values(occupancy.begin(), occupancy.end());
        auto result = env->NewLongArray(values.size());
        if (result) env->SetLongArrayRegion(result, 0, values.size(), values.data());
        return result;
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "spectrum rollout allocation failed"); return nullptr;
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_spectrumTimings(JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(spectrum_mutex);
    auto found = spectra.find(handle);
    if (found == spectra.end()) { reject(env, "unknown or closed spectrum handle"); return nullptr; }
    auto result = env->NewDoubleArray(6);
    if (result) env->SetDoubleArrayRegion(result, 0, 6, found->second->timings);
    return result;
}
