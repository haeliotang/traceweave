// Compatibility probe only; this is not the Traceform private GW circuit.
#include <emp-tool/emp-tool.h>
#include <emp-ag2pc/emp-ag2pc.h>
#include <iostream>
#include <string>
#include <memory>
#ifdef TRACEFORM_TLS_PROBE
#include "tls_io.h"
#include <fstream>

std::string circuit_digest(const char *path) {
    std::ifstream source(path, std::ios::binary);
    if (!source) throw std::runtime_error("circuit source unavailable");
    std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> hash(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    if (!hash || EVP_DigestInit_ex(hash.get(), EVP_sha256(), nullptr) != 1)
        throw std::runtime_error("circuit digest unavailable");
    char chunk[65536];
    while (source.read(chunk, sizeof(chunk)) || source.gcount())
        if (EVP_DigestUpdate(hash.get(), chunk, source.gcount()) != 1)
            throw std::runtime_error("circuit digest rejected");
    if (!source.eof()) throw std::runtime_error("circuit source read rejected");
    unsigned char digest[32];
    unsigned int length = 0;
    if (EVP_DigestFinal_ex(hash.get(), digest, &length) != 1 || length != 32)
        throw std::runtime_error("circuit digest rejected");
    return std::string(reinterpret_cast<char *>(digest), length);
}
#endif

int main(int argc, char **argv) try {
#ifdef TRACEFORM_TLS_PROBE
    if (argc != 9) return 2;
#else
    if (argc != 4) return 2;
#endif
    const std::string role(argv[1]);
    char input_line[11602];
    std::cin.getline(input_line, sizeof(input_line));
    if (!std::cin || std::cin.peek() != std::char_traits<char>::eof()) return 2;
    const std::string bits(input_line);
    if ((role != "1" && role != "2") || bits.empty() || bits.size() > 11600 ||
        bits.find_first_not_of("01") != std::string::npos) return 2;
    const int party = std::stoi(role), port = std::stoi(argv[2]);
    if (port < 1 || port > 65535) return 2;
    emp::BristolFormat circuit(argv[3]);
    const int own_inputs = party == emp::ALICE ? circuit.n2 : circuit.n1;
    if (own_inputs != static_cast<int>(bits.size()) || circuit.n1 < 1 || circuit.n2 < 1 ||
        circuit.n1 > 11600 || circuit.n2 > 11600 ||
        circuit.n3 < 1 || circuit.n3 > 849) return 2;
#ifdef TRACEFORM_TLS_PROBE
    const std::string session_hex(argv[8]);
    if (session_hex.size() != 64 || session_hex.find_first_not_of("0123456789abcdef") != std::string::npos) return 2;
    std::string session(32, '\0');
    for (int byte = 0; byte < 32; ++byte) session[byte] = std::stoul(session_hex.substr(byte * 2, 2), nullptr, 16);
    const std::string digest = circuit_digest(argv[3]);
    emp::NetIO transport(party == emp::ALICE ? nullptr : "127.0.0.1", port);
    TlsIO io(transport, argv[4], argv[5], argv[6], argv[7], session, digest, party);
    emp::C2PC<TlsIO> computation(&io, party, &circuit);
#else
    emp::NetIO transport(party == emp::ALICE ? nullptr : "127.0.0.1", port);
    emp::NetIO &io = transport;
    emp::C2PC<emp::NetIO> computation(&io, party, &circuit);
#endif
#ifdef TRACEFORM_PHASE_PROBE
    // Synthetic host diagnostics only: no inputs or intermediate wires disclosed.
    std::cerr << "TRACEFORM_MPC_PHASE=independent" << std::endl;
#endif
    computation.function_independent();
    io.flush();
    computation.function_dependent();
    io.flush();
    std::unique_ptr<bool[]> input(new bool[circuit.n1 + circuit.n2]());
    std::unique_ptr<bool[]> output(new bool[circuit.n3]());
    // This pinned backend's ALICE consumes n2; BOB consumes n1.
    const int offset = party == emp::ALICE ? circuit.n1 : 0;
    for (int index = 0; index < own_inputs; ++index) input[offset + index] = bits[index] == '1';
    computation.online(input.get(), output.get(), true);
#ifdef TRACEFORM_TLS_PROBE
    if (circuit.n3 == 849 && output[0]) {
        std::string statement_session(32, '\0');
        for (int byte = 0; byte < 32; ++byte)
            for (int bit = 0; bit < 8; ++bit)
                if (output[81 + byte * 8 + bit])
                    statement_session[byte] = static_cast<unsigned char>(statement_session[byte]) | (1u << bit);
        if (CRYPTO_memcmp(statement_session.data(), session.data(), 32) != 0)
            throw std::runtime_error("transport statement session rejected");
    }
#endif
    std::cout << "PUBLIC_OUTPUT=";
    for (int index = 0; index < circuit.n3; ++index) std::cout << (output[index] ? '1' : '0');
    std::cout << std::endl;
    return 0;
} catch (const std::exception &error) {
    std::cerr << error.what() << std::endl;
    return 1;
}
