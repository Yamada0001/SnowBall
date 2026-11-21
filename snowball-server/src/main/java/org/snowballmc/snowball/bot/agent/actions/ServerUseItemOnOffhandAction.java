package org.snowballmc.snowball.bot.agent.actions;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftUseItemOnOffhandAction;

import static org.snowballmc.snowball.bot.agent.actions.ServerUseItemOnAction.useItemOn;

public class ServerUseItemOnOffhandAction extends AbstractUseBotAction<ServerUseItemOnOffhandAction> {

    public ServerUseItemOnOffhandAction() {
        super("use_on_offhand");
    }

    @Override
    protected boolean interact(@NotNull ServerBot bot) {
        BlockHitResult hitResult = bot.getBlockHitResult();
        return useItemOn(bot, hitResult, InteractionHand.OFF_HAND).consumesAction();
    }

    @Override
    public CraftBotAction<?, ServerUseItemOnOffhandAction> asCraft() {
        return new CraftUseItemOnOffhandAction(this);
    }
}
