package com.rarchives.ripme.ripper.rippers;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <a href="https://kemono.cr/api/schema">See this link for the API schema</a>.
 *
 * kemono.party and kemono.su are dead (DNS no longer resolves as of 2026-08); kemono.cr is the
 * current domain per the project's own status updates. {@link #canRip(URL)} still accepts the
 * old hostnames so existing bookmarks/history entries keep working, but every outgoing request
 * (API + CDN) targets kemono.cr.
 */
public class KemonoPartyRipper extends AbstractJSONRipper {
    private static final Logger LOGGER = LogManager.getLogger(KemonoPartyRipper.class);
    private static final String API_HOST = "kemono.cr";
    // the picture servers are n1..n4 (c1/c3 no longer exist); they all hold everything, so take turns
    private int serverTurn = 0;

    private String dataBase() {
        serverTurn = serverTurn % 4 + 1;
        return "https://n" + serverTurn + "." + API_HOST + "/data";
    }
    private static final Pattern IMG_PATTERN = Pattern.compile("^.*\\.(jpg|jpeg|png|gif|apng|webp|tif|tiff)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VID_PATTERN = Pattern.compile("^.*\\.(webm|mp4|m4v)$", Pattern.CASE_INSENSITIVE);

    // just so we can return a JSONObject from getFirstPage
    private static final String KEY_WRAPPER_JSON_ARRAY = "array";

    private static final String KEY_FILE = "file";
    private static final String KEY_PATH = "path";
    private static final String KEY_ATTACHMENTS = "attachments";

    // One of "onlyfans" or "fansly", but might have others in future?
    private final String service;

    // Username of the page to be ripped
    private final String user;
    private int internalFileLimit;
    // set when the link is one post (.../user/<id>/post/<postId>): only that post is ripped
    private String postId = null;

    public KemonoPartyRipper(URL url) throws IOException {
        super(url);
        List<String> pathElements = Arrays.stream(url.getPath().split("/"))
                .filter(element -> !element.isBlank())
                .collect(Collectors.toList());

        service = pathElements.get(0);
        user = pathElements.get(2);

        if (service == null || user == null || service.isBlank() || user.isBlank()) {
            LOGGER.warn("service=" + service + ", user=" + user);
            throw new MalformedURLException("Invalid kemono URL: " + url);
        }
        if (pathElements.size() >= 5 && pathElements.get(3).equals("post")) {
            postId = pathElements.get(4);
        }
        LOGGER.debug("Parsed service=" + service + " and user=" + user + " post=" + postId + " from " + url);
    }

    @Override
    protected String getDomain() {
        return API_HOST;
    }

    @Override
    public String getHost() {
        return API_HOST;
    }

    @Override
    public boolean canRip(URL url) {
        String host = url.getHost();
        return host.endsWith("kemono.party") || host.endsWith("kemono.su") || host.endsWith("kemono.cr");
    }

    @Override
    public String getGID(URL url) {
        return Utils.filesystemSafe(String.format("%s_%s", service, user) + (postId == null ? "" : "_post" + postId));
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        if (postId != null) {
            return onePost();
        }
        String apiUrl = String.format("https://%s/api/v1/%s/user/%s/posts", API_HOST, service, user);
        String jsonArrayString = Http.url(apiUrl)
                .ignoreContentType()
                .header("Accept", "text/css")
                .response()
                .body();
        JSONArray jsonArray = new JSONArray(jsonArrayString);
        internalFileLimit += 50;
        // Ideally we'd just return the JSONArray from here, but we have to wrap it in a JSONObject
        JSONObject wrapperObject = new JSONObject();
        wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, jsonArray);
        return wrapperObject;
    }

    /** One post: the data address answers {"post": {...}} (or the post itself); wrap it like a page of posts. */
    private JSONObject onePost() throws IOException {
        String apiUrl = String.format("https://%s/api/v1/%s/user/%s/post/%s", API_HOST, service, user, postId);
        String body = Http.url(apiUrl).ignoreContentType().header("Accept", "text/css").response().body();
        JSONObject reply = new JSONObject(body);
        JSONObject post = reply.has("post") ? reply.getJSONObject("post") : reply;
        JSONObject wrapperObject = new JSONObject();
        wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, new JSONArray().put(post));
        return wrapperObject;
    }

    @Override
    public String getAlbumTitle(URL url) throws MalformedURLException {
        String title;
        try {
            //Gets artist name
            title = getHost() + "_" + getGID(url) + "_" + Http.url(url).get().select("meta[name=artist_name][content]").attr("content");
        }catch (Exception e){
            LOGGER.info("Failed to get album title, using id.");
            title = getGID(url);
        }
        return title;
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException, URISyntaxException {
        if (postId != null) {
            return null; // a single post has no further pages
        }
        String apiUrl = String.format("https://%s/api/v1/%s/user/%s/posts?o=%s", API_HOST, service, user, internalFileLimit);
        String jsonArrayString = Http.url(apiUrl)
                .ignoreContentType()
                .header("Accept", "text/css")
                .response()
                .body();
        JSONArray jsonArray = new JSONArray(jsonArrayString);

        // Ideally we'd just return the JSONArray from here, but we have to wrap it in a JSONObject
        JSONObject wrapperObject = new JSONObject();
        wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, jsonArray);
        internalFileLimit += 50;
        if(jsonArray.isEmpty()){
            return null;
        }
        return wrapperObject;
    }

    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        // extract the array from our wrapper JSONObject
        JSONArray posts = json.getJSONArray(KEY_WRAPPER_JSON_ARRAY);
        ArrayList<String> urls = new ArrayList<>();
        for (int i = 0; i < posts.length(); i++) {
            JSONObject post = posts.getJSONObject(i);
            pullFileUrl(post, urls);
            pullAttachmentUrls(post, urls);
        }
        LOGGER.debug("Pulled " + urls.size() + " URLs from " + posts.length() + " posts");
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }

    /**
     * Retrieves the URL of a file from a given JSONObject and adds it to the provided list.
     *
     * @param post     The JSONObject containing information about the file.
     * @param results  The list to which the URL of the file will be added.
     */
    private void pullFileUrl(JSONObject post, ArrayList<String> results) {
        try {
            JSONObject file = post.optJSONObject(KEY_FILE);
            if (file == null || file.optString(KEY_PATH, "").isEmpty()) {
                return; // this post has no main file (only text or attachments)
            }
            String path = file.getString(KEY_PATH);
            if (isImage(path)) {
                String url = dataBase() + path;
                results.add(url);
            } else if (isVideo(path)) {
                String url = dataBase() + path;
                results.add(url);
            } else {
                LOGGER.error("Unknown extension for " + API_HOST + " path: " + path);
            }
        } catch (JSONException e) {
            /* No-op */
        }
    }

    /**
     * Retrieves the URLs of attachments from a given JSONObject and adds them to the provided list.
     *
     * @param post     The JSONObject containing information about the post.
     * @param results  The list to which the URLs of the attachments will be added.
     */
    private void pullAttachmentUrls(JSONObject post, ArrayList<String> results) {
        try {
            JSONArray attachments = post.optJSONArray(KEY_ATTACHMENTS);
            if (attachments == null) {
                return;
            }
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject attachment = attachments.getJSONObject(i);
                pullFileUrl(attachment, results);
            }
        } catch (JSONException e) {
            /* No-op */
        }
    }

    /**
     * Checks if the given path represents an image file.
     *
     * @param path The path of the file to be checked.
     * @return True if the file is an image, false otherwise.
     */
    private boolean isImage(String path) {
        Matcher matcher = IMG_PATTERN.matcher(path);
        return matcher.matches();
    }

    private boolean isVideo(String path) {
        Matcher matcher = VID_PATTERN.matcher(path);
        return matcher.matches();
    }
}
