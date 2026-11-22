package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

public class HypnohubRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(HypnohubRipper.class);

    public HypnohubRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "hypnohub";
    }

    @Override
    public String getDomain() {
        return "hypnohub.net";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String query = url.getQuery();
        if (query == null) {
            throw new MalformedURLException("URL missing query: " + url);
        }
        if (query.contains("page=pool")) {
            for (String param : query.split("&")) {
                if (param.startsWith("id=")) {
                    return param.substring("id=".length());
                }
            }
            throw new MalformedURLException("Pool URL missing id: " + url);
        } else if (query.contains("page=artist")) {
            String id = queryParam(query, "id");
            if (id == null) {
                throw new MalformedURLException("Artist URL missing id: " + url);
            }
            return "artist_" + id;
        } else if (query.contains("page=post") && query.contains("s=list")) {
            String tags = queryParam(query, "tags");
            if (tags == null || tags.isEmpty()) {
                throw new MalformedURLException("Tag list URL missing tags: " + url);
            }
            return "tags_" + tags.replaceAll("[^A-Za-z0-9_.+-]", "_");
        } else if (query.startsWith("page=post")) {
            // Drop "page=" to satisfy testGetGID
            return query.substring("page=".length());
        }
        throw new MalformedURLException("Unexpected URL format for GID: " + url);
    }

    private static String queryParam(String query, String name) {
        for (String param : query.split("&")) {
            if (param.startsWith(name + "=")) {
                return param.substring(name.length() + 1);
            }
        }
        return null;
    }

    private boolean isArtistPage() {
        return url.toExternalForm().contains("page=artist");
    }

    private boolean isListPage() {
        String u = url.toExternalForm();
        return isArtistPage() || u.contains("page=pool") || (u.contains("page=post") && u.contains("s=list"));
    }

    /**
     * An artist page only links to the artist's post list (a tag search), so
     * find that link and load the list page instead.
     */
    @Override
    protected Document getFirstPage() throws IOException, URISyntaxException {
        Document doc = super.getFirstPage();
        if (!isArtistPage()) {
            return doc;
        }
        String artistName = null;
        // The artist page has a row like "Name <name>"; also try the page title.
        for (Element cell : doc.select("td")) {
            if (cell.text().trim().equalsIgnoreCase("Name") && cell.nextElementSibling() != null) {
                artistName = cell.nextElementSibling().text().trim();
                break;
            }
        }
        String best = null;
        for (Element a : doc.select("a[href*=page=post][href*=s=list][href*=tags=]")) {
            String href = a.absUrl("href");
            String tags = queryParam(href.substring(href.indexOf('?') + 1).replace("&amp;", "&"), "tags");
            if (tags == null || tags.isEmpty()) {
                continue;
            }
            if (artistName != null && tags.equalsIgnoreCase(artistName.replace(' ', '_'))) {
                best = href;
                break;
            }
            if (best == null) {
                best = href;
            }
        }
        if (best == null) {
            if (artistName == null || artistName.isEmpty()) {
                throw new IOException("Could not find this artist's post list on " + url);
            }
            best = "https://hypnohub.net/index.php?page=post&s=list&tags=" + artistName.replace(' ', '_');
        }
        logger.info("Artist post list: {}", best);
        return Http.url(best).get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException, URISyntaxException {
        // Post-list pages have a "next" link; pools and single posts do not.
        Element next = doc.selectFirst("div.pagination a[alt=next]");
        if (next == null || !isListPage() || url.toExternalForm().contains("page=pool")) {
            throw new IOException("No next page");
        }
        String href = next.absUrl("href");
        if (href.isEmpty() || href.equals(doc.location())) {
            throw new IOException("No next page");
        }
        return Http.url(href).get();
    }

    /**
     * Fetches a post page and extracts its full-size image URL.
     */
    private String ripPost(String postUrl) throws IOException {
        logger.info("Fetching post: {}", postUrl);
        Document doc = Http.url(postUrl).get();
        // Try primary selector: the displayed sample image
        Element img = doc.selectFirst("img#image");
        if (img != null) {
            String src = img.attr("src");
            if (src.startsWith("//"))
                return "https:" + src;
            if (src.startsWith("/"))
                return "https://hypnohub.net" + src;
            return src;
        }
        // Fallback to original image link
        Element origLink = doc.selectFirst("a:matchesOwn(^Original image$)");
        if (origLink != null) {
            String href = origLink.attr("href");
            if (href.startsWith("//"))
                return "https:" + href;
            if (href.startsWith("/"))
                return "https://hypnohub.net" + href;
            return href;
        }
        // Final fallback: meta og:image
        Element meta = doc.selectFirst("meta[property=og:image]");
        if (meta != null) {
            String content = meta.attr("content");
            if (content.startsWith("//"))
                return "https:" + content;
            if (content.startsWith("/"))
                return "https://hypnohub.net" + content;
            return content;
        }
        logger.warn("No image found on post page: {}", postUrl);
        return null;
    }

    /**
     * Extracts the full-size image URL from an already-fetched post Document.
     */
    private String ripPost(Document doc) {
        logger.info("Parsing post document: {}", url);
        // Use same logic as string-based ripPost
        Element img = doc.selectFirst("img#image");
        if (img != null) {
            String src = img.attr("src");
            if (src.startsWith("//"))
                return "https:" + src;
            if (src.startsWith("/"))
                return "https://hypnohub.net" + src;
            return src;
        }
        Element origLink = doc.selectFirst("a:matchesOwn(^Original image$)");
        if (origLink != null) {
            String href = origLink.attr("href");
            if (href.startsWith("//"))
                return "https:" + href;
            if (href.startsWith("/"))
                return "https://hypnohub.net" + href;
            return href;
        }
        Element meta = doc.selectFirst("meta[property=og:image]");
        if (meta != null) {
            String content = meta.attr("content");
            if (content.startsWith("//"))
                return "https:" + content;
            if (content.startsWith("/"))
                return "https://hypnohub.net" + content;
            return content;
        }
        logger.warn("No image found in document at: {}", url);
        return null;
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        List<String> result = new ArrayList<>();
        String pageUrl = url.toExternalForm();
        if (isListPage()) {
            // Iterate over all thumbnail spans on the pool page
            for (Element link : doc.select("span.thumb > a[href*='page=post']")) {
                String href = link.attr("href");
                String fullPostUrl = href.startsWith("http") ? href : "https://hypnohub.net/" + href;
                try {
                    String imgUrl = ripPost(fullPostUrl);
                    if (imgUrl != null) {
                        result.add(imgUrl);
                    }
                } catch (IOException e) {
                    logger.error("Failed to rip post {}", fullPostUrl, e);
                }
            }
        } else if (pageUrl.contains("page=post")) {
            String imgUrl = ripPost(doc);
            if (imgUrl != null) {
                result.add(imgUrl);
            }
        }
        return result;
    }

    @Override
    public void downloadURL(URL url, int index) {
        // url here is already a direct image URL
        addURLToDownload(url, getPrefix(index));
    }
}
