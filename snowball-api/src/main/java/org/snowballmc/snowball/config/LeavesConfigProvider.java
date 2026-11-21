package org.snowballmc.snowball.config;

import org.jetbrains.annotations.NotNull;

public interface LeavesConfigProvider {

    @NotNull LeavesConfigValue getConfig(@NotNull String configNode);

    void setConfig(@NotNull String configNode, @NotNull LeavesConfigValue value);
}
