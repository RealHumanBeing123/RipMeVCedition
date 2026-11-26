package com.rarchives.ripme.ripper.rippers;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

/**
 * <a href="https://coomer.su/api/schema">See this link for the API schema</a>.
 */
public class CoomerPartyRipper extends AbstractJSONRipper {

    private static final Logger logger = LogManager.getLogger(CoomerPartyRipper.class);

    // the picture servers are n1..n4 (c1/c3 no longer exist); they all hold everything, so take turns
    private int serverTurn = 0;

    private String dataBase() {
        serverTurn = serverTurn % 4 + 1;
        return "https://n" + serverTurn + ".coomer.st/data";
    }
    private static final Pattern IMG_PATTERN = Pattern.compile("^.*\\.(jpg|jpeg|png|gif|apng|webp|tif|tiff)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VID_PATTERN = Pattern.compile("^.*\\.(webm|mp4|m4v)$", Pattern.CASE_INSENSITIVE);

    // just so we can return a JSONObject from getFirstPage
    private static final String KEY_WRAPPER_JSON_ARRAY = "array";

    private static final String KEY_FILE = "file";
    private static final String KEY_PATH = "path";
    private static final String KEY_ATTACHMENTS = "attachments";

    // Posts Request Endpoint
    private static final String POSTS_ENDPOINT = "https://coomer.st/api/v1/%s/user/%s/posts?o=%d";

    // Pagination is strictly 50 posts per page, per API schema.
    private Integer pageCount = 0;
    private static final Integer postCount = 50;

    // "Service" of the page to be ripped: Onlyfans, Fansly, Candfans
    private final String service;

    // Username of the page to be ripped
    private final String user;

    // set when the link is one post (.../user/<id>/post/<postId>): only that post is ripped
    private String postId = null;



    public CoomerPartyRipper(URL url) throws IOException {
        super(url);
        List<String> pathElements = Arrays.stream(url.getPath().split("/"))
                .filter(element -> !element.isBlank())
                .collect(Collectors.toList());

        service = pathElements.get(0);
        user = pathElements.get(2);

        if (service == null || user == null || service.isBlank() || user.isBlank()) {
            logger.warn("service=" + service + ", user=" + user);
            throw new MalformedURLException("Invalid coomer.party URL: " + url);
        }
        if (pathElements.size() >= 5 && pathElements.get(3).equals("post")) {
            postId = pathElements.get(4);
        }
        logger.debug("Parsed service=" + service + " and user=" + user + " post=" + postId + " from " + url);
    }

    @Override
    protected String getDomain() {
        return "coomer.st";
    }

    @Override
    public String getHost() {
        return "coomer.st";
    }

    @Override
    public boolean canRip(URL url) {
        String host = url.getHost();
        return host.endsWith("coomer.party") || host.endsWith("coomer.su") || host.endsWith("coomer.st");
    }

    @Override
    public String getGID(URL url) {
        return Utils.filesystemSafe(String.format("%s_%s", service, user) + (postId == null ? "" : "_post" + postId));
    }

    private JSONObject getJsonPostsForOffset(Integer offset) throws IOException {
        String apiUrl = String.format(POSTS_ENDPOINT, service, user, offset);

        String jsonArrayString = Http.url(apiUrl)
                .ignoreContentType()
                .header("Accept", "text/css") // the site's API only answers with data when asked like this
                .response()
                .body();
        JSONArray jsonArray = new JSONArray(jsonArrayString);

        // Ideally we'd just return the JSONArray from here, but we have to wrap it in a JSONObject
        JSONObject wrapperObject = new JSONObject();
        wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, jsonArray);
        return wrapperObject;
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        if (postId != null) {
            String apiUrl = String.format("https://coomer.st/api/v1/%s/user/%s/post/%s", service, user, postId);
            String body = Http.url(apiUrl).ignoreContentType().header("Accept", "text/css").response().body();
            JSONObject reply = new JSONObject(body);
            JSONObject post = reply.has("post") ? reply.getJSONObject("post") : reply;
            JSONObject wrapperObject = new JSONObject();
            wrapperObject.put(KEY_WRAPPER_JSON_ARRAY, new JSONArray().put(post));
            return wrapperObject;
        }
        return getJsonPostsForOffset(0);
    }

    @Override
    protected JSONObject getNextPage(JSONObject doc) throws IOException, URISyntaxException {
        if (postId != null || doc.getJSONArray(KEY_WRAPPER_JSON_ARRAY).isEmpty()) {
            return null; // one post, or the last page was empty: nothing more
        }
        pageCount++;
        Integer offset = postCount * pageCount;
        return getJsonPostsForOffset(offset);
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
        logger.debug("Pulled " + urls.size() + " URLs from " + posts.length() + " posts");
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        sleep(1000);
        addURLToDownload(url, getPrefix(index));
    }

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
                logger.error("Unknown extension for coomer.su path: " + path);
            }
        } catch (JSONException e) {
            /* No-op */
            logger.error("Unable to Parse FileURL " + e.getMessage());
        }
    }

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
            logger.error("Unable to Parse AttachmentURL " + e.getMessage());
        }
    }

    private boolean isImage(String path) {
        Matcher matcher = IMG_PATTERN.matcher(path);
        return matcher.matches();
    }

    private boolean isVideo(String path) {
        Matcher matcher = VID_PATTERN.matcher(path);
        return matcher.matches();
    }
}
