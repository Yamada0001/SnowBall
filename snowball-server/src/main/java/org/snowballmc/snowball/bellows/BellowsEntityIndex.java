package org.snowballmc.snowball.bellows;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import org.jspecify.annotations.Nullable;
import org.snowballmc.snowball.LeavesConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 按"实体类型 / 实体标签"建立的实体索引，供 {@code @e} 选择器缩小候选集。
 *
 * <p>对齐目标：26.3 的实体查找走 Moonrise
 * {@link EntityLookup}，其 {@code get(EntityTypeTest, AbortableIterationConsumer)}
 * 会遍历整张 {@code entityById} 表，逐实体做可见性检查与 {@code tryCast}。
 * 这里缓存一个"类型/标签 → 实体集合"的倒排表，让选择器只在最小集合上做完整谓词求值。
 *
 * <p><b>安全性约束</b>（务必保持）：
 * <ul>
 *   <li>只允许<b>正向</b>约束（{@code type=x}、{@code type=#tag}、{@code tag=x}）作为索引键；
 *       反向约束不能缩小候选集。</li>
 *   <li>候选集必须是真实结果的<b>超集</b>。索引命中后 {@code filter} 与调用方谓词仍然全部走一遍。</li>
 *   <li>遍历候选集时必须重新校验 {@link Visibility#isAccessible()}：Moonrise 的
 *       {@code entityById} 同时包含尚未加载完成（HIDDEN / BORDER）的实体与已移除的实体，
 *       而原版 {@code visibleEntityStorage} 只含可见实体。漏掉这一步会返回"不该存在"的实体。</li>
 *   <li>调用方必须保证"结果集与迭代顺序无关"（例如 {@code limit} 为无限）。有 {@code limit} 时
 *       取到的子集可能与原版不同，这种情形不应启用索引。</li>
 * </ul>
 *
 * <p>索引实体由 {@code EntityLookup#addEntity} / {@code #removeEntity}，
 * 以及 {@code Entity#addTag} / {@code #removeTag} / {@code #load} 三处钩子维护。
 * 若某条维护路径被绕过（例如第三方插件直接写 {@code entityTags()}），索引只会变得"陈旧多算候选"，
 * 不会漏结果 —— 陈旧候选最终仍要被选择器自身的谓词与上面的可见性检查过滤掉。
 */
public final class BellowsEntityIndex {

    /** 索引键：既是 {@link EntityType}（类型索引），也是 {@link String}（标签索引）。 */
    private static final Map<EntityLookup, BellowsEntityIndex> INDEXES = new IdentityHashMap<>();

    /** 候选集小于该阈值时直接用索引，不再尝试用类型集合合并。 */
    private static final int REASONABLE_SEARCH_SIZE = 100;

    private final Map<Object, Set<Entity>> byKey = new HashMap<>();

    private BellowsEntityIndex() {
    }

    /**
     * 选择器索引入口：尝试用类型/标签索引给出 {@code @e} 的候选集。
     *
     * <p>补丁里的调用点只是转发，全部判断都在这里：
     * <ul>
     *   <li>配置关闭 → 不做任何事，返回 {@code false}；</li>
     *   <li>有 {@code limit}（{@code maxResults} 不是无限）→ 结果集对迭代顺序敏感，返回 {@code false}；</li>
     *   <li>选择器没有正向 {@code type=}/{@code tag=} → 返回 {@code false}；</li>
     *   <li>索引证明结果为空 → 返回 {@code true}（空结果就是真结果）。</li>
     * </ul>
     *
     * @return {@code true} 表示结果集已由索引完全确定（可能为空），调用方不应再走原版路径；
     *         {@code false} 表示不适用，调用方必须回退原版
     */
    public static <T extends Entity> boolean tryCollect(final EntityLookup lookup,
                                                        final EntityTypeTest<Entity, T> filter,
                                                        final Predicate<? super T> selector,
                                                        final List<? super T> result,
                                                        final int maxResults,
                                                        final @Nullable SelectorHints hints) {
        if (!LeavesConfig.performance.bellowsSelectorOptimizations
            || lookup == null
            || hints == null
            || maxResults != Integer.MAX_VALUE) {
            return false;
        }
        return of(lookup).collect(filter, entity -> {
            if (selector.test(entity)) {
                result.add(entity);
                if (result.size() >= maxResults) {
                    return Continuation.ABORT;
                }
            }

            return Continuation.CONTINUE;
        }, hints);
    }

    /**
     * 取 {@code lookup} 对应的索引，按需创建。
     *
     * <p>用标识映射而不是给 Moonrise 加字段：关卡数量是个位数且长生命周期，
     * 直接挂在 {@link EntityLookup} 实例上可以避免改动 Moonrise 的字段布局。
     */
    public static BellowsEntityIndex of(final EntityLookup lookup) {
        synchronized (INDEXES) {
            return INDEXES.computeIfAbsent(lookup, ignored -> new BellowsEntityIndex());
        }
    }

    /**
     * 丢弃 {@code lookup} 对应的索引。
     *
     * <p>关卡卸载时调用；未调用只会让索引随关卡对象一起变成不可达，不会影响正确性。
     */
    public static void drop(final EntityLookup lookup) {
        synchronized (INDEXES) {
            INDEXES.remove(lookup);
        }
    }

    public synchronized void add(final Entity entity) {
        addKey(entity.getType(), entity);
        for (final String tag : entity.entityTags()) {
            addKey(tag, entity);
        }
    }

    public synchronized void remove(final Entity entity) {
        removeKey(entity.getType(), entity);
        for (final String tag : entity.entityTags()) {
            removeKey(tag, entity);
        }
    }

    /** 供 {@code Entity#addTag} 钩子调用。 */
    public synchronized void addTag(final Entity entity, final String tag) {
        addKey(tag, entity);
    }

    /** 供 {@code Entity#removeTag} 钩子调用。 */
    public synchronized void removeTag(final Entity entity, final String tag) {
        removeKey(tag, entity);
    }

    /**
     * 标签集合被整体重写后重建该实体的全部条目，用于 {@code Entity#load} 的 {@code Tags} 分支。
     *
     * <p>用"先全表摘除、再按当前标签重建"而不是逐标签增删：此刻 {@code entityTags()} 已是最终集合，
     * 这种写法幂等，也不会漏掉"旧标签被移除"的那一半。
     */
    public synchronized void resync(final Entity entity) {
        for (final Set<Entity> set : this.byKey.values()) {
            set.remove(entity);
        }
        add(entity);
    }

    private void addKey(final Object key, final Entity entity) {
        this.byKey.computeIfAbsent(key, ignored -> new HashSet<>()).add(entity);
    }

    private void removeKey(final Object key, final Entity entity) {
        final Set<Entity> set = this.byKey.get(key);
        if (set != null) {
            set.remove(entity);
            if (set.isEmpty()) {
                this.byKey.remove(key);
            }
        }
    }

    /**
     * 选出最小候选集并把结果喂给 {@code action}。
     *
     * @param filter 类型过滤器（来自 {@code EntitySelector#type}）
     * @param action 结果消费者
     * @param hints  解析期收集到的选择器提示
     * @return {@code true} 表示索引已经给出最终结果集（可能为空），调用方不应再走原版路径；
     *         {@code false} 表示索引不适用，调用方必须回退原版
     */
    public <U extends Entity> boolean collect(final EntityTypeTest<Entity, U> filter,
                                               final AbortableIterationConsumer<U> action,
                                               final @Nullable SelectorHints hints) {
        if (hints == null || !hints.hasIndexKey()) {
            return false;
        }

        Set<Entity> best = null;
        List<Set<Entity>> merged = null;
        int size = Integer.MAX_VALUE;
        boolean sawEmptyIndex = false;

        for (final String tag : hints.selectorTags()) {
            final Set<Entity> set = this.byKey.get(tag);
            if (set == null || set.isEmpty()) {
                sawEmptyIndex = true;
                size = 0;
                break;
            }
            if (set.size() < size) {
                best = set;
                size = set.size();
            }
            if (size < REASONABLE_SEARCH_SIZE) {
                break;
            }
        }

        if (!sawEmptyIndex && size >= REASONABLE_SEARCH_SIZE) {
            final EntityType<?> type = hints.entityType();
            if (type != null && !hints.typeInverted()) {
                final Set<Entity> set = this.byKey.get(type);
                if (set == null || set.isEmpty()) {
                    size = 0;
                } else if (set.size() < size) {
                    best = set;
                    size = set.size();
                }
            } else if (type == null && hints.typeTag() != null && !hints.typeInverted()) {
                merged = new LinkedList<>();
                int mergeSize = 0;
                for (final EntityType<?> member : hints.resolvedTypeTag()) {
                    final Set<Entity> set = this.byKey.get(member);
                    if (set != null && !set.isEmpty()) {
                        mergeSize += set.size();
                        merged.add(set);
                    }
                }
                if (mergeSize < size) {
                    size = mergeSize;
                    best = null;
                }
            }
        }

        if (size == 0) {
            return true; // 索引证明结果为空
        }

        if (best == null && merged == null) {
            return false; // 索引不含可用信息，交给调用方走原版
        }

        if (best != null) {
            this.collectFrom(best, filter, action);
        } else {
            for (final Set<Entity> set : merged) {
                if (this.collectFrom(set, filter, action)) {
                    break;
                }
            }
        }
        return true;
    }

    /** @return true 表示消费者要求中止 */
    private <U extends Entity> boolean collectFrom(final Set<Entity> candidates,
                                                    final EntityTypeTest<Entity, U> filter,
                                                    final AbortableIterationConsumer<U> action) {
        for (final Entity entity : candidates) {
            if (!isAccessible(entity)) {
                continue;
            }
            final U casted = filter.tryCast(entity);
            if (casted != null && action.accept(casted).shouldAbort()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 与 Moonrise {@code EntityLookup#get} 一致的可见性判据。
     *
     * <p>必须用这个判据而不是 {@code !entity.isRemoved()}：{@code entityById} 里还有
     * HIDDEN 状态的实体（所在区块尚未进入可访问状态），它们不该出现在 {@code @e} 结果里。
     */
    private static boolean isAccessible(final Entity entity) {
        return EntityLookup.getEntityStatus(entity).isAccessible();
    }
}
