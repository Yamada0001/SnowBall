package org.snowballmc.snowball.event.bytebuf;

import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bytebuf.Bytebuf;
import org.snowballmc.snowball.bytebuf.PacketAudience;
import org.snowballmc.snowball.bytebuf.PacketType;

public class PacketOutEvent extends PacketEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    public PacketOutEvent(@NotNull PacketAudience audience, @NotNull PacketType packetType, @NotNull Bytebuf bytebuf) {
        super(audience, packetType, bytebuf);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
