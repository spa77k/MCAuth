package com.example.mcauth;

import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginAndLinkTest {
    @TempDir Path directory;

    private VerificationStore store() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new VerificationStore(plugin);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = MCAuthPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private MCAuthPlugin plugin(VerificationStore store, Map<String, PendingVerification> pendingCodes) throws Exception {
        // CALLS_REAL_METHODSではコンストラクターが実行されないため、必要なフィールドを設定します。
        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        set(plugin, "store", store);
        set(plugin, "pendingCodes", pendingCodes);
        set(plugin, "pendingCodesByUuid", new HashMap<UUID, String>());
        set(plugin, "codeLifetime", Duration.ofSeconds(300));
        return plugin;
    }

    @Test void rejectsLoginWhenCodeCannotBeCreated() throws Exception {
        MCAuthPlugin plugin = plugin(store(), new HashMap<>());
        // 上限0でコード生成を失敗させます。
        set(plugin, "codeUpperBound", 0);
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getName()).thenReturn("Player");
        plugin.onAsyncPlayerPreLogin(event);
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER),
                any(net.kyori.adventure.text.Component.class));
    }

    @Test void linkedDiscordUserGetsExplanationAndCodeStaysUnused() throws Exception {
        VerificationStore store = store();
        store.authenticateIfAvailable(UUID.randomUUID(), "Owner", "123", "owner");
        Map<String, PendingVerification> pendingCodes = new HashMap<>();
        pendingCodes.put("1234", new PendingVerification(UUID.randomUUID(), "Other",
                java.time.Instant.now().plusSeconds(300)));
        MCAuthPlugin plugin = plugin(store, pendingCodes);
        List<String> replies = new ArrayList<>();
        // trueは「コードの誤りではない」ことを表し、失敗回数に数えられません。
        assertTrue(plugin.verifyCode("1234", "123", "owner", replies::add));
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).contains("/unlink"));
        assertTrue(pendingCodes.containsKey("1234"));
    }

    @Test void revokesOnlyWhenLeavingVerifyGuild() {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class);
        DiscordVerificationBot bot = new DiscordVerificationBot(plugin, 42L, 6, 5,
                Duration.ofSeconds(60), "invalid", "locked", "panel", 0L);
        var event = mock(GuildMemberRemoveEvent.class, RETURNS_DEEP_STUBS);
        when(event.getJDA().getGuildChannelById(42L).getGuild().getIdLong()).thenReturn(1L);
        when(event.getUser().getId()).thenReturn("123");

        when(event.getGuild().getIdLong()).thenReturn(2L);
        bot.onGuildMemberRemove(event);
        verify(plugin, never()).revokeByDiscordUserId(anyString());

        when(event.getGuild().getIdLong()).thenReturn(1L);
        bot.onGuildMemberRemove(event);
        verify(plugin).revokeByDiscordUserId("123");
    }
}
