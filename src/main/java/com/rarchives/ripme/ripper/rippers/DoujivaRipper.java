package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Doujiva (doujiva.com). A series link (doujiva.com/manga/name) rips every chapter into its own
 * folder; a chapter link (doujiva.com/manga/name/read/ID) rips just that chapter.
 *
 * The pages carry a list of chapters (id, number, page count) and the pictures live on
 * cdn.doujiva.com/name/chapter-N/001.webp (the extension differs between series).
 */
public class DoujivaRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(DoujivaRipper.class);

    private static final Pattern LINK = Pattern.compile(
            "^https?://(?:www\\.)?doujiva\\.com/manga/([A-Za-z0-9._-]+)(?:/read/([A-Za-z0-9]+))?(?:[/?#].*)?$");
    private static final Pattern IMAGE_EXT = Pattern.compile("(?i)^(webp|jpe?g|png|gif|avif)$");

    public DoujivaRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "doujiva";
    }

    @Override
    protected String getDomain() {
        return "doujiva.com";
    }

    @Override
    public boolean canRip(URL url) {
        return LINK.matcher(url.toExternalForm()).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            throw new MalformedURLException("Expected doujiva.com/manga/name or doujiva.com/manga/name/read/ID - got " + url);
        }
        return m.group(2) == null ? m.group(1) : m.group(1) + "_" + m.group(2);
    }

    @Override
    public Document getFirstPage() throws IOException {
        return Http.url(url).get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        throw new IOException("No more pages");
    }

    /** One chapter of the series. */
    static final class Chapter {
        final String id;
        final int number;
        final int pageCount; // 0 = unknown

        Chapter(String id, int number, int pageCount) {
            this.id = id;
            this.number = number;
            this.pageCount = pageCount;
        }
    }

    /** Page text with the escaping of embedded JSON removed, so that the patterns below see plain text. */
    static String plainText(String html) {
        return html.replace("\\\"", "\"").replace("\\/", "/");
    }

    /** Finds the first JSON array that follows the given key (e.g. "chapters":) and whose items have an id and a number. */
    static List<Chapter> parseChapters(String text) {
        String key = "\"chapters\":[";
        int from = 0;
        while (true) {
            int at = text.indexOf(key, from);
            if (at < 0) {
                return new ArrayList<>();
            }
            from = at + key.length();
            String array = balancedArray(text, at + key.length() - 1);
            if (array == null) {
                continue;
            }
            try {
                JSONArray items = new JSONArray(array);
                List<Chapter> chapters = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject c = items.optJSONObject(i);
                    if (c != null && c.has("id") && c.has("number")) {
                        chapters.add(new Chapter(c.getString("id"), c.optInt("number"), c.optInt("pageCount", 0)));
                    }
                }
                if (!chapters.isEmpty()) {
                    return chapters;
                }
            } catch (JSONException e) {
                // not the list we are after; keep looking
            }
        }
    }

    /** The text from the "[" at {@code start} to its matching "]", or null. */
    static String balancedArray(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** Picture addresses of one chapter that appear in the page text, in page order. */
    static List<String> imagesInText(String text, String slug, int chapter) {
        Pattern p = Pattern.compile("https://cdn\\.doujiva\\.com/" + Pattern.quote(slug) + "/chapter-" + chapter
                + "/([0-9]+)\\.(webp|jpe?g|png|gif|avif)");
        Map<Integer, String> byNumber = new java.util.TreeMap<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            byNumber.putIfAbsent(Integer.parseInt(m.group(1)), m.group());
        }
        return new ArrayList<>(byNumber.values());
    }

    private String extensionGuess(Document page, String text, String slug, int chapter) {
        for (Element img : page.select("img[alt^=Page]")) {
            String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            int dot = src.lastIndexOf('.');
            if (dot > 0 && IMAGE_EXT.matcher(src.substring(dot + 1).replaceAll("[?#].*", "")).matches()) {
                return src.substring(dot + 1).replaceAll("[?#].*", "").toLowerCase();
            }
        }
        Matcher m = Pattern.compile("cdn\\.doujiva\\.com/" + Pattern.quote(slug) + "/cover\\.(webp|jpe?g|png|gif|avif)").matcher(text);
        return m.find() ? m.group(1).toLowerCase() : "webp";
    }

    /** Pictures of one chapter, from its read page. */
    private List<String> chapterImages(Document page, String slug, Chapter chapter) {
        String text = plainText(page.outerHtml());
        Set<String> found = new LinkedHashSet<>();
        for (Element img : page.select("img[alt^=Page]")) {
            String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            if (src.startsWith("http") && src.contains("/chapter-" + chapter.number + "/") && !src.contains(".thumb.")) {
                found.add(src);
            }
        }
        if (found.isEmpty()) {
            found.addAll(imagesInText(text, slug, chapter.number));
        }
        if (chapter.pageCount > found.size()) {
            // The page only lists the pictures it has loaded so far (often about 10); the chapter list knows
            // the real page count, so fill in every missing page number.
            String ext = null;
            Set<Integer> have = new java.util.HashSet<>();
            Matcher name = Pattern.compile("/(\\d+)\\.([A-Za-z0-9]+)(?:\\?.*)?$").matcher("");
            for (String u : found) {
                name.reset(u);
                if (name.find()) {
                    have.add(Integer.parseInt(name.group(1)));
                    ext = name.group(2);
                }
            }
            if (ext == null) {
                ext = extensionGuess(page, text, slug, chapter.number);
            }
            Set<String> all = new LinkedHashSet<>();
            for (int i = 1; i <= chapter.pageCount; i++) {
                String made = String.format("https://cdn.doujiva.com/%s/chapter-%d/%03d.%s", slug, chapter.number, i, ext);
                String existing = null;
                for (String u : found) {
                    if (u.contains(String.format("/%03d.", i))) {
                        existing = u;
                        break;
                    }
                }
                all.add(existing != null ? existing : made);
            }
            return new ArrayList<>(all);
        }
        return new ArrayList<>(found);
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            return new ArrayList<>();
        }
        String slug = m.group(1);
        String wantedId = m.group(2);
        String text = plainText(page.outerHtml());

        List<Chapter> chapters = parseChapters(text);
        Map<String, Document> pages = new HashMap<>();
        if (wantedId != null) {
            pages.put(wantedId, page);
        }
        if (chapters.isEmpty()) {
            // A series page may only link to its chapters; the read page lists all of them.
            Matcher r = Pattern.compile("/manga/" + Pattern.quote(slug) + "/read/([A-Za-z0-9]+)").matcher(text);
            Set<String> readIds = new LinkedHashSet<>();
            while (r.find()) {
                readIds.add(r.group(1));
            }
            if (wantedId == null && !readIds.isEmpty()) {
                String first = readIds.iterator().next();
                try {
                    Document firstRead = Http.url(readUrl(slug, first)).get();
                    pages.put(first, firstRead);
                    chapters = parseChapters(plainText(firstRead.outerHtml()));
                } catch (IOException e) {
                    logger.warn("Could not load the first chapter page of " + slug, e);
                }
            }
            if (chapters.isEmpty()) {
                for (String id : readIds) {
                    chapters.add(new Chapter(id, chapters.size() + 1, 0));
                }
            }
        }
        if (wantedId != null) {
            List<Chapter> only = new ArrayList<>();
            for (Chapter c : chapters) {
                if (c.id.equals(wantedId)) {
                    only.add(c);
                }
            }
            if (only.isEmpty()) {
                // no list on the page: work out the number from the pictures it shows
                Matcher n = Pattern.compile("/" + Pattern.quote(slug) + "/chapter-(\\d+)/").matcher(text);
                only.add(new Chapter(wantedId, n.find() ? Integer.parseInt(n.group(1)) : 1, 0));
            }
            chapters = only;
        }
        chapters.sort((a, b) -> Integer.compare(a.number, b.number));

        List<String> urls = new ArrayList<>();
        boolean first = true;
        for (Chapter chapter : chapters) {
            if (isStopped()) {
                break;
            }
            try {
                Document read = pages.get(chapter.id);
                if (read == null) {
                    if (!first) {
                        sleep(1000);
                    }
                    read = Http.url(readUrl(slug, chapter.id)).get();
                }
                first = false;
                List<String> images = chapterImages(read, slug, chapter);
                if (images.isEmpty()) {
                    logger.warn("No pictures found for chapter " + chapter.number + " of " + slug);
                }
                urls.addAll(images);
            } catch (IOException e) {
                logger.warn("Could not load chapter " + chapter.number + " of " + slug, e);
            }
        }
        return urls;
    }

    private static String readUrl(String slug, String id) {
        return "https://doujiva.com/manga/" + slug + "/read/" + id;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new LinkedHashMap<>();
        Matcher m = Pattern.compile("/(chapter-\\d+)/").matcher(url.toExternalForm());
        if (m.find()) {
            options.put("subdirectory", m.group(1));
        } else {
            options.put("prefix", getPrefix(index));
        }
        options.put("referrer", "https://doujiva.com/");
        addURLToDownload(url, options);
    }
}
