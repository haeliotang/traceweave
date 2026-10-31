package com.inceptless.traceweave;

public final class AddressParserHostTest {
    public static void main(String[] args) {
        check("/ip4/127.0.0.1/tcp/47961".equals(AddressParser.address("127.0.0.1:47961")), "loopback address");
        check("/ip4/192.168.0.25/tcp/47961".equals(AddressParser.address(" 192.168.0.25:47961 ")), "trimmed address");
        check(AddressParser.address("127.0.0.1") == null, "missing port rejected");
        check(AddressParser.address(":47961") == null, "missing host rejected");
        check(AddressParser.address("127.0.0.1:port") == null, "non numeric port rejected");
        check(AddressParser.address("127.0.0.1:70000") == null, "out of range port rejected");
        check(AddressParser.address("127.0.0.1:0") == null, "zero port rejected");
        check(AddressParser.address(null) == null, "null rejected");
        System.out.println("ADDRESS_OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
