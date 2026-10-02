#ifndef TRACEFORM_PRIVATE_SQUARE20_H
#define TRACEFORM_PRIVATE_SQUARE20_H

#include <emp-tool/emp-tool.h>
#include <stdexcept>
#include <vector>

// Exact unsigned square: diagonal bits plus doubled cross products. Carry-save
// full adders compress each column before one final 40-bit ripple addition.
inline emp::Integer square20(const emp::Integer &value) {
    if (value.size() != 20) throw std::invalid_argument("unsigned 20-bit square input required");
    std::vector<std::vector<emp::Bit>> columns(40);
    for (int i = 0; i < 20; ++i) {
        columns[2 * i].push_back(value[i]);
        for (int j = i + 1; j < 20; ++j)
            columns[i + j + 1].push_back(value[i] & value[j]);
    }
    for (int index = 0; index < 40; ++index) {
        auto &column = columns[index];
        while (column.size() >= 3) {
            emp::Bit a = column.back(); column.pop_back();
            emp::Bit b = column.back(); column.pop_back();
            emp::Bit c = column.back(); column.pop_back();
            emp::Bit axc = a ^ c;
            column.push_back(axc ^ b);
            if (index < 39) columns[index + 1].push_back((axc & (b ^ c)) ^ c);
        }
    }
    emp::Integer first(40,0), second(40,0);
    for (int index = 0; index < 40; ++index) {
        if (!columns[index].empty()) first.bits[index] = columns[index][0];
        if (columns[index].size() > 1) second.bits[index] = columns[index][1];
    }
    return first + second;
}

#endif
