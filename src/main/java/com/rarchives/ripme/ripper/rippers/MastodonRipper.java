package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.json.JSONArray;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class MastodonRipper extends AbstractHTMLRipper {
    private static final Logger logger = LogManager.getLogger(MastodonRipper.class);
    private Map<String, String> itemIDs = Collections.synchronizedMap(new HashMap<String, String>());

    public MastodonRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "mastodon";
    }

    @Override
    public String getDomain() {
        return "mastodon.social";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("^https?://(" + getDomain() + ")/@([a-zA-Z0-9_-]+)(/media/?)?$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Return the text contained between () in the regex
            return m.group(1) + "@" + m.group(2);
        }
        throw new MalformedURLException(
                "Expected " + getDomain() + " URL format: " +
                getDomain() + "/@username - got " + url + " instead");
    }

    /** Mastodon's own web page is now rendered by JavaScript, so the media are read from its public API. */
    private boolean useApi = true;
    private String accountId;

    private static final String JSON_ATTR = "data-statuses";

    private String username() {
        Matcher m = Pattern.compile("^/@([a-zA-Z0-9_-]+)(/media/?)?/?$").matcher(url.getPath());
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    private Document apiPage(String maxId) throws IOException {
        String api = "https://" + url.getHost() + "/api/v1/accounts/" + accountId
                + "/statuses?only_media=true&exclude_reblogs=true&limit=40"
                + (maxId != null ? "&max_id=" + maxId : "");
        JSONArray statuses = Http.url(api).getJSONArray();
        Document doc = new Document(api);
        doc.body().attr(JSON_ATTR, statuses.toString());
        return doc;
    }

    @Override
    public Document getFirstPage() throws IOException {
        String user = username();
        if (user != null) {
            try {
                JSONObject account = Http.url("https://" + url.getHost() + "/api/v1/accounts/lookup?acct=" + user).getJSON();
                accountId = account.getString("id");
                return apiPage(null);
            } catch (IOException | org.json.JSONException e) {
                logger.warn("Could not read " + url.getHost() + " through its API, falling back to the web page", e);
                useApi = false;
            }
        } else {
            useApi = false;
        }
        Pattern p = Pattern.compile("^/@[a-zA-Z0-9_-]+/media/?$");
        Matcher m = p.matcher(url.getPath());
        if (m.matches()) {
            return Http.url(url).get();
        }
        return Http.url(url.toExternalForm().replaceAll("/$", "") + "/media").get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        if (useApi) {
            JSONArray statuses = new JSONArray(doc.body().attr(JSON_ATTR));
            if (statuses.isEmpty()) {
                throw new IOException("No more pages");
            }
            String lastId = statuses.getJSONObject(statuses.length() - 1).getString("id");
            sleep(500);
            Document next = apiPage(lastId);
            if (new JSONArray(next.body().attr(JSON_ATTR)).isEmpty()) {
                throw new IOException("No more pages");
            }
            return next;
        }
        Elements hrefs = doc.select(".h-entry + .entry > a.load-more.load-gap");
        if (hrefs.isEmpty()) {
            throw new IOException("No more pages");
        }
        String nextUrl = hrefs.last().attr("href");
        sleep(500);
        return Http.url(nextUrl).get();
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        List<String> result = new ArrayList<String>();
        if (useApi) {
            JSONArray statuses = new JSONArray(doc.body().attr(JSON_ATTR));
            for (int i = 0; i < statuses.length(); i++) {
                JSONArray media = statuses.getJSONObject(i).optJSONArray("media_attachments");
                if (media == null) {
                    continue;
                }
                for (int j = 0; j < media.length(); j++) {
                    JSONObject item = media.getJSONObject(j);
                    String mediaUrl = item.optString("url", "");
                    if (mediaUrl.isEmpty() || "null".equals(mediaUrl)) {
                        continue;
                    }
                    result.add(mediaUrl);
                    itemIDs.put(mediaUrl, item.optString("id", String.valueOf(result.size())));
                }
            }
            return result;
        }
        for (Element el : doc.select("[data-component=\"MediaGallery\"]")) {
            String props = el.attr("data-props");
            JSONObject obj = new JSONObject(props);
            JSONArray arr = obj.getJSONArray("media");
            for (int i = 0; i < arr.length(); i++) {
                String url = arr.getJSONObject(i).getString("url");
                result.add(url);
                String id = arr.getJSONObject(i).getString("id");
                itemIDs.put(url, id);
            }
        }
        return result;
    }

    @Override
    public void downloadURL(URL url, int index) {
        addURLToDownload(url, itemIDs.get(url.toString()) + "_");
    }
}
