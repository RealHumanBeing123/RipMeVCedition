package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/**
 * LeakGallery (leakgallery.com). leakgallery.com/NAME rips a model's media, leakgallery.com/NAME/12345 one post.
 *
 * The site is an Angular app that sends the data it used to draw the page along with the page, in a
 * <script id="ng-state"> block: "httpGet:profile/NAME?..." holds the first 40 items of a model (each with a
 * "file_path" such as content6/NAME/xyz.jpeg) and "httpGet:media/ID" holds one post. Files live on
 * https://cdn.leakgallery.com/ + file_path.
 *
 * Further items of a model are what the "Load more" button fetches: api.leakgallery.com/profile/NAME/2, /3 ...
 * (40 items each, sent with the page's APP_TOKEN as an X-App-Token header; the token lasts about 15 minutes, so
 * it is renewed while ripping). Each such answer is one "page" for the ripper, so downloads start right away.
 */
public class LeakGalleryRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(LeakGalleryRipper.class);

    private static final Pattern LINK = Pattern.compile(
            "^https?://(?:www\\.)?leakgallery\\.com/([A-Za-z0-9._-]+)(?:/(\\d+))?/?(?:[?#].*)?$");
    private static final Set<String> RESERVED = Set.of("2257", "about-leakgallery", "add-media", "comments", "contact",
            "creator-program", "creator-terms", "api", "assets", "icons", "search", "tags", "tag", "trending",
            "discover", "login", "signup", "terms", "privacy", "dmca", "models", "videos", "photos", "bookmarks");
    private static final String SITE = "https://leakgallery.com";
    private static final String CDN = "https://cdn.leakgallery.com/";

    public LeakGalleryRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "leakgallery";
    }

    @Override
    protected String getDomain() {
        return "leakgallery.com";
    }

    @Override
    public boolean canRip(URL url) {
        Matcher m = LINK.matcher(url.toExternalForm());
        return m.matches() && !RESERVED.contains(m.group(1).toLowerCase());
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches() || RESERVED.contains(m.group(1).toLowerCase())) {
            throw new MalformedURLException("Expected leakgallery.com/NAME or leakgallery.com/NAME/12345 - got " + url);
        }
        return m.group(2) == null ? m.group(1) : m.group(1) + "_" + m.group(2);
    }

    private String model() {
        Matcher m = LINK.matcher(url.toExternalForm());
        return m.matches() ? m.group(1) : "";
    }

    private boolean isPost() {
        Matcher m = LINK.matcher(url.toExternalForm());
        return m.matches() && m.group(2) != null;
    }

    @Override
    public Document getFirstPage() throws IOException {
        if (!isPost()) {
            return Http.url(url).referrer(SITE + "/").get();
        }
        try {
            return Http.url(url).referrer(SITE + "/" + model()).get();
        } catch (IOException e) {
            // Post pages often answer 403 to RipMe, so ask the site's API for the item instead
            logger.info("Post page refused (" + e.getMessage() + "); asking the API instead");
        }
        Matcher m = LINK.matcher(url.toExternalForm());
        m.matches();
        String id = m.group(2);
        // the model page opens fine and carries the token the API wants
        Document modelPage = Http.url(SITE + "/" + model()).referrer(SITE + "/").get();
        JSONObject state = pageState(modelPage);
        if (state != null) {
            token = state.optString("APP_TOKEN", "");
        }
        Http h = Http.url(API + "/media/" + id).referrer(url.toExternalForm()).ignoreContentType();
        h.header("Accept", "application/json, text/plain, */*");
        h.header("Origin", SITE);
        h.header("X-App-Token", token);
        JSONObject media = h.getJSON();
        logger.info("Got post " + id + " from the API");
        Document d = Jsoup.parse("<html><body></body></html>", url.toExternalForm());
        d.body().attr("data-media", media.toString());
        return d;
    }

    private String token = "";
    private long tokenTime = 0;
    private int pageNo = 1;

    /** The site's token lasts about 15 minutes; this asks the model page for a fresh one. */
    private void refreshToken() {
        try {
            JSONObject state = pageState(Http.url(url).referrer(SITE + "/").get());
            if (state != null && !state.optString("APP_TOKEN", "").isEmpty()) {
                token = state.optString("APP_TOKEN");
                tokenTime = System.currentTimeMillis();
                logger.debug("Got a fresh site token");
            }
        } catch (IOException e) {
            logger.warn("Could not refresh the site token: " + e.getMessage());
        }
    }

    private JSONObject fetchApiPage(int n) throws IOException {
        String api = API + "/profile/" + model() + "/" + n + "?type=All&sort=MostRecent&tag=0";
        Http h = Http.url(api).referrer(SITE + "/" + model()).ignoreContentType();
        h.header("Accept", "application/json, text/plain, */*");
        h.header("Origin", SITE);
        h.header("X-App-Token", token);
        return h.getJSON();
    }

    /** Model pages: each further 40 items come from the API, one "page" at a time (the site's Load more). */
    @Override
    public Document getNextPage(Document doc) throws IOException {
        if (isPost()) {
            throw new IOException("No more pages");
        }
        if (System.currentTimeMillis() - tokenTime > 8 * 60 * 1000L) {
            refreshToken();
        }
        sleep(600);
        int n = pageNo + 1;
        JSONObject answer;
        try {
            answer = fetchApiPage(n);
        } catch (IOException e) {
            // most likely the token ran out: get a new one and try once more
            refreshToken();
            answer = fetchApiPage(n);
        }
        JSONArray medias = answer.optJSONArray("medias");
        if (medias == null || medias.length() == 0) {
            throw new IOException("No more pages");
        }
        pageNo = n;
        logger.info("Loaded page " + n + " (" + medias.length() + " items)");
        Document next = Jsoup.parse("<html><body></body></html>", API + "/profile/" + model() + "/" + n);
        next.body().attr("data-medias", medias.toString());
        return next;
    }

    /** The JSON the page carries along (the ng-state script), or null. */
    static JSONObject pageState(Document page) {
        Element s = page.selectFirst("script#ng-state");
        if (s == null) {
            return null;
        }
        try {
            return new JSONObject(s.data());
        } catch (JSONException e) {
            return null;
        }
    }

    /** File addresses of every media entry in a list like the profile's "medias". */
    static void addMedias(JSONArray medias, Set<String> out) {
        for (int i = 0; medias != null && i < medias.length(); i++) {
            JSONObject m = medias.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String path = m.optString("file_path", "");
            if (!path.isEmpty()) {
                out.add(path.startsWith("http") ? path : CDN + path.replaceAll("^/+", ""));
            }
        }
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        Set<String> urls = new LinkedHashSet<>();
        if (page.body() != null && page.body().hasAttr("data-media")) {
            try {
                JSONArray one = new JSONArray();
                one.put(new JSONObject(page.body().attr("data-media")));
                addMedias(one, urls);
            } catch (JSONException e) {
                logger.warn("Unreadable post data: " + e.getMessage());
            }
            return new ArrayList<>(urls);
        }
        if (page.body() != null && page.body().hasAttr("data-medias")) {
            try {
                addMedias(new JSONArray(page.body().attr("data-medias")), urls);
            } catch (JSONException e) {
                logger.warn("Unreadable page of items: " + e.getMessage());
            }
            return new ArrayList<>(urls);
        }
        JSONObject state = pageState(page);
        if (state != null) {
            if (!state.optString("APP_TOKEN", "").isEmpty()) {
                token = state.optString("APP_TOKEN");
                tokenTime = System.currentTimeMillis();
            }
            for (String key : state.keySet()) {
                if (key.startsWith("httpGet:profile/")) {
                    JSONObject profile = state.optJSONObject(key);
                    if (profile != null) {
                        addMedias(profile.optJSONArray("medias"), urls);
                        logger.info("Model has " + profile.opt("mediaCount") + " items; ripping them 40 at a time");
                    }
                } else if (key.startsWith("httpGet:media/")) {
                    JSONObject media = state.optJSONObject(key);
                    if (media != null) {
                        String path = media.optString("file_path", "");
                        if (!path.isEmpty()) {
                            urls.add(path.startsWith("http") ? path : CDN + path.replaceAll("^/+", ""));
                        }
                    }
                }
            }
        }
        if (urls.isEmpty()) {
            // Fallback: the page's JSON-LD (search-engine data) names the files too
            for (Element ld : page.select("script[type=application/ld+json]")) {
                try {
                    JSONObject o = new JSONObject(ld.data());
                    String c = o.optString("contentUrl", "");
                    if (!c.isEmpty()) {
                        urls.add(c);
                    }
                    JSONArray parts = o.optJSONArray("hasPart");
                    for (int i = 0; parts != null && i < parts.length(); i++) {
                        String pc = parts.getJSONObject(i).optString("contentUrl", "");
                        if (!pc.isEmpty()) {
                            urls.add(pc);
                        }
                    }
                } catch (JSONException e) {
                    // not the data we are after
                }
            }
        }
        if (urls.isEmpty()) {
            logger.warn("No media found on " + url);
        }
        return new ArrayList<>(urls);
    }

    private static final String API = "https://api.leakgallery.com";

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("prefix", getPrefix(index));
        options.put("referrer", SITE + "/");
        addURLToDownload(url, options);
    }
}
