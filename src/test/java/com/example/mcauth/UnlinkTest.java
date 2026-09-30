package com.example.mcauth;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnlinkTest {
    @TempDir Path directory;

    private VerificationStore store() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new VerificationStore(plugin);
    }

    @Test void removesOnlyOwnerAndPersistsAcrossReload() {
        VerificationStore store = store();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        store.authenticateIfAvailable(owner, "Owner", "123", "owner");
        store.authenticateIfAvailable(other, "Other", "456", "other");
        assertEquals(owner, store.deauthenticateByDiscordUserId("123").uuid());
        VerificationStore reloaded = store();
        reloaded.load();
        assertFalse(reloaded.isAuthenticated(owner));
        assertTrue(reloaded.isAuthenticated(other));
        assertNull(reloaded.deauthenticateByDiscordUserId("123"));
        assertTrue(reloaded.authenticateIfAvailable(owner, "Owner", "123", "owner"));
    }

    @Test void failedSaveRestoresAuthentication() throws Exception {
        VerificationStore store = store();
        UUID owner = UUID.randomUUID();
        store.authenticateIfAvailable(owner, "Owner", "123", "owner");
        java.nio.file.Files.delete(directory.resolve("data.yml"));
        java.nio.file.Files.createDirectory(directory.resolve("data.yml"));
        assertThrows(IllegalStateException.class, () -> store.deauthenticateByDiscordUserId("123"));
        assertTrue(store.isAuthenticated(owner));
    }

    @Test void registersSlashCommandInConfiguredGuild() {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class);
        DiscordVerificationBot bot = new DiscordVerificationBot(plugin, 42L, 6, 5,
                Duration.ofSeconds(60), "invalid", "locked");
        var event = mock(net.dv8tion.jda.api.events.session.ReadyEvent.class, RETURNS_DEEP_STUBS);
        bot.onReady(event);
        verify(event.getJDA().getGuildChannelById(42L).getGuild())
                .upsertCommand(eq("unlink"), anyString());
    }

    @Test void slashCommandUsesSenderAndRejectsOtherChannels() {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class);
        DiscordVerificationBot bot = new DiscordVerificationBot(plugin, 42L, 6, 5,
                Duration.ofSeconds(60), "invalid", "locked");
        var event = mock(net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent.class,
                RETURNS_DEEP_STUBS);
        when(event.getName()).thenReturn("unlink");
        when(event.isFromGuild()).thenReturn(true);
        when(event.getChannel().getIdLong()).thenReturn(42L);
        when(event.getUser().getId()).thenReturn("123");
        var hook = mock(net.dv8tion.jda.api.interactions.InteractionHook.class, RETURNS_DEEP_STUBS);
        var deferredReply = event.deferReply(true);
        doAnswer(call -> {
            call.getArgument(0, java.util.function.Consumer.class).accept(hook);
            return null;
        }).when(deferredReply).queue(any(java.util.function.Consumer.class));
        bot.onSlashCommandInteraction(event);
        verify(plugin).unlinkDiscordUser(eq("123"), any());
        clearInvocations(plugin);
        when(event.getChannel().getIdLong()).thenReturn(99L);
        bot.onSlashCommandInteraction(event);
        verify(event).reply("認証チャンネルで /unlink を実行してください。");
        when(event.isFromGuild()).thenReturn(false);
        bot.onSlashCommandInteraction(event);
        verifyNoInteractions(plugin);
    }

    @Test void unlinkRemovesWhitelistKicksAndReportsMissingLink() throws Exception {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        VerificationStore store = store();
        UUID owner = UUID.randomUUID();
        store.authenticateIfAvailable(owner, "Owner", "123", "owner");
        var field = MCAuthPlugin.class.getDeclaredField("store");
        field.setAccessible(true);
        field.set(plugin, store);
        // CALLS_REAL_METHODSではコンストラクターが実行されないため、一時コードのMapを設定します。
        for (String name : new String[]{"pendingCodes", "pendingCodesByUuid"}) {
            var map = MCAuthPlugin.class.getDeclaredField(name);
            map.setAccessible(true);
            map.set(plugin, new java.util.HashMap<>());
        }
        MessageChannel channel = mock(MessageChannel.class, RETURNS_DEEP_STUBS);
        OfflinePlayer offline = mock(OfflinePlayer.class);
        Player online = mock(Player.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getOfflinePlayer(owner)).thenReturn(offline);
            bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(online);
            when(offline.isWhitelisted()).thenReturn(true);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            plugin.unlinkDiscordUser("123", message -> channel.sendMessage(message).queue());
            assertFalse(store.isAuthenticated(owner));
            verify(offline).setWhitelisted(false);
            verify(online).kick(any(net.kyori.adventure.text.Component.class));
            verify(channel).sendMessage(startsWith("Minecraftとの連携を解除しました。"));
            plugin.unlinkDiscordUser("123", message -> channel.sendMessage(message).queue());
            verify(channel).sendMessage("連携済みのMinecraftアカウントはありません。");
        }
    }
}
