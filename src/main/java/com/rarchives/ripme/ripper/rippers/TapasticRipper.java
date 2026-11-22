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
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

class TapasticEpisode {
    public int id;
    public String filename;
    public String title;

    public TapasticEpisode(int index, int id, String title) {
        this.id = id;
        this.title = title;
        this.filename = Utils.filesystemSafe(title);
    }
}

public class TapasticRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(TapasticRipper.class);

    private List<TapasticEpisode> episodes = new ArrayList<>();

    public TapasticRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getDomain() {
        return "tapas.io";
    }

    @Override
    public String getHost() {
        return "tapas";
    }

    private static final Pattern EPISODE_LINK = Pattern.compile("/episode/(\\d+)");
    private static final Pattern EPISODE_SCRIPT = Pattern.compile("episode:\\s*\\{\\s*id:\\s*(\\d+)[\\s\\S]*?title:\\s*\"([^\"]*)\"");

    private static int episodeIdOf(String href) {
        Matcher m = EPISODE_LINK.matcher(href == null ? "" : href);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The episode a page shows (a series page shows its first episode): id and title from the page's own script. */
    private static TapasticEpisode episodeOf(Document page) {
        for (org.jsoup.nodes.Element script : page.getElementsByTag("script")) {
            Matcher m = EPISODE_SCRIPT.matcher(script.data());
            if (m.find()) {
                return new TapasticEpisode(0, Integer.parseInt(m.group(1)), m.group(2));
            }
        }
        int id = episodeIdOf(page.select("link[rel=canonical]").attr("href"));
        return id < 0 ? null : new TapasticEpisode(0, id, String.valueOf(id));
    }

    /** ID of the previous or next episode ("prev" / "next"), or -1: from the page's data-next-id / data-prev-id, else from the button link. */
    private static int linkedEpisode(Document page, String direction) {
        org.jsoup.nodes.Element holder = page.select("[data-" + direction + "-id]").first();
        if (holder != null) {
            try {
                int id = Integer.parseInt(holder.attr("data-" + direction + "-id").trim());
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the button
            }
        }
        org.jsoup.nodes.Element a = page.select("a.js-" + direction + "-ep-btn[href]").first();
        return a == null ? -1 : episodeIdOf(a.attr("href"));
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        List<String> urls = new ArrayList<>();
        TapasticEpisode current = episodeOf(page);
        if (current == null) {
            logger.error("Tapas: this page does not show an episode: " + page.location());
            return urls;
        }
        boolean wholeSeries = url.getPath().startsWith("/series/");

        // go back to the first episode (a series page already is the first one)
        Document doc = page;
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        visited.add(current.id);
        while (wholeSeries && !isThisATest()) {
            int prev = linkedEpisode(doc, "prev");
            if (prev < 0 || !visited.add(prev)) {
                break;
            }
            try {
                sleep(300);
                doc = Http.url("https://tapas.io/episode/" + prev).get();
            } catch (IOException e) {
                logger.warn("Could not load episode " + prev, e);
                break;
            }
            TapasticEpisode earlier = episodeOf(doc);
            if (earlier == null) {
                break;
            }
            current = earlier;
        }

        // then walk forwards through the "next episode" links
        visited.clear();
        while (current != null && visited.add(current.id)) {
            episodes.add(new TapasticEpisode(episodes.size(), current.id, current.title));
            urls.add("https://tapas.io/episode/" + current.id);
            if (!wholeSeries || isThisATest() || isStopped()) {
                break;
            }
            int next = linkedEpisode(doc, "next");
            if (next < 0 || visited.contains(next)) {
                break;
            }
            try {
                sleep(300);
                doc = Http.url("https://tapas.io/episode/" + next).get();
            } catch (IOException e) {
                logger.warn("Could not load episode " + next, e);
                break;
            }
            current = episodeOf(doc);
        }
        return urls;
    }

    @Override
    public void downloadURL(URL url, int index) {
        try {
            // picture addresses are signed and only valid for about an hour, so the page is loaded again right now
            Document doc = Http.url(url).get();
            List<String> images = new ArrayList<>();
            for (org.jsoup.nodes.Element img : doc.select("img.content__img, article.ep-contents img")) {
                String link = img.hasAttr("data-src") ? img.absUrl("data-src") : img.absUrl("src");
                if (!link.isEmpty() && !link.startsWith("data:")) {
                    images.add(link);
                }
            }
            if (images.isEmpty()) {
                logger.error("Tapas: no pictures on " + url + " (a locked or paid episode shows none)");
                return;
            }
            TapasticEpisode episode = episodes.get(index - 1);
            // Find maximum # of images for optimal filename indexing
            int epiLog = (int) (Math.floor(Math.log10(Math.max(1, episodes.size()))) + 1),
                imgLog = (int) (Math.floor(Math.log10(images.size())) + 1);
            for (int i = 0; i < images.size(); i++) {
                // Build elaborate filename prefix
                StringBuilder prefix = new StringBuilder();
                prefix.append(String.format("ep%0" + epiLog + "d", index));
                prefix.append(String.format("-%0" + imgLog + "dof%0" + imgLog + "d-", i + 1, images.size()));
                prefix.append(episode.filename.replace(" ", "-"));
                prefix.append("-");
                // The signed address carries a long "?__token__=..." part that itself contains slashes, which ends up in
                // the file name. Name the file after the path alone: e615dba5-...-1.jpg
                URI imageUri = new URI(images.get(i));
                String path = imageUri.getPath() == null ? "" : imageUri.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                java.util.Map<String, String> options = new java.util.HashMap<>();
                options.put("prefix", prefix.toString());
                if (!name.isEmpty()) {
                    options.put("fileName", name);
                }
                addURLToDownload(imageUri.toURL(), options);
                if (isThisATest()) {
                    break;
                }
            }
        } catch (IOException | URISyntaxException e) {
            logger.error("[!] Exception while downloading " + url, e);
        }
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("^https?://tapas.io/series/([^/?]+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return "series_ " + m.group(1);
        }
        p = Pattern.compile("^https?://tapas.io/episode/([^/?]+).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return "ep_" + m.group(1);
        }
        throw new MalformedURLException("Expected tapastic.com URL format: "
                + "tapastic.com/[series|episode]/name - got " + url + " instead");
    }
}
