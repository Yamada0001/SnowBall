package org.snowballmc.snowball.experimental.culling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.jspecify.annotations.Nullable;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.experimental.ExperimentalConfig;
import org.snowballmc.snowball.experimental.ExperimentalConfig.Culling;

/** Owner-thread capture and validation. Workers never receive entity, player, chunk, or level references. */
public final class EntityCullingManager implements AutoCloseable {
    private static final int MAX_PAIRS = 128;
    private static final int MAX_CAPTURES_PER_PLAYER = 16;
    private static final int MAX_CHUNK_READS_PER_TICK = 2048;
    private static final int MAX_CELLS_PER_TICK = 8192;
    private static final int MAX_SLICES = 160;
    private static final int MAX_CELLS_PER_PAIR = 768;
    private static final double POSE_MARGIN = 0.25;
    private static final int MAX_LATENCY_MS = 200;

    private final CullingWorker worker = new CullingWorker();
    private volatile boolean closed;
    private int budgetTick;
    private int chunkBudget;
    private int cellBudget;

    public static boolean enabled() {
        return ExperimentalConfig.current().culling().enabled();
    }

    public static boolean opaque(final BlockState state) {
        // Collision alone includes glass and thin blocks; only full, opaque render cubes count.
        return state.isSolidRender() && state.getLightDampening() >= 15 && !state.getBlock().hasDynamicShape();
    }

    private boolean available(final ServerPlayer player, final ServerLevel level) {
        // Paper #257 owns these objects on the primary thread. Do not opt into any world-thread experiment.
        return !closed && Thread.currentThread() == level.getServer().getRunningThread()
            && player.valid && !player.isRemoved() && !player.isSpectator() && !player.isSleeping()
            && !(player instanceof ServerBot) && !player.isPassenger() && player.connection != null
            && player.connection.isAcceptingMessages() && !player.connection.snowball$isAwaitingCullingTeleport()
            && player.connection.latency() <= MAX_LATENCY_MS;
    }

    private static boolean exempt(final Entity target, final Culling config) {
        return target.isRemoved() || target instanceof ServerBot || target instanceof Display || target instanceof FishingHook
            || target instanceof Interaction || target.isPassenger() || target.isVehicle()
            || target instanceof Leashable leashable && leashable.isLeashed()
            || target.isCurrentlyGlowing() || target.isCustomNameVisible()
            || target instanceof ServerPlayer watched && (!config.cullPlayers() || watched.connection == null
                || watched.connection.snowball$isAwaitingCullingTeleport())
            // The legacy key exempts all invisible armor stands, even when Marker is false.
            || config.skipMarkerArmorStands() && target instanceof ArmorStand && target.isInvisible()
            || !config.skippedEntityTypes().isEmpty()
                && config.skippedEntityTypes().contains(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
    }

    /** Only called as a veto after the caller's normal tracking/visibility checks. */
    public boolean shouldCull(final ServerPlayer player, final Entity target) {
        final Culling config = ExperimentalConfig.current().culling();
        if (!config.enabled()) {
            return false;
        }
        final ServerLevel level = player.level();
        if (player == target || target.level() != level || !available(player, level)) {
            return false;
        }
        Observer observer = player.snowball$cullingObserver;
        if (exempt(target, config)) {
            if (observer != null) {
                final Pair pair = observer.pairs.get(target);
                if (pair != null) {
                    pair.proof = null;
                    pair.epoch++;
                }
            }
            return false;
        }
        if (observer == null || observer.level != level) {
            forget(player);
            player.snowball$cullingObserver = observer = new Observer(level);
        }
        Pair pair = observer.pairs.get(target);
        if (pair == null) {
            if (observer.pairs.size() >= MAX_PAIRS || !inRange(player.getEyePosition(), box(target.getBoundingBox()), config)) {
                return false;
            }
            pair = new Pair(target);
            observer.pairs.put(target, pair);
            observer.rotation.addLast(pair);
        }
        pair.lastDemand = observer.round;
        final Proof proof = pair.proof;
        if (proof != null) {
            final long now = System.nanoTime();
            if (proof.valid(player, pair, config, now)) {
                return true;
            }
            pair.proof = null;
        }
        return false;
    }

    public void forEachOccludedWaypoint(final ServerPlayer player, final Consumer<WaypointTransmitter> action) {
        final Observer observer = player.snowball$cullingObserver;
        if (observer != null) {
            for (final Pair pair : observer.rotation) {
                if (pair.proof != null && pair.target instanceof WaypointTransmitter waypoint) {
                    action.accept(waypoint);
                }
            }
        }
    }

    public void forget(final ServerPlayer player) {
        final Observer observer = player.snowball$cullingObserver;
        if (observer != null) {
            player.snowball$cullingObserver = null;
            if (observer.pending != null) {
                worker.cancel(observer.pending.result);
            }
        }
    }

    public void tick(final ServerPlayer player) {
        final Culling config = ExperimentalConfig.current().culling();
        if (!config.enabled()) {
            return;
        }
        final ServerLevel level = player.level();
        if (!available(player, level)) {
            if (Thread.currentThread() == level.getServer().getRunningThread()) {
                forget(player);
            }
            return;
        }
        final Observer observer = player.snowball$cullingObserver;
        if (observer == null) {
            return;
        }
        if (observer.level != level) {
            forget(player);
            return;
        }
        ++observer.round;
        final long now = System.nanoTime();
        if (observer.pending != null && !Raytrace.fresh(observer.pending.captured, now, config.checkIntervalMs())) {
            worker.cancel(observer.pending.result);
            observer.pending = null;
        }
        accept(player, observer, config, now);
        for (final var iterator = observer.rotation.iterator(); iterator.hasNext();) {
            final Pair pair = iterator.next();
            if (pair.target.isRemoved() || pair.target.level() != level || observer.round - pair.lastDemand > 2) {
                iterator.remove();
                observer.pairs.remove(pair.target);
            }
        }
        if (observer.pending != null || (observer.scheduled && now - observer.nextCheck < 0)) {
            return;
        }
        final MinecraftServer server = level.getServer();
        final int tick = server.getTickCount();
        if (budgetTick != tick) {
            budgetTick = tick;
            chunkBudget = MAX_CHUNK_READS_PER_TICK;
            cellBudget = MAX_CELLS_PER_TICK;
        }
        if (chunkBudget == 0 || cellBudget == 0) {
            return;
        }
        final List<Proof> proofs = new ArrayList<>();
        final List<Raytrace.Capture> captures = new ArrayList<>();
        final Vec3 eye = player.getEyePosition();
        final Raytrace.Box eyeBox = new Raytrace.Box(eye.x, eye.y, eye.z, eye.x, eye.y, eye.z).expand(POSE_MARGIN);
        final long quiet = TimeUnit.MILLISECONDS.toNanos(config.visibilityTimeoutMs());
        // Rotate even the pairs that cannot be proved occluded. Congestion never extends an old proof.
        for (int i = observer.rotation.size(); i > 0 && captures.size() < MAX_CAPTURES_PER_PLAYER && chunkBudget > 0 && cellBudget > 0; --i) {
            final Pair pair = observer.rotation.removeFirst();
            observer.rotation.addLast(pair);
            if ((pair.hasVisibleResult && now - pair.lastVisible < quiet) || exempt(pair.target, config)) {
                continue;
            }
            final Raytrace.Box raw = box(pair.target.getBoundingBox());
            if (!inRange(eye, raw, config)) {
                continue;
            }
            final Raytrace.Box targetBox = raw.expand(config.aabbExpansion() + POSE_MARGIN);
            if (!targetBox.valid() || targetBox.farthestSquared(eye.x, eye.y, eye.z) > square(config.maxTraceDistance())) {
                continue;
            }
            final Raytrace.Capture capture = capture(player, level, eyeBox, targetBox, pair, now, proofs);
            if (capture != null) {
                captures.add(capture);
            }
        }
        if (!captures.isEmpty()) {
            final Future<BitSet> result = worker.submit(config.threads(), captures);
            if (result != null) {
                observer.pending = new Pending(proofs, result, now);
            } else {
                for (final Proof proof : proofs) {
                    proof.pair.proof = null;
                }
            }
        }
        observer.scheduled = true;
        observer.nextCheck = now + TimeUnit.MILLISECONDS.toNanos(config.checkIntervalMs());
    }

    private static void accept(final ServerPlayer player, final Observer observer, final Culling config, final long now) {
        final Pending pending = observer.pending;
        if (pending == null || !pending.result.isDone()) {
            return;
        }
        observer.pending = null;
        BitSet hidden = null;
        try {
            hidden = pending.result.get();
        } catch (ExecutionException | CancellationException ignored) {
            // Missing results cannot hide anything.
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < pending.proofs.size(); ++i) {
            final Proof proof = pending.proofs.get(i);
            final Pair pair = proof.pair;
            if (observer.pairs.get(pair.target) != pair || !proof.valid(player, pair, config, now)) {
                continue;
            }
            if (hidden != null && hidden.get(i)) {
                pair.proof = proof;
            } else {
                pair.proof = null;
                if (hidden != null) {
                    pair.hasVisibleResult = true;
                    pair.lastVisible = now;
                }
            }
        }
    }

    private Raytrace.@Nullable Capture capture(final ServerPlayer player, final ServerLevel level,
                                               final Raytrace.Box eye, final Raytrace.Box target, final Pair pair,
                                               final long now, final List<Proof> proofs) {
        final int axis = Raytrace.axis(eye, target);
        if (axis < 0) {
            return null;
        }
        final int low = (int)Math.floor(Math.min(eye.max(axis), target.max(axis)));
        final int high = (int)Math.floor(Math.max(eye.min(axis), target.min(axis)));
        if (high < low) {
            return null;
        }
        final int minX = (int)Math.floor(Math.min(eye.minX(), target.minX())) >> 4;
        final int minZ = (int)Math.floor(Math.min(eye.minZ(), target.minZ())) >> 4;
        final int maxX = (int)Math.floor(Math.max(eye.maxX(), target.maxX())) >> 4;
        final int maxZ = (int)Math.floor(Math.max(eye.maxZ(), target.maxZ())) >> 4;
        final int width = maxX - minX + 1;
        final int depth = maxZ - minZ + 1;
        if (width * depth > chunkBudget) {
            chunkBudget = 0;
            return null;
        }
        chunkBudget -= width * depth;
        final ChunkStamp[] chunks = new ChunkStamp[width * depth];
        final var source = level.getChunkSource();
        for (int z = minZ; z <= maxZ; ++z) {
            for (int x = minX; x <= maxX; ++x) {
                final LevelChunk chunk = source.getChunkAtIfLoadedImmediately(x, z);
                if (chunk == null || !source.chunkMap.isChunkTracked(player, x, z)) {
                    return null;
                }
                chunks[(z - minZ) * width + x - minX] = new ChunkStamp(x, z, chunk, chunk.snowball$cullingRevision());
            }
        }
        final byte[] cells = new byte[Math.min(MAX_CELLS_PER_PAIR, cellBudget)];
        final int[] ends = new int[Math.min(MAX_SLICES, high - low + 1)];
        int size = 0;
        int slices = 0;
        // Sample evenly over the span, including both ends. Finding no certificate is always visible.
        for (int i = 0; i < ends.length; ++i) {
            final int plane = ends.length == 1 ? low : low + i * (high - low) / (ends.length - 1);
            final Raytrace.Slice slice = Raytrace.slice(eye, target, axis, plane);
            if (slice == null || slice.cells() > cells.length - size) {
                continue;
            }
            for (int u = slice.minU(); u <= slice.maxU(); ++u) {
                for (int v = slice.minV(); v <= slice.maxV(); ++v) {
                    final int x = axis == 0 ? plane : axis == 1 ? v : u;
                    final int y = axis == 0 ? u : axis == 1 ? plane : v;
                    final int z = axis == 0 ? v : axis == 1 ? u : plane;
                    final int chunkX = (x >> 4) - minX;
                    final int chunkZ = (z >> 4) - minZ;
                    if (chunkX >= 0 && chunkX < width && chunkZ >= 0 && chunkZ < depth
                        && opaque(chunks[chunkZ * width + chunkX].chunk.getBlockStateFinal(x, y, z))) {
                        cells[size] = 1;
                    }
                    ++size;
                }
            }
            ends[slices++] = size;
        }
        cellBudget -= size;
        if (slices == 0) {
            return null;
        }
        proofs.add(new Proof(pair, pair.epoch, eye, target, chunks, now));
        return new Raytrace.Capture(java.util.Arrays.copyOf(cells, size), java.util.Arrays.copyOf(ends, slices));
    }

    private static boolean inRange(final Vec3 eye, final Raytrace.Box box, final Culling config) {
        return box.valid() && box.fits(config.boundingBoxLimit())
            && box.distanceSquared(eye.x, eye.y, eye.z) > square(config.forceVisibleRadius())
            && box.distanceSquared(eye.x, eye.y, eye.z) <= square(config.maxTraceDistance());
    }

    private static double square(final double number) {
        return number * number;
    }

    private static Raytrace.Box box(final AABB value) {
        return new Raytrace.Box(value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ);
    }

    @Override
    public void close() {
        closed = true;
        worker.close();
    }

    // Confined to the observer's owner; reference identity prevents numeric ID reuse.
    public static final class Observer {
        private final ServerLevel level;
        private final IdentityHashMap<Entity, Pair> pairs = new IdentityHashMap<>();
        private final ArrayDeque<Pair> rotation = new ArrayDeque<>();
        private @Nullable Pending pending;
        private boolean scheduled;
        private long nextCheck;
        private int round;

        private Observer(final ServerLevel level) {
            this.level = level;
        }
    }

    private static final class Pair {
        private final Entity target;
        private int lastDemand;
        private int epoch;
        private boolean hasVisibleResult;
        private long lastVisible;
        private @Nullable Proof proof;

        private Pair(final Entity target) {
            this.target = target;
        }
    }

    private record ChunkStamp(int x, int z, LevelChunk chunk, long revision) {
        boolean matches(final ServerPlayer player, final ServerLevel level) {
            final var source = level.getChunkSource();
            return source.getChunkAtIfLoadedImmediately(x, z) == chunk && chunk.snowball$cullingRevision() == revision
                && source.chunkMap.isChunkTracked(player, x, z);
        }
    }

    private record Pending(List<Proof> proofs, Future<BitSet> result, long captured) {
    }

    private record Proof(Pair pair, int epoch, Raytrace.Box eyeBox, Raytrace.Box targetBox, ChunkStamp[] chunks, long captured) {
        boolean valid(final ServerPlayer player, final Pair current, final Culling config, final long now) {
            final Entity target = current.target;
            final ServerLevel level = player.level();
            if (!Raytrace.fresh(captured, now, config.checkIntervalMs()) || current.epoch != epoch || target.level() != level || exempt(target, config)) {
                return false;
            }
            final Vec3 eye = player.getEyePosition();
            final Raytrace.Box actual = box(target.getBoundingBox());
            if (!eyeBox.contains(new Raytrace.Box(eye.x, eye.y, eye.z, eye.x, eye.y, eye.z))
                || !targetBox.contains(actual.expand(config.aabbExpansion())) || !inRange(eye, actual, config)) {
                return false;
            }
            for (final ChunkStamp chunk : chunks) {
                if (!chunk.matches(player, level)) {
                    return false;
                }
            }
            return true;
        }
    }
}
