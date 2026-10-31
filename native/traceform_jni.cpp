#include "traceform_core.h"
#include <jni.h>
#include <vector>
#include <cstdint>
#include <algorithm>
#include <new>

static void invalid(JNIEnv* environment, const char* message) {
    environment->ThrowNew(environment->FindClass("java/lang/IllegalArgumentException"), message);
}

static std::vector<double> values(JNIEnv* environment, jdoubleArray array) {
    std::vector<double> result(environment->GetArrayLength(array));
    if (!result.empty()) environment->GetDoubleArrayRegion(array, 0, result.size(), result.data());
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_counterfactual(JNIEnv* environment, jclass,
        jdoubleArray cdf_array, jdoubleArray prior_array, jint states, jint horizon,
        jint runs, jint initial, jint max_dwell, jdoubleArray baseline_array, jdoubleArray scenario_array) {
    if (!cdf_array || !prior_array || !baseline_array || !scenario_array || states < 1 || states > 128 ||
        horizon < 1 || horizon > 2880 || runs < 1 || runs > 10000 || initial < 0 || initial >= states || max_dwell < 1 ||
        static_cast<long long>(horizon) * states * states > 1000000 ||
        static_cast<long long>(runs) * (horizon - 1) > 1000000 ||
        environment->GetArrayLength(cdf_array) != horizon * states * states ||
        environment->GetArrayLength(prior_array) != states * states ||
        environment->GetArrayLength(baseline_array) != runs * (horizon - 1) ||
        environment->GetArrayLength(scenario_array) != runs * (horizon - 1)) {
        invalid(environment, "bounded matching counterfactual workload required"); return nullptr;
    }
    try {
        auto cdf = values(environment, cdf_array), prior = values(environment, prior_array);
        auto baseline = values(environment, baseline_array), scenario = values(environment, scenario_array);
        if (environment->ExceptionCheck()) return nullptr;
        if (baseline.empty()) baseline.push_back(0);
        if (scenario.empty()) scenario.push_back(0);
        std::vector<unsigned long long> output(2 * horizon * states);
        if (traceform_counterfactual(cdf.data(), prior.data(), states, horizon, runs, initial, max_dwell,
                baseline.data(), scenario.data(), output.data()) != 0) {
            invalid(environment, "invalid counterfactual prior, CDF or draws"); return nullptr;
        }
        std::vector<jlong> counts(output.begin(), output.end());
        auto result = environment->NewLongArray(counts.size());
        if (!result) return nullptr;
        environment->SetLongArrayRegion(result, 0, counts.size(), counts.data());
        return environment->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        environment->ThrowNew(environment->FindClass("java/lang/OutOfMemoryError"), "counterfactual allocation failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_randomDraws(JNIEnv* environment, jclass, jlong seed, jint count) {
    if (count < 0 || count > 1000000) {
        invalid(environment, "bounded nonnegative draw count required"); return nullptr;
    }
    auto result = environment->NewDoubleArray(count);
    if (!result) return nullptr;
    constexpr std::uint64_t mask = (1ULL << 48) - 1;
    std::uint64_t state = (static_cast<std::uint64_t>(seed) ^ 0x5deece66dULL) & mask;
    double chunk[256];
    for (int offset = 0; offset < count; offset += 256) {
        int length = std::min(256, count - offset);
        for (int index = 0; index < length; index++) {
            state = (state * 0x5deece66dULL + 0xbULL) & mask;
            std::uint64_t high = state >> 22;
            state = (state * 0x5deece66dULL + 0xbULL) & mask;
            chunk[index] = static_cast<double>((high << 27) + (state >> 21)) * 0x1.0p-53;
        }
        environment->SetDoubleArrayRegion(result, offset, length, chunk);
        if (environment->ExceptionCheck()) return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_periodicCdf(JNIEnv* environment, jclass,
        jdoubleArray prior_array, jdoubleArray query_array, jdoubleArray observed_array,
        jintArray sources_array, jintArray targets_array, jint states) {
    if (!prior_array || !query_array || !observed_array || !sources_array || !targets_array ||
        states < 1 || states > 128) { invalid(environment, "periodic arrays and vocabulary required"); return nullptr; }
    jsize queries = environment->GetArrayLength(query_array), pairs = environment->GetArrayLength(sources_array);
    if (queries < 2 || queries % 2 != 0 || queries / 2 > 2880 || pairs > 1000000 ||
        environment->GetArrayLength(prior_array) != states * states ||
        environment->GetArrayLength(observed_array) != pairs * 2 || environment->GetArrayLength(targets_array) != pairs ||
        static_cast<long long>(queries / 2) * states * states > 1000000) {
        invalid(environment, "inconsistent or excessive periodic workload"); return nullptr;
    }
    auto prior = values(environment, prior_array), query = values(environment, query_array), observed = values(environment, observed_array);
    std::vector<jint> sources(pairs), targets(pairs);
    if (pairs) {
        environment->GetIntArrayRegion(sources_array, 0, pairs, sources.data());
        environment->GetIntArrayRegion(targets_array, 0, pairs, targets.data());
    }
    std::vector<double> output((queries / 2) * states * states);
    if (traceform_periodic_cdf(prior.data(), query.data(), observed.data(), sources.data(), targets.data(), pairs,
            states, queries / 2, output.data()) != 0) {
        invalid(environment, "invalid periodic phases, prior or states"); return nullptr;
    }
    auto result = environment->NewDoubleArray(output.size());
    environment->SetDoubleArrayRegion(result, 0, output.size(), output.data());
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_normalize(JNIEnv* environment, jclass,
                                            jdoubleArray raw_array, jdoubleArray median_array,
                                            jdoubleArray iqr_array) {
    if (!raw_array || !median_array || !iqr_array) { invalid(environment, "arrays required"); return nullptr; }
    auto raw = values(environment, raw_array), median = values(environment, median_array), iqr = values(environment, iqr_array);
    if (raw.empty() || raw.size() > 128 || median.size() != raw.size() || iqr.size() != raw.size()) {
        invalid(environment, "matching dimensions up to 128 required"); return nullptr;
    }
    std::vector<double> output(raw.size());
    if (traceform_normalize(raw.data(), median.data(), iqr.data(), raw.size(), output.data()) != 0) {
        invalid(environment, "nonfinite normalization inputs"); return nullptr;
    }
    auto result = environment->NewDoubleArray(output.size());
    environment->SetDoubleArrayRegion(result, 0, output.size(), output.data());
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_quantize(JNIEnv* environment, jclass,
                                           jdoubleArray position_array, jdoubleArray velocity_array,
                                           jdoubleArray centers_array, jdoubleArray center_velocity_array,
                                           jint nodes, jdouble beta) {
    if (!position_array || !velocity_array || !centers_array || !center_velocity_array) { invalid(environment, "arrays required"); return nullptr; }
    auto position = values(environment, position_array), velocity = values(environment, velocity_array);
    auto centers = values(environment, centers_array), center_velocity = values(environment, center_velocity_array);
    if (position.empty() || position.size() > 128 || nodes < 1 || nodes > 128 || velocity.size() != position.size() ||
        centers.size() != nodes * position.size() || center_velocity.size() != centers.size()) {
        invalid(environment, "inconsistent quantizer shape"); return nullptr;
    }
    double distance;
    int index = traceform_quantize(position.data(), velocity.data(), centers.data(), center_velocity.data(), nodes, position.size(), beta, &distance);
    if (index < 0) { invalid(environment, "invalid quantizer input"); return nullptr; }
    double output[] = {static_cast<double>(index), distance};
    auto result = environment->NewDoubleArray(2);
    environment->SetDoubleArrayRegion(result, 0, 2, output);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_inceptless_traceweave_edge_NativeCore_rollouts(JNIEnv* environment, jclass,
                                           jdoubleArray cdf_array, jint states,
                                           jint horizon, jint runs, jint initial,
                                           jdoubleArray draws_array) {
    if (!cdf_array || !draws_array || states < 1 || states > 128 || horizon < 1 || horizon > 2880 || runs < 1 || runs > 10000 || initial < 0 || initial >= states) {
        invalid(environment, "invalid rollout bounds"); return nullptr;
    }
    if (environment->GetArrayLength(cdf_array) != horizon * states * states || environment->GetArrayLength(draws_array) != runs * (horizon - 1)) {
        invalid(environment, "inconsistent rollout shape"); return nullptr;
    }
    auto cdf = values(environment, cdf_array), draws = values(environment, draws_array);
    // The core accepts a non-null draw pointer even for a one-step horizon.
    if (draws.empty()) draws.push_back(0);
    std::vector<unsigned long long> occupancy(horizon * states);
    if (traceform_rollouts(cdf.data(), states, horizon, runs, initial, draws.data(), occupancy.data()) != 0) {
        invalid(environment, "invalid CDF or draws"); return nullptr;
    }
    std::vector<jlong> result_values(occupancy.begin(), occupancy.end());
    auto result = environment->NewLongArray(result_values.size());
    environment->SetLongArrayRegion(result, 0, result_values.size(), result_values.data());
    return result;
}
