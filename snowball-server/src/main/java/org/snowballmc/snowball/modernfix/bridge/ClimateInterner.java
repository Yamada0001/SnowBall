package org.snowballmc.snowball.modernfix.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.biome.Climate;

/**
 * {@link Climate.Parameter} 的去重池。
 *
 * <p>原版在 {@code Climate} 与 {@code OverworldBiomeBuilder} 里大量构造短生命周期的
 * {@code Parameter} 记录（后者有 127 处 {@code span/point} 调用），其中绝大多数入参重复。
 * 按 {@code (min, max)} 去重即可显著减少对象数与后续的装箱开销。</p>
 *
 * <p>用强引用而不是弱引用：{@code Parameter} 的取值空间由量化坐标决定，
 * 总量有限，不存在无界增长。</p>
 *
 * <p>移植自 ModernFix（LGPL-3.0）。</p>
 */
public final class ClimateInterner {

    private static final Map<Long, Climate.Parameter> POOL = new ConcurrentHashMap<>();

    private ClimateInterner() {
    }

    /**
     * 取得与 {@code new Climate.Parameter(min, max)} 等价的去重实例。
     *
     * @param min 量化后的下界
     * @param max 量化后的上界
     * @return 去重后的 {@code Parameter}
     */
    public static Climate.Parameter parameter(final long min, final long max) {
        return POOL.computeIfAbsent(key(min, max), k -> new Climate.Parameter(min, max));
    }

    private static long key(final long min, final long max) {
        // 两个 int 量级的坐标打包进一个 long：min 在高 32 位，max 在低 32 位
        return (min << 32) ^ (max & 0xFFFF_FFFFL);
    }
}
