package org.snowballmc.snowball.protocol.jade.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.protocol.core.LeavesCustomPayload;
import org.snowballmc.snowball.protocol.jade.JadeProtocol;
import org.snowballmc.snowball.protocol.jade.accessor.BlockAccessor;
import org.snowballmc.snowball.protocol.jade.provider.ServerDataProvider;

import java.util.List;
import java.util.Objects;

import static org.snowballmc.snowball.protocol.jade.JadeProtocol.blockDataProviders;

public record RequestBlockPayload(BlockAccessor.SyncData data, List<@Nullable ServerDataProvider<BlockAccessor>> dataProviders) implements LeavesCustomPayload {

    @ID
    private static final Identifier PACKET_REQUEST_BLOCK = JadeProtocol.id("request_block");

    @Codec
    private static final StreamCodec<RegistryFriendlyByteBuf, RequestBlockPayload> CODEC = StreamCodec.composite(
        BlockAccessor.SyncData.STREAM_CODEC,
        RequestBlockPayload::data,
        ByteBufCodecs.<ByteBuf, ServerDataProvider<BlockAccessor>>list()
            .apply(ByteBufCodecs.idMapper(
                $ -> Objects.requireNonNull(blockDataProviders.idMapper()).byId($),
                $ -> Objects.requireNonNull(blockDataProviders.idMapper()).getIdOrThrow($))),
        RequestBlockPayload::dataProviders,
        RequestBlockPayload::new);
}