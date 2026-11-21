package org.snowballmc.snowball.modernfix.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 围墙类方块形状的去重缓存。
 *
 * <p>{@code WallBlock#makeShapes} 每个实例会构造 3 个几何体，而 {@code getShapeForEachState}
 * 又对 324 个状态各叠加一次 {@code Shapes.or}。这些几何体的入参全是常量，
 * 按入参缓存即可把重复构造压掉。</p>
 *
 * <p>移植自 ModernFix（LGPL-3.0）。</p>
 */
public final class WallShapeCaches {

    private static final Map<ColumnKey, VoxelShape> COLUMNS = new ConcurrentHashMap<>();
    private static final Map<BoxZKey, Map<net.minecraft.core.Direction, VoxelShape>> BOX_Z = new ConcurrentHashMap<>();
    private static final Map<OrKey, VoxelShape> OR = new ConcurrentHashMap<>();

    private WallShapeCaches() {
    }

    /** 等价于 {@code Block.column(8.0, 0.0, postHeight)}。 */
    public static VoxelShape column(final double radius, final double minY, final double maxY) {
        return COLUMNS.computeIfAbsent(new ColumnKey(radius, minY, maxY),
                key -> Block.column(key.radius, key.minY, key.maxY));
    }

    /** 等价于 {@code Shapes.rotateHorizontal(Block.boxZ(...))}。 */
    public static Map<net.minecraft.core.Direction, VoxelShape> boxZRotated(
            final double thickness, final double minY, final double maxY,
            final double minZ, final double maxZ) {
        return BOX_Z.computeIfAbsent(new BoxZKey(thickness, minY, maxY, minZ, maxZ),
                key -> Shapes.rotateHorizontal(
                        Block.boxZ(key.thickness, key.minY, key.maxY, key.minZ, key.maxZ)));
    }

    /**
     * 等价于 {@code Shapes.or(a, b)}。
     *
     * <p>以两个几何体的**身份**为键：{@code VoxelShape} 不覆写 {@code equals}，
     * 而 record 的默认相等性对对象字段退化为引用比较，正好是需要的语义。</p>
     */
    public static VoxelShape or(final VoxelShape a, final VoxelShape b) {
        return OR.computeIfAbsent(new OrKey(a, b), key -> Shapes.or(key.a, key.b));
    }

    private record ColumnKey(double radius, double minY, double maxY) {
    }

    private record BoxZKey(double thickness, double minY, double maxY, double minZ, double maxZ) {
    }

    private record OrKey(VoxelShape a, VoxelShape b) {
    }
}
