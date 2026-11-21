package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to sneak.
 */
public interface SneakAction extends StateBotAction<SneakAction> {
    static @NotNull SneakAction create() {
        return Bukkit.getBotManager().newAction(SneakAction.class);
    }
}
