package org.snowballmc.snowball.protocol.core.invoker;

import net.minecraft.resources.Identifier;
import org.snowballmc.snowball.protocol.core.IdentifierSelector;
import org.snowballmc.snowball.protocol.core.LeavesProtocol;
import org.snowballmc.snowball.protocol.core.ProtocolHandler;

import java.lang.reflect.Method;

public class MinecraftRegisterInvokerHolder extends AbstractInvokerHolder<ProtocolHandler.MinecraftRegister> {
    public MinecraftRegisterInvokerHolder(LeavesProtocol owner, Method invoker, ProtocolHandler.MinecraftRegister handler) {
        super(owner, invoker, handler, null, handler.stage().identifier(), Identifier.class);
    }

    public void invoke(IdentifierSelector selector, Identifier id) {
        invoke0(false, selector.select(handler.stage()), id);
    }
}