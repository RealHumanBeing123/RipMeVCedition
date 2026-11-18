package com.rarchives.ripme.utils;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Tumblr's newer address style, where the blog name is the first part of the path
 * ({@code www.tumblr.com/blogname/123456/slug}), to the {@code blogname.tumblr.com} style
 * that the Tumblr ripper understands.
 */
public final class TumblrUrls {

    /** First path segments of www.tumblr.com that are site pages, not blog names. */
    private static final Set<String> RESERVED = Set.of("tagged", "tag", "liked", "dashboard", "explore", "search",
            "settings", "login", "register", "likes", "following", "inbox", "about", "policy", "privacy", "terms",
            "docs", "jobs", "help", "apps", "themes", "follow", "new", "blog", "communities", "reblog", "logout",
            "activity", "account", "oauth", "domains", "gifs", "changes", "business", "abuse", "contact");

    private static final Pattern VIEW = Pattern
            .compile("^https?://(?:www\\.)?tumblr\\.com/blog/view/([a-zA-Z0-9_-]+)(?:/(.*))?$");
    private static final Pattern NEW_STYLE = Pattern
            .compile("^https?://(?:www\\.)?tumblr\\.com/([a-zA-Z0-9_-]+)(?:/(.*))?$");

    private TumblrUrls() {
    }

    /**
     * @return the equivalent blogname.tumblr.com address, or null if the address is not in the newer style
     *         (for example it is already blogname.tumblr.com, or a site page such as /dashboard)
     */
    public static String fromNewStyle(String url) {
        String u = url.trim();
        int cut = indexOfAny(u, '?', '#');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }

        Matcher m = VIEW.matcher(u);
        if (!m.matches()) {
            m = NEW_STYLE.matcher(u);
            if (!m.matches() || RESERVED.contains(m.group(1).toLowerCase())) {
                return null;
            }
        }
        String blog = m.group(1);
        String rest = m.group(2) == null ? "" : m.group(2);
        String base = "https://" + blog + ".tumblr.com";

        if (rest.startsWith("tagged/")) {
            String tag = firstSegment(rest.substring("tagged/".length()));
            if (!tag.isEmpty()) {
                return base + "/tagged/" + tag;
            }
        }
        String first = firstSegment(rest);
        if (first.matches("[0-9]+")) {
            return base + "/post/" + first;
        }
        if (first.equals("likes")) {
            return base + "/likes";
        }
        return base + "/";
    }

    private static String firstSegment(String path) {
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(0, slash) : path;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) {
            return j;
        }
        return j < 0 ? i : Math.min(i, j);
    }
}
