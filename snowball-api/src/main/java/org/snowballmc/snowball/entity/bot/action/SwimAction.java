package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to float up in water.
 */
public interface SwimAction extends StateBotAction<SwimAction> {
    static @NotNull SwimAction create() {
        return Bukkit.getBotManager().newAction(SwimAction.class);
    }
}
