package org.snowballmc.snowball.pathfix;

/**
 * 路径节点超时计算。
 *
 * <p>修复的原版缺陷：{@code PathNavigation#doStuckDetection} 在生物移动速度为 0 时把
 * {@code timeoutLimit} 置为 0，而超时判定带有 {@code timeoutLimit > 0.0} 的前置条件，
 * 导致该情况下的路径节点永远不会超时，寻路陷入无限等待。</p>
 *
 * <p>本类只做纯计算，不引用任何 NMS 类型，也不读取配置——是否启用由补丁侧决定。</p>
 *
 * <p>移植自 <a href="https://github.com/litetex-oss/mcm-rabbit-pathfinding-fix">mcm-rabbit-pathfinding-fix</a>
 * 的 {@code PathNavigation} 部分（Apache-2.0）。</p>
 */
public final class PathTimeoutFix {

    /**
     * 速度为 0（或无法估算）时使用的兜底节点超时，单位：游戏刻。
     */
    public static final double DEFAULT_NODE_TIMEOUT = 200.0;

    /**
     * 节点超时上限，单位：游戏刻。
     *
     * <p>用于兜住「速度极小但大于 0」时 {@code dist / speed} 爆炸的情形。</p>
     */
    public static final double MAX_NODE_TIMEOUT = 4000.0;

    private PathTimeoutFix() {
    }

    /**
     * 计算单个路径节点的等待上限。
     *
     * @param distToNode 生物当前位置到目标节点的距离
     * @param speed      生物移动速度
     * @return 节点超时上限，恒大于 0
     */
    public static double timeoutLimit(final double distToNode, final float speed) {
        if (speed > 0.0F) {
            return Math.min(distToNode / speed * 20.0, MAX_NODE_TIMEOUT);
        }

        return DEFAULT_NODE_TIMEOUT;
    }

    /**
     * 重置时使用的节点超时上限，同样恒大于 0，避免重置后又落回「永不超时」。
     *
     * @return 节点超时上限
     */
    public static double resetTimeoutLimit() {
        return DEFAULT_NODE_TIMEOUT;
    }
}
