// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

#include "periodic_spectrum.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

static constexpr double pi = 3.14159265358979323846;
static bool canonical(double day, double week) {
    return std::isfinite(day) && std::isfinite(week) && day >= 0 && day <= 2 * pi
        && week >= 0 && week <= 2 * pi
        && std::abs(std::remainder(day - 7 * week, 2 * pi)) <= 1e-12;
}

PeriodicSpectrum::PeriodicSpectrum(std::size_t states) : states_(states) {
    if (states < 1 || states > 16) throw std::invalid_argument("spectrum supports 1..16 states");
    cosine_.resize(states * states * frequencies);
    sine_.resize(cosine_.size());
    counts_.resize(states * states);
    local_polynomial_.resize(states * states * local_terms);
    double bessel[19];
    for (int order = 0; order <= 18; ++order) {
        double term = 1;
        for (int index = 2; index <= order; ++index) term /= index;
        double sum = term;
        for (int index = 1; index < 40; ++index) {
            term /= index * (order + index); sum += term;
        }
        bessel[order] = sum;
    }
    // DLMF 10.35.2; same order-18 truncation as the Python reference.
    for (int day = -18; day <= 18; ++day)
        for (int week = -18; week <= 18; ++week) {
            int frequency = 7 * day + week;
            if (frequency >= 0) coefficients_[frequency] += std::exp(-4) * bessel[std::abs(day)] * bessel[std::abs(week)];
        }
}

int PeriodicSpectrum::apply_delta(double day, double week, int source, int target, int delta) {
    if (!canonical(day, week) || source < 0 || target < 0
        || static_cast<std::size_t>(source) >= states_ || static_cast<std::size_t>(target) >= states_
        || (delta != 1 && delta != -1)) return -1;
    std::size_t edge = source * states_ + target;
    if ((delta < 0 && counts_[edge] == 0) || (delta > 0 && pairs_ >= 1000000)) return -1;
    local_valid_ = false;
    if (delta > 0) { ++counts_[edge]; ++pairs_; }
    else { --counts_[edge]; --pairs_; }
    std::size_t offset = edge * frequencies;
    if (counts_[edge] == 0) {
        std::fill_n(cosine_.data() + offset, frequencies, 0);
        std::fill_n(sine_.data() + offset, frequencies, 0);
    } else {
        for (std::size_t frequency = 0; frequency < frequencies; ++frequency) {
            cosine_[offset + frequency] += delta * std::cos(frequency * week);
            sine_[offset + frequency] += delta * std::sin(frequency * week);
        }
    }
    return 0;
}

int PeriodicSpectrum::cdf(const double* prior, const double* queries, std::size_t horizon, double* output) {
    if (!prior || !queries || !output || horizon < 1 || horizon > 2880) return -1;
    for (std::size_t source = 0; source < states_; ++source) {
        double mass = 0;
        for (std::size_t target = 0; target < states_; ++target) {
            double value = prior[source * states_ + target];
            if (!std::isfinite(value) || value < 0) return -1;
            mass += value;
        }
        if (std::abs(mass - 1) > 1e-12) return -1;
    }
    // Local order-18 Taylor evaluation retains all 145 fitted frequencies.
    // Outside the bounded phase radius, use the direct frequency path below.
    constexpr std::size_t degree = local_terms - 1;
    constexpr double radius = 0.02;
    double center = queries[1] + std::remainder(queries[(horizon - 1) * 2 + 1] - queries[1], 2 * pi) / 2;
    double requested_center = center;
    if (local_valid_) center = local_center_;
    bool local = true;
    for (std::size_t step = 0; step < horizon; ++step) {
        if (!canonical(queries[step * 2], queries[step * 2 + 1])) return -1;
        if (std::abs(std::remainder(queries[step * 2 + 1] - center, 2 * pi)) > radius) local = false;
    }
    bool reused = local_valid_ && local;
    if (!local && local_valid_) {
        center = requested_center; local = true;
        for (std::size_t step = 0; step < horizon; ++step)
            if (std::abs(std::remainder(queries[step * 2 + 1] - center, 2 * pi)) > radius) local = false;
    }
    if (local && horizon >= 64) {
        double arguments[2880];
        for (std::size_t step = 0; step < horizon; ++step)
            arguments[step] = std::remainder(queries[step * 2 + 1] - center, 2 * pi) / radius;
        if (!reused) {
            std::fill(local_polynomial_.begin(), local_polynomial_.end(), 0);
            double basis[frequencies][degree + 1]{}, real[frequencies]{}, imaginary[frequencies]{};
            for (std::size_t frequency = 1; frequency < frequencies; ++frequency) {
                real[frequency] = std::cos(frequency * center);
                imaginary[frequency] = std::sin(frequency * center);
                double factor = 2 * coefficients_[frequency];
                for (std::size_t order = 0; order <= degree; ++order) {
                    basis[frequency][order] = ((order / 2) % 2 ? -1 : 1) * factor;
                    factor *= frequency * radius / (order + 1);
                }
            }
            for (std::size_t edge = 0; edge < states_ * states_; ++edge) {
                double* polynomial = local_polynomial_.data() + edge * (degree + 1);
                if (counts_[edge]) {
                    std::size_t offset = edge * frequencies;
                for (std::size_t frequency = 1; frequency < frequencies; ++frequency) {
                    double cosine = cosine_[offset + frequency], sine = sine_[offset + frequency];
                    double even = real[frequency] * cosine + imaginary[frequency] * sine;
                    double odd = -imaginary[frequency] * cosine + real[frequency] * sine;
                    for (std::size_t order = 0; order <= degree; ++order)
                        polynomial[order] += basis[frequency][order] * (order % 2 ? odd : even);
                }
                    polynomial[0] += coefficients_[0] * cosine_[offset];
                }
            }
            local_center_ = center; local_valid_ = true;
        }
        for (std::size_t edge = 0; edge < states_ * states_; ++edge) {
            const double* polynomial = local_polynomial_.data() + edge * (degree + 1);
            constexpr std::size_t lanes = 8;
            for (std::size_t start = 0; start < horizon; start += lanes) {
                std::size_t count = std::min(lanes, horizon - start);
                double weights[lanes], inputs[lanes]{};
                for (std::size_t lane = 0; lane < lanes; ++lane) {
                    weights[lane] = polynomial[degree];
                    if (lane < count) inputs[lane] = arguments[start + lane];
                }
                for (std::size_t order = degree; order > 0; --order)
                    for (std::size_t lane = 0; lane < lanes; ++lane)
                        weights[lane] = weights[lane] * inputs[lane] + polynomial[order - 1];
                for (std::size_t lane = 0; lane < count; ++lane) {
                    if (!std::isfinite(weights[lane]) || weights[lane] < 0) return -1;
                    output[(start + lane) * states_ * states_ + edge] = 5 * prior[edge] + weights[lane];
                }
            }
        }
        for (std::size_t step = 0; step < horizon; ++step)
            for (std::size_t source = 0; source < states_; ++source) {
                double* row = output + step * states_ * states_ + source * states_;
                double total = 0, cumulative = 0;
                for (std::size_t target = 0; target < states_; ++target) total += row[target];
                for (std::size_t target = 0; target < states_; ++target) {
                    cumulative += row[target] / total;
                    row[target] = target == states_ - 1 ? 1 : std::min(1.0, cumulative);
                }
            }
        return 0;
    }
    constexpr std::size_t block = 16;
    for (std::size_t start = 0; start < horizon; start += block) {
        std::size_t count = std::min(block, horizon - start);
        double cosine[frequencies][block]{}, sine[frequencies][block]{};
        for (std::size_t lane = 0; lane < count; ++lane) {
            std::size_t step = start + lane;
            if (!canonical(queries[step * 2], queries[step * 2 + 1])) return -1;
            double week = queries[step * 2 + 1];
            double base_cosine = std::cos(week), base_sine = std::sin(week);
            double real = 1, imaginary = 0;
            for (std::size_t frequency = 0; frequency < frequencies; ++frequency) {
                if (frequency % 16 == 0) {
                    real = std::cos(frequency * week); imaginary = std::sin(frequency * week);
                } else {
                    double next_real = real * base_cosine - imaginary * base_sine;
                    imaginary = imaginary * base_cosine + real * base_sine;
                    real = next_real;
                }
                cosine[frequency][lane] = 2 * coefficients_[frequency] * real;
                sine[frequency][lane] = 2 * coefficients_[frequency] * imaginary;
            }
        }
        for (std::size_t edge = 0; edge < states_ * states_; ++edge) {
            double weights[block]{};
            if (counts_[edge]) {
                std::size_t offset = edge * frequencies;
                for (std::size_t lane = 0; lane < block; ++lane)
                    weights[lane] = coefficients_[0] * cosine_[offset];
                for (std::size_t frequency = 1; frequency < frequencies; ++frequency) {
                    double real = cosine_[offset + frequency], imaginary = sine_[offset + frequency];
                    for (std::size_t lane = 0; lane < block; ++lane)
                        weights[lane] += cosine[frequency][lane] * real + sine[frequency][lane] * imaginary;
                }
            }
            for (std::size_t lane = 0; lane < count; ++lane) {
                if (!std::isfinite(weights[lane]) || weights[lane] < 0) return -1;
                output[(start + lane) * states_ * states_ + edge] = 5 * prior[edge] + weights[lane];
            }
        }
        for (std::size_t lane = 0; lane < count; ++lane) {
            double* matrix = output + (start + lane) * states_ * states_;
            for (std::size_t source = 0; source < states_; ++source) {
                double* row = matrix + source * states_;
                double total = 0, cumulative = 0;
                for (std::size_t target = 0; target < states_; ++target) total += row[target];
                for (std::size_t target = 0; target < states_; ++target) {
                    cumulative += row[target] / total;
                    row[target] = target == states_ - 1 ? 1 : std::min(1.0, cumulative);
                }
            }
        }
    }
    return 0;
}

std::size_t PeriodicSpectrum::payload_bytes() const {
    return (cosine_.size() + sine_.size() + coefficients_.size() + local_polynomial_.size()) * sizeof(double) + counts_.size() * sizeof(unsigned int);
}
