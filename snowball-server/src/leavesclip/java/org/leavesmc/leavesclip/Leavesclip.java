/*
 * Adapted from Leavesclip v3.0.10 (da1712d92bc2ad7976b5f426a1f3225a675a74b4).
 * Copyright (c) 2019 Kyle Wood (DemonWav)
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
package org.leavesmc.leavesclip;

import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.leavesmc.leavesclip.mixin.*;
import org.leavesmc.leavesclip.mixin.plugins.condition.BuildInfoInjector;
import org.leavesmc.leavesclip.patch.DownloadContext;
import org.leavesmc.leavesclip.patch.FileEntry;
import org.leavesmc.leavesclip.patch.PatchEntry;
import org.leavesmc.leavesclip.patch.Util;
import org.leavesmc.leavesclip.update.AutoUpdate;
import org.leavesmc.plugin.mixin.condition.condition.ConditionChecker;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;

public final class Leavesclip {
    public static final Logger logger = new SimpleLogger("Leavesclip");

    public static void main(final String[] args) {
        if (Path.of("").toAbsolutePath().toString().contains("!")) {
            logger.error("Leavesclip may not run in a directory containing '!'. Please rename the affected folder");
            System.exit(1);
        }

        final URLClassLoader classLoader;
        final String mainClassName;
        try {
            if (!Boolean.getBoolean("leavesclip.disable.auto-update")) {
                AutoUpdate.init();
            }
            final URL[] setupClasspathUrls = setupClasspath();
            if (Boolean.getBoolean("paperclip.patchonly") || Boolean.getBoolean("leavesclip.patchonly")) {
                return;
            }
            classLoader = createClassLoader(setupClasspathUrls);
            mainClassName = findMainClass();
        } finally {
            // All target resources (including the Mixin build info) have been consumed.
            // Do not leave a Windows handle open on the staged JAR while the server runs.
            AutoUpdate.close();
        }

        logger.info("Starting " + mainClassName);
        final Thread runThread = new Thread(() -> {
            try {
                final Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
                final var mainHandle = MethodHandles.lookup()
                    .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                    .asFixedArity();
                mainHandle.invoke(args);
            } catch (final Throwable failure) {
                // An uncaught exception on ServerMain does not set the JVM's exit status.
                failure.printStackTrace(System.err);
                System.exit(1);
            }
        }, "ServerMain");
        runThread.setContextClassLoader(classLoader);
        runThread.start();
    }

    private static URLClassLoader createClassLoader(final URL[] setupClasspathUrls) {
        if (!Boolean.getBoolean("leavesclip.enable.mixin")) {
            return new URLClassLoader(setupClasspathUrls, Leavesclip.class.getClassLoader().getParent());
        }

        BuildInfoInjector.inject();
        overrideAsmVersion();
        PluginResolver.extractMixins();
        MixinJarResolver.resolveMixinJars();

        System.setProperty("mixin.bootstrapService", MixinServiceKnotBootstrap.class.getName());
        System.setProperty("mixin.service", MixinServiceKnot.class.getName());

        final URL[] classpathUrls = Arrays.copyOf(setupClasspathUrls, setupClasspathUrls.length + MixinJarResolver.jarUrls.length);
        System.arraycopy(MixinJarResolver.jarUrls, 0, classpathUrls, setupClasspathUrls.length, MixinJarResolver.jarUrls.length);

        final ClassLoader parentClassLoader = Leavesclip.class.getClassLoader();
        MixinServiceKnot.classLoader = parentClassLoader;
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);

        final var classLoader = new MixinURLClassLoader(classpathUrls, parentClassLoader);
        ConditionChecker.setClassLoader(classLoader);
        Mixins.addConfiguration("mixin-extras.init.mixins.json");
        MixinServiceKnot.classLoader = classLoader;
        MixinJarResolver.mixinConfigs.forEach(Mixins::addConfiguration);
        decorateMixinConfigWithPluginId();
        AccessWidenerManager.initAccessWidener(classLoader);
        return classLoader;
    }

    private static void decorateMixinConfigWithPluginId() {
        Mixins.getConfigs().forEach(config -> {
            final String pluginId = MixinJarResolver.getPluginId(config.getName());
            if (pluginId == null) return;

            final IMixinConfig mixinConfig = config.getConfig();
            mixinConfig.decorate(FabricUtil.KEY_MOD_ID, pluginId);
            mixinConfig.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
        });
    }

    private static URL[] setupClasspath() {
        final var repoDir = Path.of(System.getProperty("bundlerRepoDir", ""));
        final PatchEntry[] patches = findPatches();
        final DownloadContext downloadContext = findDownloadContext();
        if (patches.length > 0 && downloadContext == null) {
            throw new IllegalArgumentException("patches.list file found without a corresponding original-url file");
        }

        final Path baseFile;
        if (downloadContext != null) {
            try {
                downloadContext.download(repoDir);
            } catch (final IOException ex) {
                throw new IllegalStateException("Failed to download original jar", ex);
            }
            baseFile = downloadContext.getOutputFile(repoDir);
        } else {
            baseFile = null;
        }

        // Versions must come first: they can contain changed copies of library classes.
        final Map<String, Map<String, URL>> classpathUrls = new HashMap<>();
        classpathUrls.put("versions", new HashMap<>());
        classpathUrls.put("libraries", new HashMap<>());
        try (final var originalFs = baseFile == null ? null : FileSystems.newFileSystem(baseFile)) {
            final Path originalRootDir = originalFs == null ? null : originalFs.getPath("/");
            extractEntries(classpathUrls.get("versions"), patches, originalRootDir, repoDir, "versions");
            extractEntries(classpathUrls.get("libraries"), patches, originalRootDir, repoDir, "libraries");
            for (final PatchEntry patch : patches) {
                patch.applyPatch(classpathUrls, originalRootDir, repoDir);
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to extract or patch jar files", ex);
        }
        final var urls = new ArrayList<>(classpathUrls.get("versions").values());
        urls.addAll(classpathUrls.get("libraries").values());
        return urls.toArray(URL[]::new);
    }

    private static PatchEntry[] findPatches() {
        try (final InputStream stream = AutoUpdate.openResourceFromTargetJar("/META-INF/patches.list")) {
            return stream == null ? new PatchEntry[0] : PatchEntry.parse(new BufferedReader(new InputStreamReader(stream)));
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to read patches.list file", ex);
        }
    }

    private static DownloadContext findDownloadContext() {
        try {
            return DownloadContext.parseLine(Util.readResourceText("/META-INF/download-context"));
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to read download-context file", ex);
        }
    }

    private static void extractEntries(
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final Path originalRootDir,
        final Path repoDir,
        final String targetName
    ) throws IOException {
        final String index = "/META-INF/" + targetName + ".list";
        final FileEntry[] entries;
        try (final InputStream stream = AutoUpdate.openResourceFromTargetJar(index)) {
            if (stream == null) {
                return;
            }
            entries = FileEntry.parse(new BufferedReader(new InputStreamReader(stream)));
        } catch (final IOException ex) {
            throw new IOException("Failed to read " + index, ex);
        }
        final String targetPath = "/META-INF/" + targetName;
        final Path targetDir = repoDir.resolve(targetName);
        for (final FileEntry entry : entries) {
            entry.extractFile(urls, patches, targetName, originalRootDir, targetPath, targetDir);
        }
    }

    private static String findMainClass() {
        final String override = System.getProperty("bundlerMainClass");
        if (override != null) {
            return override;
        }
        try {
            return Util.readResourceText("/META-INF/main-class");
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to read main-class file", ex);
        }
    }

    private static void overrideAsmVersion() {
        try {
            final Class<?> asmClass = Class.forName("org.spongepowered.asm.util.asm.ASM");
            final Field minorVersionField = asmClass.getDeclaredField("implMinorVersion");
            minorVersionField.setAccessible(true);
            minorVersionField.setInt(null, 5);
        } catch (final Exception ex) {
            logger.error("Failed to override asm version", ex);
        }
    }
}
