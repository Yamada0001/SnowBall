package org.snowballmc.snowball.pathfinding;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;
import org.jspecify.annotations.Nullable;

/** Owner-thread capture and validation. Only {@link FlatPathSearch.Input} may leave this thread. */
public final class FlatPathSnapshot {
    // The extra ring covers WalkNodeEvaluator's neighboring-block classification, not search expansion.
    private static final int CAPTURE_RADIUS = FlatPathSearch.RADIUS + 1;
    private static final int CAPTURE_SIDE = 2 * CAPTURE_RADIUS + 1;
    private static final int CAPTURE_CELLS = CAPTURE_SIDE * CAPTURE_SIDE;

    private final BlockPos origin;
    private final BlockPos target;
    private final BlockState[] blocks;
    private final float width;
    private final float height;
    private final FlatPathSearch.Input input;

    private FlatPathSnapshot(final BlockPos origin, final BlockPos target, final BlockState[] blocks, final float width, final float height,
                             final FlatPathSearch.Input input) {
        this.origin = origin;
        this.target = target;
        this.blocks = blocks;
        this.width = width;
        this.height = height;
        this.input = input;
    }

    public static boolean isOwnerThread(final ServerLevel level) {
        return Thread.currentThread() == level.getServer().getRunningThread();
    }

    private static boolean supports(final ServerLevel level, final Mob mob) {
        if (!isOwnerThread(level) || level.getServer().hasStopped() || mob.level() != level || !mob.valid || !mob.isAlive() || mob.isNoAi()
            || !mob.onGround() || mob.isInLiquid() || mob.isPassenger() || mob.isVehicle()
            || EntityPathfindEvent.getHandlerList().getRegisteredListeners().length != 0) {
            return false;
        }
        // Exact classes: no evaluator hooks or inherited vehicle maluses. Do not admit custom NMS subclasses.
        final Class<?> type = mob.getClass();
        return (type == Chicken.class || type == Cow.class || type == Pig.class || type == Sheep.class || type == Zombie.class)
            && mob.getBbWidth() > 0.0F && Mth.floor(mob.getBbWidth() + 1.0F) == 1
            && mob.getBbHeight() > 0.0F && Mth.floor(mob.getBbHeight() + 1.0F) <= 2
            && mob.maxUpStep() < 2.0F
            && Float.compare(mob.getPathfindingMalus(PathType.WALKABLE), 0.0F) == 0
            && Float.compare(mob.getPathfindingMalus(PathType.OPEN), 0.0F) == 0
            && Float.compare(mob.getPathfindingMalus(PathType.BLOCKED), -1.0F) == 0;
    }

    public static @Nullable FlatPathSnapshot capture(final ServerLevel level, final Mob mob, final BlockPos target, final int reachRange,
                                                     final float maxLength, final int maxVisitedNodes) {
        if (maxVisitedNodes <= 1 || reachRange < 0 || !Float.isFinite(maxLength) || maxLength < CAPTURE_RADIUS + 1 || !supports(level, mob)) {
            return null;
        }
        final BlockPos origin = mob.blockPosition();
        final int y = origin.getY();
        final int dx = target.getX() - origin.getX();
        final int dz = target.getZ() - origin.getZ();
        if (target.getY() != y || y != Mth.floor(mob.getY() + 0.5) || level.isOutsideBuildHeight(y - 1) || level.isOutsideBuildHeight(y + 1)
            || Math.abs(dx) >= FlatPathSearch.RADIUS || Math.abs(dz) >= FlatPathSearch.RADIUS || Math.abs(dx) + Math.abs(dz) <= reachRange
            || !level.getWorldBorder().isWithinBounds(origin.offset(-CAPTURE_RADIUS, 0, -CAPTURE_RADIUS))
            || !level.getWorldBorder().isWithinBounds(origin.offset(CAPTURE_RADIUS, 0, CAPTURE_RADIUS))) {
            return null;
        }

        final BlockState[] blocks = new BlockState[CAPTURE_CELLS * 3];
        final boolean[] walkable = new boolean[FlatPathSearch.CELLS];
        if (!readTerrain(level, origin, blocks, walkable)
            || !walkable[FlatPathSearch.START]
            || !walkable[(dz + FlatPathSearch.RADIUS) * FlatPathSearch.SIDE + dx + FlatPathSearch.RADIUS]) {
            return null;
        }
        return new FlatPathSnapshot(origin, target.immutable(), blocks, mob.getBbWidth(), mob.getBbHeight(),
            new FlatPathSearch.Input(walkable, dx, dz, reachRange, maxLength, maxVisitedNodes));
    }

    public FlatPathSearch.Input input() {
        return this.input;
    }

    public boolean matches(final ServerLevel level, final Mob mob, final BlockPos target, final int reachRange,
                           final float maxLength, final int maxVisitedNodes) {
        return supports(level, mob) && this.target.equals(target) && this.origin.equals(mob.blockPosition())
            && this.origin.getY() == Mth.floor(mob.getY() + 0.5) && this.width == mob.getBbWidth() && this.height == mob.getBbHeight()
            && this.input.reachRange() == reachRange && this.input.maxLength() == maxLength && this.input.maxVisitedNodes() == maxVisitedNodes
            && level.getWorldBorder().isWithinBounds(this.origin.offset(-CAPTURE_RADIUS, 0, -CAPTURE_RADIUS))
            && level.getWorldBorder().isWithinBounds(this.origin.offset(CAPTURE_RADIUS, 0, CAPTURE_RADIUS))
            && readTerrain(level, this.origin, this.blocks, null);
    }

    private static boolean readTerrain(final ServerLevel level, final BlockPos origin, final BlockState[] blocks, final boolean @Nullable [] walkable) {
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The 19x19 footprint overlaps at most three chunks on each axis. Fetch each once and never load a chunk.
        final int minX = origin.getX() - CAPTURE_RADIUS;
        final int minZ = origin.getZ() - CAPTURE_RADIUS;
        final int maxChunkX = (minX + CAPTURE_SIDE - 1) >> 4;
        final int maxChunkZ = (minZ + CAPTURE_SIDE - 1) >> 4;
        final int minChunkX = minX >> 4;
        final int minChunkZ = minZ >> 4;
        final LevelChunk[] chunks = new LevelChunk[9];
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                final LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(cx, cz);
                if (chunk == null) {
                    return false;
                }
                chunks[(cz - minChunkZ) * 3 + cx - minChunkX] = chunk;
            }
        }
        // Reject custom datapack tags that change path classification, including on revalidation.
        final Set<BlockState> palette = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int iz = 0; iz < CAPTURE_SIDE; iz++) {
            for (int ix = 0; ix < CAPTURE_SIDE; ix++) {
                final int x = minX + ix;
                final int z = minZ + iz;
                final int y = origin.getY();
                final LevelChunk chunk = chunks[((z >> 4) - minChunkZ) * 3 + (x >> 4) - minChunkX];
                final BlockState floor = chunk.getBlockState(cursor.set(x, y - 1, z));
                final BlockState feet = chunk.getBlockState(cursor.set(x, y, z));
                final BlockState head = chunk.getBlockState(cursor.set(x, y + 1, z));
                final int index = 3 * (iz * CAPTURE_SIDE + ix);
                if ((palette.add(floor) && !ordinaryState(floor)) || (palette.add(feet) && !ordinaryState(feet))
                    || (palette.add(head) && !ordinaryState(head))) {
                    return false;
                }
                if (walkable == null) {
                    if (floor != blocks[index] || feet != blocks[index + 1] || head != blocks[index + 2]) {
                        return false;
                    }
                } else {
                    final boolean clear = air(feet) && air(head);
                    if (!solid(floor) || (!clear && !(solid(feet) && solid(head)))) {
                        return false;
                    }
                    blocks[index] = floor;
                    blocks[index + 1] = feet;
                    blocks[index + 2] = head;
                    if (ix > 0 && iz > 0 && ix < CAPTURE_SIDE - 1 && iz < CAPTURE_SIDE - 1) {
                        walkable[(iz - 1) * FlatPathSearch.SIDE + ix - 1] = clear;
                    }
                }
            }
        }
        return true;
    }

    private static boolean air(final BlockState state) {
        return state.is(Blocks.AIR) || state.is(Blocks.CAVE_AIR);
    }

    private static boolean solid(final BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.BRICKS)
            || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.BEDROCK) || state.is(Blocks.SMOOTH_STONE);
    }

    private static boolean ordinaryState(final BlockState state) {
        // Air returns OPEN before inspecting tags in WalkNodeEvaluator#getPathTypeFromState.
        return air(state) || (solid(state) && !state.is(BlockTags.TRAPDOORS) && !state.is(BlockTags.SPELEOTHEMS)
            && !state.is(BlockTags.FIRE) && !state.is(BlockTags.FENCES) && !state.is(BlockTags.WALLS)
            && !state.is(BlockTags.CAULDRONS) && state.getFluidState().isEmpty()
            && !state.isPathfindable(PathComputationType.LAND));
    }

    public Path makePath(final FlatPathSearch.Result result) {
        final FlatPathSearch.Step[] steps = result.steps();
        final ArrayList<Node> nodes = new ArrayList<>(steps.length);
        Node previous = null;
        for (final FlatPathSearch.Step step : steps) {
            final Node node = new Node(this.origin.getX() + step.x(), this.origin.getY(), this.origin.getZ() + step.z());
            node.type = PathType.WALKABLE;
            node.g = step.g();
            node.h = step.h();
            node.f = step.f();
            node.walkedDistance = step.walkedDistance();
            node.closed = step.closed();
            node.heapIdx = step.heapIndex();
            node.cameFrom = previous;
            nodes.add(node);
            previous = node;
        }
        return new Path(nodes, this.target, true);
    }
}
