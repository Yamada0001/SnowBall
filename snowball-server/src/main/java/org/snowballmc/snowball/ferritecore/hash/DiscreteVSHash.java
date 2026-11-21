package org.snowballmc.snowball.ferritecore.hash;

import it.unimi.dsi.fastutil.Hash;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;

import java.util.Objects;

/**
 * Content based {@link Hash.Strategy} for {@link DiscreteVoxelShape}.
 *
 * <p>Ported from FerriteCore (MIT) {@code hash/DiscreteVSHash}. Two upstream branches were dropped:</p>
 * <ul>
 *   <li>{@code SubShapeAccess}: {@code SubShape} still keeps its bounds in {@code private final} fields, so
 *       reading them would require another accessor mixin. {@link #equals} therefore falls back to the shape's
 *       own {@code equals} (identity for {@code SubShape}), i.e. such shapes are simply never deduplicated.
 *       This is conservative: it can only lose a deduplication, never merge two different shapes.</li>
 *   <li>{@code BitSetDVSAccess}: Paper already made {@link BitSetDiscreteVoxelShape}'s fields public.</li>
 * </ul>
 */
public final class DiscreteVSHash implements Hash.Strategy<DiscreteVoxelShape> {
    public static final DiscreteVSHash INSTANCE = new DiscreteVSHash();

    private DiscreteVSHash() {
    }

    @Override
    public int hashCode(final DiscreteVoxelShape o) {
        if (o == null) {
            return 0;
        }
        int result = o.getXSize();
        result = 31 * result + o.getYSize();
        result = 31 * result + o.getZSize();
        if (o instanceof BitSetDiscreteVoxelShape bitset) {
            result = 31 * result + bitset.xMin;
            result = 31 * result + bitset.yMin;
            result = 31 * result + bitset.zMin;
            result = 31 * result + bitset.xMax;
            result = 31 * result + bitset.yMax;
            result = 31 * result + bitset.zMax;
            result = 31 * result + Objects.hashCode(bitset.storage);
            return result;
        }
        return 31 * result + Objects.hashCode(o);
    }

    @Override
    public boolean equals(final DiscreteVoxelShape a, final DiscreteVoxelShape b) {
        if (a == b) {
            return true;
        } else if (a == null || b == null) {
            return false;
        } else if (a.getClass() != b.getClass()) {
            return false;
        } else if (a.getXSize() != b.getXSize() || a.getYSize() != b.getYSize() || a.getZSize() != b.getZSize()) {
            return false;
        }
        if (a instanceof BitSetDiscreteVoxelShape bitsetA) {
            BitSetDiscreteVoxelShape bitsetB = (BitSetDiscreteVoxelShape)b;
            return bitsetA.xMin == bitsetB.xMin
                && bitsetA.yMin == bitsetB.yMin
                && bitsetA.zMin == bitsetB.zMin
                && bitsetA.xMax == bitsetB.xMax
                && bitsetA.yMax == bitsetB.yMax
                && bitsetA.zMax == bitsetB.zMax
                && bitsetA.storage.equals(bitsetB.storage);
        }
        return a.equals(b);
    }
}
