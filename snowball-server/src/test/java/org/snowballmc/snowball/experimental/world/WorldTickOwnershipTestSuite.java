package org.snowballmc.snowball.experimental.world;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@Timeout(15)
class WorldTickOwnershipTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void worldWorkerIsNotPrimaryAndCannotWaitForChunksOrReadForeignChunkCaches() throws Exception {
        ServerLevel first = world(Level.OVERWORLD);
        ServerLevel second = world(Level.NETHER);
        ServerChunkCache ownChunks = chunkCache(first);
        ServerChunkCache foreignChunks = chunkCache(second);
        var unownedAtExit = new AtomicBoolean();

        try (var ticker = new ParallelWorldTicker<ServerLevel>(1, work -> new TickThread.WorldTickThread(() -> {
            work.run();
            unownedAtExit.set(!TickThread.isTickThreadFor(first) && !TickThread.isTickThreadFor(second));
        }, "world ownership test"))) {
            ticker.tick(List.of(first), level -> {
                assertTrue(TickThread.isWorldTickThread());
                assertFalse(TickThread.isTickThread());
                assertTrue(TickThread.isTickThreadFor(first, BlockPos.ZERO));
                assertFalse(TickThread.isTickThreadFor(second, BlockPos.ZERO));
                assertThrows(IllegalStateException.class, () -> foreignChunks.getChunkNow(0, 0));
                var pending = ownChunks.getChunkFuture(0, 0, ChunkStatus.FULL, true);
                assertTrue(pending.isCompletedExceptionally());
                assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, pending::join).getCause());
            });
        }
        assertTrue(unownedAtExit.get());
    }

    private static ServerLevel world(ResourceKey<Level> dimension) {
        ServerLevel world = mock(ServerLevel.class);
        doReturn(dimension).when(world).dimension();
        return world;
    }

    private static ServerChunkCache chunkCache(ServerLevel world) throws ReflectiveOperationException {
        // These fail-fast paths must return before they touch an executor or the unloaded cache.
        ServerChunkCache cache = mock(ServerChunkCache.class, CALLS_REAL_METHODS);
        Field owner = ServerChunkCache.class.getDeclaredField("level");
        owner.setAccessible(true);
        owner.set(cache, world);
        return cache;
    }
}
