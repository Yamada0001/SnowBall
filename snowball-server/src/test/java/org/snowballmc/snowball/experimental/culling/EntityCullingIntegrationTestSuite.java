package org.snowballmc.snowball.experimental.culling;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.snowballmc.snowball.experimental.ExperimentalConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Suite
@SelectClasses(EntityCullingIntegrationTest.class)
public class EntityCullingIntegrationTestSuite {
}

class EntityCullingIntegrationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void onlyFullOpaqueCubesOcclude() {
        assertTrue(EntityCullingManager.opaque(Blocks.STONE.defaultBlockState()));
        assertFalse(EntityCullingManager.opaque(Blocks.AIR.defaultBlockState()));
        assertFalse(EntityCullingManager.opaque(Blocks.GLASS.defaultBlockState()));
        assertFalse(EntityCullingManager.opaque(Blocks.STONE_SLAB.defaultBlockState()));
        assertFalse(EntityCullingManager.opaque(Blocks.OAK_DOOR.defaultBlockState()));
        assertFalse(EntityCullingManager.opaque(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true)));
    }

    @Test
    void revisionMovementReplacementTeleportAndLatencyInvalidateAcceptedEvidence() throws ReflectiveOperationException {
        final Fixture fixture = new Fixture();
        try (final var config = mockStatic(ExperimentalConfig.class); final var manager = new EntityCullingManager()) {
            config.when(ExperimentalConfig::current).thenReturn(settings(true));
            final Entity target = fixture.target(Entity.class);
            fixture.awaitHidden(manager, target);

            fixture.wall.set(false);
            fixture.revision.incrementAndGet();
            assertFalse(manager.shouldCull(fixture.player, target), "changed opacity invalidates without a new capture");
            fixture.wall.set(true);
            fixture.revision.incrementAndGet();
            fixture.awaitHidden(manager, target);
            fixture.chunk.set(null);
            assertFalse(manager.shouldCull(fixture.player, target), "unloaded chunks are never trusted");

            fixture.chunk.set(fixture.stoneChunk);
            fixture.awaitHidden(manager, target);
            final Entity replacement = fixture.target(Entity.class);
            assertFalse(manager.shouldCull(fixture.player, replacement), "the same numeric ID is not the same entity");
            fixture.eye.set(new Vec3(0.5, 1.62, 1.25));
            assertFalse(manager.shouldCull(fixture.player, target), "moving the eye outside its certificate restores visibility");

            fixture.eye.set(new Vec3(0.5, 1.62, 0.5));
            fixture.awaitHidden(manager, target);
            when(fixture.player.connection.latency()).thenReturn(201);
            assertFalse(manager.shouldCull(fixture.player, target));
            manager.tick(fixture.player);
            assertNull(fixture.player.snowball$cullingObserver);

            when(fixture.player.connection.latency()).thenReturn(0);
            fixture.awaitHidden(manager, target);
            when(fixture.player.connection.snowball$isAwaitingCullingTeleport()).thenReturn(true);
            assertFalse(manager.shouldCull(fixture.player, target));
            manager.forget(fixture.player);
            assertNull(fixture.player.snowball$cullingObserver);
        }
    }

    @Test
    void legacyMarkerSettingExemptsInvisibleNonMarkerArmorStands() throws ReflectiveOperationException {
        final Fixture fixture = new Fixture();
        try (final var config = mockStatic(ExperimentalConfig.class); final var manager = new EntityCullingManager()) {
            config.when(ExperimentalConfig::current).thenReturn(settings(true));
            final ArmorStand target = fixture.target(ArmorStand.class);
            when(target.isInvisible()).thenReturn(true);
            when(target.isMarker()).thenReturn(false);
            assertFalse(manager.shouldCull(fixture.player, target));
            assertNull(fixture.player.snowball$cullingObserver);

            config.when(ExperimentalConfig::current).thenReturn(settings(false));
            fixture.awaitHidden(manager, target);
        }
    }

    @Test
    void locatorRestoresStationaryPlayersWithoutBypassingCanSee() throws ReflectiveOperationException {
        final Fixture fixture = new Fixture();
        try (final var config = mockStatic(ExperimentalConfig.class); final var manager = new EntityCullingManager()) {
            config.when(ExperimentalConfig::current).thenReturn(settings(true));
            final var managerField = MinecraftServer.class.getField("snowballEntityCulling");
            managerField.setAccessible(true);
            managerField.set(fixture.level.getServer(), manager);
            when(fixture.level.getGameRules()).thenReturn(new GameRules(FeatureFlags.DEFAULT_FLAGS));
            final ServerWaypointManager waypoints = new ServerWaypointManager(fixture.level);
            waypoints.locatorBarEnabled = true;

            final LivingEntity target = fixture.target(LivingEntity.class);
            final CraftLivingEntity bukkitTarget = mock(CraftLivingEntity.class);
            final CraftPlayer viewer = mock(CraftPlayer.class);
            final AtomicBoolean canSee = new AtomicBoolean(true);
            final var connection = mock(WaypointTransmitter.Connection.class);
            when(target.getBukkitEntity()).thenReturn(bukkitTarget);
            when(fixture.player.getBukkitEntity()).thenReturn(viewer);
            when(viewer.canSee(bukkitTarget)).thenAnswer(call -> canSee.get());
            when(target.getAttributeValue(Attributes.WAYPOINT_TRANSMIT_RANGE)).thenReturn(64.0);
            when(fixture.player.getAttributeValue(Attributes.WAYPOINT_RECEIVE_RANGE)).thenReturn(64.0);
            when(target.makeWaypointConnectionWith(fixture.player)).thenAnswer(call ->
                WaypointTransmitter.doesSourceIgnoreReceiver(target, fixture.player) ? Optional.empty() : Optional.of(connection));

            waypoints.addPlayer(fixture.player);
            waypoints.trackWaypoint(target);
            verify(connection).connect();
            fixture.awaitHidden(manager, target);
            waypoints.snowball$refreshCulling(fixture.player);
            verify(connection).disconnect();

            fixture.revision.incrementAndGet();
            waypoints.snowball$refreshCulling(fixture.player);
            verify(connection, times(2)).connect();
            fixture.awaitHidden(manager, target);
            waypoints.snowball$refreshCulling(fixture.player);
            verify(connection, times(2)).disconnect();

            canSee.set(false);
            fixture.revision.incrementAndGet();
            waypoints.snowball$refreshCulling(fixture.player);
            verify(connection, times(2)).connect();
        }
    }

    private static ExperimentalConfig.Settings settings(final boolean skipInvisibleStands) {
        return new ExperimentalConfig.Settings(null, null,
            new ExperimentalConfig.Culling(true, 1, 10, 64, 1.0, 0, 0.25, 20, true, skipInvisibleStands, List.of()));
    }

    private static final class Fixture {
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final LevelChunk stoneChunk = mock(LevelChunk.class);
        private final AtomicReference<LevelChunk> chunk = new AtomicReference<>(stoneChunk);
        private final AtomicReference<Vec3> eye = new AtomicReference<>(new Vec3(0.5, 1.62, 0.5));
        private final AtomicBoolean wall = new AtomicBoolean(true);
        private final AtomicLong revision = new AtomicLong();

        private Fixture() throws ReflectiveOperationException {
            final MinecraftServer server = mock(MinecraftServer.class);
            final ServerChunkCache source = mock(ServerChunkCache.class);
            final ChunkMap chunkMap = mock(ChunkMap.class);
            final var field = ServerChunkCache.class.getField("chunkMap");
            field.setAccessible(true);
            field.set(source, chunkMap);
            final AtomicInteger tick = new AtomicInteger();
            when(server.getTickCount()).thenAnswer(call -> tick.incrementAndGet());
            when(server.getRunningThread()).thenReturn(Thread.currentThread());
            when(level.getServer()).thenReturn(server);
            when(level.getChunkSource()).thenReturn(source);
            when(source.getChunkAtIfLoadedImmediately(anyInt(), anyInt())).thenAnswer(call -> chunk.get());
            when(chunkMap.isChunkTracked(eq(player), anyInt(), anyInt())).thenReturn(true);
            when(stoneChunk.snowball$cullingRevision()).thenAnswer(call -> revision.get());
            when(stoneChunk.getBlockStateFinal(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                wall.get() && (int)call.getArgument(0) == 5 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            when(player.level()).thenReturn(level);
            when(player.getEyePosition()).thenAnswer(call -> eye.get());
            player.valid = true;
            player.connection = mock(ServerGamePacketListenerImpl.class);
            when(player.connection.isAcceptingMessages()).thenReturn(true);
        }

        private <T extends Entity> T target(final Class<T> type) {
            final T target = mock(type);
            when(target.level()).thenReturn(level);
            when(target.getId()).thenReturn(42);
            when(target.getBoundingBox()).thenReturn(new AABB(10.1, 0.25, 0.2, 10.9, 2.0, 0.8));
            return target;
        }

        private void awaitHidden(final EntityCullingManager manager, final Entity target) {
            final long start = System.nanoTime();
            do {
                if (manager.shouldCull(player, target)) {
                    return;
                }
                manager.tick(player);
                LockSupport.parkNanos(1_000_000L);
            } while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
            assertTrue(manager.shouldCull(player, target), "timely opaque wall certificate");
        }
    }
}
