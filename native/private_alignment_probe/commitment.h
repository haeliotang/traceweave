// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

#ifndef TRACEFORM_PRIVATE_COMMITMENT_H
#define TRACEFORM_PRIVATE_COMMITMENT_H

#include <emp-tool/emp-tool.h>
#include <cstdint>
#include <vector>

// Bytes use least-significant-bit order internally. The pinned Bristol SHA
// compression circuit reverses both word order and bits within each word.
inline void append_public_byte(std::vector<emp::Bit> &bytes, uint8_t value) {
    for (int bit = 0; bit < 8; ++bit) bytes.emplace_back((value >> bit) & 1);
}

inline std::vector<emp::Bit> sha256_digest(
    emp::BristolFashion &sha, std::vector<emp::Bit> bytes) {
    const uint64_t length = bytes.size();
    append_public_byte(bytes, 0x80);
    while ((bytes.size() / 8) % 64 != 56) append_public_byte(bytes, 0);
    for (int i = 7; i >= 0; --i) append_public_byte(bytes, length >> (8 * i));
    const uint32_t initial[] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    std::vector<emp::Bit> state(256);
    for (int word = 0; word < 8; ++word)
        for (int bit = 0; bit < 32; ++bit)
            state[(7 - word) * 32 + bit] = emp::Bit((initial[word] >> bit) & 1);
    for (size_t offset = 0; offset < bytes.size(); offset += 512) {
        std::vector<emp::Bit> input(768), output(256);
        for (int word = 0; word < 16; ++word)
            for (int byte = 0; byte < 4; ++byte)
                for (int bit = 0; bit < 8; ++bit)
                    input[(15 - word) * 32 + (3 - byte) * 8 + bit] =
                        bytes[offset + word * 32 + byte * 8 + bit];
        std::copy(state.begin(), state.end(), input.begin() + 512);
        sha.compute(output.data(), input.data());
        state = std::move(output);
    }
    std::vector<emp::Bit> digest(256);
    for (int word = 0; word < 8; ++word)
        for (int byte = 0; byte < 4; ++byte)
            for (int bit = 0; bit < 8; ++bit)
                digest[word * 32 + byte * 8 + bit] = state[(7 - word) * 32 + (3 - byte) * 8 + bit];
    return digest;
}

inline std::vector<emp::Bit> commitment_digest(
    emp::BristolFashion &sha, int owner, int nodes,
    const std::vector<emp::Integer> &metric,
    const std::vector<emp::Integer> &masses,
    const std::vector<emp::Bit> &session,
    const std::vector<emp::Bit> &nonce) {
    std::vector<emp::Bit> bytes;
    const char domain[] = "traceform.gw.commit.v1";
    for (char value : domain) append_public_byte(bytes, value);
    append_public_byte(bytes, owner);
    append_public_byte(bytes, nodes);
    const uint32_t scale = 1000000;
    for (int i = 0; i < 4; ++i) append_public_byte(bytes, scale >> (8 * i));
    bytes.insert(bytes.end(), session.begin(), session.end());
    bytes.insert(bytes.end(), nonce.begin(), nonce.end());
    for (const auto *values : {&metric, &masses})
        for (const auto &value : *values)
            for (int bit = 0; bit < 32; ++bit)
                bytes.push_back(bit < value.size() ? value[bit] : emp::Bit(false));
    return sha256_digest(sha,std::move(bytes));
}

// This encoding binds the full model only alongside checked symmetry, zero
// diagonals, bounded distances and exactly uniform masses. It is a separate
// domain/version, never a replacement encoding for arbitrary v1 witnesses.
inline std::vector<emp::Bit> packed_uniform_commitment_digest(
    emp::BristolFashion &sha, int owner, int nodes,
    const std::vector<emp::Integer> &metric,
    const std::vector<emp::Bit> &session,
    const std::vector<emp::Bit> &nonce) {
    std::vector<emp::Bit> bytes;
    const char domain[] = "traceform.gw.uniform-packed.v2";
    for (char value : domain) append_public_byte(bytes, value);
    append_public_byte(bytes, owner);
    append_public_byte(bytes, nodes);
    for (uint32_t value : {1000000u,1000000u / static_cast<uint32_t>(nodes)})
        for (int i = 0; i < 4; ++i) append_public_byte(bytes, value >> (8 * i));
    bytes.insert(bytes.end(), session.begin(), session.end());
    bytes.insert(bytes.end(), nonce.begin(), nonce.end());
    for (int i = 0; i < nodes; ++i) for (int j = i + 1; j < nodes; ++j)
        for (int bit = 0; bit < 20; ++bit) bytes.push_back(metric[i * nodes + j][bit]);
    while (bytes.size() % 8) bytes.emplace_back(false);
    return sha256_digest(sha,std::move(bytes));
}

#endif
