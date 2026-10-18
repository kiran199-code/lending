package com.bank.lending.iam.domain;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Which webhook addresses are acceptable. Callbacks must not be a way to make this service call its own network:
 * https only, no credentials in the address, and no host that is (or resolves to) a loopback, private, link-local
 * or metadata address. {@code allowHttp} relaxes all of that for local development.
 */
public final class UrlPolicy {
    private UrlPolicy() {}

    /** @return null when acceptable, otherwise the reason */
    public static String problem(String url, boolean allowHttp) {
        if (url == null || url.isBlank() || url.length() > 300) return "url is required (up to 300 characters)";
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "url is not a valid address";
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) return "url must use https";
        if (u.getHost() == null || u.getHost().isBlank()) return "url has no host";
        if (u.getUserInfo() != null) return "url must not contain credentials";
        if (allowHttp) return null;
        String host = u.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".local")) return "url host is not allowed";
        if (isIpLiteral(host) && blockedAddress(host)) return "url host is not allowed";
        return null;
    }

    /** Checked again at send time, against what the name resolves to now. */
    public static boolean resolvesToBlocked(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (blocked(a)) return true;
            }
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("^[0-9.]+$") || host.contains(":");
    }

    private static boolean blockedAddress(String host) {
        try {
            return blocked(InetAddress.getByName(host.replace("[", "").replace("]", "")));
        } catch (UnknownHostException e) {
            return true;
        }
    }

    static boolean blocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;     // carrier-grade NAT
            if (b0 == 169 && b1 == 254) return true;                 // link-local and cloud metadata
            if (b0 == 0) return true;
        } else if (b.length == 16 && (b[0] & 0xfe) == 0xfc) {
            return true;                                              // unique local fc00::/7
        }
        return false;
    }
}
