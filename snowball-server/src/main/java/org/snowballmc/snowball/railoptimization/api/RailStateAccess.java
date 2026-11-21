package org.snowballmc.snowball.railoptimization.api;

/**
 * 把铁轨方块状态的两个热字段（{@code SHAPE}、{@code POWERED}）压进一个 byte，
 * 供桥接层在热路径上零分配地读取。
 *
 * <p>位布局：{@code bit0-3 = RailShape.ordinal()}、{@code bit4 = POWERED}、
 * {@code bit5 = 已初始化}。</p>
 *
 * <p>正确性依据：{@code BlockState} 实例不可变（每个属性组合一个实例），
 * 因此缓存的 {@code SHAPE}/{@code POWERED} 永不变更，一次性计算是安全的。</p>
 *
 * <p>移植自 RailOptimization（GPL-3.0）。</p>
 */
public interface RailStateAccess {

    int SHAPE_MASK = 0x0F;

    int POWERED_MASK = 1 << 4;

    int INITIALIZED_MASK = 1 << 5;

    int railoptimization$getRailData();
}
