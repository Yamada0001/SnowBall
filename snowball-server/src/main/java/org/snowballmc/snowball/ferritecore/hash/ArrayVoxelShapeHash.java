package org.snowballmc.snowball.ferritecore.hash;

import it.unimi.dsi.fastutil.Hash;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.core.Direction;

import java.util.Objects;

/**
 * Content based {@link Hash.Strategy} for {@link ArrayVoxelShape}.
 *
 * <p>Two shapes are considered equivalent when their per axis coordinate lists and their underlying
 * {@link DiscreteVoxelShape} are equivalent. Ported from FerriteCore (MIT) {@code hash/ArrayVoxelShapeHash}:
 * the mod used a mixin accessor for {@code xs}/{@code ys}/{@code zs}, which is unnecessary here because
 * {@code VoxelShape#getCoords(Direction.Axis)} is public and returns the exact list instances held by the shape.</p>
 */
public final class ArrayVoxelShapeHash implements Hash.Strategy<ArrayVoxelShape> {
    public static final ArrayVoxelShapeHash INSTANCE = new ArrayVoxelShapeHash();

    private ArrayVoxelShapeHash() {
    }

    @Override
    public int hashCode(final ArrayVoxelShape o) {
        if (o == null) {
            return 0;
        }
        return 31 * Objects.hash(
            o.getCoords(Direction.Axis.X),
            o.getCoords(Direction.Axis.Y),
            o.getCoords(Direction.Axis.Z)
        ) + DiscreteVSHash.INSTANCE.hashCode(o.shape);
    }

    @Override
    public boolean equals(final ArrayVoxelShape a, final ArrayVoxelShape b) {
        if (a == b) {
            return true;
        } else if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.getCoords(Direction.Axis.X), b.getCoords(Direction.Axis.X))
            && Objects.equals(a.getCoords(Direction.Axis.Y), b.getCoords(Direction.Axis.Y))
            && Objects.equals(a.getCoords(Direction.Axis.Z), b.getCoords(Direction.Axis.Z))
            && DiscreteVSHash.INSTANCE.equals(a.shape, b.shape);
    }
}
