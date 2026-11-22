package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/** Rips a single 500px photo page, e.g. https://500px.com/photo/azrKSPoE4vH */
public class FivehundredpxPhotoRipper extends AbstractHTMLRipper {

    private static final Pattern PHOTO = Pattern.compile(
            "^https?://(?:www\\.)?500px\\.com/photo/([A-Za-z0-9]+)(?:/[^?#]*)?(?:[?#].*)?$");

    public FivehundredpxPhotoRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "500px";
    }

    @Override
    protected String getDomain() {
        return "500px.com";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = PHOTO.matcher(url.toExternalForm());
        if (m.matches()) {
            return "photo_" + m.group(1);
        }
        throw new MalformedURLException("Expected 500px.com/photo/<id> - got " + url);
    }

    @Override
    protected Document getFirstPage() throws IOException, URISyntaxException {
        Document page = Http.url(url).get();
        if (getURLsFromPage(page).isEmpty()) {
            throw new IOException("500px builds its pages with JavaScript, so the photo is not in the page RipMe receives. "
                    + "This version cannot download 500px photos, galleries or favorites.");
        }
        return page;
    }

    @Override
    protected List<String> getURLsFromPage(Document page) {
        List<String> result = new ArrayList<>();
        // The big picture on the page (s=2 is its larger size); fall back to the social-media preview.
        for (Element img : page.select("img[data-src*=500px.cloud/photo/], img[src*=500px.cloud/photo/]")) {
            String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            if (!src.isEmpty()) {
                result.add(src);
                break;
            }
        }
        if (result.isEmpty()) {
            Element og = page.selectFirst("meta[property=og:image]");
            if (og != null && !og.attr("content").isEmpty()) {
                result.add(og.attr("content"));
            }
        }
        return result;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }
}
