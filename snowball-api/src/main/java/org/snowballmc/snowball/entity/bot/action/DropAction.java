package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to drop all items in its inventory.
 */
public interface DropAction extends TimerBotAction<DropAction> {
    static @NotNull DropAction create() {
        return Bukkit.getBotManager().newAction(DropAction.class);
    }
}
