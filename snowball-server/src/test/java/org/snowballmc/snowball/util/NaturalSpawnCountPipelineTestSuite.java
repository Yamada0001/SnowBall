package org.snowballmc.snowball.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Suite
@SelectClasses(NaturalSpawnCountPipelineTest.class)
public class NaturalSpawnCountPipelineTestSuite {
}

class NaturalSpawnCountPipelineTest {
    private static final NaturalSpawnCountPipeline.Snapshot EMPTY = new NaturalSpawnCountPipeline.Snapshot(3, List.of());

    @Test
    void snapshotsValuesAndAggregatesOnlyOnce() throws InterruptedException {
        final var first = new NaturalSpawnCountPipeline.Sample(-42L, 2, 3, -64, 5, 1.25);
        final var second = new NaturalSpawnCountPipeline.Sample(42L, 0, -7, 8, 9, -0.4);
        final var zeroCharge = new NaturalSpawnCountPipeline.Sample(-42L, 2, -1, 2, -3, -0.0);
        final var otherCategory = new NaturalSpawnCountPipeline.Sample(-42L, 0, -1, 2, -3, 0.0);
        final var input = new ArrayList<>(List.of(first, second, zeroCharge, otherCategory));
        final var snapshot = new NaturalSpawnCountPipeline.Snapshot(3, input);
        input.clear();
        assertThrows(UnsupportedOperationException.class, snapshot.samples()::clear);
        final var pending = new NaturalSpawnCountPipeline.Pending();
        final var worker = new ManualExecutor();
        pending.submit(worker, 9, false, snapshot);
        assertFalse(pending.canSubmit());
        pending.submit(worker, 9, false, EMPTY);
        assertNull(pending.take(10, false, error -> fail(error)));
        final Thread thread = Thread.ofPlatform().start(worker.next);
        thread.join();

        final var result = pending.take(10, false, error -> fail(error));
        assertEquals(2, result.count(0));
        assertEquals(0, result.count(1));
        assertEquals(2, result.count(2));
        assertEquals(List.of(first, second), result.charges());
        assertEquals(2, result.chunks().size());
        final var chunk = result.chunks().stream().filter(entry -> entry.chunkKey() == -42L).findFirst().orElseThrow();
        assertEquals(1, chunk.count(0));
        assertEquals(2, chunk.count(2));
        assertThrows(UnsupportedOperationException.class, result.charges()::clear);
        assertThrows(UnsupportedOperationException.class, result.chunks()::clear);
        assertNull(pending.take(10, false, error -> fail(error)));
        assertTrue(pending.canSubmit());
    }

    @Test
    void discardsOutdatedOrConfigurationIncompatibleResults() {
        final var pending = new NaturalSpawnCountPipeline.Pending();
        final var worker = new ManualExecutor();
        pending.submit(worker, 9, false, EMPTY);
        assertNull(pending.take(10, false, error -> fail(error)));
        worker.next.run();
        assertNull(pending.take(11, false, error -> fail(error)));
        pending.submit(Runnable::run, 11, false, EMPTY);
        assertNull(pending.take(12, true, error -> fail(error)));
        pending.submit(Runnable::run, Integer.MAX_VALUE, true, EMPTY);
        assertEquals(0, pending.take(Integer.MIN_VALUE, true, error -> fail(error)).count(0));
    }

    @Test
    void reportsFailuresAndRecoversFromRejectedOrDiscardedWork() {
        final var pending = new NaturalSpawnCountPipeline.Pending();
        pending.submit(task -> { throw new RejectedExecutionException(); }, 9, false, EMPTY);
        assertTrue(pending.canSubmit());

        final var invalid = new NaturalSpawnCountPipeline.Snapshot(0,
            List.of(new NaturalSpawnCountPipeline.Sample(1L, 0, 0, 0, 0, 0.0)));
        pending.submit(Runnable::run, 9, false, invalid);
        final var failure = new AtomicReference<Throwable>();
        assertNull(pending.take(10, false, failure::set));
        assertInstanceOf(IndexOutOfBoundsException.class, failure.get());
        assertTrue(pending.canSubmit());

        final var worker = new ManualExecutor();
        pending.submit(worker, 10, false, EMPTY);
        pending.discard();
        assertTrue(pending.canSubmit());
        worker.next.run();
        assertNull(pending.take(11, false, error -> fail(error)));
    }

    @Test
    void executorRunsOffThreadAndRejectsAfterShutdown() throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        final var executor = NaturalSpawnCountPipeline.newExecutor();
        try {
            final Thread worker = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            assertFalse(worker == Thread.currentThread());
            assertTrue(worker.isDaemon());
        } finally {
            executor.shutdownNow();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
    }

    private static final class ManualExecutor implements Executor {
        private Runnable next;

        @Override
        public void execute(final Runnable command) {
            if (this.next != null) {
                fail("unexpected duplicate job");
            }
            this.next = command;
        }
    }
}
