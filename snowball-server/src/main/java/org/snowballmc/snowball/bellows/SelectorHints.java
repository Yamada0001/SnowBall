package org.snowballmc.snowball.bellows;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个 {@code @e} 选择器在解析期收集到的、可用于建索引的约束。
 *
 * <p>只保存<b>正向</b>约束：
 * <ul>
 *   <li>{@code tag=a,tag=b} → {@link #selectorTags}</li>
 *   <li>{@code type=minecraft:marker} → {@link #entityType}</li>
 *   <li>{@code type=#minecraft:arrows} → {@link #typeTag}</li>
 * </ul>
 * 反向约束（{@code tag=!x}、{@code type=!x}）单独存放，仅用于让索引失效，
 * <b>绝不用来缩小候选集</b>（{@code tag=!x} 的候选集是全集，缩小会漏结果）。
 */
public final class SelectorHints {

    private final Set<String> selectorTags = new LinkedHashSet<>();
    private final Set<String> notSelectorTags = new LinkedHashSet<>();
    private @Nullable EntityType<?> entityType;
    private @Nullable String typeTag;
    private boolean typeInverted;
    private @Nullable Set<EntityType<?>> resolvedTypeTag;

    public Set<String> selectorTags() {
        return Collections.unmodifiableSet(this.selectorTags);
    }

    public Set<String> notSelectorTags() {
        return Collections.unmodifiableSet(this.notSelectorTags);
    }

    public @Nullable EntityType<?> entityType() {
        return this.entityType;
    }

    public @Nullable String typeTag() {
        return this.typeTag;
    }

    public boolean typeInverted() {
        return this.typeInverted;
    }

    void addTag(final String tag, final boolean inverted) {
        if (inverted) {
            this.notSelectorTags.add(tag);
        } else {
            this.selectorTags.add(tag);
        }
    }

    void setType(final EntityType<?> type, final boolean inverted) {
        this.entityType = type;
        this.typeTag = null;
        this.typeInverted = inverted;
        this.resolvedTypeTag = null;
    }

    void setTypeTag(final String tagId, final boolean inverted) {
        this.typeTag = tagId;
        this.entityType = null;
        this.typeInverted = inverted;
        this.resolvedTypeTag = null;
    }

    /**
     * 把 {@code type=#tag} 解析成具体实体类型集合。结果按需计算并缓存。
     *
     * <p>注意：数据包 reload 会改变标签内容，因此缓存不跨 reload 复用 ——
     * 调用 {@link #invalidateTypeTagCache()} 即可。
     */
    public Set<EntityType<?>> resolvedTypeTag() {
        if (this.typeTag == null) {
            return Set.of();
        }
        if (this.resolvedTypeTag == null) {
            this.resolvedTypeTag = resolveTag(this.typeTag);
        }
        return this.resolvedTypeTag;
    }

    public void invalidateTypeTagCache() {
        this.resolvedTypeTag = null;
    }

    private static Set<EntityType<?>> resolveTag(final String tagId) {
        try {
            return BuiltInRegistries.ENTITY_TYPE.getTags()
                .filter(named -> named.key().location().toString().equals(tagId))
                .findFirst()
                .<Set<EntityType<?>>>map(named -> {
                    final Set<EntityType<?>> out = new LinkedHashSet<>();
                    named.forEach(holder -> out.add(holder.value()));
                    return Collections.unmodifiableSet(out);
                })
                .orElseGet(Set::of);
        } catch (final Throwable t) {
            // 数据包 reload 期间注册表可能正在变更，任何异常都视为"该标签不可用"
            return Set.of();
        }
    }

    /** 是否有任何可用于缩小候选集的索引键。 */
    public boolean hasIndexKey() {
        return !this.selectorTags.isEmpty() || this.entityType != null || this.typeTag != null;
    }

    @Override
    public String toString() {
        return "SelectorHints{tags=" + this.selectorTags
            + ", notTags=" + this.notSelectorTags
            + ", type=" + typeName(this.entityType)
            + ", typeTag=" + this.typeTag
            + ", typeInverted=" + this.typeInverted + '}';
    }

    private static @Nullable String typeName(final @Nullable EntityType<?> type) {
        if (type == null) {
            return null;
        }
        final Identifier key = EntityType.getKey(type);
        return key == null ? type.toString() : key.toString();
    }
}
