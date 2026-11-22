package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

public class VidbleRipper extends AbstractHTMLRipper {

    public VidbleRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "vidble";
    }
    @Override
    public String getDomain() {
        return "vidble.com";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p; Matcher m;

        p = Pattern.compile("^.*vidble.com/album/([a-zA-Z0-9_\\-]+).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }
        throw new MalformedURLException(
                "Expected vidble.com album format: "
                        + "vidble.com/album/####"
                        + " Got: " + url);
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        return getURLsFromPageStatic(doc);
    }

    private static final Logger logger = LogManager.getLogger(VidbleRipper.class);

    /** First usable address of a (possibly lazy-loaded) picture, as an absolute URL. */
    private static String imageAddress(Element img) {
        for (String attr : new String[] { "data-src", "data-original", "data-lazy-src", "src" }) {
            String v = img.absUrl(attr);
            if (!v.isEmpty() && !v.startsWith("data:")) {
                return v;
            }
        }
        return "";
    }

    private static List<String> getURLsFromPageStatic(Document doc) {
        java.util.LinkedHashSet<String> imageURLs = new java.util.LinkedHashSet<>();
        Elements imgs = doc.select("#ContentPlaceHolder1_divContent img");
        if (imgs.isEmpty()) {
            // layout changed: take the pictures that are hosted on vidble itself (not logos or ads)
            imgs = doc.select("img[src*=vidble.com/], img[data-src*=vidble.com/]");
        }
        for (Element img : imgs) {
            String src = imageAddress(img);
            // thumbnails carry a size suffix such as _med or _sml; the original has none
            src = src.replaceAll("_[a-zA-Z]{3,5}(?=\\.[A-Za-z0-9]+(\\?.*)?$)", "");
            if (!src.isEmpty() && src.matches("(?i)^[^?#]*\\.(jpe?g|png|gif|webp|mp4|webm)(\\?.*)?$")) {
                imageURLs.add(src);
            }
        }
        if (imageURLs.isEmpty()) {
            logger.warn("Vidble: no pictures recognised on " + doc.location() + " (page has " + doc.select("img").size()
                    + " img tags). First ones: " + doc.select("img").stream().limit(6)
                            .map(e -> e.outerHtml().length() > 200 ? e.outerHtml().substring(0, 200) : e.outerHtml())
                            .collect(java.util.stream.Collectors.joining("\n   ")));
        }
        return new ArrayList<>(imageURLs);
    }

    @Override
    public void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }

    public static List<URL> getURLsFromPage(URL url) throws IOException, URISyntaxException {
        List<URL> urls = new ArrayList<>();
        Document doc = Http.url(url).get();
        for (String stringURL : getURLsFromPageStatic(doc)) {
            urls.add(new URI(stringURL).toURL());
        }
        return urls;
    }
}
