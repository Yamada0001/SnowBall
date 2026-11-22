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
package org.leavesmc.leavesclip.patch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.leavesmc.leavesclip.update.AutoUpdate;

public record FileEntry(byte[] hash, String id, String path) {
    private static final int MAX_ATTEMPTS = 3;

    public static FileEntry[] parse(final BufferedReader reader) throws IOException {
        final List<FileEntry> result = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            final String[] parts = line.split("\t");
            if (parts.length != 3) {
                throw new IllegalStateException("Malformed library entry: " + line);
            }
            result.add(new FileEntry(Util.fromHex(parts[0]), parts[1], parts[2]));
        }
        return result.toArray(FileEntry[]::new);
    }

    public void extractFile(
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final String targetName,
        final Path originalRootDir,
        final String baseDir,
        final Path outputDir
    ) throws IOException {
        for (final PatchEntry patch : patches) {
            if (patch.location().equals(targetName) && patch.outputPath().equals(this.path)) {
                return;
            }
        }

        final Path outputFile = outputDir.resolve(this.path);
        final String source = Util.endingSlash(baseDir) + this.path;
        IOException failure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; ++attempt) {
            try {
                if (!Files.exists(outputFile) || !Arrays.equals(this.hash, sha256(outputFile))) {
                    this.installVerified(originalRootDir, source, outputFile);
                }
                urls.put(this.path, outputFile.toUri().toURL());
                return;
            } catch (final IOException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    ex.addSuppressed(failure);
                    failure = ex;
                }
            }
        }
        throw new IOException("Could not extract " + this.id + " to " + outputFile.toAbsolutePath()
            + " after " + MAX_ATTEMPTS + " attempts (expected SHA-256 " + HexFormat.of().formatHex(this.hash) + "): "
            + failure.getMessage(), failure);
    }

    private void installVerified(final Path originalRootDir, final String source, final Path outputFile) throws IOException {
        Files.createDirectories(outputFile.getParent());
        final Path temporary = Files.createTempFile(outputFile.getParent(), ".leavesclip-", ".part");
        try {
            try (InputStream input = openSource(originalRootDir, source)) {
                // Copy to EOF instead of relying on a single FileChannel.transferFrom call.
                try (var output = Files.newOutputStream(temporary)) {
                    input.transferTo(output);
                }
            }
            final byte[] actual = sha256(temporary);
            if (!Arrays.equals(this.hash, actual)) {
                throw new IOException("SHA-256 mismatch for " + source + " (expected "
                    + HexFormat.of().formatHex(this.hash) + ", got " + HexFormat.of().formatHex(actual) + ")");
            }
            // Never truncate a published dependency, even during simultaneous extraction.
            Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException | RuntimeException | Error ex) {
            try {
                Files.deleteIfExists(temporary);
            } catch (final IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    private static InputStream openSource(final Path originalRootDir, final String source) throws IOException {
        final InputStream embedded = AutoUpdate.openResourceFromTargetJar(source);
        if (embedded != null) {
            return embedded;
        }
        if (originalRootDir == null) {
            throw new IOException("Missing archive entry " + source + " and no original JAR provided");
        }
        return Files.newInputStream(originalRootDir.resolve(source));
    }

    private static byte[] sha256(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex); // SHA-256 is required by the JVM.
        }
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[16 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }
}
