package com.example.mcauth;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ButtonVerificationTest {
    private static MessageEmbed embedWith(String description) {
        return argThat(embed -> embed != null && description.equals(embed.getDescription()));
    }

    private DiscordVerificationBot bot(MCAuthPlugin plugin, int maxInvalidAttempts) {
        return new DiscordVerificationBot(plugin, 42L, 6, maxInvalidAttempts,
                Duration.ofSeconds(60), "invalid", "locked", "panel", 0L);
    }

    private ButtonInteractionEvent button(long channelId) {
        var event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getComponentId()).thenReturn(DiscordVerificationBot.VERIFY_BUTTON_ID);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getChannel().getIdLong()).thenReturn(channelId);
        when(event.getUser().getIdLong()).thenReturn(123L);
        return event;
    }

    private ModalInteractionEvent modal(String input, InteractionHook hook) {
        var event = mock(ModalInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getModalId()).thenReturn(DiscordVerificationBot.CODE_MODAL_ID);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getChannel().getIdLong()).thenReturn(42L);
        when(event.getUser().getIdLong()).thenReturn(123L);
        when(event.getUser().getId()).thenReturn("123");
        when(event.getUser().getName()).thenReturn("user");
        ModalMapping mapping = mock(ModalMapping.class);
        when(mapping.getAsString()).thenReturn(input);
        when(event.getValue(DiscordVerificationBot.CODE_INPUT_ID)).thenReturn(mapping);
        var deferred = event.deferReply(true);
        doAnswer(call -> {
            call.getArgument(0, Consumer.class).accept(hook);
            return null;
        }).when(deferred).queue(any(Consumer.class));
        return event;
    }

    @Test void postsPanelWithVerifyButtonWhenNoneExists() {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class);
        var event = mock(net.dv8tion.jda.api.events.session.ReadyEvent.class, RETURNS_DEEP_STUBS);
        var channel = mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class, RETURNS_DEEP_STUBS);
        when(event.getJDA().getGuildChannelById(42L)).thenReturn(channel);
        var history = channel.getHistory().retrievePast(50);
        doAnswer(call -> {
            call.getArgument(0, Consumer.class).accept(java.util.List.of());
            return null;
        }).when(history).queue(any(Consumer.class), any(Consumer.class));
        bot(plugin, 5).onReady(event);
        verify(channel).sendMessageEmbeds(embedWith("panel"));
        verify(channel.sendMessageEmbeds(any(MessageEmbed.class))).setActionRow(
                argThat((net.dv8tion.jda.api.interactions.components.ItemComponent c) ->
                        c instanceof net.dv8tion.jda.api.interactions.components.buttons.Button b
                                && DiscordVerificationBot.VERIFY_BUTTON_ID.equals(b.getId())));
    }

    private net.dv8tion.jda.api.entities.channel.concrete.TextChannel readyWithExistingPanel(
            MCAuthPlugin plugin, String description) {
        var event = mock(net.dv8tion.jda.api.events.session.ReadyEvent.class, RETURNS_DEEP_STUBS);
        var channel = mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class, RETURNS_DEEP_STUBS);
        when(event.getJDA().getGuildChannelById(42L)).thenReturn(channel);
        when(channel.getJDA().getSelfUser().getIdLong()).thenReturn(7L);
        var existing = mock(net.dv8tion.jda.api.entities.Message.class, RETURNS_DEEP_STUBS);
        when(existing.getAuthor().getIdLong()).thenReturn(7L);
        var old = mock(net.dv8tion.jda.api.interactions.components.buttons.Button.class);
        when(old.getId()).thenReturn(DiscordVerificationBot.VERIFY_BUTTON_ID);
        when(existing.getButtons()).thenReturn(java.util.List.of(old));
        when(existing.getContentRaw()).thenReturn("");
        when(existing.getEmbeds()).thenReturn(java.util.List.of(DiscordVerificationBot.embed(description)));
        var history = channel.getHistory().retrievePast(50);
        doAnswer(call -> {
            call.getArgument(0, Consumer.class).accept(java.util.List.of(existing));
            return null;
        }).when(history).queue(any(Consumer.class), any(Consumer.class));
        var deletion = existing.delete();
        doAnswer(call -> {
            call.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(deletion).queue(any(Consumer.class), any(Consumer.class));
        bot(plugin, 5).onReady(event);
        verify(existing, never()).editMessageEmbeds(any(MessageEmbed.class));
        if (description.equals("panel")) {
            verify(deletion, never()).queue(any(Consumer.class), any(Consumer.class));
        } else {
            verify(deletion).queue(any(Consumer.class), any(Consumer.class));
        }
        return channel;
    }

    @Test void keepsUnchangedPanelWithoutEditing() {
        var channel = readyWithExistingPanel(mock(MCAuthPlugin.class), "panel");
        verify(channel, never()).sendMessageEmbeds(any(MessageEmbed.class));
    }

    @Test void replacesChangedPanelByDeletingAndPostingInsteadOfEditing() {
        var channel = readyWithExistingPanel(mock(MCAuthPlugin.class), "old panel");
        verify(channel).sendMessageEmbeds(embedWith("panel"));
    }

    @Test void buttonOpensCodeFormOnlyInVerifyChannel() {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class);
        DiscordVerificationBot bot = bot(plugin, 5);
        var event = button(42L);
        bot.onButtonInteraction(event);
        verify(event).replyModal(argThat((Modal m) -> m.getId().equals(DiscordVerificationBot.CODE_MODAL_ID)));

        var other = button(99L);
        bot.onButtonInteraction(other);
        verify(other, never()).replyModal(any());
        verify(other).replyEmbeds(embedWith("認証チャンネルのボタンを使ってください。"));
    }

    @Test void formPassesNormalizedCodeAndReportsOnlyToSender() {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class);
        when(plugin.verifyCode(eq("123456"), eq("123"), eq("user"), any())).thenReturn(true);
        var hook = mock(InteractionHook.class, RETURNS_DEEP_STUBS);
        bot(plugin, 5).onModalInteraction(modal("１２３ ４５６", hook));
        verify(plugin).verifyCode(eq("123456"), eq("123"), eq("user"), any());
        verify(hook, never()).editOriginalEmbeds(embedWith("invalid"));
    }

    @Test void wrongCodesLockTheFormButton() {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class);
        when(plugin.verifyCode(anyString(), anyString(), anyString(), any())).thenReturn(false);
        DiscordVerificationBot bot = bot(plugin, 2);
        var hook = mock(InteractionHook.class, RETURNS_DEEP_STUBS);
        bot.onModalInteraction(modal("000000", hook));
        bot.onModalInteraction(modal("abc", hook));
        verify(hook).editOriginalEmbeds(embedWith("invalid"));

        var event = button(42L);
        bot.onButtonInteraction(event);
        verify(event, never()).replyModal(any());
        verify(event).replyEmbeds(embedWith("locked"));
    }
}
