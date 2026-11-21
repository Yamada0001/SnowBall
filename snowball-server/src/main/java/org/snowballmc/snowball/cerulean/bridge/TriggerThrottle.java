package org.snowballmc.snowball.cerulean.bridge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 物品变动触发（{@code InventoryChangeTrigger}）的节流与短路逻辑。
 *
 * <p>原版每发生一次物品变动，都会完整扫一遍背包统计「满格/空格/占用」，
 * 再逐条比对进度里登记的谓词。绝大多数变动在语义上不可能推进任何进度，
 * 因此可以按下列顺序依次短路：</p>
 *
 * <ol>
 *   <li><b>采样节流</b>：每个玩家每个物品每 5 次变动才真正判定一次；</li>
 *   <li><b>堆变空</b>：变空不可能满足「拥有某物」类条件；</li>
 *   <li><b>堆变小</b>：数量减少不可能跨过「至少 N 个」的下界。</li>
 * </ol>
 *
 * <p><b>语义声明</b>：以上三项都会跳过一部分原本会执行的条件判定。对依赖
 * 「边持有边消耗」这类累积语义的自定义进度，可能延迟触发或漏触发——
 * 这正是它们默认关闭的原因。</p>
 *
 * <p>移植自 Cerulean（经原作者授权）。</p>
 */
public final class TriggerThrottle {

    private static final int SAMPLE_INTERVAL = 5;
    private static final Map<UUID, Map<String, Integer>> SKIP_TICKS = new HashMap<>();

    private TriggerThrottle() {
    }

    /**
     * 采样节流：返回 {@code true} 表示本次变动应被跳过。
     *
     * <p>每个 (玩家, 物品) 组合独立计数，每 {@value #SAMPLE_INTERVAL} 次放行一次。</p>
     */
    public static boolean skipBySampling(final ServerPlayer player, final ItemStack stack) {
        final Map<String, Integer> perItem =
                SKIP_TICKS.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
        final String key = String.valueOf(stack.getItem());
        final int ticks = perItem.getOrDefault(key, SAMPLE_INTERVAL);
        if (ticks < SAMPLE_INTERVAL - 1) {
            perItem.put(key, ticks + 1);
            return true;
        }
        perItem.put(key, 0);
        return false;
    }

    /**
     * 堆变空：变空后不可能再满足任何「持有」类条件。
     */
    public static boolean skipEmptied(final ItemStack stack) {
        return stack.isEmpty();
    }

    /**
     * 堆变小：数量减少不可能跨过「至少 N 个」的下界。
     */
    public static boolean skipDecreased(final ItemStack stack) {
        return stack.getCount() < ((ItemStackDelta) (Object) stack).cerulean$getPreviousStackSize();
    }

    /**
     * 在物品变动时记录前值，供下一次比较使用。
     *
     * <p>只有当新旧是同一个物品时才记录：换成另一种物品时，「前值」这个概念没有意义，
     * 应当归零以表示「从无到有」。</p>
     */
    public static void recordPreviousSize(final ItemStack oldStack, final ItemStack newStack) {
        if (newStack.isEmpty()) {
            return;
        }
        final int previous = !oldStack.isEmpty() && ItemStack.isSameItem(newStack, oldStack)
                ? oldStack.getCount()
                : 0;
        ((ItemStackDelta) (Object) newStack).cerulean$setPreviousStackSize(previous);
    }

    /** 玩家下线时清理，避免 UUID 表无界增长。 */
    public static void forget(final UUID playerId) {
        SKIP_TICKS.remove(playerId);
    }
}
