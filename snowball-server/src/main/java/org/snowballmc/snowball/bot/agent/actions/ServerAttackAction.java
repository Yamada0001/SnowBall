package org.snowballmc.snowball.bot.agent.actions;

import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.entity.bot.actions.CraftAttackAction;
import org.snowballmc.snowball.entity.bot.actions.CraftBotAction;

public class ServerAttackAction extends AbstractTimerBotAction<ServerAttackAction> {

    public ServerAttackAction() {
        super("attack");
    }

    @Override
    public boolean doTick(@NotNull ServerBot bot) {
        EntityHitResult hitResult = bot.getEntityHitResult(target -> target.isAttackable() && !target.skipAttackInteraction(bot));
        if (hitResult == null) {
            return false;
        } else {
            bot.attack(hitResult.getEntity());
            return true;
        }
    }

    @Override
    public CraftBotAction<?, ServerAttackAction> asCraft() {
        return new CraftAttackAction(this);
    }
}
