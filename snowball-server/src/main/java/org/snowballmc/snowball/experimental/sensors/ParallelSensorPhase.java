package org.snowballmc.snowball.experimental.sensors;

import it.unimi.dsi.fastutil.ints.IntArrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One server's bounded executor for detached sensor work. Never submits live world state. */
public final class ParallelSensorPhase implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelSensorPhase.class);
    static final int MIN_CANDIDATES = 256;
    static final int MAX_CANDIDATES = 4096;
    private static final long MAX_WAIT_NANOS = TimeUnit.MICROSECONDS.toNanos(250);
    private final IntFunction<ThreadPoolExecutor> poolFactory;
    private final long waitNanos;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile ThreadPoolExecutor pool;
    private volatile boolean closed;

    public ParallelSensorPhase() {
        this(ParallelSensorPhase::createPool, MAX_WAIT_NANOS);
    }

    ParallelSensorPhase(final IntFunction<ThreadPoolExecutor> poolFactory, final long waitNanos) {
        this.poolFactory = poolFactory;
        this.waitNanos = waitNanos;
    }

    static boolean supportsSize(final int size) {
        return size >= MIN_CANDIDATES && size <= MAX_CANDIDATES;
    }

    /** Sorts the owner's list on success. On fallback it leaves the list untouched. */
    <T> boolean trySort(final List<T> candidates, final ToDoubleFunction<? super T> ownerDistance, final int maxThreads) {
        final int size = candidates.size();
        if (maxThreads <= 0 || !supportsSize(size) || this.closed || Thread.currentThread().isInterrupted()) {
            return false;
        }

        // Only these copied numeric keys cross the worker boundary. In particular, don't capture ownerDistance.
        final DistanceSnapshot snapshot = new DistanceSnapshot(candidates, ownerDistance);
        final int middle = size / 2;
        final ThreadPoolExecutor executor;
        final Future<int[]> right;
        try {
            executor = this.pool(maxThreads);
            if (executor == null) {
                return false;
            }
            right = executor.submit(() -> sortRange(snapshot, middle, size));
        } catch (RejectedExecutionException ignored) {
            return false;
        } catch (RuntimeException failure) {
            this.logFailure(failure);
            return false;
        }

        boolean success = false;
        try {
            final int[] leftOrder = sortRange(snapshot, 0, middle);
            final int[] rightOrder = right.get(this.waitNanos, TimeUnit.NANOSECONDS);
            if (this.closed) {
                return false;
            }
            // Resolve indices to entities and publish only on the caller after both partitions succeeded.
            final List<T> sorted = new ArrayList<>(size);
            int leftIndex = 0;
            int rightIndex = 0;
            while (leftIndex < leftOrder.length || rightIndex < rightOrder.length) {
                final boolean takeLeft = rightIndex == rightOrder.length || leftIndex < leftOrder.length
                    && snapshot.compare(leftOrder[leftIndex], rightOrder[rightIndex]) <= 0;
                sorted.add(candidates.get(takeLeft ? leftOrder[leftIndex++] : rightOrder[rightIndex++]));
            }
            for (int i = 0; i < size; i++) {
                candidates.set(i, sorted.get(i));
            }
            success = true;
            return true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | CancellationException ignored) {
            return false;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error error && (error instanceof VirtualMachineError || error instanceof ThreadDeath)) {
                throw error;
            }
            this.logFailure(failure.getCause());
            return false;
        } finally {
            if (!success) {
                right.cancel(true);
                if (right instanceof Runnable queued) {
                    executor.remove(queued);
                }
            }
        }
    }

    private void logFailure(final Throwable failure) {
        if (this.failureLogged.compareAndSet(false, true)) {
            LOGGER.warn("Parallel sensor distance sort failed; sorting on the server thread instead", failure);
        }
    }

    private ThreadPoolExecutor pool(final int maxThreads) {
        final ThreadPoolExecutor existing = this.pool;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.closed) {
                return null;
            }
            if (this.pool == null) {
                this.pool = this.poolFactory.apply(maxThreads);
            }
            return this.pool;
        }
    }

    static ThreadPoolExecutor createPool(final int maxThreads) {
        final AtomicInteger ids = new AtomicInteger();
        // No backlog: a result is only useful to the sensor currently in progress.
        // Zero core threads lets the handoff reuse idle workers without eagerly growing to maxThreads.
        return new ThreadPoolExecutor(0, maxThreads, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false)
                .name("Snowball Sensor Sort #" + ids.incrementAndGet()).unstarted(task));
    }

    @Override
    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.pool != null) {
                for (final Runnable waiting : this.pool.shutdownNow()) {
                    ((Future<?>) waiting).cancel(false);
                }
            }
        }
    }

    private static int[] sortRange(final DistanceSnapshot snapshot, final int start, final int end) {
        final int[] order = new int[end - start];
        for (int i = 0; i < order.length; i++) {
            order[i] = start + i;
        }
        IntArrays.quickSort(order, snapshot::compare);
        return order;
    }

    private static final class DistanceSnapshot {
        private final double[] distances;

        private <T> DistanceSnapshot(final List<T> candidates, final ToDoubleFunction<? super T> ownerDistance) {
            this.distances = new double[candidates.size()];
            for (int i = 0; i < this.distances.length; i++) {
                this.distances[i] = ownerDistance.applyAsDouble(candidates.get(i));
            }
        }

        private int compare(final int left, final int right) {
            final int byDistance = Double.compare(this.distances[left], this.distances[right]);
            // Match List.sort's stable tie order, including NaNs and infinities.
            return byDistance != 0 ? byDistance : Integer.compare(left, right);
        }
    }
}
