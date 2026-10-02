#pragma once
#include <array>
#include <cstddef>
#include <vector>

// Experimental kappa=2 local-calendar spectrum, not wired into the SDK.
// The caller owns eligibility/model binding and validates removal against its
// authoritative history. This summary does not authenticate individual records.
// Query coefficient reuse mutates the snapshot; callers serialize queries/deltas.
class PeriodicSpectrum {
public:
    static constexpr std::size_t frequencies = 145;
    explicit PeriodicSpectrum(std::size_t states);
    int apply_delta(double day, double week, int source, int target, int delta);
    int cdf(const double* prior, const double* queries, std::size_t horizon, double* output);
    std::size_t payload_bytes() const;
private:
    static constexpr std::size_t local_terms = 19;
    std::size_t states_, pairs_ = 0;
    std::array<double, frequencies> coefficients_{};
    std::vector<double> cosine_, sine_;
    std::vector<unsigned int> counts_;
    std::vector<double> local_polynomial_;
    double local_center_ = 0;
    bool local_valid_ = false;
};
