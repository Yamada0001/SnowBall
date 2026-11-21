package org.snowballmc.snowball.event.bytebuf;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bytebuf.Bytebuf;
import org.snowballmc.snowball.bytebuf.PacketAudience;
import org.snowballmc.snowball.bytebuf.PacketType;

public abstract class PacketEvent extends Event implements Cancellable {

    private final PacketAudience audience;
    private final PacketType type;
    private Bytebuf bytebuf;

    private boolean isCancelled = false;

    public PacketEvent(@NotNull PacketAudience audience, @NotNull PacketType type, @NotNull Bytebuf bytebuf) {
        super(true);
        this.audience = audience;
        this.type = type;
        this.bytebuf = bytebuf;
    }

    public @NotNull PacketType getPacketType() {
        return type;
    }

    public @NotNull Bytebuf getBytebuf() {
        return bytebuf;
    }

    public void setBytebuf(@NotNull Bytebuf bytebuf) {
        this.bytebuf = bytebuf;
    }

    public @NotNull PacketAudience getAudience() {
        return audience;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        isCancelled = cancelled;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }
}
