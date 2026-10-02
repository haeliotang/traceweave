#include <emp-tool/emp-tool.h>
#include <emp-tool/execution/plain_prot.h>
#include "square20.h"

int main(int argc, char **argv) {
    if (argc != 2 && argc != 3) return 2;
    if (argc == 3 && std::string(argv[2]) != "--reference") return 2;
    emp::setup_plain_prot(true,argv[1]);
    emp::Integer value(20,0,emp::ALICE);
    emp::Bit dummy(false,emp::BOB);
    emp::Integer squared = argc == 2 ? square20(value) : emp::Integer(40,0);
    if (argc == 3) {
        value.resize(40,false);
        squared = value * value;
    }
    // The pinned recorder needs a real wire even for constant output bits.
    emp::Bit zero = value[0] ^ value[0];
    for (int bit = 0; bit < 40; ++bit) squared.bits[bit] = squared.bits[bit] ^ zero;
    squared.reveal<std::string>(emp::PUBLIC);
    emp::finalize_plain_prot();
}
