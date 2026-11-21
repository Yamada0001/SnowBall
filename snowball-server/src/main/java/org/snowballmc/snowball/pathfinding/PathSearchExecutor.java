package org.snowballmc.snowball.pathfinding;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-server executor. Its public submission boundary accepts only detached path inputs. */
public final class PathSearchExecutor implements AutoCloseable {
    private ThreadPoolExecutor executor;
    private boolean closed;

    public static final class SearchTask extends FutureTask<FlatPathSearch.Result> {
        private volatile boolean completed;

        private SearchTask(final FlatPathSearch.Input input) {
            super(() -> FlatPathSearch.search(input));
        }

        @Override
        protected void done() {
            this.completed = true;
        }

        // FutureTask.isDone() may be true while the future is still publishing its result.
        public boolean completed() {
            return this.completed;
        }

        public FlatPathSearch.Result completedResult() {
            return this.completed && this.state() == State.SUCCESS ? this.resultNow() : null;
        }
    }

    public SearchTask submit(final FlatPathSearch.Input input, final int threads, final int keepaliveSeconds,
                             final int queueSize, final boolean flushQueueOnRejection) {
        final ThreadPoolExecutor pool;
        synchronized (this) {
            if (this.closed) {
                return null;
            }
            if (this.executor == null) {
                this.executor = createPool(threads, keepaliveSeconds, queueSize, flushQueueOnRejection);
            }
            pool = this.executor;
        }
        final SearchTask task = new SearchTask(input);
        try {
            pool.execute(task);
            return task;
        } catch (RejectedExecutionException ignored) {
            // The server can shut down after the preceding synchronized section.
            task.cancel(false);
            return null;
        }
    }

    public synchronized void cancel(final FutureTask<FlatPathSearch.Result> task) {
        task.cancel(true);
        if (this.executor != null) {
            this.executor.remove(task);
        }
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        if (this.executor != null) {
            for (final Runnable task : this.executor.shutdownNow()) {
                ((FutureTask<?>) task).cancel(false);
            }
        }
    }

    static ThreadPoolExecutor createPool(final int threads, final int keepaliveSeconds, final int queueSize, final boolean flushQueueOnRejection) {
        final AtomicInteger ids = new AtomicInteger();
        // Java requires a positive keep-alive for core timeout; one nanosecond means immediate idle expiry for zero seconds.
        final long idleNanos = Math.max(1L, TimeUnit.SECONDS.toNanos(keepaliveSeconds));
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, idleNanos, TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(queueSize), task -> Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false)
                .name("Snowball Path Search #" + ids.incrementAndGet()).unstarted(task), (task, executor) -> {
                    if (executor.isShutdown()) {
                        throw new RejectedExecutionException();
                    }
                    if (flushQueueOnRejection) {
                        final java.util.ArrayList<Runnable> queued = new java.util.ArrayList<>();
                        executor.getQueue().drainTo(queued);
                        for (final Runnable waiting : queued) {
                            waiting.run();
                        }
                    }
                    task.run();
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
