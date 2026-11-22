/*
 * Adapted from Leavesclip v3.0.10 (da1712d92bc2ad7976b5f426a1f3225a675a74b4).
 * Copyright (c) 2021 Kyle Wood (DenWav)
 * Copyright (c) 2023 LeavesMC
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.leavesmc.leavesclip.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;

public class AutoUpdate {
    public static final Logger logger = new SimpleLogger("AutoUpdate");
    public static final String autoUpdateDir = "auto_update";
    public static String autoUpdateCorePath;
    public static boolean useAutoUpdateJar = false;

    private static final Path CORE_POINTER = Path.of(autoUpdateDir, "core.path");
    private static final String CLIP_VERSION = "/META-INF/leavesclip-version";
    private static final String BUILD_INFO = "/META-INF/build-info";
    private static final int MAX_METADATA_BYTES = 4096;
    private static ZipFile targetJar;

    public static void init() {
        final String corePath;
        try (var reader = Files.newBufferedReader(CORE_POINTER)) {
            corePath = reader.readLine();
        } catch (final NoSuchFileException ex) {
            return; // Normal on a fresh installation.
        } catch (final IOException ex) {
            fallback("Cannot read the staged core pointer: " + ex.getMessage());
            return;
        }
        if (corePath == null || corePath.isBlank()) {
            return; // The updater creates an empty pointer before its first update.
        }

        ZipFile candidate = null;
        try {
            final Path path = Path.of(corePath);
            if (!Files.isRegularFile(path)) {
                fallback("Staged server core is missing or is not a file: " + corePath);
                return;
            }
            candidate = new ZipFile(path.toFile());
            if (!Boolean.getBoolean("leavesclip.skip-leavesclip-version-check")) {
                final byte[] localVersion = metadata(AutoUpdate.class.getResourceAsStream(CLIP_VERSION));
                final byte[] stagedVersion = metadata(openEntry(candidate, CLIP_VERSION));
                if (localVersion == null || !Arrays.equals(localVersion, stagedVersion)) {
                    fallback("Staged server core requires a different Leavesclip version: " + corePath);
                    return;
                }
            }

            final Build launched = Build.parse(metadata(AutoUpdate.class.getResourceAsStream(BUILD_INFO)));
            final Build staged = Build.parse(metadata(openEntry(candidate, BUILD_INFO)));
            // A filename or mtime says nothing about what was actually launched. Only a
            // strictly newer build from the same project and Minecraft line can take over.
            if (launched == null || staged == null
                || !launched.project.equals(staged.project) || !launched.minecraft.equals(staged.minecraft)) {
                fallback("Cannot compare the staged and launched builds: " + corePath);
                return;
            }
            if (staged.number == launched.number) {
                return;
            }
            if (staged.number < launched.number) {
                fallback("Staged build " + staged.number + " is older than launched build " + launched.number + ": " + corePath);
                return;
            }
            for (final String name : new String[] {"META-INF/main-class", "META-INF/versions.list", "META-INF/libraries.list"}) {
                final ZipEntry entry = candidate.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0) {
                    fallback("Staged server core is missing " + name + ": " + corePath);
                    return;
                }
            }

            targetJar = candidate;
            candidate = null;
            autoUpdateCorePath = corePath;
            useAutoUpdateJar = true;
            logger.info("Using server core: {}", autoUpdateCorePath);
        } catch (final IOException | InvalidPathException ex) {
            fallback("Cannot open staged server core " + corePath + ": " + ex.getMessage());
        } finally {
            closeArchive(candidate);
        }
    }

    public static void close() {
        final ZipFile archive = targetJar;
        targetJar = null;
        autoUpdateCorePath = null;
        useAutoUpdateJar = false;
        closeArchive(archive);
    }

    private static void closeArchive(final ZipFile archive) {
        if (archive != null) {
            try {
                archive.close();
            } catch (final IOException ex) {
                logger.warn("Could not close staged server core", ex);
            }
        }
    }

    private static void fallback(final String reason) {
        // Leave the pointer untouched, including when the updater may have just rewritten it.
        logger.warn("{}. Using the launched JAR.", reason);
    }

    public static InputStream getResourceAsStreamFromTargetJar(final String name) {
        try {
            return openResourceFromTargetJar(name);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Cannot read " + name + " from " + autoUpdateCorePath, ex);
        }
    }

    public static InputStream openResourceFromTargetJar(final String name) throws IOException {
        if (targetJar == null) {
            return AutoUpdate.class.getResourceAsStream(name);
        }
        // A missing entry is normal: the caller may fall back to the verified Mojang archive.
        // Do not mix resources with the launched JAR when a staged archive was selected.
        return openEntry(targetJar, name);
    }

    private static InputStream openEntry(final ZipFile archive, final String name) throws IOException {
        final ZipEntry entry = archive.getEntry(name.startsWith("/") ? name.substring(1) : name);
        return entry == null || entry.isDirectory() ? null : archive.getInputStream(entry);
    }

    private static byte[] metadata(final InputStream input) throws IOException {
        if (input == null) {
            return null;
        }
        try (input) {
            final byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) {
                throw new IOException("Build metadata exceeds " + MAX_METADATA_BYTES + " bytes");
            }
            return bytes;
        }
    }

    private record Build(String project, String minecraft, int number) {
        private static Build parse(final byte[] metadata) {
            if (metadata == null) {
                return null;
            }
            final String[] fields = new String(metadata, StandardCharsets.UTF_8).strip().split("\t", -1);
            if (fields.length != 3 || fields[0].isBlank() || fields[1].isBlank() || !fields[2].matches("[0-9]+")) {
                return null;
            }
            try {
                return new Build(fields[0], fields[1], Integer.parseInt(fields[2]));
            } catch (final NumberFormatException ex) {
                return null;
            }
        }
    }
}
