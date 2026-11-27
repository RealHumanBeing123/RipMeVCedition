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
    // The picture servers are n1..n4 of the site (c1/c3 no longer exist). Some of them may not answer from your
    // connection, so the first one that accepts a connection is used for everything (found once, on first use).
    private String dataBase = null;

    private String dataBase() {
        if (dataBase == null) {
            String site = API_HOST;
            String[] candidates = { "n1." + site, "n2." + site, "n3." + site, "n4." + site, site };
            StringBuilder tried = new StringBuilder();
            for (String candidate : candidates) {
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress(candidate, 443), 5000);
                    dataBase = "https://" + candidate + "/data";
                    break;
                } catch (IOException e) {
                    tried.append(' ').append(candidate).append(" (").append(e.getClass().getSimpleName()).append(')');
                }
            }
            if (dataBase == null) {
                LOGGER.error("None of the picture servers answered from this computer:" + tried
                        + ". Check your VPN/firewall/DNS; trying the main address anyway.");
                dataBase = "https://" + site + "/data";
            } else if (tried.length() > 0) {
                LOGGER.warn("Skipped picture servers that did not answer:" + tried + "; using " + dataBase);
            }
        }
        return dataBase;
    }

    private boolean warnedThumbnails = false;

    /**
     * Address of a picture. When none of the picture servers (n1..n4) can be reached from this computer, the
     * full-size files cannot be downloaded, but the site's separate thumbnail server (img.<site>) usually can.
     * Its copies are smaller than the originals, so a warning is written to the log.
     */
    private String imageUrl(String path) {
        String base = dataBase();
        String site = API_HOST;
        if (base.equals("https://" + site + "/data")) {
            if (!warnedThumbnails) {
                warnedThumbnails = true;
                LOGGER.warn("The picture servers cannot be reached from this computer, so pictures are taken from https://img."
                        + site + " instead. These are reduced-size copies, not the originals.");
            }
            return "https://img." + site + "/thumbnail/data" + path;
        }
        return base + path;
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

    /**
     * The post's own web page lists every picture (links with class "fileThumb" and attachment links), so those
     * are added too in case the data answer left some out. Anything already known is dropped later.
     */
    private void addPageLinks(JSONObject post) {
        try {
            org.jsoup.nodes.Document page = Http.url(url).get();
            JSONArray list = post.optJSONArray("attachments");
            if (list == null) {
                list = new JSONArray();
                post.put("attachments", list);
            }
            int added = 0;
            for (org.jsoup.nodes.Element a : page.select("a.fileThumb[href], a.post__attachment-link[href]")) {
                String href = a.attr("abs:href");
                int at = href.indexOf("/data/");
                if (at < 0) {
                    continue;
                }
                String path = href.substring(at + 5).replaceAll("[?#].*$", "");
                list.put(new JSONObject().put("name", path.substring(path.lastIndexOf('/') + 1)).put("path", path));
                added++;
            }
            LOGGER.debug("Found " + added + " picture links on the post page");
        } catch (Exception e) {
            LOGGER.warn("Could not read the post page for more pictures: " + e);
        }
    }

    /** One post: the data address answers {"post": {...}} (or the post itself); wrap it like a page of posts. */
    private JSONObject onePost() throws IOException {
        String apiUrl = String.format("https://%s/api/v1/%s/user/%s/post/%s", API_HOST, service, user, postId);
        String body = Http.url(apiUrl).ignoreContentType().header("Accept", "text/css").response().body();
        JSONObject reply = new JSONObject(body);
        JSONObject post = reply.has("post") ? reply.getJSONObject("post") : reply;
        // The single-post answer keeps the pictures next to the post ("attachments", "previews"), not inside it.
        if (reply.has("post")) {
            JSONArray extra = reply.optJSONArray("attachments");
            if (extra == null || extra.isEmpty()) {
                extra = reply.optJSONArray("previews");
            }
            JSONArray own = post.optJSONArray("attachments");
            if (extra != null && !extra.isEmpty() && (own == null || own.isEmpty())) {
                post.put("attachments", extra);
            }
        }
        LOGGER.debug("Post answer has: " + reply.keySet() + "; the post has " + post.keySet() + ", "
                + (post.optJSONArray("attachments") == null ? 0 : post.optJSONArray("attachments").length()) + " attachments");
        addPageLinks(post);
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
        ArrayList<String> collected = new ArrayList<>();
        for (int i = 0; i < posts.length(); i++) {
            JSONObject post = posts.getJSONObject(i);
            pullFileUrl(post, collected);
            pullAttachmentUrls(post, collected);
        }
        ArrayList<String> urls = new ArrayList<>(new java.util.LinkedHashSet<>(collected)); // same file twice: keep one
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
                String url = imageUrl(path);
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
