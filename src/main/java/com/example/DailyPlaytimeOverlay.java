package com.example;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;

public class DailyPlaytimeOverlay extends Overlay
{
    private final Client client;
    private final DailyPlaytimePlugin plugin;
    private final DailyPlaytimeConfig config;
    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    private DailyPlaytimeOverlay(Client client, DailyPlaytimePlugin plugin, DailyPlaytimeConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showOverlay() || !plugin.isLoggedIn())
        {
            return null;
        }

        panelComponent.getChildren().clear();
        
        // Format playtime
        Duration dailyDuration = Duration.ofSeconds(plugin.getDailyPlaytimeSeconds());
        String dailyTime = formatDuration(dailyDuration);
        
        // Build overlay content
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Daily Playtime:")
            .right(dailyTime)
            .build());
            
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Sessions:")
            .right(String.valueOf(plugin.getDailySessions()))
            .build());
            
        if (plugin.isAfk())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Status:")
                .right("AFK")
                .rightColor(Color.YELLOW)
                .build());
        }

        return panelComponent.render(graphics);
    }

    private String formatDuration(Duration duration)
    {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        
        if (hours > 0)
        {
            return String.format("%dh %02dm", hours, minutes);
        }
        else
        {
            return String.format("%dm", minutes);
        }
    }
}