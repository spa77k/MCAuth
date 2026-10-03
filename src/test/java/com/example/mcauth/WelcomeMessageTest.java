package com.example.mcauth;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WelcomeMessageTest {
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target instanceof MCAuthPlugin
                ? MCAuthPlugin.class.getDeclaredField(name)
                : DiscordVerificationBot.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @ParameterizedTest
    @EnumSource(VerificationStore.AuthenticationResult.class)
    void welcomesOnlyAfterSuccessfulSave(VerificationStore.AuthenticationResult result) throws Exception {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        var store = mock(VerificationStore.class);
        var bot = mock(DiscordVerificationBot.class);
        UUID uuid = UUID.randomUUID();
        var pending = new HashMap<String, PendingVerification>();
        pending.put("1234", new PendingVerification(uuid, "Player", Instant.now().plusSeconds(300)));
        set(plugin, "store", store);
        set(plugin, "discordBot", bot);
        set(plugin, "pendingCodes", pending);
        set(plugin, "pendingCodesByUuid", new HashMap<UUID, String>());
        set(plugin, "verifiedMessage", "認証完了");
        when(store.authenticateIfAvailable(uuid, "Player", "123", "user")).thenReturn(result);
        var scheduler = mock(BukkitScheduler.class);
        doAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        List<String> replies = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertTrue(plugin.verifyCode("1234", "123", "user", replies::add));
            assertFalse(plugin.verifyCode("1234", "123", "user", replies::add));
        }
        if (result == VerificationStore.AuthenticationResult.SAVED) {
            var order = inOrder(store, bot);
            order.verify(store).authenticateIfAvailable(uuid, "Player", "123", "user");
            order.verify(bot).grantVerifiedRole("123");
            order.verify(bot).sendWelcome("123");
            assertEquals(List.of("認証完了"), replies);
        } else {
            verify(bot, never()).sendWelcome(anyString());
            assertEquals(1, replies.size());
        }
    }

    @Test void doesNotWelcomeWhenDatabaseSaveFails() throws Exception {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        var store = mock(VerificationStore.class);
        var bot = mock(DiscordVerificationBot.class);
        UUID uuid = UUID.randomUUID();
        var pending = new HashMap<String, PendingVerification>();
        pending.put("1234", new PendingVerification(uuid, "Player", Instant.now().plusSeconds(300)));
        set(plugin, "store", store);
        set(plugin, "discordBot", bot);
        set(plugin, "pendingCodes", pending);
        set(plugin, "pendingCodesByUuid", new HashMap<UUID, String>());
        when(store.authenticateIfAvailable(uuid, "Player", "123", "user"))
                .thenThrow(new IllegalStateException("DB unavailable"));
        var scheduler = mock(BukkitScheduler.class);
        doAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        List<String> replies = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            plugin.verifyCode("1234", "123", "user", replies::add);
        }
        verify(bot, never()).sendWelcome(anyString());
        assertTrue(replies.getFirst().contains("保存できませんでした"));
    }

    @Test void postsConfiguredTextToConfiguredChannelAndMentionsOnlyRecipient() throws Exception {
        var fixture = new Fixture();
        fixture.config.set("discord.welcome-channel-id", "99");
        fixture.config.set("messages.welcome", "{mention} さん、参加ありがとうございます！");
        var action = mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class, RETURNS_SELF);
        when(fixture.destination.sendMessage("<@123> さん、参加ありがとうございます！")).thenReturn(action);
        fixture.bot.sendWelcome("123");
        verify(fixture.destination).sendMessage("<@123> さん、参加ありがとうございます！");
        verify(action).setAllowedMentions(List.of());
        verify(action).mentionUsers("123");
    }

    @Test void defaultsToRequestedChannelAndOmitsMinecraftName() throws Exception {
        var fixture = new Fixture();
        fixture.bot.sendWelcome("123");
        verify(fixture.jda).getGuildChannelById(1449580988597403651L);
        verify(fixture.destination).sendMessage("<@123> さん、ようこそ！");
    }

    @Test void skipsDisabledAndOtherGuildDestinationsAndHandlesSendFailure() throws Exception {
        var fixture = new Fixture();
        fixture.config.set("discord.welcome-channel-id", "");
        fixture.bot.sendWelcome("123");
        verify(fixture.destination, never()).sendMessage(anyString());
        fixture.config.set("discord.welcome-channel-id", "99");
        fixture.config.set("messages.welcome", "");
        fixture.bot.sendWelcome("123");
        verify(fixture.destination, never()).sendMessage(anyString());
        fixture.config.set("messages.welcome", "{mention} ようこそ！");
        when(fixture.destination.getGuild().getIdLong()).thenReturn(2L);
        fixture.bot.sendWelcome("123");
        verify(fixture.destination, never()).sendMessage(anyString());
        when(fixture.destination.getGuild().getIdLong()).thenReturn(1L);
        when(fixture.destination.sendMessage(anyString())).thenThrow(new IllegalStateException("missing access"));
        assertDoesNotThrow(() -> fixture.bot.sendWelcome("123"));
    }

    private static class Fixture {
        final YamlConfiguration config = new YamlConfiguration();
        final JDA jda = mock(JDA.class);
        final TextChannel destination = mock(TextChannel.class, RETURNS_DEEP_STUBS);
        final DiscordVerificationBot bot;

        Fixture() throws Exception {
            MCAuthPlugin plugin = mock(MCAuthPlugin.class);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            bot = new DiscordVerificationBot(plugin, 42L, 4, 5,
                    Duration.ofSeconds(60), "invalid", "locked", "panel", 0L);
            set(bot, "jda", jda);
            TextChannel auth = mock(TextChannel.class, RETURNS_DEEP_STUBS);
            when(auth.getGuild().getIdLong()).thenReturn(1L);
            when(destination.getGuild().getIdLong()).thenReturn(1L);
            when(jda.getGuildChannelById(42L)).thenReturn(auth);
            when(jda.getGuildChannelById(99L)).thenReturn(destination);
            when(jda.getGuildChannelById(1449580988597403651L)).thenReturn(destination);
        }
    }
}
