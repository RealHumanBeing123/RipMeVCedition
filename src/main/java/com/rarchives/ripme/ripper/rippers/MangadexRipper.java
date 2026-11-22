package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.ui.RipStatusMessage;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection.Method;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MangaDex through its public API (api.mangadex.org). No login or cookies are needed.
 * Links look like mangadex.org/title/&lt;uuid&gt;/name or mangadex.org/chapter/&lt;uuid&gt;; the old numeric
 * links (mangadex.org/title/44625/...) are translated with MangaDex's legacy mapping.
 */
public class MangadexRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(MangadexRipper.class);

    private static final String API = "https://api.mangadex.org";
    private static final Pattern LINK = Pattern.compile(
            "^https?://(?:www\\.)?mangadex\\.org/(chapter|title|manga)/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+)(?:[/?#].*)?$");

    public MangadexRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "mangadex";
    }

    @Override
    public String getDomain() {
        return "mangadex.org";
    }

    @Override
    public boolean canRip(URL url) {
        return url.getHost().endsWith("mangadex.org");
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(2);
        }
        throw new MalformedURLException("Expected mangadex.org/title/ID/name or mangadex.org/chapter/ID, got " + url);
    }

    private JSONObject api(String path) throws IOException {
        return Http.url(API + path).header("Accept", "application/json").getJSON();
    }

    /** Old numeric MangaDex numbers are translated to the new UUIDs. */
    private String resolveId(String type, String id) throws IOException {
        if (id.contains("-")) {
            return id;
        }
        Http http = Http.url(API + "/legacy/mapping").method(Method.POST)
                .header("Content-Type", "application/json").ignoreContentType();
        http.connection().requestBody(new JSONObject().put("type", type).put("ids", new JSONArray().put(Long.parseLong(id))).toString());
        JSONObject json = new JSONObject(http.response().body());
        JSONArray data = json.optJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new IOException("MangaDex does not know the old " + type + " number " + id
                    + ". Open it on mangadex.org and use the new link (it contains a long ID with dashes).");
        }
        return data.getJSONObject(0).getJSONObject("attributes").getString("newId");
    }

    /** First "page": the list of chapter IDs to rip, in reading order. */
    @Override
    protected JSONObject getFirstPage() throws IOException {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            throw new IOException("Unsupported MangaDex link " + url);
        }
        boolean chapter = m.group(1).equals("chapter");
        String id = resolveId(chapter ? "chapter" : "manga", m.group(2));
        JSONArray chapters = new JSONArray();
        if (chapter) {
            chapters.put(id);
            return new JSONObject().put("chapters", chapters);
        }

        String language = Utils.getConfigString("mangadex.language", "en");
        // one entry per chapter number: several scanlation groups often upload the same chapter
        Map<String, String> byNumber = new LinkedHashMap<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
            JSONObject feed = api("/manga/" + id + "/feed?limit=500&offset=" + offset
                    + "&translatedLanguage%5B%5D=" + language
                    + "&order%5Bvolume%5D=asc&order%5Bchapter%5D=asc"
                    + "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive"
                    + "&contentRating%5B%5D=erotica&contentRating%5B%5D=pornographic");
            JSONArray data = feed.optJSONArray("data");
            total = feed.optInt("total", 0);
            if (data == null || data.isEmpty()) {
                break;
            }
            for (int i = 0; i < data.length(); i++) {
                JSONObject chapterJson = data.getJSONObject(i);
                JSONObject attributes = chapterJson.optJSONObject("attributes");
                if (attributes == null) {
                    continue;
                }
                // chapters hosted on another site have no pages here
                if (attributes.optInt("pages", 1) == 0 || !attributes.isNull("externalUrl")) {
                    continue;
                }
                String chapterId = chapterJson.getString("id");
                String number = attributes.isNull("chapter") ? chapterId : attributes.optString("volume", "") + "/" + attributes.optString("chapter");
                byNumber.putIfAbsent(number, chapterId);
            }
            offset += data.length();
        }
        for (String chapterId : byNumber.values()) {
            chapters.put(chapterId);
        }
        if (chapters.isEmpty()) {
            throw new IOException("No '" + language + "' chapters found for this MangaDex title "
                    + "(set mangadex.language in the configuration file to read another language)");
        }
        return new JSONObject().put("chapters", chapters);
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();
        JSONArray chapters = json.getJSONArray("chapters");
        for (int c = 0; c < chapters.length(); c++) {
            if (isStopped() || (isThisATest() && !urls.isEmpty())) {
                break;
            }
            String chapterId = chapters.getString(c);
            try {
                if (c > 0) {
                    sleep(1500); // the image-server lookup is limited to about 40 requests a minute
                }
                sendUpdate(RipStatusMessage.STATUS.LOADING_RESOURCE, "chapter " + (c + 1) + " of " + chapters.length());
                JSONObject server = api("/at-home/server/" + chapterId);
                String base = server.getString("baseUrl");
                JSONObject chapter = server.getJSONObject("chapter");
                String hash = chapter.getString("hash");
                JSONArray files = chapter.getJSONArray("data");
                for (int i = 0; i < files.length(); i++) {
                    urls.add(base + "/data/" + hash + "/" + files.getString(i));
                }
            } catch (IOException | org.json.JSONException e) {
                logger.warn("Could not load chapter " + chapterId, e);
            }
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        // mangadex does not like rippers one bit, so we wait a good long while between requests
        sleep(1000);
        addURLToDownload(url, getPrefix(index));
    }
}
