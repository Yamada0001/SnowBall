package org.snowballmc.snowball.util;

import com.google.common.collect.Iterables;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.LeavesConfig;

import java.util.List;
import java.util.Objects;

public class ItemOverstackUtils {

    private static final List<ItemUtil> overstackUtils = List.of(
        new ShulkerBox(),
        new CurseEnchantedBook()
    );

    public static int getItemStackMaxCount(ItemStack stack) {
        int size;
        for (ItemUtil util : overstackUtils) {
            if (util.isEnabled() && (size = util.getMaxServerStackCount(stack)) != -1) {
                return size;
            }
        }
        return stack.getMaxStackSize();
    }

    public static int getNetworkMaxCount(ItemStack stack) {
        int size;
        for (ItemUtil util : overstackUtils) {
            if (util.isEnabled() && (size = util.getMaxClientStackCount(stack)) != -1) {
                return size;
            }
        }
        return stack.getMaxStackSize();
    }

    public static boolean tryStackItems(ItemEntity self, ItemEntity other) {
        for (ItemUtil util : overstackUtils) {
            if (util.isEnabled() && util.tryStackItems(self, other)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOverstackingItem() {
        for (ItemUtil util : overstackUtils) {
            if (util.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    public static int getRealItemStackMaxCount(ItemStack stack) {
        CompoundTag nbt = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return nbt.getInt("Leaves.RealStackSize").orElse(stack.getMaxStackSize());
    }

    public static ItemStack encodeMaxStackSize(ItemStack itemStack) {
        int realMaxStackSize = getRealItemStackMaxCount(itemStack);
        int modifiedMaxStackSize = getNetworkMaxCount(itemStack);
        if (itemStack.getMaxStackSize() != modifiedMaxStackSize) {
            itemStack.set(DataComponents.MAX_STACK_SIZE, modifiedMaxStackSize);
            CompoundTag nbt = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            nbt.putInt("Leaves.RealStackSize", realMaxStackSize);
            itemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        }
        return itemStack;
    }

    public static ItemStack decodeMaxStackSize(ItemStack itemStack) {
        int realMaxStackSize = getRealItemStackMaxCount(itemStack);
        if (itemStack.getMaxStackSize() != realMaxStackSize) {
            itemStack.set(DataComponents.MAX_STACK_SIZE, realMaxStackSize);
            CompoundTag nbt = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            nbt.remove("Leaves.RealStackSize");
            if (nbt.isEmpty()) {
                itemStack.remove(DataComponents.CUSTOM_DATA);
            } else {
                itemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
            }
        }
        return itemStack;
    }

    public static float getItemStackSignalStrength(int maxStackSize, ItemStack itemStack) {
        float result = (float) itemStack.getCount() / Math.min(maxStackSize, itemStack.getMaxStackSize());
        return Math.clamp(result, 0f, 1f);
    }

    public static boolean isStackable(ItemStack itemStack) {
        return getItemStackMaxCount(itemStack) > 1 && (!itemStack.isDamageableItem() || !itemStack.isDamaged());
    }

    private interface ItemUtil {
        boolean isEnabled();

        boolean tryStackItems(ItemEntity self, ItemEntity other);

        // number -> modified count, -1 -> I don't care
        int getMaxServerStackCount(ItemStack stack);

        // number -> modified count, -1 -> I don't care
        default int getMaxClientStackCount(ItemStack stack) {
            return getMaxServerStackCount(stack);
        }
    }

    private static class ShulkerBox implements ItemUtil {
        public static boolean checkItems(@NotNull ItemStack stack1, @NotNull ItemStack stack2) {
            if (LeavesConfig.modify.shulkerBox.sameNbtStackable) {
                return Objects.equals(stack1.getComponents(), stack2.getComponents());
            }
            return isEmpty(stack1) && isEmpty(stack2) && Objects.equals(stack1.getComponents(), stack2.getComponents());
        }

        public static boolean isEmpty(@NotNull ItemStack stack) {
            return Iterables.isEmpty(stack.getComponents().getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems());
        }

        @Override
        public boolean isEnabled() {
            return LeavesConfig.modify.shulkerBox.stackableShulkerBoxes > 1;
        }

        @Override
        public boolean tryStackItems(ItemEntity self, ItemEntity other) {
            ItemStack selfStack = self.getItem();
            if (!(selfStack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
                return false;
            }

            ItemStack otherStack = other.getItem();
            if (selfStack.getItem() == otherStack.getItem()
                && checkItems(selfStack, otherStack)
                && selfStack.getCount() != org.snowballmc.snowball.LeavesConfig.modify.shulkerBox.stackableShulkerBoxes) {
                int amount = Math.min(otherStack.getCount(), org.snowballmc.snowball.LeavesConfig.modify.shulkerBox.stackableShulkerBoxes - selfStack.getCount());

                selfStack.grow(amount);
                self.setItem(selfStack);

                self.pickupDelay = Math.max(other.pickupDelay, self.pickupDelay);
                self.age = Math.min(other.getAge(), self.age);

                otherStack.shrink(amount);
                if (otherStack.isEmpty()) {
                    other.discard();
                } else {
                    other.setItem(otherStack);
                }
                return true;
            }
            return false;
        }

        @Override
        public int getMaxServerStackCount(ItemStack stack) {
            if (stack.getItem() instanceof BlockItem bi &&
                bi.getBlock() instanceof ShulkerBoxBlock && (LeavesConfig.modify.shulkerBox.sameNbtStackable || isEmpty(stack))) {
                return LeavesConfig.modify.shulkerBox.stackableShulkerBoxes;
            }
            return -1;
        }
    }

    /**
     * 让带诅咒附魔的附魔书可以堆叠。
     *
     * <p>只有 {@code Items.ENCHANTED_BOOK} 且至少带一个 {@link EnchantmentTags#CURSE} 附魔时生效，
     * 且只有附魔（以及其它组件）完全相同的两本书才会叠在一起 —— 不同附魔的诅咒书仍然是独立物品。</p>
     */
    private static class CurseEnchantedBook implements ItemUtil {

        public static boolean isCurseBook(@NotNull ItemStack stack) {
            if (!stack.is(Items.ENCHANTED_BOOK)) {
                return false;
            }
            ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
            for (Holder<Enchantment> enchantment : enchantments.keySet()) {
                if (enchantment.is(EnchantmentTags.CURSE)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isEnabled() {
            return LeavesConfig.modify.curseEnchantedBookStackSize > 1;
        }

        @Override
        public boolean tryStackItems(ItemEntity self, ItemEntity other) {
            ItemStack selfStack = self.getItem();
            if (!isCurseBook(selfStack)) {
                return false;
            }

            int maxCount = LeavesConfig.modify.curseEnchantedBookStackSize;
            ItemStack otherStack = other.getItem();
            if (selfStack.getItem() == otherStack.getItem()
                && Objects.equals(selfStack.getComponents(), otherStack.getComponents())
                && selfStack.getCount() != maxCount) {
                int amount = Math.min(otherStack.getCount(), maxCount - selfStack.getCount());

                selfStack.grow(amount);
                self.setItem(selfStack);

                self.pickupDelay = Math.max(other.pickupDelay, self.pickupDelay);
                self.age = Math.min(other.getAge(), self.age);

                otherStack.shrink(amount);
                if (otherStack.isEmpty()) {
                    other.discard();
                } else {
                    other.setItem(otherStack);
                }
                return true;
            }
            return false;
        }

        @Override
        public int getMaxServerStackCount(ItemStack stack) {
            if (isCurseBook(stack)) {
                return LeavesConfig.modify.curseEnchantedBookStackSize;
            }
            return -1;
        }
    }
}