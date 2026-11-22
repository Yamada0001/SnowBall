package org.snowballmc.snowball.experimental.culling;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Each task captures only a list of immutable occupancy snapshots. */
final class CullingWorker implements AutoCloseable {
    private ThreadPoolExecutor executor;
    private boolean closed;

    synchronized Future<BitSet> submit(final int threads, final List<Raytrace.Capture> input) {
        if (closed) {
            return null;
        }
        if (executor == null) {
            final AtomicInteger ids = new AtomicInteger();
            executor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.min(64, Math.max(4, threads * 2))), runnable -> {
                    final Thread thread = new Thread(null, runnable, "SnowBall culling " + ids.incrementAndGet(), 0, false);
                    thread.setDaemon(true);
                    return thread;
                });
            executor.allowCoreThreadTimeOut(true);
        }
        final FutureTask<BitSet> task = task(List.copyOf(input));
        try {
            executor.execute(task);
            return task;
        } catch (RejectedExecutionException ignored) {
            return null;
        }
    }

    // Static factory: the worker's closure cannot retain the manager or a live observer.
    private static FutureTask<BitSet> task(final List<Raytrace.Capture> input) {
        return new FutureTask<>(() -> {
            final BitSet result = new BitSet(input.size());
            for (int i = 0; i < input.size() && !Thread.currentThread().isInterrupted(); ++i) {
                if (input.get(i).fullyOccluded()) {
                    result.set(i);
                }
            }
            return result;
        });
    }

    synchronized void cancel(final Future<BitSet> task) {
        task.cancel(false);
        if (executor != null && task instanceof Runnable runnable) {
            executor.remove(runnable);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            for (final Runnable queued : executor.shutdownNow()) {
                ((Future<?>) queued).cancel(false);
            }
            executor = null;
        }
    }
}
