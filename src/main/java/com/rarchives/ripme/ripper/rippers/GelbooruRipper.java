package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

/**
 * Gelbooru (gelbooru.com). Reads the normal web pages (the site's data service now asks for an account key).
 * A tag search (index.php?page=post&amp;s=list&amp;tags=fish) rips every result on every page; a post link
 * (index.php?page=post&amp;s=view&amp;id=123) rips that one picture or video.
 */
public class GelbooruRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(GelbooruRipper.class);

    public GelbooruRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "gelbooru";
    }

    @Override
    protected String getDomain() {
        return "gelbooru.com";
    }

    private static String param(String link, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&#]*)").matcher(link);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public boolean canRip(URL url) {
        String host = url.getHost();
        if (!(host.equals("gelbooru.com") || host.endsWith(".gelbooru.com"))) {
            return false;
        }
        String link = url.toExternalForm();
        String page = param(link, "page");
        String s = param(link, "s");
        return "post".equals(page) && ("list".equals(s) || ("view".equals(s) && param(link, "id") != null));
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String link = url.toExternalForm();
        if ("view".equals(param(link, "s")) && param(link, "id") != null) {
            return "post_" + param(link, "id");
        }
        String tags = param(link, "tags");
        if (tags != null && !tags.isEmpty()) {
            return Utils.filesystemSafe(java.net.URLDecoder.decode(tags, java.nio.charset.StandardCharsets.UTF_8));
        }
        if ("list".equals(param(link, "s"))) {
            return "all";
        }
        throw new MalformedURLException("Expected a gelbooru.com tag search or post link - got " + url);
    }

    private static Map<String, String> siteCookies() {
        Map<String, String> cookies = new HashMap<>();
        cookies.put("fringeBenefits", "yup"); // the site's own "show me everything" setting
        return cookies;
    }

    private Document load(String link) throws IOException {
        return Http.url(link).cookies(siteCookies()).referrer("https://gelbooru.com/").get();
    }

    private boolean isPost(String link) {
        return "view".equals(param(link, "s"));
    }

    @Override
    public Document getFirstPage() throws IOException {
        return load(url.toExternalForm());
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        if (isPost(url.toExternalForm())) {
            throw new IOException("No more pages");
        }
        Element next = doc.selectFirst("div#paginator a[alt=next]");
        if (next == null) {
            next = doc.selectFirst("div#paginator b + a");
        }
        if (next == null || next.absUrl("href").isEmpty()) {
            throw new IOException("No more pages");
        }
        sleep(1000);
        return load(next.absUrl("href"));
    }

    /** The full-size file on a post page: the "Original image" link, else the picture or video shown. */
    static String originalOn(Document post) {
        for (Element a : post.select("a[href]")) {
            if (a.text().trim().equalsIgnoreCase("Original image")) {
                return a.absUrl("href");
            }
        }
        Element video = post.selectFirst("video source[src], video[src]");
        if (video != null) {
            String src = video.absUrl("src");
            if (!src.isEmpty()) {
                return src;
            }
        }
        Element img = post.selectFirst("img#image[src]");
        if (img != null) {
            return img.absUrl("src");
        }
        Element og = post.selectFirst("meta[property=og:image][content]");
        return og == null ? null : og.attr("content");
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        Set<String> urls = new LinkedHashSet<>();
        if (isPost(page.location().isEmpty() ? url.toExternalForm() : page.location()) || page.select("article.thumbnail-preview").isEmpty()) {
            String original = originalOn(page);
            if (original != null) {
                urls.add(original);
            }
            return new ArrayList<>(urls);
        }
        for (Element a : page.select("article.thumbnail-preview > a[href]")) {
            String postLink = a.absUrl("href");
            if (postLink.isEmpty()) {
                continue;
            }
            try {
                String original = originalOn(load(postLink));
                if (original != null) {
                    urls.add(original);
                } else {
                    logger.warn("No picture found on {}", postLink);
                }
            } catch (IOException e) {
                logger.warn("Could not open {}: {}", postLink, e.getMessage());
            }
            sleep(500);
        }
        return new ArrayList<>(urls);
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", "https://gelbooru.com/", null);
    }
}
