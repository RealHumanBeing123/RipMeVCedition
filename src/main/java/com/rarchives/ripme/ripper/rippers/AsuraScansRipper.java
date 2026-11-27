package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

/**
 * Asura Scans (asurascans.com and its older names). A series link (/comics/name-1a2b3c4d) rips every chapter
 * into its own folder; a chapter link (/comics/name-1a2b3c4d/chapter/12) rips that chapter only.
 *
 * The site sits behind Cloudflare's "Just a moment..." check. Import your browser cookies (Configuration - Site
 * cookies - Import from browser) and put the browser's user agent in the setting asura.useragent, the same way
 * as for nhentai; without them the site answers with the check page and nothing can be read.
 */
public class AsuraScansRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(AsuraScansRipper.class);

    private static final Pattern LINK = Pattern.compile(
            "^https?://(?:www\\.)?(asurascans\\.com|asuracomic\\.net|asuratoon\\.com|asura\\.gg)/(?:comics|series)/([A-Za-z0-9._-]+)"
                    + "(?:/chapter/([A-Za-z0-9._-]+))?(?:[/?#].*)?$");
    private static final Pattern SCRIPT_IMAGE = Pattern.compile("\\\\?\"order\\\\?\":\\d+,\\\\?\"url\\\\?\":\\\\?\"([^\"\\\\]+)\\\\?\"");
    private static final Pattern IMAGE_URL = Pattern.compile("(?i)^https?://.+\\.(webp|jpe?g|png|gif|avif)(\\?.*)?$");

    public AsuraScansRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "asurascans";
    }

    @Override
    protected String getDomain() {
        Matcher m = LINK.matcher(url.toExternalForm());
        return m.matches() ? m.group(1) : "asurascans.com";
    }

    @Override
    public boolean canRip(URL url) {
        return LINK.matcher(url.toExternalForm()).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            throw new MalformedURLException("Expected asurascans.com/comics/name or .../comics/name/chapter/N - got " + url);
        }
        return m.group(3) == null ? m.group(2) : m.group(2) + "_chapter" + m.group(3);
    }

    private static Http load(String address) {
        String ua = Utils.getConfigString("asura.useragent", "").trim();
        Http http = Http.url(address);
        if (!ua.isEmpty()) {
            http.userAgent(ua);
        } else {
            http.userAgent(AbstractRipper.USER_AGENT);
        }
        return http;
    }

    private static Document fetch(String address) throws IOException {
        Document doc = load(address).get();
        if (doc.title().toLowerCase().contains("just a moment")) {
            throw new IOException("Asura Scans answered with Cloudflare's \"Just a moment...\" check instead of the page. "
                    + "Import your browser cookies (Configuration - Site cookies - Import from browser) after opening the "
                    + "site in that browser, and put that browser's user agent in the setting asura.useragent.");
        }
        return doc;
    }

    @Override
    public Document getFirstPage() throws IOException {
        return fetch(url.toExternalForm());
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        throw new IOException("No more pages");
    }

    /** The pictures of a chapter page, in reading order. */
    static List<String> imagesOf(Document page) {
        Set<String> found = new LinkedHashSet<>();
        for (Element img : page.select("img[src*=comics]")) {
            String src = img.absUrl("src");
            if (IMAGE_URL.matcher(src).matches()) {
                found.add(src);
            }
        }
        if (found.isEmpty()) {
            for (Element img : page.select("img[alt*=page], img[alt*=Page]")) {
                String src = img.absUrl("src");
                if (IMAGE_URL.matcher(src).matches()) {
                    found.add(src);
                }
            }
        }
        if (found.isEmpty()) {
            Matcher m = SCRIPT_IMAGE.matcher(page.outerHtml());
            while (m.find()) {
                found.add(m.group(1).replace("\\/", "/"));
            }
        }
        return new ArrayList<>(found);
    }

    /** Chapter links of a series page, oldest first. */
    static List<String> chapterLinks(Document page, String slug) {
        Map<Double, String> byNumber = new java.util.TreeMap<>();
        Pattern p = Pattern.compile("/comics/" + Pattern.quote(slug) + "/chapter/([0-9]+(?:[.-][0-9]+)?)");
        for (Element a : page.select("a[href*=/chapter/]")) {
            String href = a.absUrl("href");
            Matcher m = p.matcher(href);
            if (m.find()) {
                byNumber.put(Double.parseDouble(m.group(1).replace('-', '.')), href.replaceAll("[?#].*$", ""));
            }
        }
        return new ArrayList<>(byNumber.values());
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        List<String> urls = new ArrayList<>();
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            return urls;
        }
        if (m.group(3) != null) {
            urls.addAll(imagesOf(page));
            if (urls.isEmpty()) {
                logger.warn("No pictures found on " + url);
            }
            return urls;
        }
        List<String> chapters = chapterLinks(page, m.group(2));
        if (chapters.isEmpty()) {
            logger.error("No chapter links found on the series page " + url + " (is it the Cloudflare check page?)");
            return urls;
        }
        boolean first = true;
        for (String chapter : chapters) {
            if (isStopped()) {
                break;
            }
            try {
                if (!first) {
                    sleep(1000);
                }
                first = false;
                List<String> images = imagesOf(fetch(chapter));
                if (images.isEmpty()) {
                    logger.warn("No pictures found on " + chapter);
                }
                urls.addAll(images);
            } catch (IOException e) {
                logger.warn("Could not load " + chapter + ": " + e.getMessage());
            }
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("prefix", getPrefix(index));
        options.put("referrer", "https://" + getDomain() + "/");
        addURLToDownload(url, options);
    }
}
