package org.snowballmc.snowball.command.leaves.subcommands;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.flag.FeatureFlagSet;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Suite
@SelectClasses(ReportCommandTest.class)
public class ReportCommandTestSuite {
}

class ReportCommandTest {
    @Test
    void reportsTheCountAndNamesOfOnlySelectablePacks() {
        Pack enabled = pack("Enabled", FeatureFlags.VANILLA_SET);
        Pack first = pack("First", FeatureFlags.VANILLA_SET);
        Pack second = pack("Second", FeatureFlags.VANILLA_SET);
        Pack incompatible = pack("Experimental", FeatureFlagSet.of(FeatureFlags.MINECART_IMPROVEMENTS));

        String report = ReportCommand.formatDataPackMessage(List.of(enabled), List.of(enabled, first, incompatible, second), FeatureFlags.VANILLA_SET);

        assertEquals("There are 1 data pack(s) enabled: Enabled\nThere are 2 data pack(s) available: First, Second", report);
    }

    @Test
    void separatesTheEmptyEnabledAndAvailableMessages() {
        assertEquals("There are no data packs enabled\nThere are no more data packs available",
            ReportCommand.formatDataPackMessage(List.of(), List.of(), FeatureFlags.VANILLA_SET));
    }

    private static Pack pack(String label, FeatureFlagSet requiredFeatures) {
        Pack pack = mock(Pack.class);
        when(pack.getRequestedFeatures()).thenReturn(requiredFeatures);
        when(pack.getChatLink(false)).thenReturn(Component.literal(label));
        return pack;
    }
}
