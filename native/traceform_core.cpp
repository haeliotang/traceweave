#include "traceform_core.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <vector>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

static double round_five(double value) {
    char rounded[64];
    std::snprintf(rounded, sizeof(rounded), "%.5f", value);
    return std::strtod(rounded, nullptr);
}

extern "C" int traceform_counterfactual(const double* cdf_by_step, const double* global_prior,
                                        std::size_t states, std::size_t horizon, std::size_t rollouts,
                                        std::size_t initial, std::size_t max_dwell,
                                        const double* baseline_draws, const double* scenario_draws,
                                        unsigned long long* occupancy) {
    if (!global_prior || !scenario_draws || states == 0 || states > 128 || horizon == 0 ||
        horizon > 2880 || rollouts == 0 || rollouts > 10000 || max_dwell == 0 || initial >= states ||
        horizon * states * states > 1000000 || rollouts * (horizon - 1) > 1000000) return -1;
    for (std::size_t source = 0; source < states; ++source) {
        double total = 0;
        for (std::size_t target = 0; target < states; ++target) {
            double value = global_prior[source * states + target];
            if (!std::isfinite(value) || value < 0) return -1;
            total += value;
        }
        if (std::abs(total - 1) > 1e-9) return -1;
    }
    if (traceform_rollouts(cdf_by_step, states, horizon, rollouts, initial, baseline_draws, occupancy) != 0) return -1;
    unsigned long long* scenario = occupancy + horizon * states;
    std::fill(scenario, scenario + horizon * states, 0);
    for (std::size_t run = 0; run < rollouts; ++run) {
        std::size_t current = initial, dwell = 1;
        ++scenario[current];
        for (std::size_t step = 1; step < horizon; ++step) {
            double draw = scenario_draws[run * (horizon - 1) + step - 1];
            if (!std::isfinite(draw) || draw < 0 || draw >= 1) return -1;
            std::size_t selected = current;
            if (dwell >= max_dwell) {
                const double* row = global_prior + current * states;
                double total = 0;
                for (std::size_t target = 0; target < states; ++target)
                    if (target != current) total += row[target];
                if (total > 0) {
                    double cumulative = 0;
                    bool first = true;
                    for (std::size_t target = 0; target < states; ++target) {
                        if (target == current || row[target] <= 0) continue;
                        if (first) { selected = target; first = false; }
                        cumulative += row[target];
                        if (draw * total <= cumulative) { selected = target; break; }
                    }
                } else if (states > 1) {
                    selected = static_cast<std::size_t>(draw * (states - 1));
                    if (selected >= current) ++selected;
                }
            } else {
                const double* row = cdf_by_step + (step * states + current) * states;
                selected = std::min(states - 1, static_cast<std::size_t>(std::upper_bound(row, row + states, draw) - row));
            }
            dwell = selected == current ? dwell + 1 : 1;
            current = selected;
            ++scenario[step * states + current];
        }
    }
    return 0;
}

extern "C" int traceform_periodic_cdf(const double* prior, const double* query_phases,
                                      const double* observed_phases, const int* sources,
                                      const int* targets, std::size_t pairs, std::size_t states,
                                      std::size_t horizon, double* output) {
    if (!prior || !query_phases || !output || states == 0 || states > 128 ||
        horizon == 0 || horizon > 2880 || pairs > 1000000 ||
        (pairs && (!observed_phases || !sources || !targets))) return -1;
    for (std::size_t source = 0; source < states; ++source) {
        double total = 0;
        for (std::size_t target = 0; target < states; ++target) {
            double value = prior[source * states + target];
            if (!std::isfinite(value) || value < 0) return -1;
            total += value;
        }
        if (std::abs(total - 1) > 1e-9) return -1;
    }
    for (std::size_t pair = 0; pair < pairs; ++pair)
        if (sources[pair] < 0 || static_cast<std::size_t>(sources[pair]) >= states ||
            targets[pair] < 0 || static_cast<std::size_t>(targets[pair]) >= states ||
            !std::isfinite(observed_phases[pair * 2]) || !std::isfinite(observed_phases[pair * 2 + 1])) return -1;
    std::vector<double> query_trig(horizon * 4);
    for (std::size_t step = 0; step < horizon; ++step) {
        double day = query_phases[step * 2], week = query_phases[step * 2 + 1];
        if (!std::isfinite(day) || !std::isfinite(week)) return -1;
        query_trig[step * 4] = std::cos(day);
        query_trig[step * 4 + 1] = std::sin(day);
        query_trig[step * 4 + 2] = std::cos(week);
        query_trig[step * 4 + 3] = std::sin(week);
        double* matrix = output + step * states * states;
        for (std::size_t index = 0; index < states * states; ++index) matrix[index] = 5 * prior[index];
    }
    for (std::size_t pair = 0; pair < pairs; ++pair) {
        double observed_day = observed_phases[pair * 2], observed_week = observed_phases[pair * 2 + 1];
        double day_cos = std::cos(observed_day), day_sin = std::sin(observed_day);
        double week_cos = std::cos(observed_week), week_sin = std::sin(observed_week);
        for (std::size_t step = 0; step < horizon; ++step) {
            double day = query_phases[step * 2], week = query_phases[step * 2 + 1];
            double day_delta = day - observed_day, week_delta = week - observed_week;
            if (!std::isfinite(day_delta) || !std::isfinite(week_delta)) return -1;
            // Preserve subtraction-based argument reduction for unusually large phases.
            double day_similarity = std::abs(day) > 32 || std::abs(observed_day) > 32
                ? std::cos(day_delta) : query_trig[step * 4] * day_cos + query_trig[step * 4 + 1] * day_sin;
            double week_similarity = std::abs(week) > 32 || std::abs(observed_week) > 32
                ? std::cos(week_delta) : query_trig[step * 4 + 2] * week_cos + query_trig[step * 4 + 3] * week_sin;
            double weight = std::exp(2 * (day_similarity - 1) + 2 * (week_similarity - 1));
            output[step * states * states + sources[pair] * states + targets[pair]] += weight;
        }
    }
    for (std::size_t step = 0; step < horizon; ++step) {
        double* matrix = output + step * states * states;
        for (std::size_t source = 0; source < states; ++source) {
            double* row = matrix + source * states;
            double total = 0, cumulative = 0;
            for (std::size_t target = 0; target < states; ++target) total += row[target];
            for (std::size_t target = 0; target < states; ++target) {
                cumulative += row[target] / total;
                row[target] = target == states - 1 ? 1 : std::min(1.0, cumulative);
            }
        }
    }
    return 0;
}

extern "C" int traceform_normalize(const double* raw, const double* median,
                                   const double* iqr, std::size_t dimension,
                                   double* output) {
    if (!raw || !median || !iqr || !output || dimension == 0) return -1;
    std::size_t index = 0;
#if defined(__aarch64__)
    for (; index + 1 < dimension; index += 2) {
        for (std::size_t lane = 0; lane < 2; ++lane)
            if (!std::isfinite(raw[index + lane]) || !std::isfinite(median[index + lane]) || !std::isfinite(iqr[index + lane])) return -1;
        float64x2_t scale = vld1q_f64(iqr + index);
        scale = vbslq_f64(vcltq_f64(scale, vdupq_n_f64(1e-6)), vdupq_n_f64(1), scale);
        float64x2_t value = vdivq_f64(vsubq_f64(vld1q_f64(raw + index), vld1q_f64(median + index)), scale);
        value = vmaxq_f64(vdupq_n_f64(-10), vminq_f64(vdupq_n_f64(10), value));
        double lanes[2];
        vst1q_f64(lanes, value);
        output[index] = round_five(lanes[0]);
        output[index + 1] = round_five(lanes[1]);
    }
#endif
    for (; index < dimension; ++index) {
        if (!std::isfinite(raw[index]) || !std::isfinite(median[index]) ||
            !std::isfinite(iqr[index])) return -1;
        double scale = iqr[index] < 1e-6 ? 1.0 : iqr[index];
        double value = std::max(-10.0, std::min(10.0, (raw[index] - median[index]) / scale));
        output[index] = round_five(value);
    }
    return 0;
}

extern "C" int traceform_quantize(const double* position, const double* velocity,
                                  const double* centers, const double* center_velocity,
                                  std::size_t nodes, std::size_t dimension, double beta,
                                  double* distance) {
    if (!position || !velocity || !centers || !center_velocity || !distance ||
        nodes == 0 || dimension == 0 || !std::isfinite(beta)) return -1;
    double speed_squared = 0;
    for (std::size_t index = 0; index < dimension; ++index) {
        if (!std::isfinite(position[index]) || !std::isfinite(velocity[index])) return -1;
        speed_squared += velocity[index] * velocity[index];
    }
    double speed = std::sqrt(speed_squared);
    double best = std::numeric_limits<double>::infinity();
    int selected = -1;
    for (std::size_t node = 0; node < nodes; ++node) {
        double position_distance = 0, velocity_distance = 0, center_speed_squared = 0, dot = 0;
        std::size_t index = 0;
#if defined(__aarch64__)
        float64x2_t position_sum = vdupq_n_f64(0), velocity_sum = vdupq_n_f64(0);
        float64x2_t center_sum = vdupq_n_f64(0), dot_sum = vdupq_n_f64(0);
        for (; index + 1 < dimension; index += 2) {
            std::size_t offset = node * dimension + index;
            for (std::size_t lane = 0; lane < 2; ++lane)
                if (!std::isfinite(centers[offset + lane]) || !std::isfinite(center_velocity[offset + lane])) return -1;
            float64x2_t position_delta = vsubq_f64(vld1q_f64(position + index), vld1q_f64(centers + offset));
            float64x2_t current_velocity = vld1q_f64(velocity + index);
            float64x2_t reference_velocity = vld1q_f64(center_velocity + offset);
            float64x2_t velocity_delta = vsubq_f64(current_velocity, reference_velocity);
            position_sum = vaddq_f64(position_sum, vmulq_f64(position_delta, position_delta));
            velocity_sum = vaddq_f64(velocity_sum, vmulq_f64(velocity_delta, velocity_delta));
            center_sum = vaddq_f64(center_sum, vmulq_f64(reference_velocity, reference_velocity));
            dot_sum = vaddq_f64(dot_sum, vmulq_f64(current_velocity, reference_velocity));
        }
        position_distance = vaddvq_f64(position_sum);
        velocity_distance = vaddvq_f64(velocity_sum);
        center_speed_squared = vaddvq_f64(center_sum);
        dot = vaddvq_f64(dot_sum);
#endif
        for (; index < dimension; ++index) {
            std::size_t offset = node * dimension + index;
            if (!std::isfinite(centers[offset]) || !std::isfinite(center_velocity[offset])) return -1;
            double delta = position[index] - centers[offset];
            double velocity_delta = velocity[index] - center_velocity[offset];
            position_distance += delta * delta;
            velocity_distance += velocity_delta * velocity_delta;
            center_speed_squared += center_velocity[offset] * center_velocity[offset];
            dot += velocity[index] * center_velocity[offset];
        }
        double center_speed = std::sqrt(center_speed_squared);
        double kinetic = velocity_distance;
        if (speed >= 1e-4 && center_speed >= 1e-4) {
            double cosine = std::max(-1.0, std::min(1.0, dot / (speed * center_speed)));
            kinetic = (1.0 - cosine) * speed + 0.1 * std::abs(speed - center_speed);
        }
        double energy = position_distance + beta * kinetic;
        if (energy < best) {
            best = energy;
            selected = static_cast<int>(node);
        }
    }
    *distance = best;
    return selected;
}

extern "C" int traceform_rollouts(const double* cdf_by_step, std::size_t states,
                                  std::size_t horizon, std::size_t rollouts,
                                  std::size_t initial, const double* draws,
                                  unsigned long long* occupancy) {
    if (!cdf_by_step || !draws || !occupancy || states == 0 || horizon == 0 ||
        rollouts == 0 || initial >= states) return -1;
    for (std::size_t step = 0; step < horizon; ++step) {
        for (std::size_t state = 0; state < states; ++state) {
            const double* row = cdf_by_step + (step * states + state) * states;
            double previous = 0;
            for (std::size_t target = 0; target < states; ++target) {
                if (!std::isfinite(row[target]) || row[target] < previous || row[target] > 1) return -1;
                previous = row[target];
            }
            if (std::abs(previous - 1) > 1e-9) return -1;
        }
    }
    std::fill(occupancy, occupancy + horizon * states, 0);
    for (std::size_t run = 0; run < rollouts; ++run) {
        std::size_t current = initial;
        ++occupancy[current];
        for (std::size_t step = 1; step < horizon; ++step) {
            double draw = draws[run * (horizon - 1) + step - 1];
            if (!std::isfinite(draw) || draw < 0 || draw >= 1) return -1;
            const double* row = cdf_by_step + (step * states + current) * states;
            if (states == 16) {
                std::size_t target = row[7] <= draw ? 8 : 0;
                target += row[target + 3] <= draw ? 4 : 0;
                target += row[target + 1] <= draw ? 2 : 0;
                target += row[target] <= draw ? 1 : 0;
                current = target;
            } else {
                current = std::min(states - 1, static_cast<std::size_t>(std::upper_bound(row, row + states, draw) - row));
            }
            ++occupancy[step * states + current];
        }
    }
    return 0;
}
