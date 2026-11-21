package org.snowballmc.snowball.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.papermc.paper.ServerBuildInfo;
import net.minecraft.util.Util;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.LeavesLogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

public class LeavesUpdateHelper {

    private static final String REPOSITORY_OWNER = "Yamada0001";
    private static final long REPOSITORY_ID = 1458310160L;
    private static final String GITHUB_WEB = "https://github.com/";
    private static final String GITHUB_API = "https://api.github.com/";
    private static final String REPOSITORY_API = GITHUB_API + "repositories/" + REPOSITORY_ID;
    public static final String DOWNLOAD_REPOSITORY = GITHUB_WEB + REPOSITORY_OWNER + "/SnowBall";
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_RELEASE_PAGES = 10;
    private static final int RELEASES_PER_PAGE = 100;
    private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private static final long MAX_JAR_BYTES = 1024L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern GIT_SHA = Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final Pattern MC_VERSION = Pattern.compile("[0-9][0-9A-Za-z._+-]*");
    private static final String REPOSITORY_SLUG_PATTERN = Pattern.quote(REPOSITORY_OWNER) + "/[A-Za-z0-9_-][A-Za-z0-9._-]*";
    private static final Pattern REPOSITORY_SLUG = Pattern.compile(REPOSITORY_SLUG_PATTERN);
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");
    private static final Pattern DOWNLOAD_PATH = Pattern.compile("/" + REPOSITORY_SLUG_PATTERN
        + "/releases/download/([0-9][0-9A-Za-z._+-]*)-[1-9][0-9]*-([0-9a-f]{7,40})/(?:SnowBall|leaves)-\\1-\\2\\.jar");
    private static final Pattern RELEASE_ASSET_PATH = Pattern.compile("/github-production-release-asset(?:-[0-9a-f]+)?/" + REPOSITORY_ID + "/[A-Za-z0-9-]+");

    private final static String autoUpdateDir = "auto_update";
    private final static String corePathFileName = autoUpdateDir + File.separator + "core.path";

    private final static ReentrantLock updateLock = new ReentrantLock();
    private static boolean updateTaskStarted = false;

    private static final ScheduledExecutorService autoUpdateExecutor = Executors.newScheduledThreadPool(1);

    public static void init() {
        File workingDirFile = new File(autoUpdateDir);
        if (!workingDirFile.exists()) {
            if (!workingDirFile.mkdir()) {
                LeavesLogger.LOGGER.warn("Failed to create working directory: {}", autoUpdateDir);
            }
        }

        File corePathFile = new File(corePathFileName);
        if (!corePathFile.exists()) {
            try {
                if (!corePathFile.createNewFile()) {
                    throw new IOException();
                }
            } catch (IOException e) {
                LeavesLogger.LOGGER.error("Failed to create core path file: {}", corePathFileName, e);
            }
        }

        File leavesUpdateDir = new File(autoUpdateDir + File.separator + "leaves");
        if (!leavesUpdateDir.exists()) {
            if (!leavesUpdateDir.mkdir()) {
                LeavesLogger.LOGGER.warn("Failed to create leaves update directory: {}", leavesUpdateDir);
            }
        }

        if (LeavesConfig.mics.autoUpdate.enable) {
            LocalTime currentTime = LocalTime.now();
            long dailyTaskPeriod = 24 * 60 * 60 * 1000;

            for (String time : LeavesConfig.mics.autoUpdate.updateTime) {
                try {
                    LocalTime taskTime = LocalTime.of(Integer.parseInt(time.split(":")[0]), Integer.parseInt(time.split(":")[1]));
                    Duration task = Duration.between(currentTime, taskTime);
                    if (task.isNegative()) {
                        task = task.plusDays(1);
                    }
                    autoUpdateExecutor.scheduleAtFixedRate(LeavesUpdateHelper::tryUpdateLeaves, task.toMillis(), dailyTaskPeriod, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    LeavesLogger.LOGGER.warn("Illegal auto-update time ignored: {}", time);
                }
            }
        }
    }

    public static void tryUpdateLeaves() {
        updateLock.lock();
        try {
            if (!updateTaskStarted) {
                updateTaskStarted = true;
                new Thread(LeavesUpdateHelper::downloadLeaves, "SnowBall GitHub updater").start();
            }
        } finally {
            updateLock.unlock();
        }
    }

    private static void downloadLeaves() {
        Path outFile = null;
        try {
            ServerBuildInfo version = ServerBuildInfo.buildInfo();
            if (version.gitCommit().isEmpty() || version.buildNumber().isEmpty()) {
                LeavesLogger.LOGGER.info("IDE, custom build? Can not update!");
                return;
            }

            LeavesLogger.LOGGER.info("Now gitHash: {}", version.gitCommit().get());
            LeavesLogger.LOGGER.info("Trying to get latest build info from GitHub Releases.");
            final String gitHash = version.gitCommit().get();
            final int currentBuild = version.buildNumber().getAsInt();
            LeavesBuildInfo buildInfo = GIT_SHA.matcher(gitHash).matches() && currentBuild >= 0
                ? getReleaseCatalog(version.minecraftVersionId()).newerBuild(gitHash, currentBuild)
                : null;
            if (buildInfo == null) {
                LeavesLogger.LOGGER.info("No newer compatible GitHub release with a verifiable checksum is available.");
                return;
            }

            LeavesLogger.LOGGER.info("Got build info, trying to download {}", buildInfo.fileName);
            outFile = Path.of(autoUpdateDir, "leaves", buildInfo.fileName + ".cache");
            Files.deleteIfExists(outFile);
            downloadAsset(buildInfo, outFile, LeavesUpdateHelper::openConnection);

            // Keep the launcher protocol: verified jar in auto_update/leaves and its relative path in core.path.
            Path nowServerCore = Path.of(autoUpdateDir, "leaves", buildInfo.fileName);
            Path backupServerCore = Path.of(autoUpdateDir, "leaves", buildInfo.fileName + ".old");
            Util.safeReplaceFile(nowServerCore, outFile, backupServerCore);
            if (!isFileValid(nowServerCore, buildInfo.sha256)) {
                throw new IOException("Failed to install the verified release asset");
            }
            try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(corePathFileName))) {
                bufferedWriter.write(autoUpdateDir + File.separator + "leaves" + File.separator + buildInfo.fileName);
            }
            LeavesLogger.LOGGER.info("SnowBall update completed, please restart your server.");
        } catch (Exception e) {
            LeavesLogger.LOGGER.error("SnowBall update failed", e);
        } finally {
            if (outFile != null) {
                try {
                    Files.deleteIfExists(outFile);
                } catch (IOException e) {
                    LeavesLogger.LOGGER.warn("Failed to remove incomplete update download", e);
                }
            }
            updateLock.lock();
            try {
                updateTaskStarted = false;
            } finally {
                updateLock.unlock();
            }
        }
    }

    static boolean isFileValid(Path file, String hash) {
        if (hash == null || !SHA256.matcher(hash).matches()) {
            return false;
        }
        try (InputStream inputStream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (int numRead; (numRead = inputStream.read(buffer)) != -1; ) {
                sha256.update(buffer, 0, numRead);
            }
            return MessageDigest.isEqual(sha256.digest(), HexFormat.of().parseHex(hash));
        } catch (Exception e) {
            LeavesLogger.LOGGER.warn("Fail to validate file {}", file, e);
            return false;
        }
    }

    public static String resolveRepositoryWebUrl() throws IOException {
        return GITHUB_WEB + identifyRepository(fetchMetadata(REPOSITORY_API, JsonObject.class, LeavesUpdateHelper::openConnection));
    }

    static ReleaseCatalog getReleaseCatalog(String mcVersion) throws IOException {
        return getReleaseCatalog(mcVersion, LeavesConfig.mics.autoUpdate.allowExperimental, LeavesUpdateHelper::openConnection);
    }

    static ReleaseCatalog getReleaseCatalog(String mcVersion, boolean allowExperimental, ConnectionFactory connections) throws IOException {
        final JsonObject metadata = fetchMetadata(REPOSITORY_API, JsonObject.class, connections);
        final ReleaseCatalog catalog = new ReleaseCatalog(identifyRepository(metadata), mcVersion, allowExperimental);
        for (int page = 1; page <= MAX_RELEASE_PAGES; page++) {
            final JsonArray releases = fetchMetadata(REPOSITORY_API + "/releases?per_page=" + RELEASES_PER_PAGE + "&page=" + page, JsonArray.class, connections);
            if (releases.size() > RELEASES_PER_PAGE) {
                throw new IOException("Invalid GitHub Releases response");
            }
            catalog.addPage(releases);
            if (releases.size() < RELEASES_PER_PAGE) {
                return catalog;
            }
        }
        throw new IOException("GitHub release listing exceeded the safe pagination limit");
    }

    private static <T> T fetchMetadata(String url, Class<T> type, ConnectionFactory connections) throws IOException {
        final HttpURLConnection connection = connections.open(URI.create(url));
        try {
            configureConnection(connection);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            final int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API returned HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                final byte[] json = input.readNBytes(MAX_METADATA_BYTES + 1);
                if (json.length > MAX_METADATA_BYTES) {
                    throw new IOException("GitHub metadata response is too large");
                }
                final T metadata = new Gson().fromJson(new String(json, StandardCharsets.UTF_8), type);
                if (metadata == null) {
                    throw new IOException("Empty GitHub metadata response");
                }
                return metadata;
            }
        } catch (JsonParseException e) {
            throw new IOException("Invalid GitHub metadata response", e);
        } finally {
            connection.disconnect();
        }
    }

    // The immutable ID authorizes the repository; the verified slug is only its current address.
    static String identifyRepository(JsonObject repository) throws IOException {
        final String slug = string(repository, "full_name");
        final JsonElement owner = repository.get("owner");
        if (!Long.toString(REPOSITORY_ID).equals(numericId(repository))
            || owner == null || !owner.isJsonObject() || !REPOSITORY_OWNER.equals(string(owner.getAsJsonObject(), "login"))
            || slug == null || !REPOSITORY_SLUG.matcher(slug).matches()
            || !(GITHUB_API + "repos/" + slug).equals(string(repository, "url"))
            || !(GITHUB_WEB + slug).equals(string(repository, "html_url"))) {
            throw new IOException("GitHub repository identity does not match the SnowBall updater source");
        }
        return slug;
    }

    private static String numericId(JsonObject object) {
        final JsonElement id = object.get("id");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        final String value = id.getAsString();
        return POSITIVE_INTEGER.matcher(value).matches() ? value : null;
    }

    // Only verified, downloadable builds for one Minecraft version and release policy are indexed.
    static final class ReleaseCatalog {
        private final String repositoryApiUrl;
        private final String repositoryWebUrl;
        private final String mcVersion;
        private final Pattern tagPattern;
        private final boolean allowExperimental;
        private final NavigableMap<Integer, LeavesBuildInfo> builds = new TreeMap<>();

        ReleaseCatalog(String canonicalSlug, String mcVersion, boolean allowExperimental) {
            this.repositoryApiUrl = GITHUB_API + "repos/" + canonicalSlug;
            this.repositoryWebUrl = GITHUB_WEB + canonicalSlug;
            this.mcVersion = mcVersion;
            this.tagPattern = MC_VERSION.matcher(mcVersion).matches()
                ? Pattern.compile(Pattern.quote(mcVersion) + "-([1-9][0-9]*)-([0-9a-f]{7,40})")
                : null;
            this.allowExperimental = allowExperimental;
        }

        void addPage(JsonArray releases) {
            if (this.tagPattern == null) {
                return;
            }
            for (JsonElement element : releases) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject release = element.getAsJsonObject();
                if (!isBoolean(release, "draft", false) || !(isBoolean(release, "prerelease", false)
                    || this.allowExperimental && isBoolean(release, "prerelease", true))) {
                    continue;
                }
                final String tag = string(release, "tag_name");
                if (tag == null) {
                    continue;
                }
                final Matcher matcher = this.tagPattern.matcher(tag);
                if (!matcher.matches()) {
                    continue;
                }
                final int build;
                try {
                    build = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                final String releaseId = numericId(release);
                final String releaseApiUrl = this.repositoryApiUrl + "/releases/" + releaseId;
                if (releaseId == null || !releaseApiUrl.equals(string(release, "url"))
                    || !(releaseApiUrl + "/assets").equals(string(release, "assets_url"))
                    || !(this.repositoryWebUrl + "/releases/tag/" + tag).equals(string(release, "html_url"))) {
                    continue;
                }
                final LeavesBuildInfo candidate = releaseAsset(release, this.mcVersion, tag, matcher.group(2), build, this.repositoryApiUrl, this.repositoryWebUrl);
                if (candidate != null) {
                    final LeavesBuildInfo existing = this.builds.putIfAbsent(build, candidate);
                    if (existing != null && !existing.equals(candidate)) {
                        throw new IllegalArgumentException("Conflicting GitHub releases have the same build number");
                    }
                }
            }
        }

        LeavesBuildInfo newerBuild(String gitHash, int currentBuild) {
            if (!GIT_SHA.matcher(gitHash).matches() || currentBuild < 0) {
                return null;
            }
            final String currentSha = gitHash.toLowerCase(Locale.ROOT);
            for (LeavesBuildInfo candidate : this.builds.tailMap(currentBuild, false).descendingMap().values()) {
                if (!sameCommit(candidate.gitSha, currentSha)) {
                    return candidate;
                }
            }
            return null;
        }

        OptionalInt versionDistance(String gitHash, int currentBuild) {
            final LeavesBuildInfo current = this.builds.get(currentBuild);
            if (current == null || !GIT_SHA.matcher(gitHash).matches() || !sameCommit(current.gitSha, gitHash.toLowerCase(Locale.ROOT))) {
                return OptionalInt.empty();
            }
            final LeavesBuildInfo update = this.newerBuild(gitHash, currentBuild);
            if (update != null) {
                return OptionalInt.of(update.build - currentBuild);
            }
            return this.builds.lastKey() == currentBuild ? OptionalInt.of(0) : OptionalInt.empty();
        }

        String releasesPage() {
            return this.repositoryWebUrl + "/releases";
        }
    }

    private static boolean sameCommit(String first, String second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static LeavesBuildInfo releaseAsset(JsonObject release, String mcVersion, String tag, String sha, int build, String repositoryApiUrl, String repositoryWebUrl) {
        final JsonElement assets = release.get("assets");
        if (assets == null || !assets.isJsonArray()) {
            return null;
        }
        final String suffix = "-" + mcVersion + "-" + sha + ".jar";
        String expectedName = "SnowBall" + suffix;
        final String legacyName = "leaves" + suffix;
        JsonObject selected = null;
        JsonObject legacy = null;
        int expectedCount = 0;
        int legacyCount = 0;
        for (JsonElement element : assets.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject asset = element.getAsJsonObject();
            final String name = string(asset, "name");
            if (expectedName.equals(name)) {
                selected = asset;
                expectedCount++;
            } else if (legacyName.equals(name)) {
                legacy = asset;
                legacyCount++;
            }
        }
        // Accept a historical name only when it still identifies the exact commit in the tag.
        if (expectedCount == 0 && legacyCount == 1) {
            selected = legacy;
            expectedName = legacyName;
        } else if (expectedCount != 1) {
            return null;
        }
        final String assetId = numericId(selected);
        if (assetId == null || !(repositoryApiUrl + "/releases/assets/" + assetId).equals(string(selected, "url"))
            || !"uploaded".equals(string(selected, "state"))) {
            return null;
        }
        final String sha256 = releaseChecksum(selected, string(release, "body"), expectedName);
        if (sha256 == null) {
            return null;
        }
        final long size;
        try {
            final JsonElement value = selected.get("size");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            size = value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
        final URI expectedUrl = URI.create(repositoryWebUrl + "/releases/download/" + tag + "/" + expectedName);
        if (size <= 0 || size > MAX_JAR_BYTES || !expectedUrl.toString().equals(string(selected, "browser_download_url"))) {
            return null;
        }
        return new LeavesBuildInfo(build, sha, expectedName, sha256, expectedUrl, size);
    }

    private static String releaseChecksum(JsonObject asset, String body, String fileName) {
        String digest = null;
        JsonElement digestValue = asset.get("digest");
        if (digestValue != null && !digestValue.isJsonNull()) {
            String value = string(asset, "digest");
            if (value == null || !value.startsWith("sha256:") || !SHA256.matcher(value.substring(7)).matches()) {
                return null;
            }
            digest = value.substring(7).toLowerCase(Locale.ROOT);
        }
        String notesHash = null;
        if (body != null) {
            // Match the exact sha256sum line emitted by build.yml, never a hash for a different asset.
            Matcher checksum = Pattern.compile("(?m)^([^\\r\\n]+)  " + Pattern.quote(fileName) + "\\r?$").matcher(body);
            while (checksum.find()) {
                String hash = checksum.group(1);
                if (!SHA256.matcher(hash).matches()) {
                    return null;
                }
                hash = hash.toLowerCase(Locale.ROOT);
                if (notesHash != null && !notesHash.equals(hash)) {
                    return null;
                }
                notesHash = hash;
            }
        }
        if (digest != null && notesHash != null && !digest.equals(notesHash)) {
            return null;
        }
        return digest != null ? digest : notesHash;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static boolean isBoolean(JsonObject object, String key, boolean expected) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean() == expected;
    }

    private static HttpURLConnection openConnection(URI uri) throws IOException {
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    private static void configureConnection(HttpURLConnection connection) {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", "SnowBall-Updater");
    }

    static boolean isAllowedDownloadUri(URI uri, URI expectedUrl) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().equals(uri.getRawAuthority()) || uri.getRawFragment() != null) {
            return false;
        }
        if ("github.com".equals(uri.getHost())) {
            return uri.equals(expectedUrl) && uri.getRawQuery() == null && DOWNLOAD_PATH.matcher(uri.getRawPath()).matches();
        }
        return ("release-assets.githubusercontent.com".equals(uri.getHost()) || "objects.githubusercontent.com".equals(uri.getHost()))
            && RELEASE_ASSET_PATH.matcher(uri.getRawPath()).matches();
    }

    static void downloadAsset(LeavesBuildInfo buildInfo, Path destination, ConnectionFactory connections) throws IOException {
        URI uri = buildInfo.url;
        if (!"github.com".equals(uri.getHost()) || !isAllowedDownloadUri(uri, buildInfo.url) || !uri.getRawPath().endsWith("/" + buildInfo.fileName)) {
            throw new IOException("Invalid GitHub release asset URL");
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!isAllowedDownloadUri(uri, buildInfo.url)) {
                throw new IOException("Untrusted GitHub release download redirect");
            }
            HttpURLConnection connection = connections.open(uri);
            try {
                configureConnection(connection);
                connection.setRequestProperty("Accept", "application/octet-stream");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == MAX_REDIRECTS) {
                        throw new IOException("Invalid or excessive GitHub release download redirects");
                    }
                    try {
                        uri = uri.resolve(URI.create(location));
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Invalid GitHub release download redirect", e);
                    }
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("GitHub release asset returned HTTP " + status);
                }
                long contentLength = connection.getContentLengthLong();
                if (contentLength >= 0 && contentLength != buildInfo.size) {
                    throw new IOException("GitHub release asset size differs from its metadata");
                }
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(destination, CREATE_NEW, WRITE)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    for (int length; (length = input.read(buffer)) != -1; ) {
                        total += length;
                        if (total > buildInfo.size || System.nanoTime() > deadline) {
                            throw new IOException("GitHub release download exceeded its size or time limit");
                        }
                        output.write(buffer, 0, length);
                    }
                    if (total != buildInfo.size) {
                        throw new IOException("Incomplete GitHub release asset download");
                    }
                }
                if (!isFileValid(destination, buildInfo.sha256)) {
                    throw new IOException("SHA-256 check failed for downloaded release asset");
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many GitHub release download redirects");
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }

    record LeavesBuildInfo(int build, String gitSha, String fileName, String sha256, URI url, long size) {
    }
}
