package org.snowballmc.snowball.bot.agent.actions;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftUseItemToOffhandAction;

import static org.snowballmc.snowball.bot.agent.actions.ServerUseItemToAction.useItemTo;

public class ServerUseItemToOffhandAction extends AbstractUseBotAction<ServerUseItemToOffhandAction> {

    public ServerUseItemToOffhandAction() {
        super("use_to_offhand");
    }

    @Override
    protected boolean interact(@NotNull ServerBot bot) {
        EntityHitResult hitResult = bot.getEntityHitResult();
        return useItemTo(bot, hitResult, InteractionHand.OFF_HAND).consumesAction();
    }

    @Override
    public CraftBotAction<?, ServerUseItemToOffhandAction> asCraft() {
        return new CraftUseItemToOffhandAction(this);
    }
}
