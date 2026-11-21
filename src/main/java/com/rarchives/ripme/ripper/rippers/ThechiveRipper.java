package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

public class ThechiveRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(ThechiveRipper.class);

    // Posts are thechive.com/CATEGORY/POST/ or thechive.com/CATEGORY/SUBCATEGORY/POST/ (older ones: /YEAR/MONTH/DAY/POST/).
    // Anything after "?" (for example ?utm_postid=...) is ignored.
    private Pattern p1 = Pattern.compile("^https?://(?:www\\.)?thechive\\.com/(?!(?:tag|author|page|search|wp-content|wp-json)/)"
            + "(?:[a-zA-Z0-9_\\-]+/)+([a-zA-Z0-9_\\-]+)/?(?:[?#].*)?$");
    private Pattern imagePattern = Pattern.compile("<img\\s(?:.|\\n)+?>");

    // i.thechive.com specific variables.
    private Pattern p2 = Pattern.compile("^https?://i.thechive.com/([0-9a-zA-Z_]+)");
    private String jsonUrl = "https://i.thechive.com/rest/uploads";
    private Map<String, String> cookies = new HashMap<>();
    private String nextSeed = "";
    private String username = "";

    public ThechiveRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        Matcher m1 = p1.matcher(url.toExternalForm());
        if (m1.matches()) {
            return "thechive";
        } else {
            return "i.thechive"; // for suitable album title.
        }
    }

    @Override
    public String getDomain() {
        return "thechive.com";
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException, URISyntaxException {
        String u = url.toExternalForm();
        if (p1.matcher(u).matches()) {
            // drop tracking parameters such as ?utm_postid=...
            u = u.replaceAll("[?#].*$", "");
            return new URI(u).toURL();
        }
        return url;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {

        Matcher m1 = p1.matcher(url.toExternalForm());
        if (m1.matches()) {
            return m1.group(1);
        }

        Matcher m2 = p2.matcher(url.toExternalForm());
        if (m2.matches()) {
            username = m2.group(1);
            return username;
        }

        throw new MalformedURLException("Expected thechive.com URL format: "
                + "thechive.com/YEAR/MONTH/DAY/POSTTITLE/ OR i.thechive.com/username, got " + url + " instead.");
    }

    @Override
    protected Document getFirstPage() throws IOException {
        Document doc = Http.url(url).get();
        // Removed posts and the closed i.thechive.com user pages are redirected to the home page.
        String finalPath = URI.create(doc.location()).getPath();
        boolean landedOnHome = finalPath == null || finalPath.isEmpty() || finalPath.equals("/");
        boolean askedForHome = url.getPath() == null || url.getPath().length() <= 1;
        if (landedOnHome && !askedForHome) {
            throw new IOException("thechive.com sent " + url + " to its home page, so this post or user page no longer exists. "
                    + "(i.thechive.com user pages were closed by the site; try a current thechive.com/YEAR/MONTH/DAY/TITLE/ link.)");
        }
        return doc;
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        List<String> result;
        Matcher matcher = p1.matcher(url.toExternalForm());

        if (matcher.matches()) {
            // for url type: thechive.com/YEAR/MONTH/DAY/POSTTITLE/
            result = getUrlsFromThechive(doc);
        } else {
            // for url type: i.thechive.com/username
            result = getUrlsFromIDotThechive();
        }
        return result;
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        Matcher matcher = p1.matcher(url.toExternalForm());

        if (matcher.matches()) {
            // url type thechive.com/YEAR/MONTH/DAY/POSTTITLE/ has a single page.
            return null;
        } else {
            if (nextSeed == null) {
                throw new IOException("No more pages.");
            }
        }

        // Following try block checks if the next JSON object has images or not.
        // This is done to avoid IOException in rip() method, caused when
        // getURLsFromPage() returns empty list.
        JSONArray imgList;
        try {
            Response response = Http.url(jsonUrl).data("seed", nextSeed).data("queryType", "by-username")
                    .data("username", username).ignoreContentType().cookies(cookies).response();
            cookies = response.cookies();
            JSONObject json = new JSONObject(response.body());
            imgList = json.getJSONArray("uploads");
        } catch (Exception e) {
            throw new IOException("Error fetching next page.", e);
        }

        if (imgList != null && imgList.length() > 0) {
            // Pass empty document as it is of no use for thechive.com/userName url type.
            return new Document(url.toString());
        } else {
            // Return null as this is last page.
            return null;
        }
    }

    @Override
    public void downloadURL(URL url, int index) {
        // thechive.com serves its uploads only to requests that look like they come from one of its pages
        Map<String, String> options = new HashMap<>();
        options.put("prefix", getPrefix(index));
        options.put("referrer", "https://thechive.com/");
        addURLToDownload(url, options);
    }

    private List<String> getUrlsFromThechive(Document doc) {
        /*
         * The image urls are stored in a <script> tag of the document. This script
         * contains a single array var by name CHIVE_GALLERY_ITEMS.
         *
         * We grab all the <img> tags from the particular script, combine them in a
         * string, parse it, and grab all the img/gif urls.
         *
         */
        List<String> result = new ArrayList<>();
        Elements scripts = doc.getElementsByTag("script");

        for (Element script : scripts) {
            String data = script.data();

            if (!data.contains("CHIVE_GALLERY_ITEMS")) {
                continue;
            }

            /*
             * We add all the <img/> tags in a single StringBuilder and parse as HTML for
             * easy sorting of img/ gifs.
             */
            StringBuilder allImgTags = new StringBuilder();
            Matcher matcher = imagePattern.matcher(data);
            while (matcher.find()) {
                // Unescape '\' from the img tags, which also unescape's img url as well.
                allImgTags.append(matcher.group(0).replaceAll("\\\\", ""));
            }

            // Now we parse and sort links.
            Document imgDoc = Jsoup.parse(allImgTags.toString());
            Elements imgs = imgDoc.getElementsByTag("img");
            for (Element img : imgs) {
                if (img.hasAttr("data-gifsrc")) {
                    // For gifs.
                    result.add(img.attr("data-gifsrc"));
                } else {
                    // For jpeg images.
                    result.add(img.attr("src"));
                }
            }
        }

        // strip all GET parameters from the links( such as quality, width, height as to
        // get the original image.).
        result.replaceAll(u -> u.contains("?") ? u.substring(0, u.indexOf("?")) : u);

        if (result.isEmpty()) {
            result = getUrlsFromCurrentLayout(doc);
        }

        return result;
    }

    private List<String> getUrlsFromIDotThechive() {
        /*
         * Image urls for i.thechive.com/someUserName as fetched via JSON request. Each
         *
         * JSON request uses the cookies from previous response( which contains the next
         * CSRF token).
         *
         * JSON request parameters:
         *  1. seed: activityId of the last url.
         *  2. queryType: 'by-username' always.
         *  3. username: username from the url itself.
         */
        List<String> result = new ArrayList<>();
        try {
            Response response = Http.url(jsonUrl).data("seed", nextSeed).data("queryType", "by-username")
                    .data("username", username).ignoreContentType().cookies(cookies).response();
            cookies = response.cookies();
            JSONObject json = new JSONObject(response.body());
            JSONArray imgList = json.getJSONArray("uploads");
            nextSeed = null; // if no more images, nextSeed stays null

            for (int i = 0; i < imgList.length(); i++) {
                JSONObject img = imgList.getJSONObject(i);
                if (img.getString("mediaType").equals("gif")) {
                    result.add("https:" + img.getString("mediaUrlOverlay"));
                } else {
                    result.add("https:" + img.getString("mediaGifFrameUrl"));
                }
                nextSeed = img.getString("activityId");
            }

        } catch (IOException e) {
            logger.error("Unable to fetch JSON data for url: " + url);
        } catch (JSONException e) {
            logger.error("JSON error while parsing data for url: " + url);
        }
        return result;
    }

    /**
     * Current thechive.com pages: pictures, GIFs and videos are ordinary files in /wp-content/uploads/
     * inside the post. Theme graphics, avatars and ads are ignored.
     */
    private List<String> getUrlsFromCurrentLayout(Document doc) {
        Element scope = doc.selectFirst("article .entry-content, .entry-content, .post-content, article, main");
        java.util.LinkedHashSet<String> found = collectUploads(scope == null ? doc.body() : scope);
        if (found.isEmpty()) {
            // layout not recognised: take everything on the page that is an uploaded file
            found = collectUploads(doc.body());
        }
        if (found.isEmpty()) {
            Element og = doc.selectFirst("meta[property=og:image]");
            if (og != null && isUpload(og.attr("content"))) {
                found.add(cleanUpload(og.attr("content")));
            }
        }
        logger.info("Found " + found.size() + " uploaded files on " + doc.location());
        return new ArrayList<>(found);
    }

    private static final Pattern UPLOAD_URL = Pattern.compile(
            "(?:https?:)?//[A-Za-z0-9.\\-]*thechive\\.com/wp-content/uploads/[^\"'\\s<>)\\\\,]+?\\.(?:jpe?g|png|gif|webp|mp4|webm)(?:\\?[^\"'\\s<>)\\\\,]*)?",
            Pattern.CASE_INSENSITIVE);

    /** Every uploaded picture/GIF/video mentioned anywhere inside the element: attributes, srcset, lazy-load data, noscript, JSON. */
    private java.util.LinkedHashSet<String> collectUploads(Element scope) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        Matcher m = UPLOAD_URL.matcher(scope.html().replace("&amp;", "&").replace("\\/", "/"));
        while (m.find()) {
            String u = m.group();
            if (u.startsWith("//")) {
                u = "https:" + u;
            }
            if (isUpload(u)) {
                found.add(cleanUpload(u));
            }
        }
        return found;
    }

    private static boolean isUpload(String u) {
        return u != null && u.contains("/wp-content/uploads/")
                && u.toLowerCase().matches("^[^?#]*\\.(jpe?g|png|gif|webp|mp4|webm)(\\?.*)?$");
    }

    /** Remove cache-busting parameters and WordPress thumbnail size suffixes (photo-300x200.jpg -> photo.jpg). */
    private static String cleanUpload(String u) {
        u = u.replaceAll("\\?.*$", "");
        return u.replaceAll("-\\d+x\\d+(\\.[A-Za-z0-9]+)$", "$1");
    }
}
