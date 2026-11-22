package org.snowballmc.snowball.experimental.world;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A coordinator-owned fixed worker pool with one exclusively owned world per job. */
public final class ParallelWorldTicker<W> implements AutoCloseable {
    public static final int MAX_WORLDS = 16;
    private static final ThreadLocal<Object> OWNED_WORLD = new ThreadLocal<>();

    private final Thread coordinator = Thread.currentThread();
    private final ThreadPoolExecutor workers;
    private final ConcurrentLinkedQueue<Thread> workerThreads = new ConcurrentLinkedQueue<>();
    private boolean ticking;
    private boolean failed;
    private boolean closed;

    public ParallelWorldTicker(int workerCount, ThreadFactory factory) {
        if (workerCount < 1 || workerCount > MAX_WORLDS) {
            throw new IllegalArgumentException("World tick worker count out of range: " + workerCount);
        }
        Objects.requireNonNull(factory);
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_WORLDS), runnable -> {
                Thread worker = Objects.requireNonNull(factory.newThread(runnable), "World tick worker was not created");
                this.workerThreads.add(worker);
                return worker;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static boolean owns(Object world) {
        return world != null && OWNED_WORLD.get() == world;
    }

    /** Submit one round, then drain every accepted job before returning or propagating failure. */
    public void tick(List<? extends W> worlds, Consumer<? super W> tickWorld) {
        this.checkCoordinator();
        if (this.closed || this.failed || this.ticking) {
            throw new IllegalStateException("World tick pool is closed, failed, or already ticking");
        }
        Objects.requireNonNull(tickWorld);
        if (worlds.size() > MAX_WORLDS) {
            throw new IllegalArgumentException("Too many worlds in one tick round");
        }
        List<? extends W> snapshot = List.copyOf(worlds);
        IdentityHashMap<W, Boolean> seen = new IdentityHashMap<>();
        for (W world : snapshot) {
            if (seen.put(world, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("A world may tick only once per round");
            }
        }

        List<Future<?>> pending = new ArrayList<>(snapshot.size());
        List<Throwable> failures = new ArrayList<>(snapshot.size() + 1);
        boolean interrupted = false;
        this.ticking = true;
        try {
            try {
                for (W world : snapshot) {
                    pending.add(this.workers.submit(() -> {
                        OWNED_WORLD.set(world);
                        try {
                            tickWorld.accept(world);
                        } finally {
                            OWNED_WORLD.remove();
                        }
                    }));
                }
            } catch (RuntimeException | Error submissionFailure) {
                failures.add(submissionFailure);
            }
            for (Future<?> result : pending) {
                for (;;) {
                    try {
                        result.get();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    } catch (ExecutionException failure) {
                        failures.add(failure.getCause());
                        break;
                    }
                }
            }
        } finally {
            this.ticking = false;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!failures.isEmpty()) {
            this.failed = true;
            Throwable first = failures.getFirst();
            for (int i = 1; i < failures.size(); i++) {
                if (failures.get(i) != first) {
                    first.addSuppressed(failures.get(i));
                }
            }
            switch (first) {
                case RuntimeException exception -> throw exception;
                case Error error -> throw error;
                default -> throw new IllegalStateException("World tick failed", first);
            }
        }
    }

    @Override
    public void close() {
        this.checkCoordinator();
        if (this.ticking) {
            throw new IllegalStateException("Cannot close the world tick pool during a round");
        }
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.workers.shutdown();
        boolean interrupted = false;
        try {
            while (!this.workers.isTerminated()) {
                try {
                    this.workers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            // The executor may signal termination before its last Java thread exits.
            for (Thread worker : this.workerThreads) {
                while (worker.isAlive()) {
                    try {
                        worker.join();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            this.workerThreads.clear();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void checkCoordinator() {
        if (Thread.currentThread() != this.coordinator || OWNED_WORLD.get() != null) {
            throw new IllegalStateException("Only the server coordinator may manage the world tick pool");
        }
    }
}
