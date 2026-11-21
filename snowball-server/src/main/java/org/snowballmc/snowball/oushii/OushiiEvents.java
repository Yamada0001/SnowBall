package org.snowballmc.snowball.oushii;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Oushii 爆炸引擎的 Bukkit 事件桥。
 *
 * <p>原模组直接在自己的求解循环里删方块，绕过了 {@code Level#explode}，也就绕过了整个
 * CraftBukkit 事件层——保护类插件（领地、WorldGuard 之类）对 TNT 会完全失效。
 * 本类把「求解」与「写世界」之间重新插入事件：</p>
 *
 * <ol>
 *   <li>Phase A（{@code FastExplosionEngine.breakBlocks}）只收集候选方块；</li>
 *   <li>Phase B（本类 {@link #fireExplode}）发出事件，把插件删掉的方块从候选集里剔除；</li>
 *   <li>Phase C（{@code FastExplosionEngine.commitBlocks}）按核准集合写世界。</li>
 * </ol>
 *
 * <p>移植自 Oushii（GPL-3.0）。</p>
 */
public final class OushiiEvents {

    private OushiiEvents() {
    }

    /**
     * 发出 {@link EntityExplodeEvent} 或 {@link BlockExplodeEvent}，返回被核准破坏的方块集合。
     *
     * @param level       爆炸所在世界
     * @param source      爆炸源实体；{@code null} 表示方块爆炸，走 {@link BlockExplodeEvent}
     * @param center      爆炸中心
     * @param yield       掉落率，与原版一致传 {@code 1.0f}
     * @param interaction 方块交互模式
     * @return 核准集合；{@code null} 表示事件被取消，调用方应放弃整个爆炸；
     *         集合为空表示插件删光了所有方块
     */
    public static LongOpenHashSet fireExplode(final ServerLevel level, final Entity source,
                                              final BlockPos center, final float yield,
                                              final Explosion.BlockInteraction interaction) {
        final long[] positions = FastExplosionEngine.pendingPositions();
        final int count = FastExplosionEngine.pendingCount();

        final List<Block> blocks = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final long packed = positions[i];
            final BlockPos pos = BlockPos.of(packed);
            final Block block = CraftBlock.at(level, pos);
            if (!block.getType().isAir()) {
                blocks.add(block);
            }
        }

        final List<Block> approved;
        if (source != null) {
            final EntityExplodeEvent event = CraftEventFactory.callEntityExplodeEvent(source, blocks, yield, interaction);
            if (event.isCancelled()) {
                return null;
            }
            approved = event.blockList();
        } else {
            final Block centerBlock = CraftBlock.at(level, center);
            final BlockExplodeEvent event = CraftEventFactory.callBlockExplodeEvent(
                    centerBlock, centerBlock.getState(), blocks, yield, interaction);
            if (event.isCancelled()) {
                return null;
            }
            approved = event.blockList();
        }

        final LongOpenHashSet result = new LongOpenHashSet(approved.size());
        for (final Block block : approved) {
            result.add(((CraftBlock) block).getPosition().asLong());
        }
        return result;
    }
}
