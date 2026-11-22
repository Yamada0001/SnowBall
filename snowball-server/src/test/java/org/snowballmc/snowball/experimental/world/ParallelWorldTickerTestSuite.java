package org.snowballmc.snowball.experimental.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class ParallelWorldTickerTestSuite {
    @Test
    void runsWorldsConcurrentlyWithExclusiveIdentityAndReusesFixedWorkers() {
        record World(String name) {}
        World first = new World("same");
        World second = new World("same");
        World third = new World("same");
        Thread coordinator = Thread.currentThread();
        var entered = new CountDownLatch(2);
        var workerThreads = Collections.synchronizedSet(new HashSet<Thread>());
        var created = new ArrayList<Thread>();

        try (var pool = new ParallelWorldTicker<World>(2, recordingFactory(created))) {
            pool.tick(List.of(first, second), world -> {
                assertNotSame(coordinator, Thread.currentThread());
                workerThreads.add(Thread.currentThread());
                assertTrue(ParallelWorldTicker.owns(world));
                assertFalse(ParallelWorldTicker.owns(world == first ? second : first));
                assertFalse(ParallelWorldTicker.owns(third));
                assertFalse(ParallelWorldTicker.owns(null));
                assertThrows(IllegalStateException.class, pool::close);
                entered.countDown();
                await(entered);
            });
            assertEquals(2, workerThreads.size());
            assertFalse(ParallelWorldTicker.owns(first));
            int[] completed = new int[1];
            pool.tick(List.of(third), world -> {
                assertTrue(workerThreads.contains(Thread.currentThread()));
                assertTrue(ParallelWorldTicker.owns(third));
                assertFalse(ParallelWorldTicker.owns(first));
                assertFalse(ParallelWorldTicker.owns(second));
                completed[0]++;
            });
            assertEquals(1, completed[0]);
        }
        assertEquals(2, created.size());
        assertTrue(created.stream().noneMatch(Thread::isAlive));
    }

    @Test
    void waitsForPeersAfterAnErrorAndDoesNotRetryTheRound() {
        var entered = new CountDownLatch(2);
        var firstFailed = new CountDownLatch(1);
        var siblingFinished = new AtomicBoolean();
        var firstError = new AssertionError("first world");
        var secondError = new IllegalStateException("second world");
        try (var pool = new ParallelWorldTicker<Integer>(2, Thread::new)) {
            var actual = assertThrows(AssertionError.class, () -> pool.tick(List.of(1, 2), world -> {
                entered.countDown();
                await(entered);
                if (world == 1) {
                    firstFailed.countDown();
                    throw firstError;
                }
                await(firstFailed);
                siblingFinished.set(true);
                throw secondError;
            }));
            assertSame(firstError, actual);
            assertEquals(List.of(secondError), List.of(actual.getSuppressed()));
            assertTrue(siblingFinished.get());
            assertThrows(IllegalStateException.class, () -> pool.tick(List.of(1), world -> {}));
        }
    }

    @Test
    void drainsAcceptedTasksWhenLaterWorkerCreationFails() {
        var creatingSecond = new CountDownLatch(1);
        var finished = new AtomicBoolean();
        var factoryCalls = new AtomicInteger();
        var rejection = new RejectedExecutionException("injected failure creating worker");
        try (var pool = new ParallelWorldTicker<Integer>(2, runnable -> {
            if (factoryCalls.incrementAndGet() == 2) {
                creatingSecond.countDown();
                throw rejection;
            }
            return new Thread(runnable);
        })) {
            assertSame(rejection, assertThrows(RejectedExecutionException.class, () -> pool.tick(List.of(1, 2), world -> {
                assertEquals(1, world);
                await(creatingSecond);
                finished.set(true);
            })));
            assertTrue(finished.get());
        }
    }

    @Test
    void interruptionsDoNotCancelWorkersAndAreRestoredAfterTickAndClose() {
        Thread coordinator = Thread.currentThread();
        var finished = new AtomicBoolean();
        var created = new ArrayList<Thread>();
        var pool = new ParallelWorldTicker<Integer>(1, recordingFactory(created));
        try {
            pool.tick(List.of(1), world -> {
                coordinator.interrupt();
                finished.set(true);
            });
            assertTrue(coordinator.isInterrupted());
            assertTrue(finished.get());
            pool.close();
            assertTrue(coordinator.isInterrupted());
            assertTrue(created.stream().noneMatch(Thread::isAlive));
            assertThrows(IllegalStateException.class, () -> pool.tick(List.of(1), world -> {}));
        } finally {
            Thread.interrupted();
            pool.close();
        }
    }

    @Test
    void rejectsDuplicatesAndOversizedBatchesBeforeAnyWorkerStarts() {
        var created = new ArrayList<Thread>();
        var world = new Object();
        try (var pool = new ParallelWorldTicker<Object>(2, recordingFactory(created))) {
            assertThrows(IllegalArgumentException.class, () -> pool.tick(List.of(world, world), ignored -> {}));
            assertThrows(IllegalArgumentException.class,
                () -> pool.tick(Collections.nCopies(ParallelWorldTicker.MAX_WORLDS + 1, world), ignored -> {}));
        }
        assertTrue(created.isEmpty());
    }

    private static ThreadFactory recordingFactory(List<Thread> created) {
        return work -> {
            Thread thread = new Thread(work);
            created.add(thread);
            return thread;
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "peer failed to reach barrier");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
