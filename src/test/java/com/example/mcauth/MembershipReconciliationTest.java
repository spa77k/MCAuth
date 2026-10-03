package com.example.mcauth;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipReconciliationTest {
    @TempDir Path directory;

    private VerificationStore store() {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        when(owner.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new VerificationStore(owner);
    }

    private MCAuthPlugin plugin(VerificationStore store) throws Exception {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        set(plugin, "store", store);
        set(plugin, "pendingCodes", new HashMap<>());
        set(plugin, "pendingCodesByUuid", new HashMap<>());
        return plugin;
    }

    private void set(MCAuthPlugin plugin, String name, Object value) throws Exception {
        var field = MCAuthPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private AsyncPlayerPreLoginEvent login(MCAuthPlugin plugin, UUID uuid) {
        var event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        plugin.onAsyncPlayerPreLogin(event);
        return event;
    }

    @Test void deniesLoginUntilCheckCompletesThenRemovesOnlyDepartedUserAndPersists() throws Exception {
        try (VerificationStore store = store()) {
            UUID departed = UUID.randomUUID();
            UUID present = UUID.randomUUID();
            store.authenticateIfAvailable(departed, "Departed", "123", "departed");
            store.authenticateIfAvailable(present, "Present", "456", "present");
            MCAuthPlugin plugin = plugin(store);
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            Player online = mock(Player.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(() -> Bukkit.getPlayer(departed)).thenReturn(online);
                doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                        .when(scheduler).runTask(eq(plugin), any(Runnable.class));
                plugin.reconcileDiscordMembership(id -> id.equals("123")
                        ? answer : CompletableFuture.completedFuture(true));
                verify(login(plugin, present)).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
                plugin.verifyCode("1234", "456", "present", reply -> assertTrue(reply.contains("在籍確認")));
                answer.complete(false);
                assertFalse(store.isAuthenticated(departed));
                assertTrue(store.isAuthenticated(present));
                verify(online).kick(any(net.kyori.adventure.text.Component.class));
                verify(login(plugin, present), never()).disallow(any(AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
                try (VerificationStore reloaded = store()) {
                    assertFalse(reloaded.isAuthenticated(departed));
                    assertTrue(reloaded.isAuthenticated(present));
                    assertEquals(VerificationStore.AuthenticationResult.SAVED,
                            reloaded.authenticateIfAvailable(departed, "Departed", "123", "departed"));
                }
            }
        }
    }

    @Test void failedLookupKeepsDataAndLocksDownUntilRestart() throws Exception {
        try (VerificationStore store = store()) {
            UUID uuid = UUID.randomUUID();
            store.authenticateIfAvailable(uuid, "Owner", "123", "owner");
            MCAuthPlugin plugin = plugin(store);
            Player online = mock(Player.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(online));
                doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                        .when(scheduler).runTask(eq(plugin), any(Runnable.class));
                plugin.reconcileDiscordMembership(id -> CompletableFuture.failedFuture(new IllegalStateException("network")));
                assertTrue(store.isAuthenticated(uuid));
                verify(online).kick(any(net.kyori.adventure.text.Component.class));
                plugin.reconcileDiscordMembership(id -> CompletableFuture.completedFuture(true));
                verify(login(plugin, uuid)).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
            }
        }
    }

    @Test void emptyDatabaseCompletesAndShutdownIgnoresLateResponses() throws Exception {
        try (VerificationStore store = store()) {
            MCAuthPlugin plugin = plugin(store);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                        .when(scheduler).runTask(eq(plugin), any(Runnable.class));
                plugin.reconcileDiscordMembership(id -> { fail("Empty DB must not call API"); return null; });
                var pending = MCAuthPlugin.class.getDeclaredField("membershipCheckPending");
                pending.setAccessible(true);
                assertFalse(pending.getBoolean(plugin));
                UUID uuid = UUID.randomUUID();
                store.authenticateIfAvailable(uuid, "Owner", "123", "owner");
                CompletableFuture<Boolean> answer = new CompletableFuture<>();
                plugin.reconcileDiscordMembership(id -> answer);
                clearInvocations(scheduler);
                plugin.onDisable();
                answer.complete(false);
                verifyNoInteractions(scheduler);
                try (VerificationStore reloaded = store()) {
                    assertTrue(reloaded.isAuthenticated(uuid));
                }
            }
        }
    }

    @Test void restLookupBypassesCacheAndOnlyAbsenceIsTreatedAsDeparture() {
        Guild guild = mock(Guild.class);
        @SuppressWarnings("unchecked")
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(guild.retrieveMemberById("123")).thenReturn(action);
        when(action.useCache(false)).thenReturn(action);
        when(action.submit()).thenReturn(CompletableFuture.completedFuture(mock(Member.class)));
        assertTrue(DiscordVerificationBot.retrieveMembership(guild, "123").join());
        for (ErrorResponse response : List.of(ErrorResponse.UNKNOWN_MEMBER, ErrorResponse.UNKNOWN_USER)) {
            ErrorResponseException error = mock(ErrorResponseException.class);
            when(error.getErrorResponse()).thenReturn(response);
            when(action.submit()).thenReturn(CompletableFuture.failedFuture(error));
            assertFalse(DiscordVerificationBot.retrieveMembership(guild, "123").join());
        }
        when(action.submit()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("network")));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> DiscordVerificationBot.retrieveMembership(guild, "123").join());
        verify(action, times(4)).useCache(false);
    }

    @Test void leavingWhileOnlineAlsoDisconnects() throws Exception {
        try (VerificationStore store = store()) {
            UUID uuid = UUID.randomUUID();
            store.authenticateIfAvailable(uuid, "Owner", "123", "owner");
            MCAuthPlugin plugin = plugin(store);
            Player online = mock(Player.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(online);
                doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                        .when(scheduler).runTask(eq(plugin), any(Runnable.class));
                plugin.revokeByDiscordUserId("123");
                assertFalse(store.isAuthenticated(uuid));
                verify(online).kick(any(net.kyori.adventure.text.Component.class));
            }
        }
    }
}
