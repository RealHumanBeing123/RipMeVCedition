package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Connection.Method;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Rips 500px single photos (https://500px.com/photo/azrKSPoE4vH) and galleries
 * (https://500px.com/gallery/mpfwW6wpTq2).
 *
 * 500px's pages are built by JavaScript, so the pictures are asked for from 500px's GraphQL data
 * address (the same one the website itself uses), using the id from the link.
 */
public class FivehundredpxPhotoRipper extends AbstractJSONRipper {

    // 500px wants a device id on every data request; the website makes up a random one per browser.
    private static final String DEVICE_ID = java.util.UUID.randomUUID().toString();

    private static final Logger logger = LogManager.getLogger(FivehundredpxPhotoRipper.class);

    private static final String API = "https://api-neo.500px.com/graphql";

    // Cookies handed out by the page (including 500px's x-csrf-token), sent along with each data request.
    private Map<String, String> siteCookies = null;

    private static final Pattern PHOTO = Pattern.compile(
            "^https?://(?:www\\.)?500px\\.com/photo/([A-Za-z0-9]+)(?:/[^?#]*)?(?:[?#].*)?$");
    private static final Pattern GALLERY = Pattern.compile(
            "^https?://(?:www\\.)?500px\\.com/gallery/([A-Za-z0-9]+)(?:[/?#].*)?$");

    private static final int PAGE_SIZE = 24;

    // What the gallery question looks like once it has been worked out (argument name -> value), kept for the next pages.
    private Map<String, String> galleryArgs = null;
    private String galleryIdType = "ID!";
    private String galleryId = null;
    private String lastCursor = null;

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
        // Claims every 500px address so that favorites and profiles get the clear explanation below
        // instead of a confusing error from the old, broken ripper.
        return url.getHost() != null && url.getHost().toLowerCase().endsWith("500px.com");
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = PHOTO.matcher(url.toExternalForm());
        if (m.matches()) {
            return "photo_" + m.group(1);
        }
        m = GALLERY.matcher(url.toExternalForm());
        if (m.matches()) {
            return "gallery_" + m.group(1);
        }
        String path = url.getPath() == null ? "" : url.getPath().replaceAll("[^A-Za-z0-9]+", "_");
        if (path.isEmpty() || path.equals("_")) {
            throw new MalformedURLException("Expected a 500px.com address - got " + url);
        }
        return "page" + path;
    }

    /** Sends one GraphQL question to 500px and returns its reply (which may hold "errors"). */
    private JSONObject post(String operationName, String query, JSONObject variables) throws IOException {
        JSONObject body = new JSONObject()
                .put("operationName", operationName)
                .put("query", query)
                .put("variables", variables);
        if (siteCookies == null) {
            // Visit the page first, like a browser does: 500px gives out an x-csrf-token cookie there.
            siteCookies = new HashMap<>();
            try {
                siteCookies.putAll(Http.url(url).ignoreContentType().response().cookies());
            } catch (IOException e) {
                // carry on without cookies; an error below will say what 500px answered
            }
        }
        // 500px's data address answers differently depending on who seems to be asking, so a few
        // different "identities" are tried, quickest first. The first reply that is data wins.
        String[] agents = { null, "curl/8.4.0", "RipMeVCedition/1.0 (+https://github.com/RealHumanBeing123/RipMeVCedition)" };
        boolean[] browserHeaders = { true, true, false };
        StringBuilder tried = new StringBuilder();
        for (int i = 0; i < agents.length; i++) {
            Http http = Http.url(API).method(Method.POST).ignoreContentType().retries(1)
                    .header("Accept", "*/*")
                    .header("Content-Type", "application/json")
                    .header("x-500px-device-id", DEVICE_ID)
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
            http.connection().ignoreHttpErrors(true); // read the explanation 500px sends with an error
            http.connection().followRedirects(false);  // a redirect would quietly turn the POST into a page request
            String label = API + " [" + (agents[i] == null ? "default" : agents[i].split("[ /]")[0]) + "]";
            try {
                org.jsoup.Connection.Response r = http.response();
                String text = r.body();
                try {
                    return new JSONObject(text);
                } catch (JSONException e) {
                    String start = text.length() > 80 ? text.substring(0, 80) : text;
                    tried.append("\n  ").append(label).append(" -> HTTP ").append(r.statusCode()).append(' ')
                            .append(r.contentType()).append(' ').append(start.replaceAll("\\s+", " "));
                }
            } catch (IOException e) {
                tried.append("\n  ").append(label).append(" -> ").append(e.getMessage());
            }
        }
        throw new IOException("500px did not answer with data. Every attempt:" + tried);
    }

    // ---- single photo ----

    private JSONObject photoPage(String photoId) throws IOException {
        String query = "query PhotoQuery($id: ID!) { getPhotoById(id: $id) { id downloadable "
                + "urls { size_600 size_1024 size_2048 size_4k } } }";
        JSONObject reply = post("PhotoQuery", query, new JSONObject().put("id", photoId));
        JSONObject data = reply.optJSONObject("data");
        JSONObject photo = data == null ? null : data.optJSONObject("getPhotoById");
        if (photo == null) {
            String text = reply.toString();
            throw new IOException("500px did not return this photo: " + text.substring(0, Math.min(700, text.length())));
        }
        return new JSONObject().put("photos", new JSONArray().put(photo));
    }

    // ---- gallery ----

    /** The gallery question for the arguments known so far. */
    private String galleryQuery(Map<String, String> args) {
        boolean usesId = args.containsValue("$id");
        boolean usesAfter = args.containsValue("$after");
        StringBuilder decl = new StringBuilder();
        if (usesId) {
            decl.append("$id: ").append(galleryIdType);
        }
        if (usesAfter) {
            decl.append(decl.length() > 0 ? ", " : "").append("$after: String");
        }
        StringBuilder a = new StringBuilder();
        for (Map.Entry<String, String> e : args.entrySet()) {
            a.append(a.length() > 0 ? ", " : "").append(e.getKey()).append(": ").append(e.getValue());
        }
        return "query GalleryItems" + (decl.length() > 0 ? "(" + decl + ")" : "") + " { pageGalleryItems"
                + (a.length() > 0 ? "(" + a + ")" : "") + " { edges { cursor node { __typename ... on Photo { id title "
                + "urls { size_600 size_1024 size_2048 size_4k } } } } pageInfo { endCursor hasNextPage } } }";
    }

    private JSONObject galleryPage(String after) throws IOException {
        if (galleryArgs == null) {
            galleryArgs = new LinkedHashMap<>();
            galleryArgs.put("first", String.valueOf(PAGE_SIZE));
            galleryArgs.put("after", "$after");
        }
        StringBuilder history = new StringBuilder();
        for (int round = 0; round < 8; round++) {
            JSONObject variables = new JSONObject();
            if (galleryArgs.containsValue("$id")) {
                variables.put("id", galleryId);
            }
            if (galleryArgs.containsValue("$after")) {
                variables.put("after", after == null ? JSONObject.NULL : after);
            }
            String query = galleryQuery(galleryArgs);
            JSONObject reply = post("GalleryItems", query, variables);
            JSONObject data = reply.optJSONObject("data");
            JSONObject page = data == null ? null : data.optJSONObject("pageGalleryItems");
            if (page != null) {
                return page;
            }
            // 500px says what it did not like (a missing or unknown argument); fix the question and ask again.
            String errors = String.valueOf(reply.opt("errors"));
            history.append("\n  ").append(query.replace("\n", " ")).append("\n    -> ")
                    .append(errors.substring(0, Math.min(500, errors.length())));
            if (!adjustGalleryArgs(errors)) {
                break;
            }
        }
        throw new IOException("500px did not return this gallery. What was asked and what 500px answered:" + history);
    }

    /** Reads "argument X of type Y is required" / "Unknown argument X" and changes the question. False if nothing could be changed. */
    private boolean adjustGalleryArgs(String errors) {
        boolean changed = false;
        Matcher required = Pattern.compile("argument \\\\?\"(\\w+)\\\\?\" of type \\\\?\"([^\"\\\\]+)\\\\?\" is required").matcher(errors);
        while (required.find()) {
            String name = required.group(1);
            String type = required.group(2);
            if (galleryArgs.containsKey(name)) {
                continue;
            }
            if (type.startsWith("Int")) {
                galleryArgs.put(name, String.valueOf(PAGE_SIZE));
            } else if (type.startsWith("ID") || type.startsWith("String")) {
                galleryArgs.put(name, "$id");
                galleryIdType = type;
            } else {
                continue;
            }
            changed = true;
        }
        Matcher unknown = Pattern.compile("Unknown argument \\\\?\"(\\w+)\\\\?\"").matcher(errors);
        while (unknown.find()) {
            changed |= galleryArgs.remove(unknown.group(1)) != null;
        }
        Matcher mismatch = Pattern.compile("Variable \\\\?\"\\$id\\\\?\" of type \\\\?\"([^\"\\\\]+)\\\\?\" used in position expecting type \\\\?\"([^\"\\\\]+)\\\\?\"").matcher(errors);
        if (mismatch.find() && !mismatch.group(2).equals(galleryIdType)) {
            galleryIdType = mismatch.group(2);
            changed = true;
        }
        return changed;
    }

    // ---- ripper plumbing ----

    @Override
    protected JSONObject getFirstPage() throws IOException, URISyntaxException {
        Matcher m = PHOTO.matcher(url.toExternalForm());
        if (m.matches()) {
            return photoPage(m.group(1));
        }
        m = GALLERY.matcher(url.toExternalForm());
        if (m.matches()) {
            galleryId = m.group(1);
            return galleryPage(null);
        }
        throw new IOException("500px favorites and profiles are not supported yet - only single photo links "
                + "like https://500px.com/photo/<id> and gallery links like https://500px.com/gallery/<id>.");
    }

    @Override
    protected JSONObject getNextPage(JSONObject page) throws IOException, URISyntaxException {
        JSONObject info = page.optJSONObject("pageInfo");
        String cursor = info == null ? "" : info.optString("endCursor", "");
        if (galleryId == null || info == null || !info.optBoolean("hasNextPage") || cursor.isEmpty() || cursor.equals(lastCursor)) {
            throw new IOException("No more pages");
        }
        lastCursor = cursor;
        sleep(1000);
        return galleryPage(cursor);
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject page) {
        List<String> result = new ArrayList<>();
        List<JSONObject> photos = new ArrayList<>();
        JSONArray single = page.optJSONArray("photos");
        for (int i = 0; single != null && i < single.length(); i++) {
            photos.add(single.getJSONObject(i));
        }
        JSONArray edges = page.optJSONArray("edges");
        for (int i = 0; edges != null && i < edges.length(); i++) {
            JSONObject node = edges.getJSONObject(i).optJSONObject("node");
            if (node != null && node.has("urls")) { // videos and other items have no picture sizes
                photos.add(node);
            }
        }
        for (JSONObject photo : photos) {
            JSONObject urls = photo.optJSONObject("urls");
            if (urls == null) {
                continue;
            }
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
        // pictures are all called 0.jpg; the photo number in the address makes the names tell them apart
        Matcher id = Pattern.compile("/photo/(\\d+)/").matcher(url.toExternalForm());
        String prefix = getPrefix(index) + (id.find() ? id.group(1) + "_" : "");
        Map<String, String> options = new HashMap<>();
        options.put("prefix", prefix);
        addURLToDownload(url, options);
    }
}
