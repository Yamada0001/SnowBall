package org.snowballmc.snowball.event.bot;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.entity.bot.Bot;

import java.util.UUID;

public class BotActionStopEvent extends BotActionEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final CommandSender sender;
    private final Reason reason;
    private boolean cancel = false;

    public BotActionStopEvent(@NotNull Bot who, @NotNull String actionName, @NotNull UUID actionUUID, @NotNull Reason stopReason, @Nullable CommandSender sender) {
        super(who, actionName, actionUUID);
        this.reason = stopReason;
        this.sender = sender;
    }

    public static @NotNull HandlerList getHandlerList() {
        return handlers;
    }

    public @NotNull Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return cancel;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    @Nullable
    public CommandSender getSender() {
        return sender;
    }

    public enum Reason {
        DONE, COMMAND, PLUGIN, INTERNAL
    }
}
