package com.example.mcauth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MarketCallTest {
    @TempDir Path directory;

    abstract static class AuctionTarget extends JavaPlugin {
        public String discordBid(UUID playerId, String name, int listingId, int amount) {
            return playerId + ":" + name + ":" + listingId + ":" + amount;
        }
    }

    @Test void onlyLinkedDiscordUserCanBidAsStoredMinecraftIdentity() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        VerificationStore store = new VerificationStore(owner);
        UUID minecraftId = UUID.randomUUID();
        store.authenticateIfAvailable(minecraftId, "Exact_Name", "123", "discord");

        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var field = MCAuthPlugin.class.getDeclaredField("store");
        field.setAccessible(true);
        field.set(plugin, store);

        AuctionTarget auction = mock(AuctionTarget.class, CALLS_REAL_METHODS);
        when(auction.isEnabled()).thenReturn(true);
        PluginManager manager = mock(PluginManager.class);
        when(manager.getPlugin("AuctionHouse")).thenReturn(auction);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        List<Object> results = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            plugin.marketCall("456", "bid", new int[]{7, 100}, results::add);
            plugin.marketCall("123", "bid", new int[]{7, 100}, results::add);
        }
        assertEquals("Minecraftアカウントの認証を完了してください。", results.get(0));
        assertEquals(minecraftId + ":Exact_Name:7:100", results.get(1));
    }
}
