package org.snowballmc.snowball.util;

import com.destroystokyo.paper.PaperVersionFetcher;
import com.mojang.logging.LogUtils;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.OptionalInt;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.TextColor.color;

public class LeavesVersionFetcher extends PaperVersionFetcher {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    @NotNull
    @Override
    public Component getVersionMessage() {
        final ServerBuildInfo build = ServerBuildInfo.buildInfo();
        final Component updateMessage;
        if (build.buildNumber().isEmpty() && build.gitCommit().isEmpty()) {
            updateMessage = text("You are running a development version without access to version information", color(0xFF5300));
        } else if (build.buildNumber().isEmpty()) {
            updateMessage = text("You are running a development version from CI", color(0xFF5300));
        } else if (build.gitCommit().isEmpty()) {
            updateMessage = text("Unknown version", NamedTextColor.YELLOW);
        } else {
            updateMessage = getUpdateStatusMessage(build);
        }
        final @Nullable Component history = this.getHistory();
        return history != null ? Component.textOfChildren(updateMessage, Component.newline(), history) : updateMessage;
    }

    private static Component getUpdateStatusMessage(ServerBuildInfo build) {
        try {
            final LeavesUpdateHelper.ReleaseCatalog releases = LeavesUpdateHelper.getReleaseCatalog(build.minecraftVersionId());
            final OptionalInt distance = releases.versionDistance(build.gitCommit().get(), build.buildNumber().getAsInt());
            if (distance.isEmpty()) {
                return text("Unknown version", NamedTextColor.YELLOW);
            }
            if (distance.getAsInt() == 0) {
                return text("You are running the latest compatible SnowBall release", NamedTextColor.GREEN);
            }
            final String downloads = releases.releasesPage();
            final int latestBuild = build.buildNumber().getAsInt() + distance.getAsInt();
            return text("A newer compatible SnowBall release is available (build " + latestBuild + ", running " + build.buildNumber().getAsInt() + ")", NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(text("Download the new version at: ")
                    .append(text(downloads, NamedTextColor.GOLD)
                        .hoverEvent(text("Click to open", NamedTextColor.WHITE))
                        .clickEvent(ClickEvent.openUrl(downloads))));
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Could not check SnowBall GitHub releases", e);
            return text("Error obtaining version information", NamedTextColor.YELLOW);
        }
    }
}
