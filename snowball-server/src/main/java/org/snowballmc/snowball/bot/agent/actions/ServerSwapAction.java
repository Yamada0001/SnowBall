package org.snowballmc.snowball.bot.agent.actions;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;
import org.snowballmc.snowball.entity.bot.actions.CraftSwapAction;

public class ServerSwapAction extends AbstractBotAction<ServerSwapAction> {

    public ServerSwapAction() {
        super("swap");
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        ItemStack mainHandItem = bot.getMainHandItem();
        ItemStack offHandItem = bot.getOffhandItem();
        bot.setItemInHand(InteractionHand.MAIN_HAND, offHandItem);
        bot.setItemInHand(InteractionHand.OFF_HAND, mainHandItem);
        return true;
    }

    @Override
    public CraftBotAction<?, ServerSwapAction> asCraft() {
        return new CraftSwapAction(this);
    }
}
