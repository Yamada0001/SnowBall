// SPDX-License-Identifier: GPL-3.0-only
// Based on Leaf 0077 (Pufferfish: Optimize mob spawning), by Kevin Raneri and booky10.
package org.snowballmc.snowball.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The worker boundary for natural-spawn counts. No server or entity objects cross it. */
public final class NaturalSpawnCountPipeline {
    private NaturalSpawnCountPipeline() {
    }

    public record Sample(long playerCountChunk, int category, int x, int y, int z, double charge) {
    }

    public record Snapshot(int categories, List<Sample> samples) {
        public Snapshot {
            samples = List.copyOf(samples);
        }
    }

    public static final class ChunkPopulation {
        private final long chunkKey;
        private final int[] counts;

        private ChunkPopulation(final long chunkKey, final int categories) {
            this.chunkKey = chunkKey;
            this.counts = new int[categories];
        }

        public long chunkKey() {
            return this.chunkKey;
        }

        public int count(final int category) {
            return this.counts[category];
        }
    }

    public static final class Result {
        private final int[] totals;
        private final List<ChunkPopulation> chunks;
        private final List<Sample> charges;

        private Result(final int[] totals, final List<ChunkPopulation> chunks, final List<Sample> charges) {
            this.totals = totals;
            this.chunks = chunks;
            this.charges = charges;
        }

        public int count(final int category) {
            return this.totals[category];
        }

        public List<ChunkPopulation> chunks() {
            return this.chunks;
        }

        /** Preserve encounter order: spawn potential sums floating-point charges in that order. */
        public List<Sample> charges() {
            return this.charges;
        }
    }

    private static Result aggregate(final Snapshot snapshot) {
        final int[] totals = new int[snapshot.categories()];
        final Long2ObjectOpenHashMap<ChunkPopulation> chunks = new Long2ObjectOpenHashMap<>();
        final List<Sample> charges = new ArrayList<>();
        for (final Sample sample : snapshot.samples()) {
            ++totals[sample.category()];
            ++chunks.computeIfAbsent(sample.playerCountChunk(), key -> new ChunkPopulation(key, snapshot.categories())).counts[sample.category()];
            if (sample.charge() != 0.0) {
                charges.add(sample);
            }
        }
        return new Result(totals, List.copyOf(chunks.values()), List.copyOf(charges));
    }

    public static ThreadPoolExecutor newExecutor() {
        // One shared worker per server, with bounded backlog; saturation falls back synchronously.
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
            Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("SnowBall Mob Spawn Count").factory());
    }

    /** One job per world. Never wait for a result or consume a result more than once. */
    public static final class Pending {
        private Job job;

        private record Job(int submittedTick, boolean countAllMobs, CompletableFuture<Result> future) {
        }

        public synchronized boolean canSubmit() {
            return this.job == null;
        }

        public synchronized void submit(final Executor executor, final int tick, final boolean countAllMobs, final Snapshot snapshot) {
            if (this.job != null) {
                return;
            }
            final CompletableFuture<Result> future;
            try {
                future = CompletableFuture.supplyAsync(() -> aggregate(snapshot), executor);
            } catch (final RejectedExecutionException ignored) {
                return;
            }
            this.job = new Job(tick, countAllMobs, future);
        }

        public synchronized Result take(final int tick, final boolean countAllMobs, final Consumer<Throwable> onFailure) {
            if (this.job == null || !this.job.future().isDone()) {
                return null;
            }
            final Job completed = this.job;
            this.job = null;
            try {
                final Result result = completed.future().getNow(null);
                // Equality across int wraparound is intentional; never apply older counts.
                return completed.submittedTick() + 1 == tick && completed.countAllMobs() == countAllMobs ? result : null;
            } catch (final CompletionException failure) {
                onFailure.accept(failure.getCause());
                return null;
            }
        }

        public synchronized void discard() {
            if (this.job != null) {
                this.job.future().cancel(false);
                this.job = null;
            }
        }
    }
}
