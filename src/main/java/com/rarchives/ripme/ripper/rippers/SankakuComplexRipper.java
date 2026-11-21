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

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("^https?://([a-zA-Z0-9]+\\.)?sankakucomplex\\.com/.*tags=([^&]+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            try {
                return URLDecoder.decode(m.group(1) + "_" + m.group(2), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new MalformedURLException("Cannot decode tag name '" + m.group(1) + "'");
            }
        }
        throw new MalformedURLException("Expected sankakucomplex.com URL format: " +
                        "idol.sankakucomplex.com?...&tags=something... - got " +
                        url + "instead");
    }

    public String getSubDomain(URL url){
        Pattern p = Pattern.compile("^https?://([a-zA-Z0-9]+\\.)?sankakucomplex\\.com/.*tags=([^&]+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            try {
                return URLDecoder.decode(m.group(1), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return null;
            }
        }
        return null;

    }

    // Sankaku's pages are rendered by JavaScript and need a login; its public JSON API does not.
    private static final String API = "https://sankakuapi.com/v2/posts/keyset";
    private static final String JSON_ATTR = "data-json";

    private String rawTags() throws IOException {
        String query = url.getRawQuery();
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
                .header("Origin", "https://chan.sankakucomplex.com")
                .referrer("https://chan.sankakucomplex.com/")
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
        return apiPage(null);
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
        options.put("referrer", "https://chan.sankakucomplex.com/");
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
