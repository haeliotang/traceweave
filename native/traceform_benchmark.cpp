// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

#include "traceform_core.h"
#include <chrono>
#include <cstdio>
#include <cmath>
#include <cstring>
#include <random>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

int main(int argc, char** argv) {
    bool canonical_month = argc == 2 && std::strcmp(argv[1], "--canonical-monthly-history") == 0;
    if (argc > 2 || (argc == 2 && std::strcmp(argv[1], "--monthly-history") != 0 && !canonical_month)) return 2;
    const std::size_t pairs = argc == 2 ? 86400 : 78;
    rusage launcher_usage{};
    if (getrusage(RUSAGE_SELF, &launcher_usage) != 0) return 1;
#ifdef __APPLE__
    unsigned long long launcher_peak = launcher_usage.ru_maxrss;
#else
    unsigned long long launcher_peak = static_cast<unsigned long long>(launcher_usage.ru_maxrss) * 1024;
#endif
    // A fresh child separates benchmark RSS from pre-exec shell resource history.
    pid_t child = fork();
    if (child < 0) return 1;
    if (child > 0) {
        int status;
        if (waitpid(child, &status, 0) != child || !WIFEXITED(status)) return 1;
        return WEXITSTATUS(status);
    }
    constexpr std::size_t dimension = 128, states = 16, horizon = 120, runs = 300;
    std::vector<double> raw(dimension), median(dimension, 0.2), iqr(dimension, 2);
    std::vector<double> position(dimension), velocity(dimension);
    std::vector<double> centers(states * dimension), center_velocity(states * dimension);
    std::vector<double> cdf(horizon * states * states), draws(runs * (horizon - 1));
    std::vector<unsigned long long> occupancy(horizon * states);
    std::mt19937_64 random(42);
    std::uniform_real_distribution<double> unit(0, 1);
    for (double& value : raw) value = 100 * unit(random) - 50;
    for (double& value : velocity) value = 2 * unit(random) - 1;
    for (double& value : centers) value = unit(random);
    for (double& value : center_velocity) value = unit(random);
    for (double& value : draws) value = unit(random);
    std::vector<double> prior(states * states), query_phases(horizon * 2), observed_phases(pairs * 2);
    std::vector<int> sources(pairs), targets(pairs);
    constexpr double pi = 3.14159265358979323846;
    for (std::size_t pair = 0; pair < pairs; ++pair) {
        sources[pair] = pair % states;
        targets[pair] = (pair + pair / states + 1) % states;
        prior[sources[pair] * states + targets[pair]]++;
        observed_phases[pair * 2] = 2 * pi * std::fmod(pair * 30.0 / 86400, 1.0);
        observed_phases[pair * 2 + 1] = 2 * pi * std::fmod(pair * 30.0 / (7 * 86400), 1.0);
        if (canonical_month) {
            // Match the SDK's weekday + local time construction without rounding phases.
            std::size_t seconds = pair * 30;
            double fraction = (seconds % 86400) / 86400.0;
            observed_phases[pair * 2] = 2 * pi * fraction;
            observed_phases[pair * 2 + 1] = 2 * pi * ((seconds / 86400) % 7 + fraction) / 7;
        }
    }
    for (std::size_t source = 0; source < states; ++source) {
        double total = 0;
        for (std::size_t target = 0; target < states; ++target) total += prior[source * states + target];
        for (std::size_t target = 0; target < states; ++target) prior[source * states + target] /= total;
    }
    for (std::size_t step = 0; step < horizon; ++step) {
        query_phases[step * 2] = 2 * pi * (0.25 + step * 30.0 / 86400);
        query_phases[step * 2 + 1] = 2 * pi * (0.25 + step * 30.0 / 86400) / 7;
    }
    double distance;
    if (traceform_normalize(raw.data(), median.data(), iqr.data(), dimension, position.data()) != 0 ||
        traceform_quantize(position.data(), velocity.data(), centers.data(), center_velocity.data(), states, dimension, 0.35, &distance) < 0 ||
        traceform_periodic_cdf(prior.data(), query_phases.data(), observed_phases.data(), sources.data(), targets.data(), pairs, states, horizon, cdf.data()) != 0 ||
        traceform_rollouts(cdf.data(), states, horizon, runs, 0, draws.data(), occupancy.data()) != 0) return 1;
    auto start = std::chrono::steady_clock::now();
    if (traceform_normalize(raw.data(), median.data(), iqr.data(), dimension, position.data()) != 0 ||
        traceform_quantize(position.data(), velocity.data(), centers.data(), center_velocity.data(), states, dimension, 0.35, &distance) < 0) return 1;
    auto cdf_start = std::chrono::steady_clock::now();
    if (traceform_periodic_cdf(prior.data(), query_phases.data(), observed_phases.data(), sources.data(), targets.data(), pairs, states, horizon, cdf.data()) != 0) return 1;
    double cdf_elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - cdf_start).count();
    auto rollout_start = std::chrono::steady_clock::now();
    if (traceform_rollouts(cdf.data(), states, horizon, runs, 0, draws.data(), occupancy.data()) != 0) return 1;
    double rollout_elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - rollout_start).count();
    double elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    for (std::size_t step = 0; step < horizon; ++step) {
        unsigned long long total = 0;
        for (std::size_t state = 0; state < states; ++state) total += occupancy[step * states + state];
        if (total != runs) return 1;
    }
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) return 1;
    // Linux/Android reports ru_maxrss in KiB; Darwin reports bytes.
#ifdef __APPLE__
    unsigned long long peak = usage.ru_maxrss;
#else
    unsigned long long peak = static_cast<unsigned long long>(usage.ru_maxrss) * 1024;
#endif
    std::printf("{\"scope\":\"isolated_child_native_normalization_quantization_periodic_cdf_and_rollouts_precomputed_phases\",\"phase_construction\":\"%s\",\"dimension\":128,\"states\":16,\"horizon_steps\":120,\"rollouts\":300,\"history_pairs\":%zu,\"peak_rss_bytes\":%llu,\"launcher_peak_rss_bytes\":%llu,\"elapsed_ms\":%.6f,\"periodic_cdf_ms\":%.6f,\"rollout_ms\":%.6f,\"mass_conserved\":true,\"phase_preparation_timed\":false}\n", canonical_month ? "sdk_weekday_and_local_fraction" : "legacy_fmod_elapsed_fraction", pairs, peak, launcher_peak, elapsed, cdf_elapsed, rollout_elapsed);
    return 0;
}
