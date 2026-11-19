import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prepares ripme.json for a new release of RipMeVCedition: reads the built jar, works out the version
 * from its file name, computes the SHA-256 hash the updater checks, and updates ripme.json.
 *
 * Run from the project folder (next to gradlew.bat):
 *   java ReleaseUpdate.java "Short description of what changed" [path\to\ripme-<version>.jar]
 * or just double-click release-update.bat.
 */
public class ReleaseUpdate {

    private static final String REPO = "RealHumanBeing123/RipMeVCedition";

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path json = root.resolve("ripme.json");
        if (!Files.isRegularFile(json)) {
            fail("ripme.json was not found in " + root + "\nRun this from the project folder (the one with gradlew.bat).");
        }

        Path jar = args.length > 1 ? Paths.get(args[1]).toAbsolutePath() : newestJar(root.resolve("build").resolve("libs"));
        if (!Files.isRegularFile(jar)) {
            fail("Jar not found: " + jar);
        }
        String jarName = jar.getFileName().toString();
        Matcher nameMatch = Pattern.compile("^ripme-(.+)\\.jar$").matcher(jarName);
        if (!nameMatch.matches() || jarName.endsWith("-all.jar")) {
            fail("Expected a jar named ripme-<version>.jar, found: " + jarName);
        }
        String version = nameMatch.group(1);
        if (version.startsWith("0.0.0")) {
            fail("The jar has version " + version + ". Build it with a real version number first, e.g.\n"
                    + "  gradlew.bat build -x test -PcustomVersion=2.1.21");
        }

        String message = args.length > 0 ? args[0].trim() : "";
        if (message.isEmpty()) {
            System.out.print("Short description of what changed in " + version + ": ");
            message = new Scanner(System.in).nextLine().trim();
        }
        if (message.isEmpty()) {
            message = "Update";
        }

        String hash = sha256(jar);
        String text = Files.readString(json, StandardCharsets.UTF_8);

        Matcher latest = Pattern.compile("(\"latestVersion\"\\s*:\\s*\")([^\"]*)(\")").matcher(text);
        Matcher current = Pattern.compile("(\"currentHash\"\\s*:\\s*\")([^\"]*)(\")").matcher(text);
        if (!latest.find() || !current.find()) {
            fail("ripme.json does not look right: \"latestVersion\" or \"currentHash\" is missing. Nothing was changed.");
        }
        String previous = latest.group(2);
        boolean sameVersion = previous.equals(version);

        text = text.substring(0, latest.start(2)) + version + text.substring(latest.end(2));
        Matcher hashMatch = Pattern.compile("(\"currentHash\"\\s*:\\s*\")([^\"]*)(\")").matcher(text);
        hashMatch.find();
        text = text.substring(0, hashMatch.start(2)) + hash + text.substring(hashMatch.end(2));

        if (!sameVersion) {
            Matcher list = Pattern.compile("(\"changeList\"\\s*:\\s*\\[)(\\s*)").matcher(text);
            if (!list.find()) {
                fail("ripme.json has no \"changeList\". Nothing was changed.");
            }
            String ws = list.group(2);
            boolean emptyList = text.startsWith("]", list.end());
            String entry = "\"" + jsonEscape(version + ": " + message) + "\"" + (emptyList ? "" : ",");
            text = text.substring(0, list.end(1)) + ws + entry + ws + text.substring(list.end());
        }

        Files.writeString(json, text, StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("ripme.json updated.");
        System.out.println("  version : " + version + (sameVersion ? "  (same as before: only the hash was updated)" : "  (was " + previous + ")"));
        System.out.println("  jar     : " + jar);
        System.out.println("  sha-256 : " + hash);
        if (!sameVersion && compare(version, previous) <= 0) {
            System.out.println();
            System.out.println("WARNING: " + version + " is not newer than " + previous + ".");
            System.out.println("Installed copies will NOT be offered this update. Rebuild with a higher version.");
        }
        System.out.println();
        System.out.println("Now, IN THIS ORDER:");
        System.out.println(" 1. Do not rebuild the jar again - any rebuild changes its hash.");
        System.out.println(" 2. On GitHub open https://github.com/" + REPO + "/releases/new");
        System.out.println("      - Tag: type  " + version + "  and choose \"Create new tag\"");
        System.out.println("      - Attach this exact file (keep its name):  " + jarName);
        System.out.println("      - Click \"Publish release\"");
        System.out.println(" 3. Check that this address downloads the jar:");
        System.out.println("      https://github.com/" + REPO + "/releases/download/" + version + "/" + jarName);
        System.out.println(" 4. Only then upload the changed ripme.json to the main branch of the repo");
        System.out.println("    (or send it to Claude to push). Uploading it before step 2 makes every copy");
        System.out.println("    of the program offer an update that cannot be downloaded.");
    }

    private static Path newestJar(Path libs) throws IOException {
        if (!Files.isDirectory(libs)) {
            fail("Folder not found: " + libs + "\nBuild first:  gradlew.bat build -x test -PcustomVersion=<new version>");
        }
        try (Stream<Path> files = Files.list(libs)) {
            List<Path> jars = files.filter(p -> p.getFileName().toString().matches("ripme-.+\\.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-all.jar"))
                    .filter(p -> !p.getFileName().toString().matches(".*-(plain|sources|javadoc)\\.jar"))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                    .collect(Collectors.toList());
            if (jars.isEmpty()) {
                fail("No ripme-<version>.jar found in " + libs);
            }
            return jars.get(0);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString(); // lower case, like the updater expects
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
            case '"': sb.append("\\\""); break;
            case '\\': sb.append("\\\\"); break;
            case '\n': case '\r': case '\t': sb.append(' '); break;
            default:
                if (c < 0x20) {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Same idea as the updater: compare the first four numeric parts of "2.1.20-14-abc". */
    private static int compare(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < 4; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        String[] p = v.split("[.-]");
        int[] r = new int[4];
        for (int i = 0; i < Math.min(4, p.length); i++) {
            if (p[i].matches("\\d+")) {
                r[i] = Integer.parseInt(p[i]);
            }
        }
        return r;
    }

    private static void fail(String message) {
        System.err.println("ERROR: " + message);
        System.exit(1);
    }
}
