package org.snowballmc.snowball.region;

import ca.spottedleaf.moonrise.paper.PaperHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

public final class LeavesHooks extends PaperHooks {

    @Override
    public String getBrand() {
        return "SnowBall";
    }

    @Override
    public void onChunkWatch(ServerLevel world, LevelChunk chunk, ServerPlayer player) {
        super.onChunkWatch(world, chunk, player);
        org.snowballmc.snowball.protocol.servux.ServuxStructuresProtocol.onStartedWatchingChunk(player, chunk); // servux
    }
}
