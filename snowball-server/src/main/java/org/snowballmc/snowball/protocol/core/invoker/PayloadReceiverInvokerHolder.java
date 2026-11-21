package org.snowballmc.snowball.protocol.core.invoker;

import org.snowballmc.snowball.protocol.core.IdentifierSelector;
import org.snowballmc.snowball.protocol.core.LeavesCustomPayload;
import org.snowballmc.snowball.protocol.core.LeavesProtocol;
import org.snowballmc.snowball.protocol.core.ProtocolHandler;

import java.lang.reflect.Method;

public class PayloadReceiverInvokerHolder extends AbstractInvokerHolder<ProtocolHandler.PayloadReceiver> {
    public PayloadReceiverInvokerHolder(LeavesProtocol owner, Method invoker, ProtocolHandler.PayloadReceiver handler) {
        super(owner, invoker, handler, null, handler.stage().identifier(), handler.payload());
    }

    public void invoke(IdentifierSelector selector, LeavesCustomPayload payload) {
        invoke0(false, selector.select(handler.stage()), payload);
    }
}
