package org.snowballmc.snowball.bot.agent.actions;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftUseItemAction;

public class ServerUseItemAction extends AbstractUseBotAction<ServerUseItemAction> {

    public ServerUseItemAction() {
        super("use");
    }

    @Override
    protected boolean interact(@NotNull ServerBot bot) {
        return useItem(bot, InteractionHand.MAIN_HAND).consumesAction();
    }

    public static @NotNull InteractionResult useItem(@NotNull ServerBot bot, InteractionHand hand) {
        bot.updateItemInHand(hand);
        InteractionResult result = bot.gameMode.useItem(bot, bot.level(), bot.getItemInHand(hand), hand);
        if (shouldSwing(result)) {
            bot.swing(hand, bot.getItemInHand(hand).getInteractAnimation(), false);
        }
        return result;
    }

    @Override
    public CraftBotAction<?, ServerUseItemAction> asCraft() {
        return new CraftUseItemAction(this);
    }
}
