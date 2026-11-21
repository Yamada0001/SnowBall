package org.snowballmc.snowball.pathfinding;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Suite
@SelectClasses(FlatPathSearchTest.class)
public class FlatPathSearchTestSuite {
}

class FlatPathSearchTest {
    @Test
    void straightSearchReturnsDetachedPath() {
        final boolean[] walkable = ground();
        final var input = new FlatPathSearch.Input(walkable, 4, 0, 0, 16, 256);
        Arrays.fill(walkable, false);
        Arrays.fill(input.walkable(), false);
        final var result = FlatPathSearch.search(input);
        assertNotNull(result);
        final var steps = result.steps();
        assertArrayEquals(new int[]{0, 1, 2, 3, 4}, Arrays.stream(steps).mapToInt(FlatPathSearch.Step::x).toArray());
        assertTrue(Arrays.stream(steps).allMatch(step -> step.z() == 0));
        assertEquals(4F, steps[4].walkedDistance());
        steps[4] = null;
        assertNotNull(result.steps()[4]);
    }

    @Test
    void routesAroundWallWithoutCuttingItsCorner() {
        final boolean[] walkable = ground();
        walkable[index(1, 0)] = false;
        final var result = FlatPathSearch.search(new FlatPathSearch.Input(walkable, 3, 0, 0, 16, 256));
        assertNotNull(result);
        final var steps = result.steps();
        assertEquals(5, steps.length);
        assertEquals(0, steps[1].x());
        assertEquals(1, Math.abs(steps[1].z()));
        for (int i = 1; i < steps.length; i++) {
            final var from = steps[i - 1];
            final var to = steps[i];
            assertTrue(walkable[index(to.x(), to.z())]);
            if (from.x() != to.x() && from.z() != to.z()) {
                assertTrue(walkable[index(from.x(), to.z())]);
                assertTrue(walkable[index(to.x(), from.z())]);
            }
        }
    }

    @Test
    void honorsReachAndLimitsWithoutPublishingPartialPaths() {
        final boolean[] walkable = ground();
        final var result = FlatPathSearch.search(new FlatPathSearch.Input(walkable, 5, 0, 2, 16, 256));
        assertNotNull(result);
        assertEquals(3, result.steps()[result.steps().length - 1].x());
        assertNull(FlatPathSearch.search(new FlatPathSearch.Input(walkable, 5, 0, 0, 16, 2)));
        assertNull(FlatPathSearch.search(new FlatPathSearch.Input(walkable, 5, 0, 0, 3.0F, 256)));
        for (int z = -FlatPathSearch.RADIUS; z <= FlatPathSearch.RADIUS; z++) {
            walkable[index(1, z)] = false;
        }
        assertNull(FlatPathSearch.search(new FlatPathSearch.Input(walkable, 5, 0, 0, 16, 256)));
    }

    @Test
    void runningSearchUsesAWorkerAndShutdownDisallowsAnotherSubmission() throws Exception {
        final var input = new FlatPathSearch.Input(ground(), 4, 0, 0, 16, 256);
        final var task = new FutureTask<Thread>(Thread::currentThread);
        try (final ThreadPoolExecutor pool = PathSearchExecutor.createPool(1, 60, 1, false)) {
            pool.execute(task);
            assertTrue(task.get(5, TimeUnit.SECONDS).getName().startsWith("Snowball Path Search #"));
        }
        final PathSearchExecutor executor = new PathSearchExecutor();
        try (executor) {
            assertNotNull(executor.submit(input, 1, 60, 1, false).get(5, TimeUnit.SECONDS));
        }
        assertNull(executor.submit(input, 1, 60, 1, false));
    }

    @Test
    void fullQueueRunsOnlyOverflowOnCallerOrFlushesQueueFirst() throws Exception {
        for (final boolean flush : new boolean[]{false, true}) {
            final var release = new CountDownLatch(1);
            final var running = new CountDownLatch(1);
            final var completed = new CountDownLatch(1);
            final var order = new CopyOnWriteArrayList<String>();
            final Thread caller = Thread.currentThread();
            try (final ThreadPoolExecutor pool = PathSearchExecutor.createPool(1, 60, 1, flush)) {
                try {
                    pool.execute(() -> {
                        running.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    assertTrue(running.await(5, TimeUnit.SECONDS));
                    pool.execute(() -> {
                        order.add(Thread.currentThread() == caller ? "queued/caller" : "queued/worker");
                        completed.countDown();
                    });
                    pool.execute(() -> order.add(Thread.currentThread() == caller ? "overflow/caller" : "overflow/worker"));
                    if (flush) {
                        assertEquals(List.of("queued/caller", "overflow/caller"), order);
                    } else {
                        assertEquals(List.of("overflow/caller"), order);
                        assertFalse(pool.getQueue().isEmpty());
                    }
                } finally {
                    release.countDown();
                }
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                if (!flush) {
                    assertEquals(List.of("overflow/caller", "queued/worker"), order);
                }
            }
        }
    }

    private static int index(final int x, final int z) {
        return (z + FlatPathSearch.RADIUS) * FlatPathSearch.SIDE + x + FlatPathSearch.RADIUS;
    }

    private static boolean[] ground() {
        final boolean[] walkable = new boolean[FlatPathSearch.CELLS];
        Arrays.fill(walkable, true);
        return walkable;
    }
}
