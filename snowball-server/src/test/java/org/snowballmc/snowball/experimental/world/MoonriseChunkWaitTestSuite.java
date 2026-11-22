package org.snowballmc.snowball.experimental.world;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.ChunkInfo;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(20)
class MoonriseChunkWaitTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void interleavedWaitersDoNotPopEachOthersRecords() throws Exception {
        ServerLevel firstWorld = mock(ServerLevel.class);
        ServerLevel secondWorld = mock(ServerLevel.class);
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var finishSecond = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            try {
                var first = threads.submit(() -> ChunkTaskScheduler.runWithChunkWait(firstWorld, 1, 2, () -> {
                    firstEntered.countDown();
                    await(finishFirst);
                }));
                await(firstEntered);
                var second = threads.submit(() -> ChunkTaskScheduler.runWithChunkWait(secondWorld, 3, 4, () -> {
                    secondEntered.countDown();
                    await(finishSecond);
                }));
                await(secondEntered);
                var both = entriesFor(firstWorld, secondWorld);
                assertEquals(2, both.size());
                assertNotEquals(both.get(0).threadId, both.get(1).threadId);
                assertNotEquals(Thread.currentThread().threadId(), both.get(0).threadId);

                // A entered first but leaves while B is still waiting; a global LIFO would remove B.
                finishFirst.countDown();
                first.get(5, TimeUnit.SECONDS);
                var remaining = entriesFor(firstWorld, secondWorld);
                assertEquals(1, remaining.size());
                assertSame(secondWorld, remaining.getFirst().world);
                assertEquals(3, remaining.getFirst().chunkX);
                assertEquals(4, remaining.getFirst().chunkZ);
                assertEquals(2, both.size());

                finishSecond.countDown();
                second.get(5, TimeUnit.SECONDS);
                assertTrue(entriesFor(firstWorld, secondWorld).isEmpty());
            } finally {
                finishFirst.countDown();
                finishSecond.countDown();
            }
        }
    }

    @Test
    void nestedFailureLeavesOuterWaitInLifoOrder() {
        ServerLevel world = mock(ServerLevel.class);
        var injected = new IllegalStateException("nested blocking failure");
        ChunkTaskScheduler.runWithChunkWait(world, 10, 20, () -> {
            assertEquals(List.of(10), entriesFor(world).stream().map(info -> info.chunkX).toList());
            assertSame(injected, assertThrows(IllegalStateException.class, () ->
                ChunkTaskScheduler.runWithChunkWait(world, 11, 21, () -> {
                    var waits = entriesFor(world);
                    assertEquals(List.of(11, 10), waits.stream().map(info -> info.chunkX).toList());
                    assertTrue(waits.stream().allMatch(info -> info.threadId == Thread.currentThread().threadId()));
                    assertEquals(Thread.currentThread().getName(), waits.getFirst().threadName);
                    throw injected;
                })));
            assertEquals(List.of(10), entriesFor(world).stream().map(info -> info.chunkX).toList());
        });
        assertTrue(entriesFor(world).isEmpty());
    }

    @Test
    void topLevelFailureDoesNotLeaveAStaleWaitOnTheSameThread() {
        ServerLevel world = mock(ServerLevel.class);
        var injected = new AssertionError("blocking task failed");
        assertSame(injected, assertThrows(AssertionError.class, () ->
            ChunkTaskScheduler.runWithChunkWait(world, 1, 2, () -> { throw injected; })));
        assertTrue(entriesFor(world).isEmpty());
        ChunkTaskScheduler.runWithChunkWait(world, 1, 2, () -> assertEquals(1, entriesFor(world).size()));
        assertTrue(entriesFor(world).isEmpty());
    }

    private static List<ChunkInfo> entriesFor(ServerLevel... worlds) {
        return Arrays.stream(ChunkTaskScheduler.getChunkInfos())
            .filter(info -> Arrays.stream(worlds).anyMatch(world -> info.world == world))
            .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for the other thread");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the other thread", e);
        }
    }
}
