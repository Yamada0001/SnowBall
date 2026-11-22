package org.snowballmc.snowball.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Suite
@SelectClasses(LeavesUpdateHelperTest.class)
public class LeavesUpdateHelperTestSuite {
}

class LeavesUpdateHelperTest {
    private static final String CANONICAL_SLUG = "Yamada0001/SnowBall";
    private static final String RENAMED_SLUG = "Yamada0001/Fixture-Renamed";
    private static final String REPOSITORY_API = "https://api.github.com/repositories/1458310160";
    private static final String MC_VERSION = "26.3";
    private static final String CURRENT_SHA = "1234567";
    private static final String NEW_SHA = "abcdef0";
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"; // SHA-256 of abc
    private static final String OTHER_HASH = "0".repeat(64);
    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.UTF_8);
    private static final URI CDN_URL = URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1458310160/abc-def?signature=fixture");

    @TempDir
    Path directory;

    @Test
    void fetchesAllPagesByRepositoryIdAndUsesTheVerifiedCanonicalSlug() throws IOException {
        JsonArray firstPage = array(release(100, CURRENT_SHA, RENAMED_SLUG, "SnowBall-26.3-1234567.jar"));
        for (int index = 1; index < 100; index++) {
            firstPage.add(JsonNull.INSTANCE);
        }
        JsonArray lastPage = array(release(104, NEW_SHA, RENAMED_SLUG, "leaves-26.3-abcdef0.jar"));
        FixtureConnection metadata = jsonResponse(repository(RENAMED_SLUG).toString());
        Queue<FixtureConnection> responses = new ArrayDeque<>(List.of(metadata, jsonResponse(firstPage.toString()), jsonResponse(lastPage.toString())));
        List<URI> requested = new ArrayList<>();
        LeavesUpdateHelper.ReleaseCatalog catalog = LeavesUpdateHelper.getReleaseCatalog(MC_VERSION, false, uri -> {
            requested.add(uri);
            return responses.remove();
        });
        assertEquals(List.of(URI.create(REPOSITORY_API), URI.create(REPOSITORY_API + "/releases?per_page=100&page=1"),
            URI.create(REPOSITORY_API + "/releases?per_page=100&page=2")), requested);
        assertEquals("https://github.com/" + RENAMED_SLUG + "/releases", catalog.releasesPage());
        LeavesUpdateHelper.LeavesBuildInfo update = catalog.newerBuild(CURRENT_SHA, 100);
        assertNotNull(update);
        assertEquals(URI.create(catalog.releasesPage() + "/download/26.3-104-abcdef0/leaves-26.3-abcdef0.jar"), update.url());
        assertTrue(LeavesUpdateHelper.isAllowedDownloadUri(update.url(), update.url()));
        assertEquals(4, catalog.versionDistance(CURRENT_SHA, 100).orElseThrow());
        assertTrue(metadata.disconnected);
        assertFalse(metadata.getInstanceFollowRedirects());
    }

    @Test
    void rejectsWrongRepositoryIdAndOwnerBeforeListingReleases() {
        JsonObject wrongId = repository(CANONICAL_SLUG);
        wrongId.addProperty("id", 1458310161L);
        JsonObject stringId = repository(CANONICAL_SLUG);
        stringId.addProperty("id", "1458310160");
        JsonObject wrongOwner = repository(CANONICAL_SLUG);
        wrongOwner.getAsJsonObject("owner").addProperty("login", "another");
        for (JsonObject repository : List.of(wrongId, stringId, wrongOwner)) {
            FixtureConnection response = jsonResponse(repository.toString());
            List<URI> requested = new ArrayList<>();
            assertThrows(IOException.class, () -> LeavesUpdateHelper.getReleaseCatalog(MC_VERSION, false, uri -> {
                requested.add(uri);
                return response;
            }));
            assertEquals(List.of(URI.create(REPOSITORY_API)), requested);
            assertTrue(response.disconnected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"another/SnowBall", "Yamada0001/..", "Yamada0001/a/b", "Yamada0001/SnowBall?invalid", "Yamada0001/Snow%42all"})
    void rejectsInvalidCanonicalSlugs(String slug) {
        assertThrows(IOException.class, () -> LeavesUpdateHelper.identifyRepository(repository(slug)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "html_url"})
    void rejectsInconsistentRepositoryUrls(String key) {
        JsonObject repository = repository(CANONICAL_SLUG);
        repository.addProperty(key, repository.get(key).getAsString().replace(CANONICAL_SLUG, RENAMED_SLUG));
        assertThrows(IOException.class, () -> LeavesUpdateHelper.identifyRepository(repository));
    }

    @Test
    void neverFollowsGitHubApiRedirects() {
        FixtureConnection identity = jsonResponse(repository(CANONICAL_SLUG).toString());
        FixtureConnection redirect = connection(302, "https://api.github.com/repos/" + CANONICAL_SLUG + "/releases", new byte[0], -1);
        Queue<FixtureConnection> responses = new ArrayDeque<>(List.of(identity, redirect));
        List<URI> requested = new ArrayList<>();
        assertThrows(IOException.class, () -> LeavesUpdateHelper.getReleaseCatalog(MC_VERSION, false, uri -> {
            requested.add(uri);
            return responses.remove();
        }));
        assertEquals(List.of(URI.create(REPOSITORY_API), URI.create(REPOSITORY_API + "/releases?per_page=100&page=1")), requested);
        assertFalse(redirect.getInstanceFollowRedirects());
        assertTrue(redirect.disconnected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "assets_url", "html_url"})
    void rejectsReleaseUrlsNotBoundToTheCanonicalRepository(String key) {
        JsonObject release = release(101, NEW_SHA);
        release.addProperty(key, release.get(key).getAsString().replace(CANONICAL_SLUG, RENAMED_SLUG));
        assertNull(select(release));
    }

    @Test
    void rejectsIncorrectAssetApiPathEvenWithAValidBrowserUrl() {
        JsonObject release = release(101, NEW_SHA);
        asset(release).addProperty("url", "https://api.github.com/repos/" + CANONICAL_SLUG + "/releases/assets/1");
        assertNull(select(release));
    }

    @Test
    void comparesOnlyKnownLocalBuildAndCommitForTheExactMinecraftVersion() {
        LeavesUpdateHelper.ReleaseCatalog catalog = new LeavesUpdateHelper.ReleaseCatalog(CANONICAL_SLUG, MC_VERSION, false);
        JsonObject experimental = release(105, "fedcba0");
        experimental.addProperty("prerelease", true);
        catalog.addPage(array(experimental, release(101, NEW_SHA), release(100, CURRENT_SHA)));
        assertEquals(1, catalog.versionDistance(CURRENT_SHA, 100).orElseThrow());
        assertEquals(0, catalog.versionDistance("ABCDEF0123456789ABCDEF0123456789ABCDEF01", 101).orElseThrow());
        assertTrue(catalog.versionDistance(NEW_SHA, 100).isEmpty());
        assertTrue(catalog.versionDistance(NEW_SHA, 102).isEmpty());
        assertTrue(catalog.versionDistance("fedcba0", 105).isEmpty());
        LeavesUpdateHelper.ReleaseCatalog incompatible = new LeavesUpdateHelper.ReleaseCatalog(CANONICAL_SLUG, "26.2", false);
        incompatible.addPage(array(release(101, NEW_SHA)));
        assertTrue(incompatible.versionDistance(NEW_SHA, 101).isEmpty());
    }

    @Test
    void reportsKnownBuildAsCurrentWhenNewerReleasesOnlyRedistributeItsCommit() {
        LeavesUpdateHelper.ReleaseCatalog catalog = new LeavesUpdateHelper.ReleaseCatalog(CANONICAL_SLUG, MC_VERSION, false);
        catalog.addPage(array(release(103, "1234567890abcdef1234567890abcdef12345678"),
            release(101, CURRENT_SHA), release(100, CURRENT_SHA), release(99, NEW_SHA)));
        String localSha = "1234567890ABCDEF1234567890ABCDEF12345678";
        assertNull(catalog.newerBuild(localSha, 100));
        assertEquals(0, catalog.versionDistance(localSha, 100).orElseThrow());
        assertEquals(0, catalog.versionDistance(localSha, 103).orElseThrow());
        assertTrue(catalog.versionDistance(localSha, 102).isEmpty());
    }

    @Test
    void reportsDistanceToHighestNewerBuildWithADistinctCommit() {
        LeavesUpdateHelper.ReleaseCatalog catalog = new LeavesUpdateHelper.ReleaseCatalog(CANONICAL_SLUG, MC_VERSION, false);
        catalog.addPage(array(release(105, CURRENT_SHA), release(102, NEW_SHA), release(100, CURRENT_SHA),
            release(104, "fedcba0"), release(101, CURRENT_SHA)));
        assertEquals(104, catalog.newerBuild(CURRENT_SHA, 100).build());
        assertEquals(4, catalog.versionDistance(CURRENT_SHA, 100).orElseThrow());
        assertTrue(catalog.versionDistance(CURRENT_SHA, 99).isEmpty());
    }

    @Test
    void selectsHighestBuildRegardlessOfReleaseOrder() {
        LeavesUpdateHelper.LeavesBuildInfo selected = select(release(103, NEW_SHA), release(101, "fedcba0"), release(102, "bcd1234"));
        assertNotNull(selected);
        assertEquals(103, selected.build());
        assertEquals("SnowBall-26.3-abcdef0.jar", selected.fileName());
        assertEquals(HASH, selected.sha256());
        assertEquals(URI.create(LeavesUpdateHelper.DOWNLOAD_REPOSITORY + "/releases/download/26.3-103-abcdef0/SnowBall-26.3-abcdef0.jar"), selected.url());
    }

    @Test
    void neverSelectsCurrentOrOlderBuildEvenWithDifferentCommit() {
        assertNull(select(release(99, NEW_SHA), release(100, NEW_SHA)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "1234567890abcdef1234567890abcdef1234567890"})
    void doesNotUpdateToTheSameCommit(String sha) {
        assertNull(select(release(101, sha)));
    }

    @Test
    void detectsSameCommitWithFullLocalAndShortRemoteSha() {
        JsonArray releases = array(release(101, NEW_SHA));
        assertNull(selectRelease(releases, MC_VERSION, "abcdef0123456789abcdef0123456789abcdef0123", 100, false));
    }

    @Test
    void sameCommitComparisonIgnoresLocalHexCase() {
        assertNull(selectRelease(array(release(101, NEW_SHA)), MC_VERSION, "ABCDEF0", 100, false));
    }

    @Test
    void excludesDraftsEvenWhenExperimentalIsAllowed() {
        JsonObject draft = release(101, NEW_SHA);
        draft.addProperty("draft", true);
        assertNull(selectRelease(array(draft), MC_VERSION, CURRENT_SHA, 100, true));
    }

    @Test
    void honorsAllowExperimentalAndCanSelectOlderStableRelease() {
        JsonObject experimental = release(102, "fedcba0");
        experimental.addProperty("prerelease", true);
        JsonObject stable = release(101, NEW_SHA);
        assertEquals(101, select(experimental, stable).build());
        assertEquals(102, selectRelease(array(stable, experimental), MC_VERSION, CURRENT_SHA, 100, true).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "prerelease"})
    void rejectsMissingOrCoercedReleaseFlags(String key) {
        JsonObject release = release(101, NEW_SHA);
        release.remove(key);
        assertNull(select(release));
        release.addProperty(key, "false");
        assertNull(select(release));
    }

    @ParameterizedTest
    @ValueSource(strings = {"26.2-101-abcdef0", "26.3-abcdef0", "26.3-DEV-abcdef0", "26.3-0-abcdef0", "26.3-0101-abcdef0", "26.3-2147483648-abcdef0", "26.3-101-notasha", "26.3-101-abcdef0/other"})
    void requiresExactWorkflowTagWithBuildAndSha(String tag) {
        JsonObject release = release(101, NEW_SHA);
        release.addProperty("tag_name", tag);
        assertNull(select(release));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../26.3", "26.3/path", "26.3?query", "26.3%2fpath"})
    void rejectsUnsafeMinecraftVersion(String version) {
        assertNull(selectRelease(array(release(101, NEW_SHA)), version, CURRENT_SHA, 100, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdef0", "", "not-a-commit"})
    void missingOrMatchingLocalIdentityDoesNotProduceAnUpdate(String localSha) {
        assertNull(selectRelease(array(release(101, NEW_SHA)), MC_VERSION, localSha, 100, false));
    }

    @Test
    void acceptsExactWorkflowNotesChecksumWhenGithubDigestIsUnavailable() {
        JsonObject release = release(101, NEW_SHA);
        asset(release).add("digest", JsonNull.INSTANCE);
        release.addProperty("body", notes(fileName(release), HASH));
        assertEquals(HASH, select(release).sha256());
        asset(release).remove("digest");
        assertEquals(HASH, select(release).sha256());
    }

    @Test
    void acceptsConsistentDigestAndCrLfNotesIgnoringHashCase() {
        JsonObject release = release(101, NEW_SHA);
        asset(release).addProperty("digest", "sha256:" + HASH.toUpperCase(java.util.Locale.ROOT));
        release.addProperty("body", notes(fileName(release), HASH).replace("\n", "\r\n"));
        assertEquals(HASH, select(release).sha256());
    }

    @Test
    void rejectsMissingOrConflictingChecksumAuthorities() {
        JsonObject release = release(101, NEW_SHA);
        asset(release).remove("digest");
        assertNull(select(release));
        release.addProperty("body", notes(fileName(release), OTHER_HASH));
        asset(release).addProperty("digest", "sha256:" + HASH);
        assertNull(select(release));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "sha1:0123456789", "sha256:abc", "sha256:zzzz", "SHA256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"})
    void malformedGithubDigestCannotFallBackToNotes(String digest) {
        JsonObject release = release(101, NEW_SHA);
        asset(release).addProperty("digest", digest);
        release.addProperty("body", notes(fileName(release), HASH));
        assertNull(select(release));
    }

    @Test
    void checksumMustNameTheExactSelectedAsset() {
        JsonObject release = release(101, NEW_SHA);
        asset(release).remove("digest");
        release.addProperty("body", notes("leaves-26.3-fedcba0.jar", HASH));
        assertNull(select(release));
        release.addProperty("body", HASH + "  " + fileName(release) + ".other\n");
        assertNull(select(release));
        release.addProperty("body", "- " + HASH + "  " + fileName(release) + "\n");
        assertNull(select(release));
    }

    @Test
    void rejectsMalformedAndContradictoryExactNotesChecksums() {
        JsonObject release = release(101, NEW_SHA);
        release.addProperty("body", notes(fileName(release), "not-a-hash"));
        assertNull(select(release));
        release.addProperty("body", notes(fileName(release), HASH) + notes(fileName(release), OTHER_HASH));
        assertNull(select(release));
    }

    @Test
    void rejectsLegacyFilenameWithoutExactTagCommitEvenWithExactNotesChecksum() {
        JsonObject release = release(101, NEW_SHA, CANONICAL_SLUG, "leaves-26.3.jar");
        release.addProperty("body", notes(fileName(release), HASH));
        assertNull(select(release));
    }

    @Test
    void acceptsHistoricalFilenameWithExactTagCommitAndChecksum() {
        JsonObject release = legacyRelease();
        assertEquals("leaves-26.3-abcdef0.jar", select(release).fileName());
        asset(release).remove("digest");
        assertNull(select(release));
        release.addProperty("body", notes(fileName(release), HASH));
        assertEquals("leaves-26.3-abcdef0.jar", select(release).fileName());
        release.getAsJsonArray("assets").add(asset(release).deepCopy());
        assertNull(select(release));
    }

    @Test
    void prefersSnowBallAssetAndDoesNotFallBackIfItIsInvalid() {
        JsonObject release = release(101, NEW_SHA);
        release.getAsJsonArray("assets").add(asset(legacyRelease()).deepCopy());
        assertEquals("SnowBall-26.3-abcdef0.jar", select(release).fileName());
        asset(release).addProperty("digest", "invalid");
        assertNull(select(release));
    }

    @Test
    void rejectsDuplicateOrUnuploadedMatchingAsset() {
        JsonObject release = release(101, NEW_SHA);
        release.getAsJsonArray("assets").add(asset(release).deepCopy());
        assertNull(select(release));
        release = release(101, NEW_SHA);
        asset(release).addProperty("state", "starter");
        assertNull(select(release));
    }

    @Test
    void conflictingSameBuildReleasesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> select(release(101, NEW_SHA), release(101, "fedcba0")));
    }

    @Test
    void rejectsWrongVersionAndWrongCommitAssetNames() {
        JsonObject release = release(101, NEW_SHA);
        asset(release).addProperty("name", "leaves-26.2-abcdef0.jar");
        assertNull(select(release));
        asset(release).addProperty("name", "leaves-26.3-fedcba0.jar");
        assertNull(select(release));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/SnowBall.jar",
        "https://github.com/another/SnowBall/releases/download/26.3-101-abcdef0/SnowBall-26.3-abcdef0.jar",
        "https://github.com/Yamada0001/Fixture-Renamed/releases/download/26.3-101-abcdef0/SnowBall-26.3-abcdef0.jar",
        "https://github.com/Yamada0001/SnowBall/releases/download/wrong-tag/SnowBall-26.3-abcdef0.jar",
        "https://github.com/Yamada0001/SnowBall/releases/download/26.3-101-abcdef0/SnowBall-26.3-abcdef0.jar?mirror=true",
        "https://github.com/Yamada0001/SnowBall/releases/download/26.3-101-abcdef0/../SnowBall-26.3-abcdef0.jar"
    })
    void rejectsUnapprovedOrUnexpectedBrowserDownloadUrl(String url) {
        JsonObject release = release(101, NEW_SHA);
        asset(release).addProperty("browser_download_url", url);
        assertNull(select(release));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1073741825L})
    void rejectsInvalidAssetSize(long size) {
        JsonObject release = release(101, NEW_SHA);
        asset(release).addProperty("size", size);
        assertNull(select(release));
    }

    @Test
    void rejectsFractionalOrStringSize() {
        JsonObject release = release(101, NEW_SHA);
        asset(release).addProperty("size", 3.5);
        assertNull(select(release));
        asset(release).addProperty("size", "3");
        assertNull(select(release));
    }

    @Test
    void validatesActualSha256WithoutAcceptingMissingOrMalformedHashes() throws IOException {
        Path file = directory.resolve("leaves.jar");
        Files.write(file, CONTENT);
        assertTrue(LeavesUpdateHelper.isFileValid(file, HASH));
        assertFalse(LeavesUpdateHelper.isFileValid(file, OTHER_HASH));
        assertFalse(LeavesUpdateHelper.isFileValid(file, null));
        assertFalse(LeavesUpdateHelper.isFileValid(file, "abc"));
    }

    @Test
    void followsOnlyGithubAssetRedirectAndSetsTimeouts() throws IOException {
        LeavesUpdateHelper.LeavesBuildInfo selected = select(release(101, NEW_SHA));
        FixtureConnection redirect = connection(302, CDN_URL.toString(), new byte[0], -1);
        FixtureConnection download = connection(200, null, CONTENT, CONTENT.length);
        Queue<FixtureConnection> responses = new ArrayDeque<>(List.of(redirect, download));
        List<URI> requested = new ArrayList<>();
        Path file = directory.resolve("leaves.cache");
        LeavesUpdateHelper.downloadAsset(selected, file, uri -> {
            requested.add(uri);
            return responses.remove();
        });
        assertEquals(List.of(selected.url(), CDN_URL), requested);
        assertArrayEquals(CONTENT, Files.readAllBytes(file));
        assertFalse(redirect.getInstanceFollowRedirects());
        assertTrue(redirect.getConnectTimeout() > 0);
        assertTrue(download.getReadTimeout() > 0);
        assertTrue(redirect.disconnected);
        assertTrue(download.disconnected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://release-assets.githubusercontent.com/github-production-release-asset/1458310160/abc-def", "https://example.com/github-production-release-asset/1458310160/abc-def", "https://github.com.evil.example/github-production-release-asset/1458310160/abc-def", "https://github.com/another/repo/releases/download/tag/leaves.jar", "https://release-assets.githubusercontent.com@evil.example/github-production-release-asset/1458310160/abc-def", "https://release-assets.githubusercontent.com:444/github-production-release-asset/1458310160/abc-def", "https://release-assets.githubusercontent.com/unexpected/1458310160/abc-def", "https://release-assets.githubusercontent.com/github-production-release-asset/1458310160/%2e%2e", "https://release-assets.githubusercontent.com/github-production-release-asset/1458310160/abc-def#fragment", "file:///tmp/leaves.jar"})
    void rejectsUntrustedRedirectBeforeOpeningIt(String location) {
        FixtureConnection redirect = connection(302, location, new byte[0], -1);
        List<URI> requested = new ArrayList<>();
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(select(release(101, NEW_SHA)), directory.resolve("leaves.cache"), uri -> {
            requested.add(uri);
            return redirect;
        }));
        assertEquals(1, requested.size());
        assertTrue(redirect.disconnected);
    }

    @Test
    void acceptsTheOlderGithubObjectsReleaseAssetHost() {
        URI initial = select(release(101, NEW_SHA)).url();
        assertFalse(LeavesUpdateHelper.isAllowedDownloadUri(URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/999/abc-def?fixture=true"), initial));
        assertFalse(LeavesUpdateHelper.isAllowedDownloadUri(URI.create("https://objects.githubusercontent.com/github-production-release-asset-2e65be/999/abc-def?fixture=true"), initial));
        assertTrue(LeavesUpdateHelper.isAllowedDownloadUri(URI.create("https://objects.githubusercontent.com/github-production-release-asset-2e65be/1458310160/abc-def?fixture=true"), initial));
        assertFalse(LeavesUpdateHelper.isAllowedDownloadUri(URI.create("https://github.com/another/repo/releases/download/26.3-101-abcdef0/leaves-26.3.jar"), initial));
    }

    @Test
    void rejectsRedirectLoopsMissingLocationsAndErrorResponses() {
        LeavesUpdateHelper.LeavesBuildInfo selected = select(release(101, NEW_SHA));
        int[] opens = {0};
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(selected, directory.resolve("loop.cache"), uri -> {
            opens[0]++;
            return connection(302, selected.url().toString(), new byte[0], -1);
        }));
        assertEquals(4, opens[0]);
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(selected, directory.resolve("missing.cache"), uri -> connection(302, null, new byte[0], -1)));
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(selected, directory.resolve("error.cache"), uri -> connection(404, null, new byte[0], -1)));
    }

    @Test
    void rejectsTruncatedOversizedAndCorruptedDownloads() {
        LeavesUpdateHelper.LeavesBuildInfo selected = select(release(101, NEW_SHA));
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(selected, directory.resolve("length.cache"), uri -> connection(200, null, CONTENT, 2)));
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(selected, directory.resolve("short.cache"), uri -> connection(200, null, new byte[]{1}, -1)));
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(selected, directory.resolve("large.cache"), uri -> connection(200, null, new byte[]{1, 2, 3, 4}, -1)));
        assertThrows(IOException.class, () -> LeavesUpdateHelper.downloadAsset(selected, directory.resolve("corrupt.cache"), uri -> connection(200, null, new byte[]{1, 2, 3}, 3)));
    }

    private static LeavesUpdateHelper.LeavesBuildInfo select(JsonObject... releases) {
        return selectRelease(array(releases), MC_VERSION, CURRENT_SHA, 100, false);
    }

    private static LeavesUpdateHelper.LeavesBuildInfo selectRelease(JsonArray releases, String mcVersion, String gitHash, int currentBuild, boolean allowExperimental) {
        LeavesUpdateHelper.ReleaseCatalog catalog = new LeavesUpdateHelper.ReleaseCatalog(CANONICAL_SLUG, mcVersion, allowExperimental);
        catalog.addPage(releases);
        return catalog.newerBuild(gitHash, currentBuild);
    }

    private static JsonArray array(JsonObject... releases) {
        JsonArray array = new JsonArray();
        for (JsonObject release : releases) {
            array.add(release);
        }
        return array;
    }

    private static JsonObject release(int build, String sha) {
        return release(build, sha, CANONICAL_SLUG, "SnowBall-" + MC_VERSION + "-" + sha + ".jar");
    }

    private static JsonObject release(int build, String sha, String slug, String fileName) {
        String tag = MC_VERSION + "-" + build + "-" + sha;
        String api = "https://api.github.com/repos/" + slug + "/releases";
        String web = "https://github.com/" + slug + "/releases";
        JsonObject release = new JsonObject();
        release.addProperty("id", 1000 + build);
        release.addProperty("url", api + "/" + (1000 + build));
        release.addProperty("assets_url", api + "/" + (1000 + build) + "/assets");
        release.addProperty("html_url", web + "/tag/" + tag);
        release.addProperty("tag_name", tag);
        release.addProperty("draft", false);
        release.addProperty("prerelease", false);
        release.addProperty("body", "");
        JsonObject asset = new JsonObject();
        asset.addProperty("id", 2000 + build);
        asset.addProperty("url", api + "/assets/" + (2000 + build));
        asset.addProperty("name", fileName);
        asset.addProperty("state", "uploaded");
        asset.addProperty("size", CONTENT.length);
        asset.addProperty("digest", "sha256:" + HASH);
        asset.addProperty("browser_download_url", web + "/download/" + tag + "/" + fileName);
        JsonArray assets = new JsonArray();
        assets.add(asset);
        release.add("assets", assets);
        return release;
    }

    private static JsonObject legacyRelease() {
        return release(101, NEW_SHA, CANONICAL_SLUG, "leaves-" + MC_VERSION + "-" + NEW_SHA + ".jar");
    }

    private static JsonObject asset(JsonObject release) {
        return release.getAsJsonArray("assets").get(0).getAsJsonObject();
    }

    private static String fileName(JsonObject release) {
        return asset(release).get("name").getAsString();
    }

    private static String notes(String fileName, String hash) {
        return "### 校验\n\n```\n" + hash + "  " + fileName + "\n```\n";
    }

    private static JsonObject repository(String slug) {
        JsonObject repository = new JsonObject();
        repository.addProperty("id", 1458310160L);
        repository.addProperty("full_name", slug);
        repository.addProperty("url", "https://api.github.com/repos/" + slug);
        repository.addProperty("html_url", "https://github.com/" + slug);
        JsonObject owner = new JsonObject();
        owner.addProperty("login", "Yamada0001");
        repository.add("owner", owner);
        return repository;
    }

    private static FixtureConnection jsonResponse(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return connection(200, null, bytes, bytes.length);
    }

    private static FixtureConnection connection(int status, String location, byte[] content, long contentLength) {
        try {
            return new FixtureConnection(status, location, content, contentLength);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // All HTTP responses are in memory. No test opens a socket or calls the production connection factory.
    private static final class FixtureConnection extends HttpURLConnection {
        private final int status;
        private final String location;
        private final byte[] content;
        private final long contentLength;
        private boolean disconnected;

        private FixtureConnection(int status, String location, byte[] content, long contentLength) throws IOException {
            super(URI.create(LeavesUpdateHelper.DOWNLOAD_REPOSITORY).toURL());
            this.status = status;
            this.location = location;
            this.content = content;
            this.contentLength = contentLength;
        }

        @Override
        public int getResponseCode() {
            return this.status;
        }

        @Override
        public String getHeaderField(String name) {
            return "Location".equals(name) ? this.location : null;
        }

        @Override
        public long getContentLengthLong() {
            return this.contentLength;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(this.content);
        }

        @Override
        public void disconnect() {
            this.disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
