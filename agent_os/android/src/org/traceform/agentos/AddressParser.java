package org.traceform.agentos;

/** Parses "host:port" into a multiaddr without depending on Android classes. */
final class AddressParser {
    private AddressParser() {}

    static String address(String hostPort) {
        String value = hostPort == null ? "" : hostPort.trim();
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return null;
        String host = value.substring(0, colon).trim();
        String port = value.substring(colon + 1).trim();
        if (host.isEmpty() || port.isEmpty() || port.length() > 5) return null;
        for (int index = 0; index < port.length(); index++) if (!Character.isDigit(port.charAt(index))) return null;
        int number;
        try {
            number = Integer.parseInt(port);
        } catch (NumberFormatException error) {
            return null;
        }
        if (number <= 0 || number > 65535) return null;
        return "/ip4/" + host + "/tcp/" + number;
    }
}
