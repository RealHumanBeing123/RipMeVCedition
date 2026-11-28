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
 * Further items of a model are loaded by the "Load more" button from the site's API; that request is tried
 * below and logged, so its real address can be corrected from a log if the guess is wrong.
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
        return Http.url(url).referrer(SITE + "/").get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        throw new IOException("No more pages");
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
        JSONObject state = pageState(page);
        String token = state == null ? "" : state.optString("APP_TOKEN", "");
        if (state != null) {
            for (String key : state.keySet()) {
                if (key.startsWith("httpGet:profile/")) {
                    JSONObject profile = state.optJSONObject(key);
                    if (profile != null) {
                        addMedias(profile.optJSONArray("medias"), urls);
                        logger.info("Model has " + profile.opt("mediaCount") + " items; the page carried " + urls.size());
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
        if (!isPost()) {
            loadMore(urls, token);
        }
        if (urls.isEmpty()) {
            logger.warn("No media found on " + url);
        }
        return new ArrayList<>(urls);
    }

    private static final String API = "https://api.leakgallery.com";

    /**
     * The "Load more" button: more of the model's items from the site's API (api.leakgallery.com, the same
     * "profile/NAME?type=All&sort=MostRecent&tag=0" request the page makes first). The name of the paging
     * setting is not known yet, so the likely ones are tried and the first that brings new items is kept.
     */
    private void loadMore(Set<String> urls, String token) {
        String base = API + "/profile/" + model() + "?type=All&sort=MostRecent&tag=0";
        String style = null;
        List<String> styles = new ArrayList<>(List.of("page", "p", "offset"));
        int pageSize = Math.max(urls.size(), 1);
        for (int n = 2; n < 3000 && !isStopped(); n++) {
            int before = urls.size();
            List<String> toTry = style == null ? styles : List.of(style);
            boolean gotNew = false;
            for (String st : toTry) {
                String api = base + "&" + st + "=" + (st.equals("offset") ? (n - 1) * pageSize : n);
                try {
                    sleep(600);
                    Http h = Http.url(api).referrer(SITE + "/").ignoreContentType();
                    h.header("Accept", "application/json");
                    h.header("Origin", SITE);
                    if (!token.isEmpty()) {
                        h.header("Authorization", "Bearer " + token);
                    }
                    JSONObject answer = h.getJSON();
                    addMedias(answer.optJSONArray("medias"), urls);
                    if (urls.size() > before) {
                        style = st;
                        gotNew = true;
                        logger.info("More items come from " + api);
                        break;
                    }
                } catch (IOException | JSONException e) {
                    logger.info("Could not load more items from " + api + " (" + e.getMessage() + ")");
                }
            }
            if (!gotNew) {
                if (style == null) {
                    logger.info("No way of loading more items worked; only the items on the page itself are ripped");
                }
                return;
            }
        }
    }

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("prefix", getPrefix(index));
        options.put("referrer", SITE + "/");
        addURLToDownload(url, options);
    }
}
