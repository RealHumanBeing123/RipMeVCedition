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

    // vidble.com/watch?v=<32 characters>  (video page)
    private static final Pattern WATCH = Pattern.compile("^https?://(?:www\\.)?vidble\\.com/watch\\?(?:.*&)?v=([A-Za-z0-9]+).*$");
    // vidble.com/ID.jpg, vidble.com/explore/ID.jpg, vidble.com/ID_med.png, ... (the file itself)
    private static final Pattern DIRECT = Pattern.compile(
            "^https?://(?:www\\.)?vidble\\.com/(?:[a-z]+/)*([A-Za-z0-9]+?)(?:_[a-zA-Z]{3,5})?\\.(jpe?g|png|gif|webp|mp4|webm|mov)(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    // vidble.com/show/ID or vidble.com/ID  (a page showing one picture)
    private static final Pattern SINGLE = Pattern.compile(
            "^https?://(?:www\\.)?vidble\\.com/(?:show/|explore/)?([A-Za-z0-9]{8,12})/?(?:[?#].*)?$");
    private static final java.util.Set<String> RESERVED = new java.util.HashSet<>(java.util.Arrays.asList(
            "favorites", "uploads", "albums", "register", "explore", "signin", "deletion", "termsofservice"));

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p; Matcher m;
        String u = url.toExternalForm();

        p = Pattern.compile("^.*vidble.com/album/([a-zA-Z0-9_\\-]+).*$");
        m = p.matcher(u);
        if (m.matches()) {
            return m.group(1);
        }
        m = WATCH.matcher(u);
        if (m.matches()) {
            return "watch_" + m.group(1);
        }
        m = DIRECT.matcher(u);
        if (m.matches()) {
            return m.group(1);
        }
        m = SINGLE.matcher(u);
        if (m.matches() && !RESERVED.contains(m.group(1).toLowerCase())) {
            return m.group(1);
        }
        throw new MalformedURLException(
                "Expected a vidble.com album (vidble.com/album/####), video (vidble.com/watch?v=####) "
                        + "or picture (vidble.com/####.jpg) link. Got: " + url);
    }

    private static final String DIRECT_ATTR = "data-direct";

    @Override
    protected Document getFirstPage() throws IOException {
        Matcher m = DIRECT.matcher(url.toExternalForm());
        if (m.matches()) {
            // a link to the file itself: nothing to look up, the file is www.vidble.com/<id>.<ext>
            Document doc = new Document(url.toExternalForm());
            doc.body().attr(DIRECT_ATTR, "https://www.vidble.com/" + m.group(1) + "." + m.group(2).toLowerCase());
            return doc;
        }
        return Http.url(url).get();
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        if (doc.body() != null && doc.body().hasAttr(DIRECT_ATTR)) {
            List<String> one = new ArrayList<>();
            one.add(doc.body().attr(DIRECT_ATTR));
            return one;
        }
        Matcher w = WATCH.matcher(url.toExternalForm());
        if (w.matches()) {
            return videoFromWatchPage(doc, w.group(1));
        }
        return getURLsFromPageStatic(doc);
    }

    private static final Pattern VIDEO_FILE = Pattern.compile(
            "(?:https?:)?//[A-Za-z0-9.\\-]*vidble\\.com/[^\"'\\s<>)\\\\]+?\\.(?:mp4|webm|mov)", Pattern.CASE_INSENSITIVE);

    /** The video file of a vidble.com/watch?v=... page. */
    private static List<String> videoFromWatchPage(Document doc, String id) {
        List<String> result = new ArrayList<>();
        for (Element el : doc.select("video source[src], video[src], source[src$=.mp4], source[src$=.webm]")) {
            String v = el.absUrl("src");
            if (!v.isEmpty()) {
                result.add(v);
                break;
            }
        }
        if (result.isEmpty()) {
            for (String meta : new String[] { "meta[property=og:video]", "meta[property=og:video:url]",
                    "meta[property=og:video:secure_url]", "meta[name=twitter:player:stream]" }) {
                String v = doc.select(meta).attr("content");
                if (!v.isEmpty()) {
                    result.add(v.startsWith("//") ? "https:" + v : v);
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            Matcher m = VIDEO_FILE.matcher(doc.html().replace("\\/", "/"));
            if (m.find()) {
                String v = m.group();
                result.add(v.startsWith("//") ? "https:" + v : v);
            }
        }
        if (result.isEmpty()) {
            // last resort: videos are stored like pictures, as www.vidble.com/<id>.mp4
            logger.warn("Vidble: no video found on " + doc.location() + ", trying www.vidble.com/" + id + ".mp4");
            result.add("https://www.vidble.com/" + id + ".mp4");
        }
        return result;
    }

    private static final Logger logger = LogManager.getLogger(VidbleRipper.class);

    /** First usable address of a (possibly lazy-loaded) picture, as an absolute URL. */
    private static String imageAddress(Element img) {
        for (String attr : new String[] { "data-src", "data-original", "data-lazy-src", "src" }) {
            String v = img.absUrl(attr);
            if (!v.isEmpty() && !v.startsWith("data:") && !v.contains("TkIDtImlZQ")) { // TkIDtImlZQ is the lazy-load placeholder
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
