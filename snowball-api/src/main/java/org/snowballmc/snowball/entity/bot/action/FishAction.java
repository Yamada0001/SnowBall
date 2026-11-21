package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action for a bot to perform auto fishing.
 */
public interface FishAction extends TimerBotAction<FishAction> {
    static @NotNull FishAction create() {
        return Bukkit.getBotManager().newAction(FishAction.class);
    }
}
