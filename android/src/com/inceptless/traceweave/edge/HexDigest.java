// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

final class HexDigest {
    static String encode(byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 255;
            output[index * 2] = digits.charAt(value >>> 4);
            output[index * 2 + 1] = digits.charAt(value & 15);
        }
        return new String(output);
    }
}
