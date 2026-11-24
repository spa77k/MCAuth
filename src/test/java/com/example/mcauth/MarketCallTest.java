package com.example.mcauth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        public String discordSell(UUID playerId, String name, int stagedId, int start, int buyout, int hours) {
            return "sell:" + playerId + ":" + name + ":" + stagedId + ":" + start + ":" + buyout + ":" + hours;
        }

        public String discordCancel(UUID playerId, String name, int listingId) {
            return "cancel:" + playerId + ":" + name + ":" + listingId;
        }

        public List<Map<String, String>> discordListings() {
            return List.of(Map.of("id", "1", "item", "ダイヤ"));
        }

        public List<Map<String, String>> discordStaged(UUID owner) {
            return List.of(Map.of("id", "2", "owner", owner.toString()));
        }

        public List<Map<String, String>> discordMyListings(UUID owner) {
            return List.of(Map.of("id", "3", "owner", owner.toString()));
        }

        public List<Map<String, String>> discordMyBids(UUID bidder) {
            return List.of(Map.of("id", "4", "bidder", bidder.toString()));
        }

        public List<Map<String, String>> discordVault(UUID owner) {
            return List.of(Map.of("id", "5", "owner", owner.toString()));
        }
    }

    abstract static class RequestTarget extends JavaPlugin {
        public String discordAccept(UUID playerId, String name, int requestId) {
            return "accept:" + playerId + ":" + name + ":" + requestId;
        }

        public List<Map<String, String>> discordOpenRequests() {
            return List.of(Map.of("id", "6", "title", "ダイヤを集めて"));
        }

        public List<Map<String, String>> discordMyRequests(UUID requester) {
            return List.of(Map.of("id", "7", "requester", requester.toString()));
        }

        public List<Map<String, String>> discordMyAccepted(UUID worker) {
            return List.of(Map.of("id", "8", "worker", worker.toString()));
        }
    }

    @Test void onlyLinkedDiscordUserCanBidAsStoredMinecraftIdentity() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        VerificationStore store = new VerificationStore(owner);
        UUID minecraftId = UUID.randomUUID();
        store.authenticateIfAvailable(minecraftId, "Exact_Name", "123", "discord");

        AuctionTarget auction = mock(AuctionTarget.class, CALLS_REAL_METHODS);
        RequestTarget requests = mock(RequestTarget.class, CALLS_REAL_METHODS);
        try (var fixture = openFixture(store, auction, requests)) {
            MCAuthPlugin plugin = fixture.plugin;
            List<Object> results = new ArrayList<>();
            plugin.tradeOperation("456", "bid", new int[]{7, 100}, results::add);
            plugin.tradeOperation("123", "bid", new int[]{7, 100}, results::add);
            assertEquals("Minecraftアカウントの認証を完了してください。", results.get(0));
            assertEquals(minecraftId + ":Exact_Name:7:100", results.get(1));
        }
    }

    @Test void operationsPassStoredIdentityAndNumbersToTheRightPlugin() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        UUID minecraftId = UUID.randomUUID();
        try (VerificationStore store = new VerificationStore(owner)) {
            store.authenticateIfAvailable(minecraftId, "Exact_Name", "123", "discord");
            AuctionTarget auction = mock(AuctionTarget.class, CALLS_REAL_METHODS);
            RequestTarget requests = mock(RequestTarget.class, CALLS_REAL_METHODS);
            try (var fixture = openFixture(store, auction, requests)) {
                List<Object> results = new ArrayList<>();
                fixture.plugin.tradeOperation("123", "sell", new int[]{3, 50, 200, 24}, results::add);
                fixture.plugin.tradeOperation("123", "cancel", new int[]{9}, results::add);
                fixture.plugin.tradeOperation("123", "accept", new int[]{5}, results::add);
                assertEquals("sell:" + minecraftId + ":Exact_Name:3:50:200:24", results.get(0));
                assertEquals("cancel:" + minecraftId + ":Exact_Name:9", results.get(1));
                assertEquals("accept:" + minecraftId + ":Exact_Name:5", results.get(2));
            }
        }
    }

    @Test void queryReturnsEverySectionForLinkedPlayer() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        UUID minecraftId = UUID.randomUUID();
        try (VerificationStore store = new VerificationStore(owner)) {
            store.authenticateIfAvailable(minecraftId, "Owner", "123", "owner");
            AuctionTarget auction = mock(AuctionTarget.class, CALLS_REAL_METHODS);
            RequestTarget requests = mock(RequestTarget.class, CALLS_REAL_METHODS);
            try (var fixture = openFixture(store, auction, requests)) {
                AtomicReference<Map<String, Object>> result = new AtomicReference<>();
                fixture.plugin.tradeQuery("123", result::set);
                assertEquals(Boolean.TRUE, result.get().get("linked"));

                @SuppressWarnings("unchecked")
                Map<String, Object> auctionView = (Map<String, Object>) result.get().get("auction");
                assertEquals(List.of(Map.of("id", "1", "item", "ダイヤ")), auctionView.get("listings"));
                assertEquals(List.of(Map.of("id", "2", "owner", minecraftId.toString())), auctionView.get("staged"));
                assertEquals(List.of(Map.of("id", "3", "owner", minecraftId.toString())), auctionView.get("mine"));
                assertEquals(List.of(Map.of("id", "4", "bidder", minecraftId.toString())), auctionView.get("bids"));
                assertEquals(List.of(Map.of("id", "5", "owner", minecraftId.toString())), auctionView.get("vault"));

                @SuppressWarnings("unchecked")
                Map<String, Object> requestView = (Map<String, Object>) result.get().get("requests");
                assertEquals(List.of(Map.of("id", "6", "title", "ダイヤを集めて")), requestView.get("open"));
                assertEquals(List.of(Map.of("id", "7", "requester", minecraftId.toString())), requestView.get("mine"));
                assertEquals(List.of(Map.of("id", "8", "worker", minecraftId.toString())), requestView.get("accepted"));
            }
        }
    }

    @Test void queryReportsUnlinkedAndUnavailableStates() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        try (VerificationStore store = new VerificationStore(owner)) {
            AuctionTarget auction = mock(AuctionTarget.class, CALLS_REAL_METHODS);
            RequestTarget requests = mock(RequestTarget.class, CALLS_REAL_METHODS);
            try (var fixture = openFixture(store, auction, requests)) {
                AtomicReference<Map<String, Object>> unlinked = new AtomicReference<>();
                fixture.plugin.tradeQuery("456", unlinked::set);
                assertEquals(Map.of("linked", false), unlinked.get());

                var lockdown = MCAuthPlugin.class.getDeclaredField("lockdown");
                lockdown.setAccessible(true);
                lockdown.setBoolean(fixture.plugin, true);
                AtomicReference<Map<String, Object>> unavailable = new AtomicReference<>(Map.of());
                fixture.plugin.tradeQuery("456", unavailable::set);
                assertNull(unavailable.get());
                List<Object> operation = new ArrayList<>(List.of("kept"));
                fixture.plugin.tradeOperation("456", "cancel", new int[]{1}, operation::add);
                assertNull(operation.get(1));
            }
        }
    }

    @Test void queryKeepsSectionsNullWhenMarketPluginsAreDisabled() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        UUID minecraftId = UUID.randomUUID();
        try (VerificationStore store = new VerificationStore(owner)) {
            store.authenticateIfAvailable(minecraftId, "Owner", "123", "owner");
            try (var fixture = openFixture(store, null, null)) {
                AtomicReference<Map<String, Object>> result = new AtomicReference<>();
                fixture.plugin.tradeQuery("123", result::set);
                assertEquals(Boolean.TRUE, result.get().get("linked"));
                assertNull(result.get().get("auction"));
                assertNull(result.get().get("requests"));
            }
        }
    }

    /** mockStatic(Bukkit) をテストの間だけ有効にするための AutoCloseable です。 */
    private static Fixture openFixture(
            VerificationStore store, AuctionTarget auction, RequestTarget requests
    ) throws Exception {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var field = MCAuthPlugin.class.getDeclaredField("store");
        field.setAccessible(true);
        field.set(plugin, store);

        PluginManager manager = mock(PluginManager.class);
        if (auction != null) {
            when(auction.isEnabled()).thenReturn(true);
            when(manager.getPlugin("AuctionHouse")).thenReturn(auction);
        }
        if (requests != null) {
            when(requests.isEnabled()).thenReturn(true);
            when(manager.getPlugin("ContractBoard")).thenReturn(requests);
        }
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        var bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        return new Fixture(plugin, bukkit);
    }

    private record Fixture(MCAuthPlugin plugin, org.mockito.MockedStatic<Bukkit> bukkit) implements AutoCloseable {
        @Override public void close() {
            bukkit.close();
        }
    }
}
