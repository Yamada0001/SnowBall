package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an action where a bot only uses an item, without using it on blocks or entities.
 */
public interface UseItemAction extends TimerBotAction<UseItemAction> {

    /**
     * Gets the equivalent right-click hold duration timeout in ticks.
     * Default is -1, which means no timeout.
     *
     * @return the equivalent right-click hold duration timeout
     */
    int getUseTickTimeout();

    /**
     * Sets the equivalent right-click hold duration timeout in ticks.
     *
     * @param timeout the equivalent right-click hold duration timeout
     * @return this action instance
     */
    @NotNull UseItemAction setUseTickTimeout(int timeout);

    static @NotNull UseItemAction create() {
        return Bukkit.getBotManager().newAction(UseItemAction.class);
    }
}
