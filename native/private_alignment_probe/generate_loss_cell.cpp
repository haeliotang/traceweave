// One bounded loss contribution, not a complete GW or commitment circuit.
#include <emp-tool/emp-tool.h>
#include <emp-tool/execution/plain_prot.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    emp::setup_plain_prot(true, argv[1]);
    emp::Integer first(21, 0, emp::ALICE), second(21, 0, emp::BOB);
    emp::Integer zero(21, 0), scale(21, 1000000);
    emp::Bit valid = (first >= zero) & (first <= scale) & (second >= zero) & (second <= scale);
    first.resize(80, true);
    second.resize(80, true);
    emp::Integer difference = first - second;
    emp::Integer loss = difference * difference * emp::Integer(80, 1000000000000LL);
    emp::Integer masked = emp::Integer(80, 0).select(valid, loss);
    // The pinned recorder cannot reveal public constants directly: its public
    // label has no wire id. Give every output bit a real, identically-zero wire.
    emp::Bit output_zero = first[0] ^ first[0];
    for (int index = 0; index < 80; ++index) masked[index] = masked[index] ^ output_zero;
    valid.reveal<bool>(emp::PUBLIC);
    bool output[80];
    masked.revealBools(output, emp::PUBLIC);
    emp::finalize_plain_prot();
    return 0;
}
