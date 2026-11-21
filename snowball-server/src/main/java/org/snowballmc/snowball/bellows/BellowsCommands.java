package org.snowballmc.snowball.bellows;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.data.DataAccessor;
import net.minecraft.server.commands.data.EntityDataAccessor;
import org.jspecify.annotations.Nullable;
import org.snowballmc.snowball.LeavesConfig;

/**
 * {@code /data} 与 {@code /execute ... data} 的 NBT 读侧转发层。
 *
 * <p>替换散落在 {@code DataCommands} / {@code ExecuteCommand} 里的 {@code getData()} 调用点：
 * 有源码时不需要字节码钩子，直接改调用点即可。
 *
 * <p><b>契约</b>：本类在"优化不适用"时原样退化为 {@code accessor.getData()}，
 * 不允许改变任何可观测语义。
 *
 * <p><b>只覆盖纯读调用点</b>。{@code DataAccessor#setData} 走 {@code Entity#load}，
 * 对缺失的键是无条件重置，所以任何"读-改-写回"路径都不能过滤读侧，见 {@link BellowsNbt} 的类注释。
 * 因此本类<b>没有</b> {@code setData} / {@code getDataForMerge} 之类的写侧入口。
 */
public final class BellowsCommands {

    private BellowsCommands() {
    }

    /**
     * 读取 {@code accessor} 的数据，尽量只序列化 {@code path} 需要的顶层键。
     *
     * @param accessor 数据访问器
     * @param path     NBT 路径（用于抽取顶层键）
     * @return 至少包含 {@code path} 所需数据的 tag
     */
    public static CompoundTag getData(final DataAccessor accessor, final @Nullable String path) throws CommandSyntaxException {
        if (LeavesConfig.performance.bellowsNbtOptimizations
            && path != null
            && accessor instanceof EntityDataAccessor entityAccessor) {
            final CompoundTag filtered = entityAccessor.bellows$getDataFiltered(path);
            if (filtered != null) {
                return filtered;
            }
        }
        return accessor.getData();
    }
}
