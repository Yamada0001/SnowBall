package org.snowballmc.snowball.experimental.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only ownership protocol model. It never invokes Minecraft or dispatches a Bukkit event. */
@Timeout(15)
class WorldTickSafePointTestSuite {
    @Test
    void twoWorldsOverlapAndCallbacksRunOnTheOwnerAfterBothYield() {
        var entered = new CountDownLatch(2);
        var running = new AtomicInteger();
        var callbacks = new ArrayList<String>();
        Thread owner = Thread.currentThread();
        try (Rounds rounds = new Rounds(2)) {
            rounds.run(List.of(world("A", entered, running, callbacks, owner), world("B", entered, running, callbacks, owner)));
            assertEquals(List.of("A", "B"), callbacks);
        }
    }

    @Test
    void singleWorkerSerializesWorldWorkWithoutBlockingOnOwnerCallbacks() {
        var progress = new AtomicInteger();
        Thread owner = Thread.currentThread();
        try (Rounds rounds = new Rounds(1)) {
            rounds.run(List.of(
                () -> { assertEquals(0, progress.getAndIncrement()); return () -> {
                    assertSame(owner, Thread.currentThread());
                    assertEquals(2, progress.getAndIncrement());
                }; },
                () -> { assertEquals(1, progress.getAndIncrement()); return () -> assertEquals(3, progress.getAndIncrement()); }
            ));
            assertEquals(4, progress.get());
        }
    }

    @Test
    void failureDrainsSiblingAndDoesNotPublishCallbacksOrRetryPartialRound() {
        var entered = new CountDownLatch(2);
        var failed = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        try (Rounds rounds = new Rounds(2)) {
            var failure = assertThrows(CompletionException.class, () -> rounds.run(List.of(
                () -> {
                    attempts.incrementAndGet();
                    entered.countDown();
                    await(entered);
                    failed.countDown();
                    throw new IOException("injected world failure");
                },
                () -> {
                    attempts.incrementAndGet();
                    entered.countDown();
                    await(failed);
                    finished.countDown();
                    return () -> fail("callback committed for failed round");
                }
            )));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(0, finished.getCount());
            assertThrows(IllegalStateException.class, () -> rounds.run(List.of(() -> fail("partial round retried"))));
            assertThrows(IllegalStateException.class, () -> rounds.stopAndSave(() -> fail("partial round saved")));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void callbacksCannotReenterTheRoundAndShutdownFencesOffNewWorkBeforeSaving() {
        var callbacks = new AtomicInteger();
        var saves = new AtomicInteger();
        try (Rounds rounds = new Rounds(2)) {
            rounds.run(List.of(() -> () -> {
                assertThrows(IllegalStateException.class, () -> rounds.run(List.of()));
                assertThrows(IllegalStateException.class, () -> rounds.stopAndSave(() -> fail("save inside callback")));
                callbacks.incrementAndGet();
            }));
            rounds.stopAndSave(() -> {
                assertEquals(1, callbacks.get());
                assertTrue(rounds.workers.isTerminated());
                assertThrows(IllegalStateException.class, () -> rounds.run(List.of()));
                saves.incrementAndGet();
            });
            assertThrows(IllegalStateException.class, () -> rounds.stopAndSave(() -> fail("duplicate save")));
            assertEquals(1, saves.get());
        }
    }

    private static Callable<Runnable> world(String name, CountDownLatch entered, AtomicInteger running,
                                             List<String> callbacks, Thread owner) {
        return () -> {
            assertNotSame(owner, Thread.currentThread());
            running.incrementAndGet();
            entered.countDown();
            try {
                await(entered);
            } finally {
                running.decrementAndGet();
            }
            return () -> {
                assertSame(owner, Thread.currentThread());
                assertEquals(0, running.get());
                callbacks.add(name);
            };
        };
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "timed out waiting for a safe point");
    }

    private static final class Rounds implements AutoCloseable {
        private enum Phase { IDLE, WORK, CALLBACKS, FAILED, STOPPED }
        private final Thread owner = Thread.currentThread();
        private final ExecutorService workers;
        private Phase phase = Phase.IDLE;

        Rounds(int limit) {
            this.workers = Executors.newFixedThreadPool(limit);
        }

        // Workers return callbacks at a cooperative safe point instead of waiting on the owner.
        void run(List<Callable<Runnable>> jobs) {
            requireIdle();
            this.phase = Phase.WORK;
            try {
                List<Future<Runnable>> completed = this.workers.invokeAll(jobs);
                List<Runnable> callbacks = new ArrayList<>(completed.size());
                for (Future<Runnable> task : completed) {
                    callbacks.add(task.get());
                }
                this.phase = Phase.CALLBACKS;
                callbacks.forEach(Runnable::run);
                this.phase = Phase.IDLE;
            } catch (InterruptedException e) {
                this.phase = Phase.FAILED;
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (java.util.concurrent.ExecutionException e) {
                this.phase = Phase.FAILED;
                throw new CompletionException(e.getCause());
            } catch (RuntimeException | Error e) {
                this.phase = Phase.FAILED;
                throw e;
            }
        }

        void stopAndSave(Runnable save) {
            requireIdle();
            this.phase = Phase.STOPPED;
            this.workers.close();
            save.run();
        }

        private void requireIdle() {
            if (Thread.currentThread() != this.owner || this.phase != Phase.IDLE) {
                throw new IllegalStateException("operation requires an idle coordinator on its owner thread");
            }
        }

        @Override
        public void close() {
            this.phase = Phase.STOPPED;
            this.workers.close();
        }
    }
}
