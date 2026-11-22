package net.minecraft.world.level.block.entity;

import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HopperTransferStateTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void transferUsesItsOwnWorldPolicyAndListenerPresence() {
        ServerLevel quiet = world(true);
        ServerLevel listening = world(false);
        assertFalse(new HopperBlockEntity.MoveEvents(quiet, true).shouldFire());
        assertFalse(new HopperBlockEntity.MoveEvents(listening, false).shouldFire());
        assertTrue(new HopperBlockEntity.MoveEvents(listening, true).shouldFire());
    }

    @Test
    void shortCircuitIsLocalEvenDuringNestedTransfers() {
        ServerLevel level = world(false);
        var outer = new HopperBlockEntity.MoveEvents(level, true);
        var nested = new HopperBlockEntity.MoveEvents(level, true);
        nested.afterEvent(false, false);
        assertFalse(nested.shouldFire());
        assertTrue(outer.shouldFire());
        outer.afterEvent(true, false);
        assertTrue(outer.shouldFire());
        outer.afterEvent(false, true);
        assertTrue(outer.shouldFire());
        outer.afterEvent(false, false);
        assertFalse(outer.shouldFire());
        assertTrue(new HopperBlockEntity.MoveEvents(level, true).shouldFire());
    }

    @Test
    void onlyTheTargetHalfOfACompoundContainerSuppressesNotifications() {
        ServerLevel firstWorld = world(false);
        ServerLevel otherWorld = world(false);
        ProbeChest first = new ProbeChest(firstWorld, BlockPos.ZERO);
        ProbeChest other = new ProbeChest(otherWorld, new BlockPos(16, 0, 0));
        other.onSet = first::setChanged;

        HopperBlockEntity.setItemWithoutDuplicateNotification(new CompoundContainer(first, other), 28, ItemStack.EMPTY);

        verify(firstWorld).blockEntityChanged(first.getBlockPos());
        verify(otherWorld, never()).blockEntityChanged(other.getBlockPos());
        assertEquals(1, other.lastSlot);
        other.setChanged();
        verify(otherWorld).blockEntityChanged(other.getBlockPos());
    }

    @Test
    void aNestedWriteAndExceptionRestoreOuterAndFinalSuppression() {
        ServerLevel level = world(false);
        ProbeChest chest = new ProbeChest(level, BlockPos.ZERO);
        chest.onSet = () -> {
            chest.onSet = () -> { throw new IllegalStateException("injected"); };
            assertThrows(IllegalStateException.class,
                () -> HopperBlockEntity.setItemWithoutDuplicateNotification(chest, 0, ItemStack.EMPTY));
            chest.setChanged();
            throw new IllegalStateException("outer failure");
        };

        assertThrows(IllegalStateException.class,
            () -> HopperBlockEntity.setItemWithoutDuplicateNotification(chest, 1, ItemStack.EMPTY));
        verify(level, never()).blockEntityChanged(chest.getBlockPos());
        chest.setChanged();
        verify(level).blockEntityChanged(chest.getBlockPos());
    }

    private static ServerLevel world(boolean disableMoveEvent) {
        ServerLevel level = mock(ServerLevel.class);
        WorldConfiguration config = mock(WorldConfiguration.class);
        config.hopper = config.new Hopper();
        config.hopper.disableMoveEvent = disableMoveEvent;
        when(level.paperConfig()).thenReturn(config);
        return level;
    }

    private static final class ProbeChest extends ChestBlockEntity {
        private Runnable onSet = () -> {};
        private int lastSlot = -1;

        ProbeChest(ServerLevel level, BlockPos pos) {
            super(pos, Blocks.CHEST.defaultBlockState());
            this.setLevel(level);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            this.lastSlot = slot;
            this.setChanged();
            this.onSet.run();
        }
    }
}
