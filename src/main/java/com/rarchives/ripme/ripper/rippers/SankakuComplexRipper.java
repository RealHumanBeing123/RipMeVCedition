package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

public class SankakuComplexRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(SankakuComplexRipper.class);

    public SankakuComplexRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "sankakucomplex";
    }

    @Override
    public String getDomain() {
        return "sankakucomplex.com";
    }

    // A single post: sankaku.app/posts/<code>, www.sankakucomplex.com/posts/<code>, .../en/posts/<code>, .../post/show/<code>
    private static final Pattern SINGLE_POST = Pattern.compile(
            "^https?://[^/]+/(?:[a-z]{2}(?:-[A-Za-z]+)?/)?(?:posts?(?:/show)?)/([A-Za-z0-9]+)/?(?:[?#].*)?$");

    /** The post code of a single-post link, or null for tag searches and other pages. */
    static String singlePostId(URL url) {
        Matcher m = SINGLE_POST.matcher(url.toExternalForm());
        return m.matches() ? m.group(1) : null;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String post = singlePostId(url);
        if (post != null) {
            return "post_" + post;
        }
        Pattern p = Pattern.compile("^https?://([a-zA-Z0-9]+\\.)?(?:sankakucomplex\\.com|sankaku\\.app)/.*tags=([^&]+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            try {
                return URLDecoder.decode((m.group(1) == null ? "sankaku." : m.group(1)) + "_" + m.group(2), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new MalformedURLException("Cannot decode tag name '" + m.group(1) + "'");
            }
        }
        throw new MalformedURLException("Expected a Sankaku tag-search link like https://chan.sankakucomplex.com/?tags=something or https://sankaku.app/?tags=something - got " +
                        url + "instead");
    }

    public String getSubDomain(URL url){
        Pattern p = Pattern.compile("^https?://([a-zA-Z0-9]+\\.)?(?:sankakucomplex\\.com|sankaku\\.app)/.*tags=([^&]+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            try {
                return m.group(1) == null ? null : URLDecoder.decode(m.group(1), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return null;
            }
        }
        return null;

    }

    // Sankaku's pages are rendered by JavaScript and need a login; its public JSON API does not.
    private static final String API = "https://sankakuapi.com/v2/posts/keyset";
    private static final String JSON_ATTR = "data-json";

    private String siteOrigin() {
        return url.getHost() != null && url.getHost().toLowerCase().endsWith("sankaku.app")
                ? "https://sankaku.app" : "https://chan.sankakucomplex.com";
    }

    private String rawTags() throws IOException {
        String query = url.getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("tags=")) {
                    return part.substring("tags=".length());
                }
            }
        }
        throw new IOException("No tags= found in " + url);
    }

    private Document apiPage(String next) throws IOException {
        String api = API + "?tags=" + rawTags() + "&lang=en&limit=100"
                + (next != null ? "&next=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8) : "");
        JSONObject json = Http.url(api)
                .header("Accept", "application/vnd.sankaku.api+json;v=2")
                .header("Origin", siteOrigin())
                .referrer(siteOrigin() + "/")
                .getJSON();
        Document doc = new Document(api);
        doc.body().attr(JSON_ATTR, json.toString());
        return doc;
    }

    @Override
    public Document getFirstPage() throws IOException {
        String sub = getSubDomain(url);
        if (sub != null && sub.startsWith("idol")) {
            throw new IOException("idol.sankakucomplex.com has moved to idolcomplex.com, which ripme cannot read. "
                    + "Only chan.sankakucomplex.com tag searches are supported.");
        }
        String post = singlePostId(url);
        if (post != null) {
            return singlePostPage(post);
        }
        return apiPage(null);
    }

    /** Asks Sankaku's data address for one post, trying the forms it is known to answer to. */
    private Document singlePostPage(String code) throws IOException {
        String[] candidates = {
                "https://sankakuapi.com/v2/posts/keyset?lang=en&limit=1&tags=id_range:" + code,
                "https://sankakuapi.com/v2/posts?lang=en&page=1&limit=1&tags=id_range:" + code,
                "https://sankakuapi.com/posts/" + code + "?lang=en",
                "https://sankakuapi.com/v2/posts/" + code + "?lang=en"
        };
        StringBuilder tried = new StringBuilder();
        for (String api : candidates) {
            try {
                Http http = Http.url(api).ignoreContentType().retries(1)
                        .header("Accept", "application/vnd.sankaku.api+json;v=2")
                        .header("Origin", siteOrigin()).referrer(siteOrigin() + "/");
                http.connection().ignoreHttpErrors(true);
                org.jsoup.Connection.Response r = http.response();
                String body = r.body();
                JSONObject post = firstPost(body);
                if (post != null) {
                    Document doc = new Document(api);
                    doc.body().attr(JSON_ATTR, new JSONObject().put("data", new JSONArray().put(post)).toString());
                    return doc;
                }
                tried.append("\n  ").append(api).append(" -> HTTP ").append(r.statusCode()).append(' ')
                        .append(body.length() > 150 ? body.substring(0, 150) : body);
            } catch (IOException | org.json.JSONException e) {
                tried.append("\n  ").append(api).append(" -> ").append(e.getMessage());
            }
        }
        throw new IOException("Sankaku did not give post " + code + ". Tried:" + tried);
    }

    /** A post object from any of the reply shapes (a post, {"data":[post]} or [post]); null if there is none with a file. */
    static JSONObject firstPost(String body) {
        Object parsed;
        try {
            parsed = body.trim().startsWith("[") ? new JSONArray(body) : new JSONObject(body);
        } catch (org.json.JSONException e) {
            return null;
        }
        JSONObject post = null;
        if (parsed instanceof JSONArray) {
            post = ((JSONArray) parsed).optJSONObject(0);
        } else {
            JSONObject o = (JSONObject) parsed;
            if (o.has("file_url") || o.has("id") && o.has("md5")) {
                post = o;
            } else if (o.optJSONArray("data") != null) {
                post = o.getJSONArray("data").optJSONObject(0);
            }
        }
        return post != null && post.has("file_url") ? post : null;
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        List<String> imageURLs = new ArrayList<>();
        JSONArray data = new JSONObject(doc.body().attr(JSON_ATTR)).optJSONArray("data");
        if (data == null) {
            return imageURLs;
        }
        int hidden = 0;
        for (int i = 0; i < data.length(); i++) {
            String file = data.getJSONObject(i).optString("file_url", "");
            if (file.isEmpty() || file.equals("null")) {
                hidden++; // posts that need a Sankaku login give no file address
                continue;
            }
            imageURLs.add(file.startsWith("//") ? "https:" + file : file);
        }
        if (hidden > 0) {
            logger.info(hidden + " posts on this page need a Sankaku login and were skipped");
        }
        return imageURLs;
    }

    @Override
    public void downloadURL(URL url, int index) {
        sleep(1000);
        java.util.Map<String, String> options = new HashMap<>();
        options.put("prefix", getPrefix(index));
        options.put("referrer", siteOrigin() + "/");
        addURLToDownload(url, options);
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        JSONObject json = new JSONObject(doc.body().attr(JSON_ATTR));
        JSONObject meta = json.optJSONObject("meta");
        String next = meta == null ? "" : meta.optString("next", "");
        if (next.isEmpty() || next.equals("null")) {
            throw new IOException("No more pages");
        }
        sleep(1000);
        Document nextDoc = apiPage(next);
        JSONArray data = new JSONObject(nextDoc.body().attr(JSON_ATTR)).optJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new IOException("No more pages");
        }
        return nextDoc;
    }
}
