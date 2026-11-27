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
import com.rarchives.ripme.utils.Http;

/**
 * Babepedia (babepedia.com).
 *  - A profile link (babepedia.com/babe/Name) rips the profile pictures, the user-uploaded photos and every
 *    gallery listed on the profile (each gallery in its own folder).
 *  - A gallery link (babepedia.com/gallery/Name/12345) rips that one gallery.
 * The full-size pictures are the links around the thumbnails (/pics/..., /user-uploads/..., /galleries/.../01.jpg).
 */
public class BabepediaRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(BabepediaRipper.class);

    private static final Pattern PROFILE = Pattern.compile("^https?://(?:www\\.)?babepedia\\.com/babe/([^/?#]+)/?(?:[?#].*)?$");
    private static final Pattern GALLERY = Pattern.compile("^https?://(?:www\\.)?babepedia\\.com/gallery/([^/?#]+)/(\\d+)/?(?:[?#].*)?$");
    private static final Pattern IMAGE = Pattern.compile("(?i)\\.(jpe?g|png|webp|gif)(?:\\?.*)?$");
    private static final String SITE = "https://www.babepedia.com";

    public BabepediaRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "babepedia";
    }

    @Override
    protected String getDomain() {
        return "babepedia.com";
    }

    @Override
    public boolean canRip(URL url) {
        String u = url.toExternalForm();
        return PROFILE.matcher(u).matches() || GALLERY.matcher(u).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String u = url.toExternalForm();
        Matcher m = PROFILE.matcher(u);
        if (m.matches()) {
            return m.group(1);
        }
        m = GALLERY.matcher(u);
        if (m.matches()) {
            return m.group(1) + "_gallery_" + m.group(2);
        }
        throw new MalformedURLException("Expected babepedia.com/babe/Name or babepedia.com/gallery/Name/12345 - got " + url);
    }

    @Override
    public Document getFirstPage() throws IOException {
        return Http.url(url).referrer(SITE + "/").get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        throw new IOException("No more pages");
    }

    /** Full-size picture links on a page: every link whose target is a picture file in the site's picture folders. */
    static List<String> pictureLinks(Document page) {
        Set<String> found = new LinkedHashSet<>();
        for (Element a : page.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isEmpty() || !IMAGE.matcher(href).find()) {
                continue;
            }
            if (href.contains("/pics/") || href.contains("/user-uploads/") || href.contains("/galleries/")) {
                found.add(href);
            }
        }
        return new ArrayList<>(found);
    }

    /** Addresses of the galleries listed on a profile page. */
    static List<String> galleryLinks(Document page) {
        Set<String> found = new LinkedHashSet<>();
        for (Element a : page.select("a[href*=/gallery/]")) {
            String href = a.absUrl("href");
            if (GALLERY.matcher(href).matches()) {
                found.add(href.replaceAll("[?#].*$", ""));
            }
        }
        return new ArrayList<>(found);
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        Set<String> urls = new LinkedHashSet<>(pictureLinks(page));
        if (PROFILE.matcher(url.toExternalForm()).matches()) {
            List<String> galleries = galleryLinks(page);
            logger.info("Found " + galleries.size() + " galleries on the profile");
            boolean first = true;
            for (String g : galleries) {
                if (isStopped()) {
                    break;
                }
                try {
                    if (!first) {
                        sleep(800);
                    }
                    first = false;
                    Document gp = Http.url(g).referrer(url.toExternalForm()).get();
                    List<String> pics = pictureLinks(gp);
                    if (pics.isEmpty()) {
                        logger.warn("No pictures found in gallery " + g);
                    }
                    urls.addAll(pics);
                } catch (IOException e) {
                    logger.warn("Could not load gallery " + g, e);
                }
            }
        }
        return new ArrayList<>(urls);
    }

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new LinkedHashMap<>();
        String u = url.toExternalForm();
        Matcher g = Pattern.compile("/galleries/([^/]+)/([^/]+)$").matcher(u);
        if (g.find()) {
            // one folder per gallery, files keep their own numbers (01.jpg, 02.jpg ...)
            options.put("subdirectory", g.group(1));
        } else if (u.contains("/user-uploads/")) {
            options.put("subdirectory", "user-uploads");
        } else {
            options.put("subdirectory", "profile");
        }
        options.put("prefix", g.find(0) ? "" : getPrefix(index));
        options.put("referrer", SITE + "/");
        addURLToDownload(url, options);
    }
}
