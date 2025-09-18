package com.example;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
    name = "Daily Playtime Tracker",
    description = "Tracks daily playtime and uploads statistics to server",
    tags = {"playtime", "statistics", "tracking"}
)
public class DailyPlaytimePlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private DailyPlaytimeConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private DailyPlaytimeOverlay overlay;

    @Inject
    private ScheduledExecutorService executorService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson = new Gson();
    
    // Session tracking variables
    private Instant sessionStartTime;
    private Instant lastActivityTime;
    private long dailyPlaytimeSeconds = 0;
    private int dailySessions = 0;
    private boolean isLoggedIn = false;
    private boolean isAfk = false;
    private String currentDate;
    
    // AFK detection
    private static final int AFK_THRESHOLD_MINUTES = 5;
    private static final int TICK_INTERVAL_MS = 600; // ~600ms per game tick
    
    @Override
    protected void startUp() throws Exception
    {
        log.info("Daily Playtime Tracker started");
        overlayManager.add(overlay);
        
        // Initialize current date and load existing data
        currentDate = getCurrentDate();
        loadDailyData();
        
        // Start session if already logged in
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            startSession();
        }
        
        // Schedule periodic uploads (every 5 minutes)
        executorService.scheduleWithFixedDelay(this::uploadDataIfNeeded, 
            5, 5, TimeUnit.MINUTES);
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Daily Playtime Tracker stopped");
        overlayManager.remove(overlay);
        
        // End current session and upload final data
        if (isLoggedIn)
        {
            endSession();
        }
        uploadDailyData();
        saveDailyData();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged)
    {
        GameState newState = gameStateChanged.getGameState();
        
        switch (newState)
        {
            case LOGGED_IN:
                if (!isLoggedIn)
                {
                    startSession();
                }
                break;
                
            case LOGIN_SCREEN:
            case HOPPING:
                if (isLoggedIn)
                {
                    endSession();
                }
                break;
        }
    }

    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        if (!isLoggedIn)
        {
            return;
        }
        
        // Update activity time
        lastActivityTime = Instant.now();
        
        // Check if we've moved to a new day
        String newDate = getCurrentDate();
        if (!newDate.equals(currentDate))
        {
            // Upload yesterday's data and start fresh
            uploadDailyData();
            saveDailyData();
            resetDailyData();
            currentDate = newDate;
        }
        
        // Update AFK status
        updateAfkStatus();
        
        // Update playtime if not AFK
        if (!isAfk)
        {
            dailyPlaytimeSeconds += TICK_INTERVAL_MS / 1000.0;
        }
    }

    private void startSession()
    {
        log.debug("Starting new session");
        sessionStartTime = Instant.now();
        lastActivityTime = sessionStartTime;
        isLoggedIn = true;
        isAfk = false;
        dailySessions++;
        
        // Save the session start
        saveDailyData();
    }

    private void endSession()
    {
        if (!isLoggedIn || sessionStartTime == null)
        {
            return;
        }
        
        log.debug("Ending session");
        
        // Calculate final session time (excluding AFK time)
        Instant sessionEndTime = Instant.now();
        long sessionSeconds = ChronoUnit.SECONDS.between(sessionStartTime, sessionEndTime);
        
        // Add remaining active time to daily total
        if (!isAfk && lastActivityTime != null)
        {
            long activeSeconds = ChronoUnit.SECONDS.between(sessionStartTime, lastActivityTime);
            dailyPlaytimeSeconds += Math.min(activeSeconds, sessionSeconds);
        }
        
        isLoggedIn = false;
        sessionStartTime = null;
        lastActivityTime = null;
        
        // Save and potentially upload
        saveDailyData();
    }

    private void updateAfkStatus()
    {
        if (lastActivityTime == null)
        {
            return;
        }
        
        long minutesSinceActivity = ChronoUnit.MINUTES.between(lastActivityTime, Instant.now());
        boolean wasAfk = isAfk;
        isAfk = minutesSinceActivity >= AFK_THRESHOLD_MINUTES;
        
        if (wasAfk != isAfk)
        {
            log.debug("AFK status changed: {}", isAfk ? "AFK" : "Active");
        }
    }

    private void uploadDataIfNeeded()
    {
        if (!config.enableUploads() || dailyPlaytimeSeconds < 60)
        {
            return; // Don't upload if disabled or less than 1 minute played
        }
        
        uploadDailyData();
    }

    private void uploadDailyData()
    {
        if (!config.enableUploads())
        {
            return;
        }
        
        try
        {
            String playerName = getPlayerName();
            if (playerName == null)
            {
                log.warn("Cannot upload data: player not identified");
                return;
            }
            
            PlaytimeData data = new PlaytimeData(
                playerName,
                currentDate,
                (int) (dailyPlaytimeSeconds / 60), // Convert to minutes
                dailySessions
            );
            
            String jsonData = gson.toJson(data);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.serverUrl() + "/api/playtime"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonData))
                .timeout(Duration.ofSeconds(10))
                .build();
            
            executorService.submit(() -> {
                try
                {
                    HttpResponse<String> response = httpClient.send(request, 
                        HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 201)
                    {
                        log.debug("Successfully uploaded playtime data");
                    }
                    else
                    {
                        log.warn("Failed to upload data: HTTP {}", response.statusCode());
                    }
                }
                catch (IOException | InterruptedException e)
                {
                    log.warn("Error uploading playtime data", e);
                }
            });
        }
        catch (Exception e)
        {
            log.error("Error preparing upload data", e);
        }
    }

    private String getPlayerName()
    {
        return client.getLocalPlayer() != null ? 
            client.getLocalPlayer().getName() : null;
    }

    private void saveDailyData()
    {
        if (currentDate == null)
        {
            return;
        }
        
        configManager.setConfiguration("dailyplaytime", currentDate + "_seconds", 
            String.valueOf(dailyPlaytimeSeconds));
        configManager.setConfiguration("dailyplaytime", currentDate + "_sessions", 
            String.valueOf(dailySessions));
    }

    private void loadDailyData()
    {
        if (currentDate == null)
        {
            return;
        }
        
        String savedSeconds = configManager.getConfiguration("dailyplaytime", 
            currentDate + "_seconds");
        String savedSessions = configManager.getConfiguration("dailyplaytime", 
            currentDate + "_sessions");
        
        if (savedSeconds != null)
        {
            try
            {
                dailyPlaytimeSeconds = Long.parseLong(savedSeconds);
            }
            catch (NumberFormatException e)
            {
                log.warn("Invalid saved seconds value: {}", savedSeconds);
                dailyPlaytimeSeconds = 0;
            }
        }
        
        if (savedSessions != null)
        {
            try
            {
                dailySessions = Integer.parseInt(savedSessions);
            }
            catch (NumberFormatException e)
            {
                log.warn("Invalid saved sessions value: {}", savedSessions);
                dailySessions = 0;
            }
        }
    }

    private void resetDailyData()
    {
        dailyPlaytimeSeconds = 0;
        dailySessions = 0;
    }

    private String getCurrentDate()
    {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    // Getters for overlay
    public long getDailyPlaytimeSeconds()
    {
        return dailyPlaytimeSeconds;
    }

    public int getDailySessions()
    {
        return dailySessions;
    }

    public boolean isAfk()
    {
        return isAfk;
    }

    public boolean isLoggedIn()
    {
        return isLoggedIn;
    }

    @Provides
    DailyPlaytimeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DailyPlaytimeConfig.class);
    }

    // Data class for JSON serialization
    private static class PlaytimeData
    {
        private final String player_name;
        private final String date;
        private final int minutes_played;
        private final int sessions;

        public PlaytimeData(String playerName, String date, int minutesPlayed, int sessions)
        {
            this.player_name = playerName;
            this.date = date;
            this.minutes_played = minutesPlayed;
            this.sessions = sessions;
        }
    }
}