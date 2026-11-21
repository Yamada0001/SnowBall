package org.snowballmc.snowball.ferritecore;

import it.unimi.dsi.fastutil.booleans.BooleanArrays;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.snowballmc.snowball.ferritecore.hash.ArrayVoxelShapeHash;

import java.util.Map;

/**
 * Canonicalising containers for the two allocation heavy parts of {@code BlockBehaviour.Cache}.
 *
 * <p>Ported from FerriteCore (MIT) {@code impl/BlockStateCacheImpl}, reduced to the two normalisation entry points.
 * Upstream wrote the {@code final} {@code collisionShape}/{@code faceSturdy} fields through mixin accessors after the
 * cache had been rebuilt; here the normalisation happens <em>inside</em> {@code BlockBehaviour.Cache}'s constructor,
 * before the values reach their {@code final} fields, so no accessor is needed and nothing is mutated afterwards.</p>
 *
 * <p>{@link #canonicalCollisionShape} does not rewrite the internals of an already built {@link VoxelShape}
 * (upstream's {@code replaceInternals} was dropped): Paper's Moonrise fills fourteen derived cache fields at the end
 * of every concrete {@code VoxelShape} constructor ({@code VoxelShape#moonrise$initCache}), so patching the coordinate
 * arrays afterwards would leave those caches stale.</p>
 *
 * <p><b>Thread safety:</b> these methods are reached from {@code Cache} constructors that run concurrently while
 * {@code Blocks.&lt;clinit&gt;} initialises the block registry. fastutil's {@code OpenCustomHashMap} is not thread safe,
 * so every access is performed while holding the container's monitor; that monitor also publishes the canonical
 * instance to other threads. Blocks are static after start-up, so the lock contention window is start-up only.</p>
 */
public final class BlockStateCacheImpl {
    private static final Map<ArrayVoxelShape, ArrayVoxelShape> CACHE_COLLIDE =
        new Object2ObjectOpenCustomHashMap<>(ArrayVoxelShapeHash.INSTANCE);
    private static final Map<boolean[], boolean[]> CACHE_FACE_STURDY =
        new Object2ObjectOpenCustomHashMap<>(BooleanArrays.HASH_STRATEGY);

    private BlockStateCacheImpl() {
    }

    /**
     * Returns a canonical instance carrying the same collision geometry as {@code raw}, keeping the first instance
     * that was registered for that geometry.
     *
     * <p>Only {@link ArrayVoxelShape} takes part. {@code CubeVoxelShape} and {@code SliceShape} are returned
     * unchanged, and {@code Shapes.empty()} is already a singleton, so its repeated registration costs nothing.</p>
     */
    public static VoxelShape canonicalCollisionShape(final VoxelShape raw) {
        if (!(raw instanceof ArrayVoxelShape array)) {
            return raw;
        }
        synchronized (CACHE_COLLIDE) {
            ArrayVoxelShape canonical = CACHE_COLLIDE.get(array);
            if (canonical == null) {
                CACHE_COLLIDE.put(array, array);
                return array;
            }
            return canonical;
        }
    }

    /**
     * Returns a canonical instance carrying the same {@code faceSturdy} bits as {@code raw}.
     *
     * <p>The array is written exactly once, inside {@code Cache}'s constructor, and is only read afterwards, so sharing
     * it between block states with identical support tables is safe.</p>
     */
    public static boolean[] canonicalFaceSturdy(final boolean[] raw) {
        synchronized (CACHE_FACE_STURDY) {
            boolean[] canonical = CACHE_FACE_STURDY.get(raw);
            if (canonical == null) {
                CACHE_FACE_STURDY.put(raw, raw);
                return raw;
            }
            return canonical;
        }
    }
}
