package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;

/**
 * The Sankaku Complex forum (forum.sankakucomplex.com) is a Discourse forum. A thread link
 * (forum.sankakucomplex.com/t/name/ID) downloads every picture posted in the thread, in full size.
 */
public class SankakuForumRipper extends AbstractJSONRipper {

    private static final String HOST = "forum.sankakucomplex.com";
    private static final String BASE = "https://" + HOST;

    public SankakuForumRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "sankakuforum";
    }

    @Override
    protected String getDomain() {
        return HOST;
    }

    /** The thread number of /t/name/123, /t/name/123/5 or /t/123, or null. */
    static String threadId(URL url) {
        if (url.getHost() == null || !url.getHost().equalsIgnoreCase(HOST) || url.getPath() == null
                || !url.getPath().startsWith("/t/")) {
            return null;
        }
        String[] parts = url.getPath().substring(3).split("/");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        // "/t/123" or "/t/123/5" (post number) start with the thread number; "/t/name/123..." has the name first
        if (parts[0].matches("\\d+") && (parts.length == 1 || parts[1].matches("\\d+"))) {
            return parts[0];
        }
        return parts.length > 1 && parts[1].matches("\\d+") ? parts[1] : null;
    }

    @Override
    public boolean canRip(URL url) {
        return threadId(url) != null;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String id = threadId(url);
        if (id == null) {
            throw new MalformedURLException("Expected a forum thread link like " + BASE + "/t/name/12345 - got " + url);
        }
        return "thread_" + id;
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        String id = threadId(url);
        JSONObject thread = Http.url(BASE + "/t/" + id + ".json").ignoreContentType().getJSON();
        JSONObject stream = thread.optJSONObject("post_stream");
        if (stream == null) {
            throw new IOException("The forum did not return thread " + id + " (it may need a login).");
        }
        JSONArray posts = new JSONArray();
        Set<Integer> loaded = new LinkedHashSet<>();
        JSONArray first = stream.optJSONArray("posts");
        for (int i = 0; first != null && i < first.length(); i++) {
            posts.put(first.getJSONObject(i));
            loaded.add(first.getJSONObject(i).optInt("id"));
        }
        // the thread answer only holds the first posts; the rest are asked for in batches
        List<Integer> missing = new ArrayList<>();
        JSONArray ids = stream.optJSONArray("stream");
        for (int i = 0; ids != null && i < ids.length(); i++) {
            if (!loaded.contains(ids.getInt(i))) {
                missing.add(ids.getInt(i));
            }
        }
        for (int from = 0; from < missing.size() && !isStopped(); from += 20) {
            StringBuilder query = new StringBuilder();
            for (int j = from; j < Math.min(from + 20, missing.size()); j++) {
                query.append(query.length() == 0 ? "?" : "&").append("post_ids%5B%5D=").append(missing.get(j));
            }
            sleep(500);
            JSONObject more = Http.url(BASE + "/t/" + id + "/posts.json" + query).ignoreContentType().getJSON();
            JSONObject moreStream = more.optJSONObject("post_stream");
            JSONArray morePosts = moreStream == null ? null : moreStream.optJSONArray("posts");
            for (int k = 0; morePosts != null && k < morePosts.length(); k++) {
                posts.put(morePosts.getJSONObject(k));
            }
        }
        return new JSONObject().put("posts", posts);
    }

    /** Pictures and videos in one post's HTML: full-size lightbox links first, then plain pictures and links. */
    static List<String> mediaIn(String cooked, String base) {
        Set<String> found = new LinkedHashSet<>();
        Document doc = Jsoup.parse(cooked, base);
        for (Element a : doc.select("a.lightbox[href]")) {
            found.add(a.absUrl("href"));
        }
        for (Element img : doc.select("img")) {
            if (img.hasClass("emoji") || img.hasClass("avatar") || img.hasClass("site-icon")
                    || (img.parent() != null && img.parent().hasClass("lightbox"))) {
                continue;
            }
            String src = img.absUrl("src");
            if (!src.isEmpty() && !src.contains("/images/emoji/") && !src.startsWith("data:")) {
                found.add(src);
            }
        }
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.matches("(?i)^https?://[^?#]+\\.(png|jpe?g|gif|webp|mp4|webm)(\\?.*)?$")) {
                found.add(href);
            }
        }
        for (Element source : doc.select("video source[src], video[src]")) {
            String src = source.absUrl("src");
            if (!src.isEmpty()) {
                found.add(src);
            }
        }
        return new ArrayList<>(found);
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        Set<String> urls = new LinkedHashSet<>();
        JSONArray posts = json.getJSONArray("posts");
        for (int i = 0; i < posts.length(); i++) {
            urls.addAll(mediaIn(posts.getJSONObject(i).optString("cooked", ""), BASE + "/"));
        }
        return new ArrayList<>(urls);
    }

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new HashMap<>();
        options.put("prefix", getPrefix(index));
        options.put("referrer", BASE + "/");
        addURLToDownload(url, options);
    }
}
