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

    // The picture servers are n1..n4 of the site (c1/c3 no longer exist). Some of them may not answer from your
    // connection, so the first one that accepts a connection is used for everything (found once, on first use).
    private String dataBase = null;

    private String dataBase() {
        if (dataBase == null) {
            String site = "coomer.st";
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
                logger.error("None of the picture servers answered from this computer:" + tried
                        + ". Check your VPN/firewall/DNS; trying the main address anyway.");
                dataBase = "https://" + site + "/data";
            } else if (tried.length() > 0) {
                logger.warn("Skipped picture servers that did not answer:" + tried + "; using " + dataBase);
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
        String site = "coomer.st";
        if (base.equals("https://" + site + "/data")) {
            if (!warnedThumbnails) {
                warnedThumbnails = true;
                logger.warn("The picture servers cannot be reached from this computer, so pictures are taken from https://img."
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

    /** An attachment is {name, path} itself (unlike a post, which keeps its main file under "file"). */
    private void addAttachmentPath(String path, ArrayList<String> results) {
        if (path.isEmpty()) {
            return;
        }
        if (isImage(path)) {
            results.add(imageUrl(path));
        } else {
            results.add(dataBase() + path); // videos and any other file type
        }
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
            logger.debug("Found " + added + " picture links on the post page");
        } catch (Exception e) {
            logger.warn("Could not read the post page for more pictures: " + e);
        }
    }

    @Override
    protected JSONObject getFirstPage() throws IOException {
        if (postId != null) {
            String apiUrl = String.format("https://coomer.st/api/v1/%s/user/%s/post/%s", service, user, postId);
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
        logger.debug("Post answer has: " + reply.keySet() + "; the post has " + post.keySet() + ", "
                + (post.optJSONArray("attachments") == null ? 0 : post.optJSONArray("attachments").length()) + " attachments");
        addPageLinks(post);
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
        ArrayList<String> collected = new ArrayList<>();
        for (int i = 0; i < posts.length(); i++) {
            JSONObject post = posts.getJSONObject(i);
            pullFileUrl(post, collected);
            pullAttachmentUrls(post, collected);
        }
        ArrayList<String> urls = new ArrayList<>(new java.util.LinkedHashSet<>(collected)); // same file twice: keep one
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
                String url = imageUrl(path);
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
                addAttachmentPath(attachment.optString(KEY_PATH, ""), results);
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
