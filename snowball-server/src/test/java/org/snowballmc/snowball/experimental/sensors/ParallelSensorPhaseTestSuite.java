package org.snowballmc.snowball.experimental.sensors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Suite
@SelectClasses(ParallelSensorPhaseTest.class)
public class ParallelSensorPhaseTestSuite {
}

class ParallelSensorPhaseTest {
    @Test
    void productionPoolEnforcesThreadLimitWithoutBacklog() throws InterruptedException {
        final ThreadPoolExecutor pool = ParallelSensorPhase.createPool(1);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try {
            assertEquals(0, pool.getPoolSize());
            occupyWorker(pool, started, release);
            assertEquals(1, pool.getPoolSize());
            assertEquals(0, pool.getQueue().remainingCapacity());
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> pool.submit(() -> {}));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void matchesStableVanillaOrderAndAccessesCandidatesOnlyOnOwner() {
        final Thread owner = Thread.currentThread();
        final var worker = new AtomicReference<Thread>();
        final var pool = new ThreadPoolExecutor(2, 2, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            task -> Thread.ofPlatform().daemon().unstarted(() -> {
                worker.set(Thread.currentThread());
                task.run();
            }));
        try (var phase = new ParallelSensorPhase(threads -> {
            assertEquals(2, threads);
            return pool;
        }, TimeUnit.SECONDS.toNanos(2))) {
            final List<Candidate> original = candidates(ParallelSensorPhase.MAX_CANDIDATES);
            final List<Candidate> expected = vanillaOrder(original);
            final List<Candidate> actual = new ArrayList<>(original) {
                @Override
                public Candidate get(final int index) {
                    assertSame(owner, Thread.currentThread());
                    return super.get(index);
                }

                @Override
                public Candidate set(final int index, final Candidate candidate) {
                    assertSame(owner, Thread.currentThread());
                    return super.set(index, candidate);
                }
            };
            final var captured = new AtomicInteger();
            assertTrue(phase.trySort(actual, candidate -> {
                assertSame(owner, Thread.currentThread());
                captured.incrementAndGet();
                return candidate.distance();
            }, 2));
            assertNotNull(worker.get());
            assertNotSame(owner, worker.get());
            assertEquals(original.size(), captured.get());
            assertEquals(expected, actual);
        }
    }

    @Test
    void bypassesBothSizeBoundsInvalidThreadCountAndClosedPoolWithoutDoingWork() {
        try (var phase = new ParallelSensorPhase(threads -> fail("Pool was created"), 0)) {
            assertFalse(phase.trySort(candidates(ParallelSensorPhase.MIN_CANDIDATES - 1), candidate -> fail(), 1));
            assertFalse(phase.trySort(candidates(ParallelSensorPhase.MAX_CANDIDATES + 1), candidate -> fail(), 1));
            assertFalse(phase.trySort(candidates(ParallelSensorPhase.MIN_CANDIDATES), candidate -> fail(), 0));
            phase.close();
            assertFalse(phase.trySort(candidates(ParallelSensorPhase.MIN_CANDIDATES), candidate -> fail(), 1));
        }
    }

    @Test
    void queueSaturationLeavesOriginalEncounterOrderForInlineFallback() throws InterruptedException {
        final ThreadPoolExecutor pool = poolWithOneQueueSlot();
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (var phase = new ParallelSensorPhase(threads -> pool, TimeUnit.SECONDS.toNanos(2))) {
            occupyWorker(pool, started, release);
            pool.submit(() -> {});
            final List<Candidate> original = candidates(ParallelSensorPhase.MIN_CANDIDATES);
            final List<Candidate> actual = new ArrayList<>(original);
            assertFalse(phase.trySort(actual, Candidate::distance, 1));
            assertEquals(original, actual);
            assertEquals(1, pool.getQueue().size());
            actual.sort(Comparator.comparingDouble(Candidate::distance));
            assertEquals(vanillaOrder(original), actual);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anUnavailableWorkerResultDoesNotPublishPartialOrderOrLeaveQueuedWork() throws InterruptedException {
        final ThreadPoolExecutor pool = poolWithOneQueueSlot();
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (var phase = new ParallelSensorPhase(threads -> pool, 0)) {
            occupyWorker(pool, started, release);
            final List<Candidate> original = candidates(ParallelSensorPhase.MIN_CANDIDATES);
            final List<Candidate> actual = new ArrayList<>(original);
            assertFalse(phase.trySort(actual, Candidate::distance, 1));
            assertEquals(original, actual);
            assertTrue(pool.getQueue().isEmpty());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void reportsWorkerFailureAsInlineFallbackAndDoesNotPublishPartialOrder() {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().factory()) {
            @Override
            protected <T> RunnableFuture<T> newTaskFor(final Callable<T> callable) {
                return new FutureTask<>(() -> {
                    throw new IllegalStateException("injected worker failure");
                });
            }
        };
        try (var phase = new ParallelSensorPhase(threads -> pool, TimeUnit.SECONDS.toNanos(2))) {
            final List<Candidate> original = candidates(ParallelSensorPhase.MIN_CANDIDATES);
            final List<Candidate> actual = new ArrayList<>(original);
            assertFalse(phase.trySort(actual, Candidate::distance, 1));
            assertEquals(original, actual);
        }
    }

    @Test
    void preservesOwnerInterruptAndCancelsQueuedSort() throws InterruptedException {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().factory()) {
            @Override
            public void execute(final Runnable command) {
                super.execute(command);
                if (command instanceof Future<?>) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (var phase = new ParallelSensorPhase(threads -> pool, TimeUnit.SECONDS.toNanos(2))) {
            occupyWorker(pool, started, release);
            final List<Candidate> original = candidates(ParallelSensorPhase.MIN_CANDIDATES);
            final List<Candidate> actual = new ArrayList<>(original);
            assertFalse(phase.trySort(actual, Candidate::distance, 1));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(original, actual);
            assertTrue(pool.getQueue().isEmpty());
        } finally {
            Thread.interrupted();
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void closeCancelsQueuedWorkAndNeverRestartsThePool() throws InterruptedException {
        final ThreadPoolExecutor pool = poolWithOneQueueSlot();
        final var starts = new AtomicInteger();
        try (var phase = new ParallelSensorPhase(threads -> {
            starts.incrementAndGet();
            return pool;
        }, TimeUnit.SECONDS.toNanos(2))) {
            assertTrue(phase.trySort(candidates(ParallelSensorPhase.MIN_CANDIDATES), Candidate::distance, 1));
            final var started = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            try {
                occupyWorker(pool, started, release);
                final Future<?> queued = pool.submit(() -> {});
                phase.close();
                phase.close();
                assertTrue(queued.isCancelled());
                assertTrue(pool.isShutdown());
                assertFalse(phase.trySort(candidates(ParallelSensorPhase.MIN_CANDIDATES), candidate -> fail(), 1));
                assertEquals(1, starts.get());
                assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private static ThreadPoolExecutor poolWithOneQueueSlot() {
        return new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().factory());
    }

    private static void occupyWorker(final ThreadPoolExecutor pool, final CountDownLatch started, final CountDownLatch release) throws InterruptedException {
        pool.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
    }

    private static List<Candidate> candidates(final int size) {
        final double[] distances = {Double.NaN, -0.0, +0.0, Double.POSITIVE_INFINITY, 4.0, 16.0, 25.0, 64.0};
        final Random random = new Random(42);
        final List<Candidate> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(new Candidate(i, distances[random.nextInt(distances.length)]));
        }
        return result;
    }

    private static List<Candidate> vanillaOrder(final List<Candidate> candidates) {
        final List<Candidate> expected = new ArrayList<>(candidates);
        expected.sort(Comparator.comparingDouble(Candidate::distance));
        return expected;
    }

    private record Candidate(int id, double distance) {
    }
}
