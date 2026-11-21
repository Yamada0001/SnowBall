package org.snowballmc.snowball.bytebuf.internal;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.bytebuf.Bytebuf;
import org.snowballmc.snowball.bytebuf.PacketAudience;
import org.snowballmc.snowball.bytebuf.PacketType;

public class AudienceHolder {

    private volatile PacketAudience audience;

    private volatile String name;

    private volatile Player player;

    AudienceHolder(Channel initChannel) {
        this.audience = new PacketAudience() {
            @Override
            public void send(PacketType packetType, Bytebuf bytebuf) {
                InternalBytebufHandler.sendPacket(this, packetType, bytebuf);
            }

            @Override
            public @Nullable Player getPlayer() {
                return player;
            }

            @Override
            public @Nullable String getName() {
                return name;
            }

            @Override
            public @NotNull Object getChannel() {
                return initChannel;
            }
        };
    }

    public void setPlayer(Player player) {
        this.player = player;
        this.audience = player;
    }

    public PacketAudience get() {
        return audience;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
