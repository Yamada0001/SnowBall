package org.snowballmc.snowball.entity.bot.action;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Represents an action that can be performed by a Bot.
 * <p>
 * This interface defines the basic properties of an action (such as name and UUID)
 * and lifecycle event callbacks (success, failure, stop).
 *
 * @param <T> the type of the callback parameter, usually a context object related to the action
 */
public interface BotAction<T> {

    /**
     * Gets the name of this action.
     *
     * @return the action name
     */
    @NotNull String getName();

    /**
     * Gets the UUID of this action.
     *
     * @return the UUID of the action
     */
    @NotNull UUID getUUID();

    /**
     * Sets whether this action is cancelled.
     *
     * @param cancel true to cancel the action, false otherwise
     */
    void setCancelled(boolean cancel);

    /**
     * Checks whether this action has been cancelled.
     *
     * @return true if cancelled, false otherwise
     */
    boolean isCancelled();

    /**
     * Sets the callback to be executed when the action fails.
     *
     * @param onFail the callback to execute on failure, with a parameter of type T
     */
    void setOnFail(@NotNull Consumer<T> onFail);

    /**
     * Gets the callback to be executed when the action fails.
     *
     * @return the failure callback
     */
    @Nullable Consumer<T> getOnFail();

    /**
     * Sets the callback to be executed when the action succeeds.
     *
     * @param onSuccess the callback to execute on success, with a parameter of type T
     */
    void setOnSuccess(@NotNull Consumer<T> onSuccess);

    /**
     * Gets the callback to be executed when the action succeeds.
     *
     * @return the success callback
     */
    @Nullable Consumer<T> getOnSuccess();

    /**
     * Sets the callback to be executed when the action is stopped.
     *
     * @param onStop the callback to execute on stop, with a parameter of type T
     */
    void setOnStop(@NotNull Consumer<T> onStop);

    /**
     * Gets the callback to be executed when the action is stopped.
     *
     * @return the stop callback
     */
    @Nullable Consumer<T> getOnStop();
}
