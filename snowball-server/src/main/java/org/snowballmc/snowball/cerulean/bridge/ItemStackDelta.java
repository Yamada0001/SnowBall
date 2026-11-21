package org.snowballmc.snowball.cerulean.bridge;

/**
 * 让 {@code ItemStack} 记住「本次变动之前的堆叠数量」。
 *
 * <p>进度判定关心的是「堆叠数量有没有跨过某个阈值」，而不是绝对数量。原版每做一次
 * 物品变动都要重新扫一遍背包并逐条比对谓词，靠的就是没记住前值。
 * 有了这个前值，绝大多数变动可以在极便宜的比较里被排除。</p>
 *
 * <p>移植自 Cerulean（经原作者授权）。</p>
 */
public interface ItemStackDelta {

    int cerulean$getPreviousStackSize();

    void cerulean$setPreviousStackSize(int value);
}
