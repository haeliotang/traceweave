#pragma once
#include <cstddef>

extern "C" {
int traceform_periodic_cdf(const double* prior, const double* query_phases,
                          const double* observed_phases, const int* sources,
                          const int* targets, std::size_t pairs, std::size_t states,
                          std::size_t horizon, double* output);
int traceform_normalize(const double* raw, const double* median, const double* iqr,
                        std::size_t dimension, double* output);
int traceform_quantize(const double* position, const double* velocity,
                       const double* centers, const double* center_velocity,
                       std::size_t nodes, std::size_t dimension, double beta,
                       double* distance);
int traceform_rollouts(const double* cdf_by_step, std::size_t states,
                       std::size_t horizon, std::size_t rollouts,
                       std::size_t initial, const double* draws,
                       unsigned long long* occupancy);
int traceform_counterfactual(const double* cdf_by_step, const double* global_prior,
                            std::size_t states, std::size_t horizon, std::size_t rollouts,
                            std::size_t initial, std::size_t max_dwell,
                            const double* baseline_draws, const double* scenario_draws,
                            unsigned long long* occupancy);
}
