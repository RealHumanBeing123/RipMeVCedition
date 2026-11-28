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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Fapello (fapello.com) and TheFap (thefap.net). The two sites share one page design: a model page lists
 * small thumbnails that link to post pages, and each post page links to the full-size picture (or holds a video).
 *
 *  fapello.com/NAME/       the model's posts          fapello.com/NAME/14/            one post
 *  thefap.net/NAME-ID/     the model's posts          thefap.net/NAME-ID/SOURCE/i1    one post
 *
 * TheFap loads more posts while scrolling, from /ajax/model/ID/page-N, so the same address is read here.
 */
public class FapelloRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(FapelloRipper.class);

    private static final Pattern FAPELLO = Pattern.compile(
            "^https?://(?:www\\.)?fapello\\.com/([A-Za-z0-9._-]+)/(?:(\\d+)/?)?(?:[?#].*)?$");
    private static final Pattern THEFAP = Pattern.compile(
            "^https?://(?:www\\.)?thefap\\.net/([A-Za-z0-9._-]+-(\\d+))/(?:([A-Za-z0-9_-]+)/i(\\d+)/?)?(?:[?#].*)?$");
    private static final Pattern MEDIA_FILE = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp|mp4|webm|mov|m4v)(?:\\?.*)?$");
    private static final Set<String> RESERVED = Set.of("a", "ajax", "signup", "login", "search", "explore", "top-likes",
            "top-followers", "videos", "popular-videos", "popular", "trending", "contact", "contacts", "dmca", "terms",
            "privacy", "p", "assets", "content", "tags", "lists", "ranking", "request", "spotlight");

    public FapelloRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "fapello";
    }

    @Override
    protected String getDomain() {
        return "fapello.com";
    }

    private boolean isTheFap() {
        return url.getHost().toLowerCase().endsWith("thefap.net");
    }

    private String site() {
        return isTheFap() ? "https://thefap.net" : "https://fapello.com";
    }

    @Override
    public boolean canRip(URL url) {
        String u = url.toExternalForm();
        Matcher f = FAPELLO.matcher(u);
        if (f.matches()) {
            return !RESERVED.contains(f.group(1).toLowerCase());
        }
        return THEFAP.matcher(u).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String u = url.toExternalForm();
        Matcher f = FAPELLO.matcher(u);
        if (f.matches() && !RESERVED.contains(f.group(1).toLowerCase())) {
            return f.group(2) == null ? f.group(1) : f.group(1) + "_post" + f.group(2);
        }
        Matcher t = THEFAP.matcher(u);
        if (t.matches()) {
            return t.group(3) == null ? t.group(1) : t.group(1) + "_" + t.group(3) + "_i" + t.group(4);
        }
        throw new MalformedURLException("Expected fapello.com/NAME/ or thefap.net/NAME-ID/ - got " + url);
    }

    private boolean isSinglePost() {
        String u = url.toExternalForm();
        Matcher f = FAPELLO.matcher(u);
        if (f.matches()) {
            return f.group(2) != null;
        }
        Matcher t = THEFAP.matcher(u);
        return t.matches() && t.group(3) != null;
    }

    @Override
    public Document getFirstPage() throws IOException {
        return Http.url(url).referrer(site() + "/").get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        throw new IOException("No more pages");
    }

    /** Post page addresses found in a model page (or in a piece of one loaded by scrolling). */
    private Set<String> postLinks(Document doc) {
        Set<String> found = new LinkedHashSet<>();
        String own;
        Matcher f = FAPELLO.matcher(url.toExternalForm());
        Matcher t = THEFAP.matcher(url.toExternalForm());
        Pattern post;
        if (f.matches()) {
            post = Pattern.compile("^https?://(?:www\\.)?fapello\\.com/" + Pattern.quote(f.group(1)) + "/\\d+/?$", Pattern.CASE_INSENSITIVE);
        } else if (t.matches()) {
            post = Pattern.compile("^https?://(?:www\\.)?thefap\\.net/" + Pattern.quote(t.group(1)) + "/[A-Za-z0-9_-]+/i\\d+/?$", Pattern.CASE_INSENSITIVE);
        } else {
            return found;
        }
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href").replaceAll("[?#].*$", "");
            if (post.matcher(href).matches()) {
                found.add(href);
            }
        }
        return found;
    }

    /** The full-size picture(s) and video(s) of one post page. */
    static List<String> mediaOf(Document post) {
        Set<String> media = new LinkedHashSet<>();
        for (Element a : post.select("a.uk-align-center[href]")) {
            String href = a.absUrl("href");
            // the same class is also used by advertisement links, so only real picture/video files count
            if (!href.isEmpty() && MEDIA_FILE.matcher(href).find()) {
                media.add(href);
            }
        }
        for (Element v : post.select("video[src], video source[src]")) {
            String src = v.absUrl("src");
            if (!src.isEmpty()) {
                media.add(src);
            }
        }
        return new ArrayList<>(media);
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        List<String> posts = new ArrayList<>();
        Document first = page;
        if (isSinglePost()) {
            posts.add(url.toExternalForm());
        } else {
            Set<String> found = new LinkedHashSet<>(postLinks(page));
            Matcher t = THEFAP.matcher(url.toExternalForm());
            Matcher f = FAPELLO.matcher(url.toExternalForm());
            String ajaxBase = null;
            if (t.matches()) {
                ajaxBase = site() + "/ajax/model/" + t.group(2) + "/page-";
            } else if (f.matches()) {
                ajaxBase = site() + "/ajax/model/" + f.group(1) + "/page-";
            }
            // more posts load while scrolling: ask for page 2, 3 ... until nothing new comes
            for (int n = 2; ajaxBase != null && n < 300 && !isStopped(); n++) {
                try {
                    sleep(600);
                    String html = Http.url(ajaxBase + n).referrer(url.toExternalForm()).ignoreContentType()
                            .header("X-Requested-With", "XMLHttpRequest").response().body();
                    Document more = Jsoup.parse(html, site() + "/");
                    int before = found.size();
                    found.addAll(postLinks(more));
                    if (found.size() == before) {
                        break;
                    }
                } catch (IOException e) {
                    logger.info("No more posts after page " + (n - 1) + " (" + e.getMessage() + ")");
                    break;
                }
            }
            logger.info("Found " + found.size() + " posts");
            posts.addAll(found);
        }
        List<String> urls = new ArrayList<>();
        boolean firstRequest = true;
        for (String p : posts) {
            if (isStopped()) {
                break;
            }
            try {
                Document d;
                if (isSinglePost() && first != null) {
                    d = first;
                    first = null;
                } else {
                    if (!firstRequest) {
                        sleep(600);
                    }
                    d = Http.url(p).referrer(url.toExternalForm()).get();
                }
                firstRequest = false;
                List<String> media = mediaOf(d);
                if (media.isEmpty()) {
                    logger.warn("No picture or video found on " + p);
                }
                urls.addAll(media);
            } catch (IOException e) {
                logger.warn("Could not load " + p + ": " + e.getMessage());
            }
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("prefix", getPrefix(index));
        // thefap.net asks browsers not to send a referrer; its picture servers are other sites
        options.put("referrer", isTheFap() ? "" : site() + "/");
        addURLToDownload(url, options);
    }
}
