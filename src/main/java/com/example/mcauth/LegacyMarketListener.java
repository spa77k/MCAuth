package com.example.mcauth;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;

/**
 * 廃止した Discord の市場パネルの後始末だけを行うリスナーです。
 *
 * <p>Bot の起動時に、設定された市場チャンネルの直近50件から Bot 自身が投稿した
 * {@code market:} ボタン付きのメッセージを削除します。チャンネルの作成・削除はしません。
 * 残っていたボタンやフォームを押されたときは、取引窓口へは渡さず終了したことを本人だけに返します。</p>
 */
final class LegacyMarketListener extends ListenerAdapter {
    static final String ENDED_MESSAGE = "この画面は終了しました。Discordの /profile から操作してください。";
    private static final String COMPONENT_PREFIX = "market:";

    private final MCAuthPlugin plugin;
    private final long authChannelId;

    LegacyMarketListener(MCAuthPlugin plugin, long authChannelId) {
        this.plugin = plugin;
        this.authChannelId = authChannelId;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        var authChannel = event.getJDA().getGuildChannelById(authChannelId);
        if (authChannel == null) {
            return;
        }
        Guild guild = authChannel.getGuild();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                removeOldPanels(guild, "discord.auction-channel-id", "オークション取引");
                removeOldPanels(guild, "discord.request-channel-id", "依頼掲示板");
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "古い市場パネルの削除を予約できませんでした", exception);
        }
    }

    private void removeOldPanels(Guild guild, String configKey, String channelName) {
        String configured = plugin.getConfig().getString(configKey, "").trim();
        TextChannel channel = configured.isBlank() ? null : guild.getTextChannelById(configured);
        if (channel == null && configured.isBlank()) {
            // 設定が空なら、以前と同じ名前のチャンネルを探すだけにします。
            List<TextChannel> existing = guild.getTextChannelsByName(channelName, true);
            channel = existing.isEmpty() ? null : existing.get(0);
        }
        if (channel == null) {
            return;
        }
        TextChannel target = channel;
        target.getHistory().retrievePast(50).queue(messages -> {
            long selfId = target.getJDA().getSelfUser().getIdLong();
            for (Message message : messages) {
                if (message.getAuthor().getIdLong() != selfId) {
                    continue;
                }
                boolean oldPanel = message.getButtons().stream()
                        .anyMatch(button -> button.getId() != null && button.getId().startsWith(COMPONENT_PREFIX));
                if (!oldPanel) {
                    continue;
                }
                message.delete().queue(ok -> {}, error -> plugin.getLogger().log(
                        Level.WARNING, "古い市場パネルを削除できませんでした", error));
            }
        }, error -> plugin.getLogger().log(Level.WARNING, "市場チャンネルの履歴を読めませんでした: " + configKey, error));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (isMarketComponent(event.getComponentId())) {
            replyEnded(event);
        }
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if (isMarketComponent(event.getComponentId())) {
            replyEnded(event);
        }
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (isMarketComponent(event.getModalId())) {
            replyEnded(event);
        }
    }

    private static boolean isMarketComponent(String id) {
        return id != null && id.startsWith(COMPONENT_PREFIX);
    }

    private static void replyEnded(IReplyCallback interaction) {
        interaction.replyEmbeds(DiscordVerificationBot.errorEmbed(ENDED_MESSAGE)).setEphemeral(true).queue();
    }
}
