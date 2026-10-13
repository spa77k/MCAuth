package com.example.mcauth;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** 認証済みプレイヤー向けの Discord 市場画面。取引処理は各プラグインに委譲する。 */
final class MarketPanels extends ListenerAdapter {
    private static final int PAGE_SIZE = 5;
    private final MCAuthPlugin plugin;
    private final long authChannelId;
    private final long verifiedRoleId;
    private TextChannel auctionChannel;
    private TextChannel requestChannel;
    private Message auctionPanel;
    private Message requestPanel;

    MarketPanels(MCAuthPlugin plugin, long authChannelId, long verifiedRoleId) {
        this.plugin = plugin;
        this.authChannelId = authChannelId;
        this.verifiedRoleId = verifiedRoleId;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        var authChannel = event.getJDA().getGuildChannelById(authChannelId);
        if (authChannel == null) return;
        Guild guild = authChannel.getGuild();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var manager = plugin.getServer().getPluginManager();
            if (manager.isPluginEnabled("AuctionHouse")) {
                resolveChannel(guild, "discord.auction-channel-id", "オークション取引", true);
            }
            if (manager.isPluginEnabled("ContractBoard")) {
                resolveChannel(guild, "discord.request-channel-id", "依頼掲示板", false);
            }
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshPanels, 40L, 1200L);
        });
    }

    private void resolveChannel(Guild guild, String key, String name, boolean auction) {
        String configured = plugin.getConfig().getString(key, "").trim();
        TextChannel channel = configured.isBlank() ? null : guild.getTextChannelById(configured);
        if (!configured.isBlank() && channel == null) {
            plugin.getLogger().warning("市場チャンネルが見つかりません: " + key);
            return;
        }
        if (channel == null) {
            List<TextChannel> existing = guild.getTextChannelsByName(name, true);
            if (!existing.isEmpty()) channel = existing.get(0);
        }
        if (channel != null) {
            useChannel(channel, auction);
            return;
        }
        ChannelAction<TextChannel> creation = guild.createTextChannel(name);
        if (verifiedRoleId != 0) {
            if (guild.getRoleById(verifiedRoleId) == null) {
                plugin.getLogger().warning("認証ロールが見つからないため、市場チャンネルを作成しません: " + name);
                return;
            }
            creation.addRolePermissionOverride(guild.getPublicRole().getIdLong(), 0L,
                    Permission.VIEW_CHANNEL.getRawValue());
            creation.addRolePermissionOverride(verifiedRoleId, Permission.VIEW_CHANNEL.getRawValue(), 0L);
            creation.addMemberPermissionOverride(guild.getSelfMember().getIdLong(),
                    Permission.getRaw(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY), 0L);
        }
        creation.queue(created -> useChannel(created, auction),
                error -> plugin.getLogger().log(Level.WARNING, name + " チャンネルを作成できませんでした", error));
    }

    private void useChannel(TextChannel channel, boolean auction) {
        channel.getHistory().retrievePast(50).queue(history -> {
            Message found = history.stream()
                    .filter(message -> message.getAuthor().getIdLong() == channel.getJDA().getSelfUser().getIdLong())
                    .filter(message -> message.getButtons().stream().anyMatch(button ->
                            (auction ? "market:page:auction:0" : "market:page:request:0").equals(button.getId())))
                    .findFirst().orElse(null);
            if (auction) {
                auctionPanel = found;
                auctionChannel = channel;
            } else {
                requestPanel = found;
                requestChannel = channel;
            }
            refresh(auction);
        }, error -> plugin.getLogger().log(Level.WARNING, "市場チャンネルの履歴を読めませんでした", error));
    }

    private void refreshPanels() {
        refresh(true);
        refresh(false);
    }

    private void refresh(boolean auction) {
        TextChannel channel = auction ? auctionChannel : requestChannel;
        if (channel == null) return;
        plugin.marketPublicList(auction ? "listings" : "requests", value -> {
            List<Map<String, String>> records = records(value);
            StringBuilder description = new StringBuilder(auction
                    ? "**出品中のオークション**\n手持ち品を先に `/ah deposit` で預けると、ここから出品できます。\n"
                    : "**募集中の依頼**\n受諾した依頼の納品はゲーム内で行ってください。\n");
            if (records.isEmpty()) description.append("現在、募集中のものはありません。\n");
            for (int i = 0; i < Math.min(PAGE_SIZE, records.size()); i++) {
                description.append(line(records.get(i), auction)).append('\n');
            }
            MessageEmbed embed = new EmbedBuilder().setDescription(description.toString())
                    .setColor(0x5865F2).build();
            List<ActionRow> rows = new ArrayList<>();
            List<Button> buttons = new ArrayList<>();
            for (int i = 0; i < Math.min(PAGE_SIZE, records.size()); i++) {
                String id = records.get(i).get("id");
                buttons.add(Button.primary("market:" + (auction ? "bid" : "accept") + ":" + id,
                        (auction ? "入札 " : "受諾 ") + id));
            }
            if (!buttons.isEmpty()) rows.add(ActionRow.of(buttons));
            rows.add(ActionRow.of(auction
                    ? List.of(Button.secondary("market:page:auction:0", "全件を見る"),
                            Button.success("market:staged", "預かり品から出品"))
                    : List.of(Button.secondary("market:page:request:0", "全件を見る"))));
            Message previous = auction ? auctionPanel : requestPanel;
            if (previous == null) {
                channel.sendMessageEmbeds(embed).setComponents(rows).queue(sent -> {
                    if (auction) auctionPanel = sent;
                    else requestPanel = sent;
                }, error -> plugin.getLogger().log(Level.WARNING, "市場一覧を投稿できませんでした", error));
            } else {
                previous.editMessageEmbeds(embed).setComponents(rows).queue(
                        edited -> {}, error -> {
                            if (error instanceof ErrorResponseException response
                                    && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                                if (auction) auctionPanel = null;
                                else requestPanel = null;
                            }
                            plugin.getLogger().log(Level.WARNING, "市場一覧を更新できませんでした", error);
                        });
            }
        });
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        if (parts.length < 2 || !parts[0].equals("market")) return;
        if (!sameGuild(event.getGuild())) {
            event.replyEmbeds(DiscordVerificationBot.errorEmbed("このサーバーの市場画面を使ってください。"))
                    .setEphemeral(true).queue();
            return;
        }
        try {
            switch (parts[1]) {
                case "page" -> {
                    if (parts.length != 4) throw new IllegalArgumentException();
                    int page = Integer.parseInt(parts[3]);
                    if (page < 0) throw new IllegalArgumentException();
                    event.deferReply(true).queue(hook -> plugin.marketCall(event.getUser().getId(),
                            parts[2].equals("auction") ? "listings" : "requests", new int[0],
                            value -> showPage(hook, value, parts[2].equals("auction"), page)));
                }
                case "staged" -> event.deferReply(true).queue(hook ->
                        plugin.marketCall(event.getUser().getId(), "staged", new int[0],
                                value -> showStaged(hook, value)));
                case "bid" -> {
                    int id = Integer.parseInt(parts[2]);
                    TextInput amount = TextInput.create("amount", "入札額", TextInputStyle.SHORT)
                            .setRequiredRange(1, 10).build();
                    event.replyModal(Modal.create("market:bid:" + id, "入札額を入力")
                            .addComponents(ActionRow.of(amount)).build()).queue();
                }
                case "accept" -> {
                    int id = Integer.parseInt(parts[2]);
                    event.replyEmbeds(DiscordVerificationBot.embed("依頼番号 " + id + " を受諾しますか？"))
                            .addActionRow(Button.success("market:doaccept:" + id, "受諾を確定"))
                            .setEphemeral(true).queue();
                }
                case "doaccept" -> {
                    int id = Integer.parseInt(parts[2]);
                    event.deferReply(true).queue(hook -> plugin.marketCall(event.getUser().getId(),
                            "accept", new int[]{id}, value -> {
                                hook.editOriginalEmbeds(resultEmbed(value)).queue();
                                refresh(false);
                            }));
                }
                case "sell" -> {
                    int id = Integer.parseInt(parts[2]);
                    TextInput price = TextInput.create("price", "開始価格", TextInputStyle.SHORT)
                            .setRequiredRange(1, 10).build();
                    TextInput buyout = TextInput.create("buyout", "即決価格（不要なら0）", TextInputStyle.SHORT)
                            .setValue("0").setRequiredRange(1, 10).build();
                    TextInput hours = TextInput.create("hours", "期間（6・12・24・48時間）", TextInputStyle.SHORT)
                            .setValue("24").setRequiredRange(1, 2).build();
                    event.replyModal(Modal.create("market:sell:" + id, "預かり品を出品")
                            .addComponents(ActionRow.of(price), ActionRow.of(buyout), ActionRow.of(hours))
                            .build()).queue();
                }
                default -> event.replyEmbeds(DiscordVerificationBot.errorEmbed("操作を認識できませんでした。"))
                        .setEphemeral(true).queue();
            }
        } catch (RuntimeException error) {
            event.replyEmbeds(DiscordVerificationBot.errorEmbed("操作を認識できませんでした。"))
                    .setEphemeral(true).queue();
        }
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String[] parts = event.getModalId().split(":");
        if (parts.length != 3 || !parts[0].equals("market")) return;
        if (!sameGuild(event.getGuild())) {
            event.replyEmbeds(DiscordVerificationBot.errorEmbed("このサーバーの市場画面を使ってください。"))
                    .setEphemeral(true).queue();
            return;
        }
        try {
            int id = Integer.parseInt(parts[2]);
            String operation = parts[1];
            int[] numbers = operation.equals("bid")
                    ? new int[]{id, Integer.parseInt(value(event, "amount"))}
                    : new int[]{id, Integer.parseInt(value(event, "price")),
                            Integer.parseInt(value(event, "buyout")), Integer.parseInt(value(event, "hours"))};
            event.deferReply(true).queue(hook -> plugin.marketCall(event.getUser().getId(), operation,
                    numbers, result -> {
                        hook.editOriginalEmbeds(resultEmbed(result)).queue();
                        refresh(true);
                    }));
        } catch (RuntimeException error) {
            event.replyEmbeds(DiscordVerificationBot.errorEmbed("価格と期間は整数で入力してください。"))
                    .setEphemeral(true).queue();
        }
    }

    private boolean sameGuild(Guild guild) {
        var authChannel = auctionChannel != null ? auctionChannel.getJDA().getGuildChannelById(authChannelId)
                : requestChannel != null ? requestChannel.getJDA().getGuildChannelById(authChannelId) : null;
        return guild != null && authChannel != null && guild.getIdLong() == authChannel.getGuild().getIdLong();
    }

    private static String value(ModalInteractionEvent event, String key) {
        ModalMapping mapping = event.getValue(key);
        return mapping == null ? "" : mapping.getAsString().trim();
    }

    private static MessageEmbed resultEmbed(Object value) {
        String message = String.valueOf(value);
        boolean success = message.startsWith("入札しました") || message.startsWith("即決価格で落札")
                || message.startsWith("出品しました")
                || (message.startsWith("依頼「") && message.endsWith("」を受諾しました。"));
        return success ? DiscordVerificationBot.embed(message) : DiscordVerificationBot.errorEmbed(message);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> records(Object value) {
        return value instanceof List<?> list ? (List<Map<String, String>>) list : List.of();
    }

    private static String line(Map<String, String> record, boolean auction) {
        String deadline = "<t:" + (Long.parseLong(record.get("expires")) / 1000L) + ":R>";
        return auction
                ? "`#" + record.get("id") + "` " + record.get("item") + " ×" + record.get("amount")
                        + "／最低入札 " + record.get("minBid") + "S"
                        + ("0".equals(record.get("buyout")) ? "" : "／即決 " + record.get("buyout") + "S")
                        + "／終了 " + deadline
                : "`#" + record.get("id") + "` " + record.get("title") + "／報酬 "
                        + record.get("reward") + "S／終了 " + deadline
                        + ("0".equals(record.get("minStars")) ? "" : "／必要評価 ★" + record.get("minStars"))
                        + ("true".equals(record.get("itemDelivery")) ? "／アイテム納品あり" : "")
                        + "\n　" + record.get("description");
    }

    private void showPage(net.dv8tion.jda.api.interactions.InteractionHook hook,
                          Object value, boolean auction, int requestedPage) {
        if (!(value instanceof List<?>)) {
            hook.editOriginalEmbeds(DiscordVerificationBot.errorEmbed(String.valueOf(value))).queue();
            return;
        }
        List<Map<String, String>> records = records(value);
        int page = Math.min(requestedPage, Math.max(0, (records.size() - 1) / PAGE_SIZE));
        int start = page * PAGE_SIZE;
        StringBuilder description = new StringBuilder(auction ? "**オークション一覧**\n" : "**依頼一覧**\n");
        List<Button> actions = new ArrayList<>();
        for (int i = start; i < Math.min(start + PAGE_SIZE, records.size()); i++) {
            Map<String, String> record = records.get(i);
            description.append(line(record, auction)).append('\n');
            actions.add(Button.primary("market:" + (auction ? "bid" : "accept") + ":" + record.get("id"),
                    (auction ? "入札 " : "受諾 ") + record.get("id")));
        }
        if (records.isEmpty()) description.append("現在、募集中のものはありません。\n");
        List<ActionRow> rows = new ArrayList<>();
        if (!actions.isEmpty()) rows.add(ActionRow.of(actions));
        List<Button> navigation = new ArrayList<>();
        if (page > 0) navigation.add(Button.secondary("market:page:" + (auction ? "auction" : "request")
                + ":" + (page - 1), "前へ"));
        if (start + PAGE_SIZE < records.size()) navigation.add(Button.secondary(
                "market:page:" + (auction ? "auction" : "request") + ":" + (page + 1), "次へ"));
        if (!navigation.isEmpty()) rows.add(ActionRow.of(navigation));
        hook.editOriginalEmbeds(DiscordVerificationBot.embed(description.toString())).setComponents(rows).queue();
    }

    private void showStaged(net.dv8tion.jda.api.interactions.InteractionHook hook, Object value) {
        if (!(value instanceof List<?>)) {
            hook.editOriginalEmbeds(DiscordVerificationBot.errorEmbed(String.valueOf(value))).queue();
            return;
        }
        List<Map<String, String>> staged = records(value);
        StringBuilder description = new StringBuilder("**出品用の預かり品**\n");
        if (staged.isEmpty()) description.append("ゲーム内で品物を手に持ち、`/ah deposit` で預けてください。\n");
        List<ActionRow> rows = new ArrayList<>();
        for (Map<String, String> item : staged.stream().limit(5).toList()) {
            description.append("`#").append(item.get("id")).append("` ").append(item.get("item"))
                    .append(" ×").append(item.get("amount")).append('\n');
            rows.add(ActionRow.of(Button.success("market:sell:" + item.get("id"),
                    "出品 " + item.get("id"))));
        }
        hook.editOriginalEmbeds(DiscordVerificationBot.embed(description.toString())).setComponents(rows).queue();
    }
}
