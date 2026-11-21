package org.snowballmc.snowball.protocol.core.invoker;

import net.minecraft.network.FriendlyByteBuf;
import org.snowballmc.snowball.protocol.core.IdentifierSelector;
import org.snowballmc.snowball.protocol.core.LeavesProtocol;
import org.snowballmc.snowball.protocol.core.ProtocolHandler;

import java.lang.reflect.Method;

public class BytebufReceiverInvokerHolder extends AbstractInvokerHolder<ProtocolHandler.BytebufReceiver> {
    public BytebufReceiverInvokerHolder(LeavesProtocol owner, Method invoker, ProtocolHandler.BytebufReceiver handler) {
        super(owner, invoker, handler, null, handler.stage().identifier(), FriendlyByteBuf.class);
    }

    public boolean invoke(IdentifierSelector selector, FriendlyByteBuf buf) {
        return invoke0(false, selector.select(handler.stage()), buf) instanceof Boolean b && b;
    }
}