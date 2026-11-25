package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Connection.Method;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Rips a single 500px photo page, e.g. https://500px.com/photo/azrKSPoE4vH
 *
 * 500px's pages are built by JavaScript, so the photo is asked for from 500px's GraphQL data
 * address (the same one the website itself uses), using the id from the link.
 */
public class FivehundredpxPhotoRipper extends AbstractJSONRipper {

    private static final String API = "https://api.500px.com/graphql";

    private static final Pattern PHOTO = Pattern.compile(
            "^https?://(?:www\\.)?500px\\.com/photo/([A-Za-z0-9]+)(?:/[^?#]*)?(?:[?#].*)?$");

    public FivehundredpxPhotoRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "500px";
    }

    @Override
    protected String getDomain() {
        return "500px.com";
    }

    @Override
    public boolean canRip(URL url) {
        // Claims every 500px address so that galleries and favorites get the clear explanation below
        // instead of a confusing error from the old, broken ripper.
        return url.getHost() != null && url.getHost().toLowerCase().endsWith("500px.com");
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = PHOTO.matcher(url.toExternalForm());
        if (m.matches()) {
            return "photo_" + m.group(1);
        }
        String path = url.getPath() == null ? "" : url.getPath().replaceAll("[^A-Za-z0-9]+", "_");
        if (path.isEmpty() || path.equals("_")) {
            throw new MalformedURLException("Expected a 500px.com address - got " + url);
        }
        return "page" + path;
    }

    private JSONObject ask(String idType, String photoId) throws IOException {
        String query = "query PhotoQuery($id: " + idType + "!) { getPhotoById(id: $id) { id downloadable "
                + "urls { size_600 size_1024 size_2048 size_4k } } }";
        JSONObject body = new JSONObject()
                .put("operationName", "PhotoQuery")
                .put("query", query)
                .put("variables", new JSONObject().put("id", photoId));
        Http http = Http.url(API).method(Method.POST).ignoreContentType()
                .referrer("https://500px.com/")
                .header("Origin", "https://500px.com")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        http.connection().requestBody(body.toString());
        String text = http.response().body();
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            String start = text.length() > 120 ? text.substring(0, 120) : text;
            throw new IOException("500px did not answer with data. Reply started with: " + start.replaceAll("\\s+", " "), e);
        }
    }

    @Override
    protected JSONObject getFirstPage() throws IOException, URISyntaxException {
        Matcher m = PHOTO.matcher(url.toExternalForm());
        if (!m.matches()) {
            throw new IOException("500px galleries, favorites and profiles are not supported yet - only single photo links "
                    + "like https://500px.com/photo/<id>.");
        }
        String photoId = m.group(1);
        JSONObject reply = ask("String", photoId);
        JSONObject firstData = reply.optJSONObject("data");
        if (reply.has("errors") && (firstData == null || firstData.optJSONObject("getPhotoById") == null)) {
            // 500px may name this id type differently; try once more.
            reply = ask("ID", photoId);
        }
        JSONObject data = reply.optJSONObject("data");
        JSONObject photo = data == null ? null : data.optJSONObject("getPhotoById");
        if (photo == null) {
            throw new IOException("500px did not return this photo: " + reply.toString().substring(0, Math.min(300, reply.toString().length())));
        }
        return photo;
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject photo) {
        List<String> result = new ArrayList<>();
        JSONObject urls = photo.optJSONObject("urls");
        if (urls != null) {
            for (String key : new String[] { "size_4k", "size_2048", "size_1024", "size_600" }) {
                String u = urls.optString(key, "");
                if (u.startsWith("http")) {
                    result.add(u);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }
}
