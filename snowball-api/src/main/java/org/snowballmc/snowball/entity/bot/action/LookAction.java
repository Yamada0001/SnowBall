package org.snowballmc.snowball.entity.bot.action;

import org.bukkit.Bukkit;
import org.bukkit.UndefinedNullability;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an action for a bot to look at a specific position or player.
 * <p>
 * If both a position and a player are set, the player target takes precedence.
 */
public interface LookAction extends BotAction<LookAction> {

    /**
     * Sets the position in the world for the bot to look at.
     * <p>
     * If a player target is set via {@link #setTarget(Player)}, the bot will look at the player instead of this position.
     *
     * @param pos the {@link Vector} representing the position to look at
     * @return this {@code LookAction} instance for method chaining
     */
    @NotNull LookAction setPos(@NotNull Vector pos);

    /**
     * Gets the position in the world that the bot is set to look at.
     * <p>
     * If a player target is set, this value may be ignored.
     *
     * @return the {@link Vector} position to look at, or {@code null} if not set
     */
    @Nullable Vector getPos();

    /**
     * Sets the player for the bot to look at.
     * <p>
     * When a player is set as the target, the bot will continuously look at the player's current position,
     * overriding any position set by {@link #setPos(Vector)}.
     *
     * @param player the {@link Player} to look at; whether {@code null} clears the target depends on the implementation
     * @return this {@code LookAction} instance for method chaining
     */
    @NotNull LookAction setTarget(@UndefinedNullability("The built-in implementation does not accept null to clear the target.") Player player);

    /**
     * Gets the player that the bot is set to look at.
     *
     * @return the {@link Player} target; behavior when no target is set depends on the implementation
     */
    @UndefinedNullability("The built-in implementation throws if no target has been set.") Player getTarget();

    static @NotNull LookAction create() {
        return Bukkit.getBotManager().newAction(LookAction.class);
    }
}
