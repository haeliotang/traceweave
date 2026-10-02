// Isolated SHA adapter check before integration into the feasible GW relation.
#include "commitment.h"
#include <emp-tool/execution/plain_prot.h>

int main(int argc, char **argv) {
    if (argc != 5) return 2;
    const int owner = std::stoi(argv[1]), nodes = std::stoi(argv[2]);
    if ((owner != 0 && owner != 1) || nodes < 1 || nodes > 16) return 2;
    emp::BristolFashion sha(argv[3]);
    if (sha.num_input != 768 || sha.num_output != 256) return 2;
    emp::setup_plain_prot(true, argv[4]);
    std::vector<emp::Integer> metric, masses;
    for (int i = 0; i < nodes * nodes; ++i) metric.emplace_back(21, 0, emp::ALICE);
    for (int i = 0; i < nodes; ++i) masses.emplace_back(21, 0, emp::ALICE);
    std::vector<emp::Bit> nonce, session;
    for (int i = 0; i < 256; ++i) nonce.emplace_back(false, emp::ALICE);
    for (int i = 0; i < 256; ++i) session.emplace_back(false, emp::BOB);
    auto digest = commitment_digest(sha, owner, nodes, metric, masses, session, nonce);
    emp::Bit zero = metric[0][0] ^ metric[0][0];
    for (auto &bit : digest) (bit ^ zero).reveal<bool>(emp::PUBLIC);
    emp::finalize_plain_prot();
    return 0;
}
