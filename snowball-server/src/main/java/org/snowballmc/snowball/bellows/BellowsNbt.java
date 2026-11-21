package org.snowballmc.snowball.bellows;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;
import org.snowballmc.snowball.LeavesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bellows NBT 读侧（save 方向）桥接。
 *
 * <p>核心思想：不再"复制一份按字段分派的 save"，而是让原版 {@code Entity#saveWithoutId}
 * 完整跑一遍，再用 {@link FilteringValueOutput} 把不需要的顶层键丢掉。这样：
 * <ul>
 *   <li>不需要给 {@code Entity}/{@code LivingEntity}/{@code Mob}/{@code Player}/... 逐个打补丁；</li>
 *   <li>上游保存逻辑怎么改都不会导致这里产生语义偏差；</li>
 *   <li>{@code data get entity @s Health} 这类高频命令从"序列化全部 NBT"降到"序列化 1 个字段"。</li>
 * </ul>
 *
 * <p><b>只用于纯读路径</b>。任何"读出来改完再写回"的路径（{@code data modify} /
 * {@code data remove} / {@code data merge} / {@code execute store ... data}）都不能用本类：
 * {@code EntityDataAccessor#setData} 走 {@code Entity#load}，而 {@code load} 对缺失的键是
 * 无条件重置（例如 {@code Pos} 缺失即归零），过滤后的 tag 写回去会摧毁实体状态。
 */
public final class BellowsNbt {

    private static final Logger LOGGER = LoggerFactory.getLogger("Bellows/NBT");

    /** 与 {@code NbtPredicate.SELECTED_ITEM_TAG} 一致。 */
    public static final String SELECTED_ITEM_TAG = "SelectedItem";

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private BellowsNbt() {
    }

    /**
     * 只序列化 {@code topLevelKeys} 中的顶层键，返回结果 tag。
     *
     * @param entity       目标实体
     * @param topLevelKeys 需要保留的顶层键集合
     * @return 只含被请求字段的 CompoundTag
     */
    public static CompoundTag filteredSave(final Entity entity, final Set<String> topLevelKeys) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
            final TagValueOutput tagOut = TagValueOutput.createWithContext(reporter, entity.registryAccess());
            entity.saveWithoutId(new FilteringValueOutput(tagOut, topLevelKeys));

            // 原版 NbtPredicate#getEntityTagToCompare 会在 saveWithoutId 之后额外补一个 SelectedItem，
            // saveWithoutId 本身不写这个键，这里要复刻，否则 data get ... SelectedItem 会查不到。
            if (entity instanceof Player player && topLevelKeys.contains(SELECTED_ITEM_TAG)) {
                final ItemStack selected = player.getInventory().getSelectedItem();
                if (!selected.isEmpty()) {
                    tagOut.store(SELECTED_ITEM_TAG, ItemStack.CODEC, selected);
                }
            }

            return tagOut.buildResult();
        }
    }

    /**
     * 按 NBT 路径做过滤保存：只序列化 {@code path} 首段顶层键对应的字段。
     *
     * <p>供 {@code EntityDataAccessor} 的读侧入口转发使用；本方法承担全部判断与异常兜底，
     * 补丁里的调用点保持为纯转发。
     *
     * <p><b>只允许纯读路径使用。</b>{@code DataAccessor#setData} 走 {@code Entity#load}，
     * 对缺失的键是无条件重置，所以"读出来改完再写回"的调用点不能走这里。
     *
     * @return 过滤后的 tag；{@code null} 表示"不适用/失败"，调用方必须回退原版全量序列化
     */
    public static @Nullable CompoundTag filteredSaveForPath(final Entity entity, final @Nullable String path) {
        if (!LeavesConfig.performance.bellowsNbtOptimizations || entity == null) {
            return null;
        }
        final String topLevel = topLevelKey(path);
        if (topLevel.isEmpty()) {
            return null;
        }
        final Set<String> keys = Set.of(topLevel);
        try {
            return filteredSave(entity, keys);
        } catch (final Throwable t) {
            reportFailure(entity, keys, t);
            return null;
        }
    }

    /**
     * 把 {@code path} 的首段顶层键抽出来。
     *
     * <p>例：{@code "Inventory[{Slot:3b}]"} → {@code "Inventory"}；{@code "Health"} → {@code "Health"}。
     * 对空串与无分隔符路径都安全。
     *
     * @return 顶层键；{@code path} 为空时返回空串（调用方应据此回退原版）
     */
    public static String topLevelKey(final @Nullable String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int end = path.length();
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            if (c == '.' || c == '{' || c == '[') {
                end = i;
                break;
            }
        }
        return path.substring(0, end);
    }

    /**
     * 谓词/选择器场景的过滤保存。
     *
     * <p>{@code required} 是谓词要求匹配的 tag。只序列化 {@code required.keySet()} 覆盖的顶层键，
     * 因为 {@code NbtUtils.compareNbt(required, actual, true)} 只关心这些键。
     *
     * @return 过滤后的 tag；{@code null} 表示"不适用/失败"，调用方必须回退原版全量路径
     */
    public static @Nullable CompoundTag filteredSaveForPredicate(final Entity entity, final @Nullable CompoundTag required) {
        if (!LeavesConfig.performance.bellowsNbtOptimizations || entity == null || required == null || required.isEmpty()) {
            return null;
        }
        try {
            return filteredSave(entity, required.keySet());
        } catch (final Throwable t) {
            reportFailure(entity, required.keySet(), t);
            return null;
        }
    }

    /**
     * 记录一次过滤失败并降级为一次性告警。
     *
     * <p>失败不应影响命令正确性 —— 调用方一律回退原版路径。这里只提示一次，
     * 避免异常实体每 tick 刷屏。
     */
    public static void reportFailure(final Entity entity, final Set<String> keys, final Throwable cause) {
        if (!WARNED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("Bellows NBT 过滤保存失败，已回退到原版全量序列化（后续同类失败不再提示）。"
            + " entity={}, keys={}", entity == null ? "null" : entity.getClass().getName(), keys, cause);
    }
}
