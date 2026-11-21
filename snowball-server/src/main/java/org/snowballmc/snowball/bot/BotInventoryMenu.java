package org.snowballmc.snowball.bot;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;

import javax.annotation.Nonnull;

/**
 * 假人背包界面：在 6 行箱子菜单上拦截按钮位点击。
 *
 * <p>只有在 {@code modify.fakeplayer.fakeplayer-inventory-gui-buttons} 打开、
 * 且该位确实渲染了按钮时才会拦截；关闭时行为与普通 {@link ChestMenu} 完全一致。</p>
 */
public class BotInventoryMenu extends ChestMenu {

    private final BotInventoryContainer botContainer;

    public BotInventoryMenu(int containerId, @Nonnull Inventory playerInventory, @Nonnull BotInventoryContainer container) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, container, 6);
        this.botContainer = container;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, @Nonnull ContainerInput containerInput, @Nonnull Player player) {
        if (slotIndex >= 0 && slotIndex < this.botContainer.getContainerSize()
            && this.botContainer.clickButton(slotIndex, player)) {
            this.broadcastChanges();
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }
}
