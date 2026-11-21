// This file is licensed under the MIT license.
package org.snowballmc.snowball.plugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface FeatureManager {
    @NotNull Set<String> getAvailableFeatures();

    boolean isFeatureAvailable(@Nullable String feature);
}
