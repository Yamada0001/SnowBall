package org.snowballmc.snowball.bytebuf;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface PacketAudience {

    void send(@NotNull PacketType packetType, @NotNull Bytebuf bytebuf);

    @Nullable Player getPlayer();

    @Nullable String getName();

    @NotNull Object getChannel();
}
