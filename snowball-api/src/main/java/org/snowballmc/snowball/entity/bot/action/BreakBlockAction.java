package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to break a block.
 */
public interface BreakBlockAction extends TimerBotAction<BreakBlockAction> {
    static @NotNull BreakBlockAction create() {
        return Bukkit.getBotManager().newAction(BreakBlockAction.class);
    }
}
