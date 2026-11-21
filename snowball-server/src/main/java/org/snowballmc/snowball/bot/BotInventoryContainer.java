package org.snowballmc.snowball.bot;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.bot.agent.actions.AbstractBotAction;
import org.snowballmc.snowball.event.bot.BotActionStopEvent;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * 假人背包容器：把假人真实的 {@link Inventory} 以 6 行箱子界面暴露给玩家。
 *
 * <p>槽位映射见 {@link #convertSlot(int)}。映射不到的槽位在旧布局里是占位符，
 * 开启 {@code modify.fakeplayer.fakeplayer-inventory-gui-buttons} 后会变成可点击按钮。</p>
 */
public class BotInventoryContainer extends Inventory {

    /** 按钮在玩家背包数据里打的标记，用于让菜单识别“这是我们的按钮”。 */
    public static final String BUTTON_DATA_TAG = "Leaves.Gui.Button";

    private static final int SLOT_MAINHAND = 0;
    private static final int SLOT_OFFHAND = 40;

    private static final int BUTTON_STOP = 0;
    private static final int BUTTON_SWAP_HANDS = 5;
    private static final int BUTTON_DROP = 8;

    private static final ItemStack PLACEHOLDER;
    private static final ItemStack[] BUTTONS;

    static {
        CompoundTag customData = new CompoundTag();
        customData.putBoolean("Leaves.Gui.Placeholder", true);

        DataComponentPatch patch = DataComponentPatch.builder()
            .set(DataComponents.CUSTOM_NAME, Component.empty())
            .set(DataComponents.CUSTOM_DATA, CustomData.of(customData))
            .build();

        PLACEHOLDER = new ItemStack(Items.STRUCTURE_VOID);
        PLACEHOLDER.applyComponents(patch);

        BUTTONS = new ItemStack[54];
        BUTTONS[BUTTON_STOP] = createButton(Items.BARRIER, "§c停止假人当前动作", "§7中断所有正在执行的动作");
        BUTTONS[BUTTON_SWAP_HANDS] = createButton(Items.SHIELD, "§e交换主副手", "§7交换假人主手与副手的物品");
        BUTTONS[BUTTON_DROP] = createButton(Items.DROPPER, "§6丢弃选中物品", "§7丢出假人快捷栏当前选中的 1 个物品");
    }

    private static ItemStack createButton(Item item, String name, String... lore) {
        CompoundTag customData = new CompoundTag();
        customData.putBoolean(BUTTON_DATA_TAG, true);

        List<Component> loreComponents = new ArrayList<>(lore.length);
        for (String line : lore) {
            loreComponents.add(Component.literal(line).setStyle(Style.EMPTY.withItalic(false)));
        }

        DataComponentPatch patch = DataComponentPatch.builder()
            .set(DataComponents.CUSTOM_NAME, Component.literal(name).setStyle(Style.EMPTY.withItalic(false)))
            .set(DataComponents.LORE, new ItemLore(loreComponents))
            .set(DataComponents.CUSTOM_DATA, CustomData.of(customData))
            .build();

        ItemStack stack = new ItemStack(item);
        stack.applyComponents(patch);
        return stack;
    }

    private final Inventory original;

    public BotInventoryContainer(Inventory original) {
        super(original.player, original.equipment);
        this.original = original;
    }

    @Override
    public int getContainerSize() {
        return 54;
    }

    @Override
    @Nonnull
    public ItemStack getItem(int slot) {
        if (isButtonSlot(slot)) {
            if (LeavesConfig.modify.fakeplayer.inventoryGuiButtons) {
                ItemStack button = BUTTONS[slot];
                if (button != null) {
                    // 返回副本，避免任何容器写入路径改到共享的静态按钮栈
                    return button.copy();
                }
            }
            return PLACEHOLDER;
        }
        return original.getItem(convertSlot(slot));
    }

    public int convertSlot(int slot) {
        return switch (slot) {
            // Mainhand is always store at slot 0
            case 6 -> 0;

            // Offhand
            case 7 -> 40;

            // Equipment slot start at 36
            case 1, 2, 3, 4 -> 40 - slot;

            // Inventory storage
            case 18, 19, 20, 21, 22, 23, 24, 25, 26,
                 27, 28, 29, 30, 31, 32, 33, 34, 35,
                 36, 37, 38, 39, 40, 41, 42, 43, 44 -> slot - 9;

            // Hotbar, 45 -> Mainhand (0)
            case 45, 46, 47, 48, 49, 50, 51, 52, 53 -> slot - 45;

            // Buttons
            default -> -999;
        };
    }

    /**
     * 该槽位是否是“映射不到实体物品”的按钮位。
     *
     * @param slot 界面槽位
     * @return 是按钮位则为 {@code true}
     */
    public boolean isButtonSlot(int slot) {
        return convertSlot(slot) == -999;
    }

    /**
     * 按钮位是否真的显示了一个可点击按钮。
     *
     * <p>关闭 {@code fakeplayer-inventory-gui-buttons} 时按钮位仍是占位符，
     * 点击应当和以前一样被忽略。</p>
     *
     * @param slot 界面槽位
     * @return 有可点击按钮则为 {@code true}
     */
    public boolean isClickableButton(int slot) {
        return LeavesConfig.modify.fakeplayer.inventoryGuiButtons
            && isButtonSlot(slot)
            && slot >= 0 && slot < BUTTONS.length
            && BUTTONS[slot] != null;
    }

    /**
     * 处理按钮点击。
     *
     * @param slot   界面槽位
     * @param player 点击的玩家
     * @return {@code true} 表示点到了按钮并被处理（无论动作是否真的产生了效果）
     */
    public boolean clickButton(int slot, @Nonnull Player player) {
        if (!isClickableButton(slot)) {
            return false;
        }
        switch (slot) {
            case BUTTON_STOP -> {
                return stopActions();
            }
            case BUTTON_SWAP_HANDS -> {
                swapHands();
                return true;
            }
            case BUTTON_DROP -> {
                dropSelected();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean stopActions() {
        if (!(this.player instanceof ServerBot bot)) {
            return false;
        }
        boolean stopped = false;
        // 迭代副本：stop() 的 onStop 回调有可能改动 actions 列表
        for (AbstractBotAction<?> action : List.copyOf(bot.getBotActions())) {
            if (!action.isCancelled()) {
                action.stop(bot, BotActionStopEvent.Reason.COMMAND);
                stopped = true;
            }
        }
        return stopped;
    }

    private void swapHands() {
        ItemStack mainHand = original.getItem(SLOT_MAINHAND);
        ItemStack offHand = original.getItem(SLOT_OFFHAND);
        original.setItem(SLOT_MAINHAND, offHand);
        original.setItem(SLOT_OFFHAND, mainHand);
        player.detectEquipmentUpdates();
    }

    private void dropSelected() {
        int selected = original.getSelectedSlot();
        ItemStack stack = original.getItem(selected);
        if (stack.isEmpty()) {
            return;
        }
        ItemStack dropping = stack.copyWithCount(1);
        stack.shrink(1);
        player.drop(dropping, false, Prediction.SERVER_ONLY);
        player.detectEquipmentUpdates();
    }

    @Override
    @Nonnull
    public ItemStack removeItem(int slot, int amount) {
        if (isButtonSlot(slot)) {
            // Don't remove buttons
            return ItemStack.EMPTY;
        }
        ItemStack removed = original.removeItem(convertSlot(slot), amount);
        player.detectEquipmentUpdates();
        return removed;
    }

    @Override
    @Nonnull
    public ItemStack removeItemNoUpdate(int slot) {
        if (isButtonSlot(slot)) {
            // Don't remove buttons
            return ItemStack.EMPTY;
        }
        return original.removeItemNoUpdate(convertSlot(slot));
    }

    @Override
    public void setItem(int slot, @Nonnull ItemStack stack) {
        if (isButtonSlot(slot)) {
            // Don't modify buttons
            return;
        }
        original.setItem(convertSlot(slot), stack);
        player.detectEquipmentUpdates();
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(@Nonnull Player player) {
        if (this.player.isRemoved()) {
            return false;
        }
        return !(player.distanceToSqr(this.player) > 64.0);
    }
}
