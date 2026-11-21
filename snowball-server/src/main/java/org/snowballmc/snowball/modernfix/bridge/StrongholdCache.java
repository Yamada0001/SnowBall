package org.snowballmc.snowball.modernfix.bridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.ChunkPos;

/**
 * 要塞（concentric rings）环位置的内存缓存。
 *
 * <p>原版每次查询都要把整圈环位重算一遍：{@code generateRingPositions} 会按 spread 逐环
 * 采样候选点并做生物群系判定，代价很高，而同一份 {@code (structureSet, placement, seed)}
 * 在一局游戏里是常量。</p>
 *
 * <p><b>与上游模组的一处修正</b>：ModernFix 原版的缓存键只拼了 {@code concentricRingsSeed}，
 * 但 Paper 在 {@code generateRingPositions} 内部会优先用服务器配置的
 * {@code stronghold-seed} 覆盖它。若只按模组的键做缓存，配了 {@code stronghold-seed}
 * 的服务器会读到错误的环位。本实现的键把「实际生效的种子」纳入，
 * 由调用方通过 {@code seed} 参数传入。</p>
 *
 * <p>移植自 ModernFix（LGPL-3.0）。</p>
 */
public final class StrongholdCache {

    private static final Map<Key, CompletableFuture<List<ChunkPos>>> CACHE = new ConcurrentHashMap<>();

    private StrongholdCache() {
    }

    /**
     * 取缓存，未命中则执行并回填。
     *
     * @param state      区块生成结构状态（仅用于隔离不同世界，键里用身份值）
     * @param structureSet 结构集
     * @param placement  环状放置
     * @param seed       实际生效的种子（已把 {@code stronghold-seed} 覆盖纳入）
     * @param loader     未命中时的计算逻辑
     * @return 环位列表的 future
     */
    public static CompletableFuture<List<ChunkPos>> wrap(final ChunkGeneratorStructureState state,
                                                         final Holder<StructureSet> structureSet,
                                                         final ConcentricRingsStructurePlacement placement,
                                                         final long seed,
                                                         final Supplier<CompletableFuture<List<ChunkPos>>> loader) {
        final Key key = new Key(System.identityHashCode(state), structureSet.getRegisteredName(),
                placement.count(), placement.distance(), placement.spread(), seed);
        final CompletableFuture<List<ChunkPos>> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        final CompletableFuture<List<ChunkPos>> computed = loader.get();
        final CompletableFuture<List<ChunkPos>> previous = CACHE.putIfAbsent(key, computed);
        return previous != null ? previous : computed;
    }

    /** 世界卸载时清空，避免跨世界串味。 */
    public static void clear() {
        CACHE.clear();
    }

    private record Key(int state, String structureSet, int count, int distance, int spread, long seed) {
    }
}
