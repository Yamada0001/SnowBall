package org.snowballmc.snowball.bot.agent.actions;

import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftUseItemOffhandAction;

import static org.snowballmc.snowball.bot.agent.actions.ServerUseItemAction.useItem;

public class ServerUseItemOffhandAction extends AbstractUseBotAction<ServerUseItemOffhandAction> {

    public ServerUseItemOffhandAction() {
        super("use_offhand");
    }

    @Override
    protected boolean interact(@NotNull ServerBot bot) {
        return useItem(bot, InteractionHand.OFF_HAND).consumesAction();
    }

    @Override
    public CraftBotAction<?, ServerUseItemOffhandAction> asCraft() {
        return new CraftUseItemOffhandAction(this);
    }
}
