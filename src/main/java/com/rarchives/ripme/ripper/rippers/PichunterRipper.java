package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

public class PichunterRipper extends AbstractHTMLRipper {

    public PichunterRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "pichunter";
    }

    @Override
    public String getDomain() {
        return "pichunter.com";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("https?://www.pichunter.com/(|tags|models|sites)/(\\S*)/?");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(2);
        }
        p = Pattern.compile("https?://www.pichunter.com/(tags|models|sites)/(\\S*)/photos/\\d+/?");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(2);
        }
        p = Pattern.compile("https?://www.pichunter.com/tags/all/(\\S*)/\\d+/?");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }

        p = Pattern.compile("https?://www.pichunter.com/gallery/\\d+/(\\S*)/?");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }
        throw new MalformedURLException("Expected pichunter URL format: " +
                "pichunter.com/(tags|models|sites)/Name/ - got " + url + " instead");
    }

    private boolean isPhotoSet(URL url) {
        Pattern p = Pattern.compile("https?://www.pichunter.com/gallery/\\d+/(\\S*)/?");
        Matcher m = p.matcher(url.toExternalForm());
        return m.matches();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        Element link = doc.select("a[rel=next]").first();
        if (link == null) {
            Element elem = doc.select("div.paperSpacings > ul > li.arrow").last();
            if (elem != null) {
                link = elem.select("a").first();
            }
        }
        if (link != null) {
            String nextPage = link.absUrl("href");
            // Sometimes the link is empty or points back to the same page
            if (!nextPage.isEmpty() && !nextPage.equals(doc.location())) {
                return Http.url(nextPage).get();
            }
        }
        throw new IOException("No more pages");
    }

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

    @Override
    public List<String> getURLsFromPage(Document doc) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        String known = isPhotoSet(url) ? "div.flex-images > figure > a.item > img" : "div.thumbtable > a.thumb > img";
        for (Element el : doc.select(known)) {
            result.add(imageAddress(el));
        }
        if (result.isEmpty()) {
            // The page layout has changed: use any thumbnail that sits inside a link to a gallery or a photo
            String fallback = isPhotoSet(url)
                    ? "figure img, a.item img, .flex-images img"
                    : "a[href*=/gallery/] img, a.thumb img, .thumbtable img";
            for (Element el : doc.select(fallback)) {
                result.add(imageAddress(el));
            }
        }
        List<String> out = new ArrayList<>();
        for (String u : result) {
            if (u.isEmpty()) {
                continue;
            }
            // thumbnails end in _i, the full size picture in _o
            out.add(u.replaceAll("_i(\\.[A-Za-z0-9]+)(\\?.*)?$", "_o$1"));
        }
        return out;
    }

    @Override
    public void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }
}