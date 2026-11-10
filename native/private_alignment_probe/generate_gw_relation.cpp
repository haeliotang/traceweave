// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

// Bounded GW relation with optional private single-sweep transport descent.
#include <emp-tool/emp-tool.h>
#include <emp-tool/execution/plain_prot.h>
#include <vector>
#include "commitment.h"
#include "square20.h"

using emp::Bit;
using emp::Integer;

Integer wide(Integer value, int width) {
    value.resize(width, true);
    return value;
}

std::vector<Integer> inputs(int count, int owner) {
    std::vector<Integer> values;
    for (int index = 0; index < count; ++index) values.emplace_back(21, 0, owner);
    return values;
}

Bit bounded(const Integer &value) {
    return (value >= Integer(21, 0)) & (value <= Integer(21, 1000000));
}

Bit metric_valid(const std::vector<Integer> &metric, int nodes) {
    Bit valid(true);
    for (int i = 0; i < nodes; ++i) {
        valid = valid & (metric[i * nodes + i] == Integer(21, 0));
        for (int j = i + 1; j < nodes; ++j) {
            valid = valid & bounded(metric[i * nodes + j]);
            valid = valid & (metric[i * nodes + j] == metric[j * nodes + i]);
            // Nonnegative symmetric zero-diagonal metrics make repeated-node
            // triangles tautologies. Each distinct triple has three bounds.
            // Two valid distances sum to <=2000000, within signed 22 bits.
            for (int k = j + 1; k < nodes; ++k) {
                Integer a = wide(metric[i * nodes + j],22);
                Integer b = wide(metric[i * nodes + k],22);
                Integer c = wide(metric[j * nodes + k],22);
                valid = valid & (a <= b + c) & (b <= a + c) & (c <= a + b);
            }
        }
    }
    return valid;
}

int main(int argc, char **argv) {
    if (argc != 4 && argc != 5 && argc != 6) return 2;
    const bool optimize = argc == 6;
    const bool approved = optimize && std::string(argv[5]) == "--approved-packed-lowmemory-permutation";
    const bool low_memory = approved || (optimize && std::string(argv[5]) == "--packed-lowmemory-permutation");
    const bool packed = low_memory || (optimize && std::string(argv[5]) == "--packed-quantized-permutation");
    const bool quantized = packed || (optimize && std::string(argv[5]) == "--quantized-permutation");
    const bool permutation = quantized || (optimize && std::string(argv[5]) == "--uniform-permutation");
    if (optimize && std::string(argv[5]) != "--optimize" && !permutation) return 2;
    const int n = std::stoi(argv[1]), m = std::stoi(argv[2]);
    if (n < 1 || n > 16 || m < 1 || m > 16) return 2;
    if (permutation && (n != m || 1000000 % n)) return 2;
    emp::setup_plain_prot(true, argv[3]);
    // Record all inputs before gates. Runtime BOB supplies this first block.
    auto first = inputs(n * n, emp::ALICE);
    auto first_mass = inputs(n, emp::ALICE);
    std::vector<Integer> transport;
    if (!optimize) transport = inputs(n * m, emp::ALICE);
    std::vector<Bit> first_session, first_nonce, second_session, second_nonce;
    std::vector<Bit> first_approval, second_approval;
    if (argc >= 5) {
        for (int i = 0; i < 256; ++i) first_session.emplace_back(false, emp::ALICE);
        for (int i = 0; i < 256; ++i) first_nonce.emplace_back(false, emp::ALICE);
    }
    if (approved) for (int i = 0; i < 768; ++i) first_approval.emplace_back(false, emp::ALICE);
    auto second = inputs(m * m, emp::BOB);
    auto second_mass = inputs(m, emp::BOB);
    if (argc >= 5) {
        for (int i = 0; i < 256; ++i) second_session.emplace_back(false, emp::BOB);
        for (int i = 0; i < 256; ++i) second_nonce.emplace_back(false, emp::BOB);
    }
    if (approved) for (int i = 0; i < 768; ++i) second_approval.emplace_back(false, emp::BOB);
    if (optimize && !permutation) {
        // Private northwest seed: no owner supplies or learns the full coupling.
        auto columns = second_mass;
        for (int i = 0; i < n; ++i) {
            Integer remaining = first_mass[i];
            for (int k = 0; k < m; ++k) {
                Integer amount = remaining.select(remaining > columns[k], columns[k]);
                transport.push_back(amount);
                remaining = remaining - amount;
                columns[k] = columns[k] - amount;
            }
        }
    }
    Bit valid = metric_valid(first, n) & metric_valid(second, m);
    std::vector<Bit> public_binding;
    if (argc >= 5) {
        emp::BristolFashion sha(argv[4]);
        if (sha.num_input != 768 || sha.num_output != 256) return 2;
        for (int i = 0; i < 256; ++i) valid = valid & (first_session[i] == second_session[i]);
        auto first_digest = packed ? packed_uniform_commitment_digest(sha,0,n,first,first_session,first_nonce)
            : commitment_digest(sha, 0, n, first, first_mass, first_session, first_nonce);
        auto second_digest = packed ? packed_uniform_commitment_digest(sha,1,m,second,second_session,second_nonce)
            : commitment_digest(sha, 1, m, second, second_mass, second_session, second_nonce);
        public_binding = first_session;
        public_binding.insert(public_binding.end(), first_digest.begin(), first_digest.end());
        public_binding.insert(public_binding.end(), second_digest.begin(), second_digest.end());
        // Both parties supply their independently authenticated request. A
        // changed model or request must be masked before any public reveal.
        if (approved) for (int i = 0; i < 768; ++i)
            valid = valid & (first_approval[i] == second_approval[i])
                & (public_binding[i] == first_approval[i]);
    }
    Integer first_total(32, 0), second_total(32, 0);
    for (const auto &mass : first_mass) {
        valid = valid & bounded(mass);
        first_total = first_total + wide(mass, 32);
    }
    for (const auto &mass : second_mass) {
        valid = valid & bounded(mass);
        second_total = second_total + wide(mass, 32);
    }
    valid = valid & (first_total == Integer(32, 1000000)) & (second_total == Integer(32, 1000000));
    if (!permutation) {
    for (const auto &value : transport) valid = valid & bounded(value);
    for (int i = 0; i < n; ++i) {
        Integer row(32, 0);
        for (int k = 0; k < m; ++k) row = row + wide(transport[i * m + k], 32);
        valid = valid & (row == wide(first_mass[i], 32));
    }
    for (int k = 0; k < m; ++k) {
        Integer column(32, 0);
        for (int i = 0; i < n; ++i) column = column + wide(transport[i * m + k], 32);
        valid = valid & (column == wide(second_mass[k], 32));
    }
    }
    // Exact expansion: A^2 weighted by p*p + B^2 weighted by q*q
    // - 2 sum_{i,l} (A*T)[i,l] * (T*B)[i,l]. Marginal checks above
    // bound each inner sum by SCALE^2. Totals need 81 bits before subtraction.
    auto product40 = [](Integer left, Integer right) {
        left.resize(40, false);
        right.resize(40, false);
        return left * right;
    };
    auto product81 = [](Integer left, Integer right) {
        left.resize(81, false);
        right.resize(81, false);
        return left * right;
    };
    auto weighted_square = [&](const std::vector<Integer> &metric,
                               const std::vector<Integer> &mass, int count) {
        Integer total(81, 0);
        for (int i = 0; i < count; ++i)
            for (int j = 0; j < count; ++j)
                total = total + product81(product40(metric[i * count + j], metric[i * count + j]),
                                          product40(mass[i], mass[j]));
        return total;
    };
    Integer objective(81, 0);
    if (permutation) {
        const int mass = 1000000 / n;
        for (const auto &value : first_mass) valid = valid & (value == Integer(21,mass));
        for (const auto &value : second_mass) valid = valid & (value == Integer(21,mass));
        // Uniform permutation couplings reduce the exact loss to
        // mass^2 * sum_ij (Aij-Bpi(i),pi(j))^2. Keep the reordered B private.
        // For valid metrics the unweighted loss is <=256*SCALE^2<2^48;
        // signed 49-bit values cover every candidate and swap difference.
        auto aligned = second;
        auto magnitude20 = [](Integer value) {
            value = value.abs();
            value.resize(20,false);
            return value;
        };
        auto signed_product = [&](Integer left, Integer right) {
            Bit negative = left.bits.back() ^ right.bits.back();
            Integer value = product40(magnitude20(left),magnitude20(right));
            value.resize(49,false);
            return value.select(negative,Integer(49,0) - value);
        };
        if (quantized) {
            // Search uses floor(distance/4096), or /16384 in low-memory mode;
            // commitments and final acceptance retain the full distances.
            const int search_bits = low_memory ? 6 : 8;
            const int search_shift = 20 - search_bits;
            const int gradient_bits = 2 * search_bits + 5;
            // At most 14 terms, each magnitude <2^(2*search_bits): signed
            // 2*search_bits+5 bits cover all valid and invalid coarse inputs.
            auto coarse = [&](const Integer &value) {
                Integer result(search_bits + 1,0);
                for (int bit = 0; bit < search_bits; ++bit) result.bits[bit] = value.bits[search_shift + bit];
                return result;
            };
            // The accepted model is symmetric with zero diagonal. Canonical
            // aliases let paired swaps share wires; validity still checks all
            // original private entries before any public output is accepted.
            for (int i = 0; i < n; ++i) {
                aligned[i * n + i] = Integer(21,0);
                for (int j = 0; j < i; ++j) aligned[i * n + j] = aligned[j * n + i];
            }
            for (int i = 0; i < n; ++i) for (int j = i + 1; j < n; ++j) {
                Integer gradient(gradient_bits,0);
                for (int r = 0; r < n; ++r) if (r != i && r != j) {
                    Integer da = coarse(first[i < r ? i * n + r : r * n + i])
                        - coarse(first[j < r ? j * n + r : r * n + j]);
                    Integer db = coarse(aligned[i * n + r]) - coarse(aligned[j * n + r]);
                    Bit negative = da.bits.back() ^ db.bits.back();
                    da = da.abs(); db = db.abs();
                    da.resize(search_bits,false); db.resize(search_bits,false);
                    da.resize(2 * search_bits,false); db.resize(2 * search_bits,false);
                    Integer value = da * db;
                    value.resize(gradient_bits,false);
                    gradient = gradient + value.select(negative,Integer(gradient_bits,0) - value);
                }
                Bit improve = gradient < Integer(gradient_bits,0);
                for (int r = 0; r < n; ++r) if (r != i && r != j) {
                    Integer left = aligned[i * n + r], right = aligned[j * n + r];
                    aligned[i * n + r] = left.select(improve,right);
                    aligned[j * n + r] = right.select(improve,left);
                    aligned[r * n + i] = aligned[i * n + r];
                    aligned[r * n + j] = aligned[j * n + r];
                }
            }
            Integer initial(49,0), candidate(49,0);
            // Symmetry and zero diagonals are already required by valid.
            // Sum the upper triangle twice; invalid inputs remain masked.
            for (int i = 0; i < n; ++i) for (int j = i + 1; j < n; ++j) {
                Integer before = magnitude20(first[i * n + j] - second[i * n + j]);
                Integer after = magnitude20(first[i * n + j] - aligned[i * n + j]);
                before = square20(before); after = square20(after);
                before.resize(49,false); after.resize(49,false);
                initial = initial + before + before;
                candidate = candidate + after + after;
            }
            Integer score = candidate.select(candidate > initial,initial);
            score.resize(81,false);
            objective = score * Integer(81,static_cast<long long>(mass) * mass);
        } else {
        Integer score(49,0);
        for (int i = 0; i < n * n; ++i) {
            Integer difference = magnitude20(first[i] - aligned[i]);
            Integer square = product40(difference,difference);
            square.resize(49,false);
            score = score + square;
        }
        for (int i = 0; i < n; ++i) for (int j = i + 1; j < n; ++j) {
            Integer gradient(49,0);
            for (int r = 0; r < n; ++r) if (r != i && r != j) {
                Integer da = first[i * n + r] - first[j * n + r];
                Integer db = aligned[i * n + r] - aligned[j * n + r];
                gradient = gradient + signed_product(da,db);
            }
            Integer candidate = score + gradient * Integer(49,4);
            Bit improve = candidate < score;
            score = score.select(improve,candidate);
            for (int r = 0; r < n; ++r) {
                Integer left = aligned[i * n + r], right = aligned[j * n + r];
                aligned[i * n + r] = left.select(improve,right);
                aligned[j * n + r] = right.select(improve,left);
            }
            for (int r = 0; r < n; ++r) {
                Integer left = aligned[r * n + i], right = aligned[r * n + j];
                aligned[r * n + i] = left.select(improve,right);
                aligned[r * n + j] = right.select(improve,left);
            }
        }
        score.resize(81,false);
        objective = score * Integer(81,static_cast<long long>(mass) * mass);
        }
    } else {
    Integer cross(81, 0);
    for (int i = 0; i < n; ++i)
        for (int l = 0; l < m; ++l) {
            Integer at(40, 0), tb(40, 0);
            for (int j = 0; j < n; ++j)
                at = at + product40(first[i * n + j], transport[j * m + l]);
            for (int k = 0; k < m; ++k)
                tb = tb + product40(transport[i * m + k], second[k * m + l]);
            cross = cross + product81(at, tb);
        }
    objective = weighted_square(first, first_mass, n)
        + weighted_square(second, second_mass, m) - cross - cross;
    }
    if (optimize && !permutation) {
        // |gradient| <= SCALE^3 for valid couplings. Candidate arithmetic
        // is bounded in magnitude by 13*SCALE^4 < 2^84, so signed 85 bits
        // cover its intermediate terms; accepted objectives fit unsigned 80.
        objective.resize(85, false);
        for (int i = 0; i < n; ++i) for (int j = i + 1; j < n; ++j)
            for (int k = 0; k < m; ++k) for (int l = k + 1; l < m; ++l) {
                Integer gradient(64, 0);
                for (int a = 0; a < n; ++a) for (int b = 0; b < m; ++b) {
                    Integer da = first[i * n + a] - first[j * n + a];
                    Integer db = second[b * m + k] - second[b * m + l];
                    Integer pair = wide(da, 42) * wide(db, 42);
                    gradient = gradient + wide(pair, 64) * wide(transport[a * m + b], 64);
                }
                Integer lower = Integer(21, 0) - transport[i * m + k].select(
                    transport[i * m + k] > transport[j * m + l], transport[j * m + l]);
                Integer upper = transport[i * m + l].select(
                    transport[i * m + l] > transport[j * m + k], transport[j * m + k]);
                Integer delta(21, 0), best = objective;
                for (const auto &endpoint : {lower, upper}) {
                    Integer absolute = endpoint.select(endpoint < Integer(21, 0), Integer(21, 0) - endpoint);
                    Integer square = product40(absolute, absolute);
                    Integer metric_pair = product40(first[i * n + j], second[k * m + l]);
                    square.resize(85, false);
                    metric_pair.resize(85, false);
                    Integer candidate = objective - wide(gradient, 85) * wide(endpoint, 85) * Integer(85, 4)
                        - metric_pair * square * Integer(85, 8);
                    Bit improve = candidate < best;
                    delta = delta.select(improve, endpoint);
                    best = best.select(improve, candidate);
                }
                transport[i * m + k] = transport[i * m + k] + delta;
                transport[j * m + l] = transport[j * m + l] + delta;
                transport[i * m + l] = transport[i * m + l] - delta;
                transport[j * m + k] = transport[j * m + k] - delta;
                objective = best;
            }
        // Enforce the optimized coupling, including zero-mass rows/columns.
        for (const auto &value : transport) valid = valid & bounded(value);
        for (int i = 0; i < n; ++i) {
            Integer row(32, 0);
            for (int k = 0; k < m; ++k) row = row + wide(transport[i * m + k], 32);
            valid = valid & (row == wide(first_mass[i], 32));
        }
        for (int k = 0; k < m; ++k) {
            Integer column(32, 0);
            for (int i = 0; i < n; ++i) column = column + wide(transport[i * m + k], 32);
            valid = valid & (column == wide(second_mass[k], 32));
        }
    }
    // Valid squared-loss objectives are nonnegative and <= SCALE^4 < 2^80.
    objective.resize(80, false);
    Integer masked = Integer(80, 0).select(valid, objective);
    Bit output_zero = first[0][0] ^ first[0][0];
    valid = valid ^ output_zero;
    for (int index = 0; index < 80; ++index) masked[index] = masked[index] ^ output_zero;
    valid.reveal<bool>(emp::PUBLIC);
    bool output[80];
    masked.revealBools(output, emp::PUBLIC);
    for (const auto &bit : public_binding) ((bit & valid) ^ output_zero).reveal<bool>(emp::PUBLIC);
    emp::finalize_plain_prot();
    return 0;
}
