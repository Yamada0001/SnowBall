package net.minecraft.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.snowballmc.snowball.LeavesConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@VanillaFeature
class LargeBarrelTestSuite {

    @Test
    void opensNewLargeBarrelBeforeLoadingAnyBlockEntityData() {
        boolean previousLargeBarrel = LeavesConfig.modify.largeBarrel;
        boolean previousInitSlots = LeavesConfig.performance.ceruleanInitLastSlots;
        try {
            LeavesConfig.modify.largeBarrel = true;
            LeavesConfig.performance.ceruleanInitLastSlots = true;
            BarrelBlockEntity barrel = newBarrel();
            ChestMenu menu = open(barrel);
            assertSame(barrel, menu.getContainer());
            assertEquals(6, menu.getRowCount());
            assertEquals(54, barrel.getContents().size());
            assertTrue(menu.getSlot(27).getItem().isEmpty());
            assertTrue(menu.getSlot(53).getItem().isEmpty());
        } finally {
            LeavesConfig.modify.largeBarrel = previousLargeBarrel;
            LeavesConfig.performance.ceruleanInitLastSlots = previousInitSlots;
        }
    }

    @Test
    void preservesContentsAndPerInstanceSizeAcrossLoadingAndConfigChanges() {
        boolean previous = LeavesConfig.modify.largeBarrel;
        try {
            LeavesConfig.modify.largeBarrel = false;
            BarrelBlockEntity normal = newBarrel();
            normal.setItem(26, new ItemStack(Items.STONE, 3));
            assertEquals(3, open(normal).getRowCount());
            assertEquals(27, normal.getContents().size());

            LeavesConfig.modify.largeBarrel = true;
            assertEquals(3, open(normal).getRowCount());
            BarrelBlockEntity large = newBarrel();
            loadFrom(large, normal);
            assertEquals(6, open(large).getRowCount());
            assertEquals(3, large.getItem(26).getCount());
            assertTrue(large.getItem(27).isEmpty());

            large.setItem(53, new ItemStack(Items.DIAMOND, 2));
            BarrelBlockEntity reloaded = newBarrel();
            loadFrom(reloaded, large);
            assertEquals(54, reloaded.getContents().size());
            assertTrue(open(reloaded).getSlot(53).getItem().is(Items.DIAMOND));
            assertEquals(2, reloaded.getItem(53).getCount());

            LeavesConfig.modify.largeBarrel = false;
            assertEquals(3, open(newBarrel()).getRowCount());
            assertEquals(6, open(reloaded).getRowCount());
            assertEquals(2, reloaded.getItem(53).getCount());
        } finally {
            LeavesConfig.modify.largeBarrel = previous;
        }
    }

    private static BarrelBlockEntity newBarrel() {
        return new BarrelBlockEntity(BlockPos.ZERO, Blocks.BARREL.defaultBlockState());
    }

    private static ChestMenu open(BarrelBlockEntity barrel) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getItem(anyInt())).thenReturn(ItemStack.EMPTY);
        return (ChestMenu) barrel.createMenu(1, inventory);
    }

    private static void loadFrom(BarrelBlockEntity target, BarrelBlockEntity source) {
        var registries = RegistryHelper.registryAccess();
        target.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, source.saveWithoutMetadata(registries)));
    }
}
