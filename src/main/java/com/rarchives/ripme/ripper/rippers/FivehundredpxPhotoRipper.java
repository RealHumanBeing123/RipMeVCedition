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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final Logger logger = LogManager.getLogger(FivehundredpxPhotoRipper.class);

    // 500px has used more than one address for this; the first one that answers with data is used.
    private static final String[] APIS = { "https://api.500px.com/graphql", "https://api.500px.com/graphql/" };

    // Cookies handed out by the photo page (including 500px's x-csrf-token), sent along with each data request.
    private java.util.Map<String, String> siteCookies = null;

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
        String lastStart = "";
        if (siteCookies == null) {
            // Visit the photo page first, like a browser does: 500px gives out an x-csrf-token cookie there
            // and its data address wants that token back.
            siteCookies = new java.util.HashMap<>();
            try {
                siteCookies.putAll(Http.url("https://500px.com/photo/" + photoId).ignoreContentType().response().cookies());
            } catch (IOException e) {
                // carry on without cookies; the error below will say what 500px answered
            }
        }
        // 500px's data address answers differently depending on who seems to be asking, so a few
        // different "identities" are tried, quickest first. The first reply that is data wins.
        String[] agents = { null, "curl/8.4.0", "RipMeVCedition/1.0 (+https://github.com/RealHumanBeing123/RipMeVCedition)" };
        boolean[] browserHeaders = { true, true, false };
        StringBuilder tried = new StringBuilder();
        for (String api : APIS) {
            for (int i = 0; i < agents.length; i++) {
                Http http = Http.url(api).method(Method.POST).ignoreContentType().retries(1)
                        .header("Accept", "*/*")
                        .header("Content-Type", "application/json")
                        .cookies(siteCookies);
                if (agents[i] != null) {
                    http.userAgent(agents[i]);
                }
                if (browserHeaders[i]) {
                    http.referrer("https://500px.com/").header("Origin", "https://500px.com");
                }
                String csrf = siteCookies.get("x-csrf-token");
                if (csrf != null) {
                    http.header("x-csrf-token", csrf);
                }
                http.connection().requestBody(body.toString());
                String label = api + " [" + (agents[i] == null ? "default" : agents[i].split("[ /]")[0])
                        + (browserHeaders[i] ? "" : ", no origin") + "]";
                try {
                    org.jsoup.Connection.Response r = http.response();
                    String text = r.body();
                    try {
                        return new JSONObject(text);
                    } catch (JSONException e) {
                        String start = text.length() > 80 ? text.substring(0, 80) : text;
                        lastStart = label + " -> HTTP " + r.statusCode() + " " + r.contentType() + " "
                                + start.replaceAll("\\s+", " ");
                    }
                } catch (IOException e) {
                    lastStart = label + " -> " + e.getMessage();
                }
                logger.info("500px data request failed: " + lastStart);
                tried.append("\n  ").append(lastStart);
            }
        }
        throw new IOException("500px did not answer with data. Every attempt:" + tried);
    }

    @Override
    protected JSONObject getFirstPage() throws IOException, URISyntaxException {
        Matcher m = PHOTO.matcher(url.toExternalForm());
        if (!m.matches()) {
            throw new IOException("500px galleries, favorites and profiles are not supported yet - only single photo links "
                    + "like https://500px.com/photo/<id>.");
        }
        String photoId = m.group(1);
        JSONObject reply = ask("ID", photoId);
        JSONObject firstData = reply.optJSONObject("data");
        if (reply.has("errors") && (firstData == null || firstData.optJSONObject("getPhotoById") == null)) {
            // 500px may name this id type differently; try once more.
            reply = ask("String", photoId);
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
