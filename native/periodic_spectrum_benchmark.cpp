#include "traceform_core.h"
#include "periodic_spectrum.h"
#include <chrono>
#include <cstdio>
#include <cmath>
#include <cstring>
#include <cstdint>
#include <random>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

int main(int argc, char** argv) {
    bool streamed = argc == 2 && std::strcmp(argv[1], "--streamed-monthly-history") == 0;
    bool canonical_month = argc == 2 && std::strcmp(argv[1], "--canonical-monthly-history") == 0 || streamed;
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
    auto fit_start = std::chrono::steady_clock::now();
    PeriodicSpectrum spectrum(states);
    std::vector<double> prior(states * states), query_phases(horizon * 2), observed_phases(streamed ? 0 : pairs * 2);
    std::vector<int> sources(streamed ? 0 : pairs), targets(streamed ? 0 : pairs);
    constexpr double pi = 3.14159265358979323846;
    for (std::size_t pair = 0; pair < pairs; ++pair) {
        int source = pair % states, target = (pair + pair / states + 1) % states;
        prior[source * states + target]++;
        double day = 2 * pi * std::fmod(pair * 30.0 / 86400, 1.0);
        double week = 2 * pi * std::fmod(pair * 30.0 / (7 * 86400), 1.0);
        if (canonical_month) {
            std::size_t seconds = pair * 30;
            double fraction = (seconds % 86400) / 86400.0;
            day = 2 * pi * fraction;
            week = 2 * pi * ((seconds / 86400) % 7 + fraction) / 7;
        }
        if (streamed) {
            if (spectrum.apply_delta(day, week, source, target, 1)) return 1;
        } else {
            sources[pair] = source; targets[pair] = target;
            observed_phases[pair * 2] = day; observed_phases[pair * 2 + 1] = week;
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
    for (std::size_t pair = 0; !streamed && pair < pairs; ++pair)
        if (spectrum.apply_delta(observed_phases[pair * 2], observed_phases[pair * 2 + 1], sources[pair], targets[pair], 1)) return 1;
    double fit_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - fit_start).count();
    auto update_start = std::chrono::steady_clock::now();
    for (std::size_t pair = 0; pair < std::min(pairs, std::size_t(1000)); ++pair) {
        double day, week;
        int source, target;
        if (streamed) {
            std::size_t seconds = pair * 30;
            double fraction = (seconds % 86400) / 86400.0;
            day = 2 * pi * fraction; week = 2 * pi * ((seconds / 86400) % 7 + fraction) / 7;
            source = pair % states; target = (pair + pair / states + 1) % states;
        } else {
            day = observed_phases[pair * 2]; week = observed_phases[pair * 2 + 1];
            source = sources[pair]; target = targets[pair];
        }
        if (spectrum.apply_delta(day, week, source, target, -1)
            || spectrum.apply_delta(day, week, source, target, 1)) return 1;
    }
    double update_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - update_start).count();
    double distance;
    auto warmup_start = std::chrono::steady_clock::now();
    if (traceform_normalize(raw.data(), median.data(), iqr.data(), dimension, position.data()) != 0 ||
        traceform_quantize(position.data(), velocity.data(), centers.data(), center_velocity.data(), states, dimension, 0.35, &distance) < 0 ||
        spectrum.cdf(prior.data(), query_phases.data(), horizon, cdf.data()) != 0 ||
        traceform_rollouts(cdf.data(), states, horizon, runs, 0, draws.data(), occupancy.data()) != 0) return 1;
    double warmup_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - warmup_start).count();
    auto start = std::chrono::steady_clock::now();
    if (traceform_normalize(raw.data(), median.data(), iqr.data(), dimension, position.data()) != 0 ||
        traceform_quantize(position.data(), velocity.data(), centers.data(), center_velocity.data(), states, dimension, 0.35, &distance) < 0) return 1;
    auto cdf_start = std::chrono::steady_clock::now();
    if (spectrum.cdf(prior.data(), query_phases.data(), horizon, cdf.data()) != 0) return 1;
    double cdf_elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - cdf_start).count();
    auto rollout_start = std::chrono::steady_clock::now();
    if (traceform_rollouts(cdf.data(), states, horizon, runs, 0, draws.data(), occupancy.data()) != 0) return 1;
    double rollout_elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - rollout_start).count();
    double elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    // Diagnostic fingerprint for comparing fixture paths; not a security digest.
    std::uint64_t fingerprint = 14695981039346656037ULL;
    const unsigned char* bytes = reinterpret_cast<const unsigned char*>(cdf.data());
    for (std::size_t index = 0; index < cdf.size() * sizeof(double); ++index)
        fingerprint = (fingerprint ^ bytes[index]) * 1099511628211ULL;
    std::printf("{\"cdf_fixture_fingerprint\":\"%016llx\"}\n", static_cast<unsigned long long>(fingerprint));
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
    std::printf("{\"spectrum_fit_ms\":%.6f,\"remove_add_1000_pairs_ms\":%.6f,\"summary_payload_bytes\":%zu,\"fit_and_update_in_prediction_timer\":false,\"phase_and_prior_preparation_in_fit_timer\":true,\"history_arrays_retained\":%s}\n", fit_ms, update_ms, spectrum.payload_bytes(), streamed ? "false" : "true");
    std::printf("{\"scope\":\"isolated_child_fitted_spectrum_normalization_quantization_cdf_and_rollouts_precomputed_phases\",\"phase_construction\":\"%s\",\"dimension\":128,\"states\":16,\"horizon_steps\":120,\"rollouts\":300,\"history_pairs\":%zu,\"peak_rss_bytes\":%llu,\"launcher_peak_rss_bytes\":%llu,\"elapsed_ms\":%.6f,\"periodic_cdf_ms\":%.6f,\"rollout_ms\":%.6f,\"mass_conserved\":true,\"phase_preparation_timed\":false}\n", canonical_month ? "sdk_weekday_and_local_fraction" : "legacy_fmod_elapsed_fraction", pairs, peak, launcher_peak, elapsed, cdf_elapsed, rollout_elapsed);
    std::printf("{\"untimed_warmup_pipeline_ms\":%.6f,\"first_query_coefficient_build_in_warmup\":true}\n", warmup_ms);
    return 0;
}
