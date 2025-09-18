package com.example;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("dailyplaytime")
public interface DailyPlaytimeConfig extends Config
{
    @ConfigItem(
        keyName = "enableUploads",
        name = "Enable Data Uploads",
        description = "Upload playtime data to server",
        position = 1
    )
    default boolean enableUploads()
    {
        return false; // Disabled by default for privacy
    }

    @ConfigItem(
        keyName = "serverUrl",
        name = "Server URL",
        description = "URL of the playtime tracking server",
        position = 2
    )
    default String serverUrl()
    {
        return "https://ewoh.circuitlogic.org";
    }

    @ConfigItem(
        keyName = "showOverlay",
        name = "Show Overlay",
        description = "Display playtime overlay in-game",
        position = 3
    )
    default boolean showOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "overlayPosition",
        name = "Overlay Position",
        description = "Position of the playtime overlay",
        position = 4
    )
    default OverlayPosition overlayPosition()
    {
        return OverlayPosition.TOP_LEFT;
    }

    enum OverlayPosition
    {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
}