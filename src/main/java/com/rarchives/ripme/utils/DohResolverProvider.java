package com.rarchives.ripme.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Looks up the addresses of a few websites through "DNS over HTTPS" (the same secure lookup Firefox uses by default).
 *
 * Some internet providers hand out wrong or blocked addresses for certain websites through the normal lookup. A
 * browser using DNS over HTTPS gets the real ones and works, while Java programs such as ripme keep trying the
 * wrong address and time out. For the websites listed below ripme now asks Cloudflare (then Google) directly;
 * every other website, and any failure, goes through the normal system lookup.
 *
 * Change the list with the Java option -Dripme.doh.domains=a.com,b.org, or turn it off with -Dripme.doh=off.
 */
public class DohResolverProvider extends InetAddressResolverProvider {

    private static final String DEFAULT_DOMAINS = "kemono.cr,kemono.su,kemono.party,coomer.st,coomer.su,coomer.party";
    // Addresses (not names), so asking them needs no lookup of its own.
    private static final String[] SERVERS = { "https://1.1.1.1/dns-query", "https://8.8.8.8/resolve" };
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;

    private static final class Cached {
        final List<InetAddress> addresses;
        final long when = System.currentTimeMillis();

        Cached(List<InetAddress> addresses) {
            this.addresses = addresses;
        }
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        if ("off".equalsIgnoreCase(System.getProperty("ripme.doh", "on"))) {
            return builtin;
        }
        List<String> domains = domains();
        Map<String, Cached> cache = new ConcurrentHashMap<>();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                if (host != null && matches(host, domains)) {
                    String key = host.toLowerCase();
                    Cached cached = cache.get(key);
                    if (cached == null || System.currentTimeMillis() - cached.when > CACHE_MILLIS) {
                        List<InetAddress> found = query(key);
                        cached = found.isEmpty() ? null : new Cached(found);
                        if (cached != null) {
                            cache.put(key, cached);
                        }
                    }
                    if (cached != null) {
                        return cached.addresses.stream();
                    }
                }
                return builtin.lookupByName(host, policy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "ripme DNS over HTTPS";
    }

    static List<String> domains() {
        List<String> list = new ArrayList<>();
        for (String d : System.getProperty("ripme.doh.domains", DEFAULT_DOMAINS).split(",")) {
            String trimmed = d.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    static boolean matches(String host, List<String> domains) {
        String h = host.toLowerCase();
        for (String d : domains) {
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /** Asks the secure lookup servers for the website's IPv4 addresses; empty when none answered. */
    static List<InetAddress> query(String host) {
        for (String server : SERVERS) {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(server + "?name=" + host + "&type=A").toURL().openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Accept", "application/dns-json");
                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        body.append(line);
                    }
                }
                List<InetAddress> found = parse(host, body.toString());
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (IOException | RuntimeException e) {
                // try the next server
            }
        }
        return new ArrayList<>();
    }

    /** Reads {"Answer":[{"type":1,"data":"1.2.3.4"}, ...]} and keeps the IPv4 (type 1) entries. */
    static List<InetAddress> parse(String host, String json) throws IOException {
        List<InetAddress> found = new ArrayList<>();
        JSONArray answers = new JSONObject(json).optJSONArray("Answer");
        if (answers == null) {
            return found;
        }
        for (int i = 0; i < answers.length(); i++) {
            JSONObject a = answers.getJSONObject(i);
            if (a.optInt("type") == 1) {
                byte[] raw = InetAddress.getByName(a.getString("data")).getAddress(); // a number, no lookup needed
                found.add(InetAddress.getByAddress(host, raw));
            }
        }
        return found;
    }
}
