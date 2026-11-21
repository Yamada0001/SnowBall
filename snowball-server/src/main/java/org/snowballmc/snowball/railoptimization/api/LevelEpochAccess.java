package org.snowballmc.snowball.railoptimization.api;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 让桥接层能探测某个 {@code Level} 的方块变更纪元。
 *
 * <p>铁轨信号缓存（{@code RailUpdateMemo}、{@code RailSupportCache}）依赖它做整体失效：
 * 每次方块写入递增纪元，缓存据此判定是否需要重算。</p>
 *
 * <p>类型必须是 {@link AtomicLong} 而不是 {@code long}，因为缓存持有的是引用，
 * 会在每次校验时 {@code get()} 探测并发写入。</p>
 *
 * <p>移植自 RailOptimization（GPL-3.0）。</p>
 */
public interface LevelEpochAccess {

    AtomicLong railoptimization$getBlockChangeEpoch();
}
