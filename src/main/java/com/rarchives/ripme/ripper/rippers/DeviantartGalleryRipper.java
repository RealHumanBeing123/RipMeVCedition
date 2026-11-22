package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Rips a DeviantArt gallery: deviantart.com/&lt;artist&gt;/gallery  or
 * deviantart.com/&lt;artist&gt;/gallery/&lt;folder number&gt;/&lt;folder name&gt;.
 *
 * The gallery page already contains its first artworks as JSON; the rest are asked for from
 * DeviantArt's own gallery data address, 24 at a time.
 */
public class DeviantartGalleryRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(DeviantartGalleryRipper.class);

    // static: the constructor of AbstractRipper calls getGID before instance fields exist
    private static final Pattern GALLERY = Pattern.compile(
            "^https?://(?:www\\.)?deviantart\\.com/([A-Za-z0-9_-]+)/gallery(?:/([0-9]+|all)(?:/[^/?#]*)?)?/?(?:[?#].*)?$");

    private final Set<String> seen = new HashSet<>();
    private boolean firstDone = false;
    private boolean hasMore = true;
    private int nextOffset = 0;

    public DeviantartGalleryRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "deviantart";
    }

    @Override
    protected String getDomain() {
        return "deviantart.com";
    }

    @Override
    public boolean canRip(URL url) {
        // The ripper factory picks the first ripper whose canRip says yes, so match the address shape, not just the site.
        return GALLERY.matcher(url.toExternalForm()).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = GALLERY.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1) + "_gallery_" + (m.group(2) == null ? "all" : m.group(2));
        }
        throw new MalformedURLException("Expected deviantart.com/<artist>/gallery[/<folder number>/<name>] - got " + url);
    }

    private String artist() {
        Matcher m = GALLERY.matcher(url.toExternalForm());
        return m.matches() ? m.group(1) : "";
    }

    private String folder() {
        Matcher m = GALLERY.matcher(url.toExternalForm());
        return m.matches() && m.group(2) != null ? m.group(2) : "all";
    }

    private JSONObject wrap(List<String> urls) {
        JSONObject json = new JSONObject();
        json.put("urls", new JSONArray(urls));
        return json;
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        Document page = Http.url(url).get();
        List<String> urls = new ArrayList<>();
        for (Element script : page.select("script")) {
            String literal = DeviantartArtRipper.stateLiteral(script.data());
            if (literal == null) {
                continue;
            }
            JSONObject state = new JSONObject(DeviantartArtRipper.unescapeJsString(literal));
            JSONObject entities = state.optJSONObject("@@entities");
            JSONObject deviations = entities == null ? null : entities.optJSONObject("deviation");
            JSONObject streams = state.optJSONObject("@@streams");
            JSONObject stream = null;
            if (streams != null) {
                for (String key : streams.keySet()) {
                    if (key.startsWith("folder-deviations-gallery-")) {
                        stream = streams.getJSONObject(key);
                        break;
                    }
                }
            }
            if (deviations != null) {
                List<String> ids = new ArrayList<>();
                if (stream != null && stream.optJSONArray("items") != null) {
                    JSONArray items = stream.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        ids.add(String.valueOf(items.get(i)));
                    }
                } else {
                    ids.addAll(deviations.keySet());
                }
                for (String id : ids) {
                    addDeviation(deviations.optJSONObject(id), id, urls);
                }
            }
            if (stream != null) {
                hasMore = stream.optBoolean("hasMore", false);
                nextOffset = stream.optInt("nextOffset", urls.size());
            } else {
                hasMore = false;
            }
            break;
        }
        firstDone = true;
        if (urls.isEmpty()) {
            throw new IOException("No artworks found on " + url + " (the gallery may be empty or need a login).");
        }
        return wrap(urls);
    }

    private void addDeviation(JSONObject dev, String id, List<String> out) {
        if (dev == null || !seen.add(id)) {
            return;
        }
        String type = dev.optString("type", "image");
        if (!"image".equals(type)) {
            logger.info("Skipping {} (type {}) - only pictures are downloaded", id, type);
            return;
        }
        String pic = DeviantartArtRipper.pictureFromDeviation(dev);
        if (pic != null) {
            out.add(pic);
        }
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException {
        if (!firstDone || !hasMore) {
            throw new IOException("No more pages");
        }
        String address = "https://www.deviantart.com/_napi/da-user-profile/api/gallery/contents?username=" + artist()
                + "&offset=" + nextOffset + "&limit=24"
                + ("all".equals(folder()) ? "&all_folder=true" : "&folderid=" + folder());
        JSONObject reply;
        try {
            reply = Http.url(address).referrer(url).header("Accept", "application/json").getJSON();
        } catch (org.json.JSONException e) {
            throw new IOException("DeviantArt did not give the next part of the gallery", e);
        }
        JSONArray results = reply.optJSONArray("results");
        List<String> urls = new ArrayList<>();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject dev = results.getJSONObject(i).optJSONObject("deviation");
                if (dev != null) {
                    addDeviation(dev, String.valueOf(dev.opt("deviationId")), urls);
                }
            }
        }
        hasMore = reply.optBoolean("hasMore", false);
        nextOffset = reply.optInt("nextOffset", nextOffset + 24);
        if (results == null || results.isEmpty()) {
            throw new IOException("No more pages");
        }
        return wrap(urls);
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> res = new ArrayList<>();
        JSONArray arr = json.getJSONArray("urls");
        for (int i = 0; i < arr.length(); i++) {
            res.add(arr.getString(i));
        }
        return res;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }
}
