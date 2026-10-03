package com.example.mcauth;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.regex.Pattern;

// Discord 側の処理を担当するクラスです。
// 認証チャンネルに「認証コードを入力」ボタン付きの案内を置き、
// ボタンから開く入力フォームで受け取った認証コードを Minecraft 側のプラグイン本体へ渡します。
final class DiscordVerificationBot extends ListenerAdapter {
    // ボタン・入力フォーム・入力欄を見分けるためのIDです。
    static final String VERIFY_BUTTON_ID = "mcauth:verify";
    static final String CODE_MODAL_ID = "mcauth:code-modal";
    static final String CODE_INPUT_ID = "code";

    // Minecraft 側の処理を呼び出すために保持します。
    private final MCAuthPlugin plugin;

    // 認証を受け付けるDiscordチャンネルIDです。
    private final long channelId;

    // 認証コードの桁数です。入力欄の文字数制限にも使います。
    private final int codeLength;

    // 認証コードとして扱う文字列パターンです。例: 6桁なら \d{6}
    private final Pattern codePattern;

    // 何回コードを間違えたら一時ロックするかです。
    private final int maxInvalidAttempts;

    // 一時ロックの長さです。
    private final Duration lockoutDuration;

    // コードが間違っていたときに本人へ表示する文です。
    private final String invalidCodeMessage;

    // 一時ロック中に本人へ表示する文です。
    private final String rateLimitedMessage;

    // 認証チャンネルに置く案内文です。
    private final String panelMessage;

    // 認証成功時に付けるロールのIDです。0なら付けません。
    private final long verifiedRoleId;

    // key: DiscordユーザーID, value: 失敗回数とロック期限。
    // Bot再起動で消える一時データです。
    private final Map<Long, FailedAttemptState> failedAttempts = new ConcurrentHashMap<>();

    // JDA の本体です。Discordとの接続を管理します。
    private JDA jda;

    DiscordVerificationBot(
            MCAuthPlugin plugin,
            long channelId,
            int codeLength,
            int maxInvalidAttempts,
            Duration lockoutDuration,
            String invalidCodeMessage,
            String rateLimitedMessage,
            String panelMessage,
            long verifiedRoleId
    ) {
        // コンストラクタでは、MCAuthPlugin から受け取った設定をフィールドに保存します。
        this.plugin = plugin;
        this.channelId = channelId;
        this.codeLength = codeLength;
        this.codePattern = Pattern.compile("\\d{" + codeLength + "}");
        this.maxInvalidAttempts = maxInvalidAttempts;
        this.lockoutDuration = lockoutDuration;
        this.invalidCodeMessage = invalidCodeMessage;
        this.rateLimitedMessage = rateLimitedMessage;
        this.panelMessage = panelMessage;
        this.verifiedRoleId = verifiedRoleId;
    }

    void start(String token) {
        try {
            // Token を使って Discord Bot としてログインします。
            jda = JDABuilder.createDefault(token)
                    // GUILD_MEMBERS は退出検知に使います。Privileged Intent のため、Developer Portal での有効化も必要です。
                    .enableIntents(GatewayIntent.GUILD_MEMBERS)
                    // このクラスのイベント処理メソッドが呼ばれるように登録します。
                    .addEventListeners(this)
                    .build();
        } catch (RuntimeException exception) {
            // Token が間違っている、ネットワークに繋がらない等の場合はここに来ます。
            plugin.getLogger().log(Level.SEVERE, "Failed to start Discord bot", exception);
            throw exception;
        }
    }

    void stop() {
        // プラグイン停止時に Discord との接続を閉じます。
        JDA currentJda = jda;
        jda = null;
        if (currentJda != null) {
            currentJda.shutdownNow();
        }
        failedAttempts.clear();
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        GuildChannel channel = event.getJDA().getGuildChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("認証チャンネルが見つからないため /unlink と認証ボタンを設置できません。");
            plugin.discordMembershipCheckFailed();
            return;
        }
        plugin.reconcileDiscordMembership(id -> retrieveMembership(channel.getGuild(), id));
        channel.getGuild().upsertCommand("unlink", "自分のMinecraft連携を解除し、接続中なら切断します")
                .queue(command -> {}, error -> plugin.getLogger().log(Level.SEVERE, "Failed to register /unlink", error));
        if (channel instanceof GuildMessageChannel messageChannel) {
            postPanel(messageChannel);
        } else {
            plugin.getLogger().warning("認証チャンネルにメッセージを送れないため、認証ボタンを設置できません。");
        }
    }

    static CompletableFuture<Boolean> retrieveMembership(Guild guild, String discordUserId) {
        return guild.retrieveMemberById(discordUserId).useCache(false).submit()
                .handle((member, error) -> {
                    if (error == null) {
                        return true;
                    }
                    Throwable cause = error;
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof ErrorResponseException response
                            && (response.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER
                            || response.getErrorResponse() == ErrorResponse.UNKNOWN_USER)) {
                        return false;
                    }
                    throw new CompletionException(cause);
                });
    }

    private void postPanel(GuildMessageChannel channel) {
        Button button = Button.primary(VERIFY_BUTTON_ID, "認証コードを入力");
        long selfId = channel.getJDA().getSelfUser().getIdLong();
        // 再起動のたびに案内が増えないよう、以前Botが置いた案内があれば書き換えます。
        channel.getHistory().retrievePast(50).queue(messages -> {
            Message existing = messages.stream()
                    .filter(message -> message.getAuthor().getIdLong() == selfId)
                    .filter(message -> message.getButtons().stream()
                            .anyMatch(b -> VERIFY_BUTTON_ID.equals(b.getId())))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                existing.editMessage(panelMessage).setActionRow(button).queue(
                        ok -> {}, error -> plugin.getLogger().log(Level.SEVERE, "Failed to update verify panel", error));
            } else {
                channel.sendMessage(panelMessage).setActionRow(button).queue(
                        ok -> {}, error -> plugin.getLogger().log(Level.SEVERE, "Failed to post verify panel", error));
            }
        }, error -> plugin.getLogger().log(Level.SEVERE, "Failed to read verify channel history", error));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (!VERIFY_BUTTON_ID.equals(event.getComponentId())) {
            return;
        }
        if (!event.isFromGuild() || event.getChannel().getIdLong() != channelId) {
            event.reply("認証チャンネルのボタンを使ってください。").setEphemeral(true).queue();
            return;
        }
        // ロック中ならフォームを開かず、待つように本人へ伝えます。
        if (isLockedOut(event.getUser().getIdLong(), Instant.now())) {
            event.reply(rateLimitedText()).setEphemeral(true).queue();
            return;
        }
        // 区切りの空白やハイフン込みで貼り付けても入るよう、文字数の上限は桁数の倍にしています。
        TextInput input = TextInput.create(CODE_INPUT_ID, "認証コード", TextInputStyle.SHORT)
                .setPlaceholder("Minecraftの画面に表示されたコード")
                .setRequiredRange(codeLength, codeLength * 2)
                .build();
        event.replyModal(Modal.create(CODE_MODAL_ID, "Minecraft認証")
                .addComponents(ActionRow.of(input))
                .build()).queue();
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!CODE_MODAL_ID.equals(event.getModalId())) {
            return;
        }
        if (!event.isFromGuild() || event.getChannel().getIdLong() != channelId) {
            event.reply("認証チャンネルのボタンを使ってください。").setEphemeral(true).queue();
            return;
        }

        // 失敗回数制限は Discord ユーザーID単位で行います。
        long discordUserId = event.getUser().getIdLong();
        Instant now = Instant.now();
        if (isLockedOut(discordUserId, now)) {
            event.reply(rateLimitedText()).setEphemeral(true).queue();
            return;
        }

        // 全角数字・空白・ハイフンを取り除いて、コードの形にそろえます。
        // キック画面で「123 456」のように区切って表示したコードを、そのまま貼り付けても通るようにするためです。
        ModalMapping value = event.getValue(CODE_INPUT_ID);
        String code = value == null ? "" : CodeFormat.normalize(value.getAsString());
        if (!codePattern.matcher(code).matches()) {
            recordFailedAttempt(discordUserId, now);
            event.reply(invalidCodeText()).setEphemeral(true).queue();
            return;
        }

        // 結果は本人だけに表示します。
        event.deferReply(true).queue(hook -> {
            boolean accepted = plugin.verifyCode(
                    code,
                    event.getUser().getId(),
                    event.getUser().getName(),
                    message -> hook.editOriginal(message).queue()
            );
            if (accepted) {
                failedAttempts.remove(discordUserId);
                return;
            }
            recordFailedAttempt(discordUserId, now);
            hook.editOriginal(invalidCodeText()).queue();
        });
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("unlink")) {
            return;
        }
        // 認証後は認証チャンネルが見えなくなるため、チャンネルは制限しません。
        // 対象はコマンド実行者本人のみ。処理結果は本人だけに表示します。
        event.deferReply(true).queue(hook -> plugin.unlinkDiscordUser(event.getUser().getId(),
                message -> hook.editOriginal(message).queue()));
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        // 認証チャンネルのあるDiscordサーバーを退出したユーザーだけ、認証を取り消します。
        // Botが入っている別のサーバーからの退出では取り消しません。
        GuildChannel channel = event.getJDA().getGuildChannelById(channelId);
        if (channel == null || channel.getGuild().getIdLong() != event.getGuild().getIdLong()) {
            return;
        }
        plugin.revokeByDiscordUserId(event.getUser().getId());
    }

    void grantVerifiedRole(String discordUserId) {
        Role role = verifiedRole();
        if (role == null) {
            return;
        }
        role.getGuild().addRoleToMember(UserSnowflake.fromId(discordUserId), role).queue(
                ok -> {}, error -> plugin.getLogger().log(Level.SEVERE, "Failed to add verified role", error));
    }

    void removeVerifiedRole(String discordUserId) {
        // 連携解除時に、認証で付けたロールを外します。
        Role role = verifiedRole();
        if (role == null) {
            return;
        }
        role.getGuild().removeRoleFromMember(UserSnowflake.fromId(discordUserId), role).queue(
                ok -> {}, error -> plugin.getLogger().log(Level.SEVERE, "Failed to remove verified role", error));
    }

    private Role verifiedRole() {
        // ロールが設定されていなければ null を返します。
        JDA currentJda = jda;
        if (verifiedRoleId == 0 || currentJda == null) {
            return null;
        }
        GuildChannel channel = currentJda.getGuildChannelById(channelId);
        if (channel == null) {
            return null;
        }
        Guild guild = channel.getGuild();
        Role role = guild.getRoleById(verifiedRoleId);
        if (role == null) {
            plugin.getLogger().warning("認証後に付けるロールが見つかりません: " + verifiedRoleId);
        }
        return role;
    }

    private String invalidCodeText() {
        return invalidCodeMessage.isBlank() ? "認証コードが無効、または期限切れです。" : invalidCodeMessage;
    }

    private String rateLimitedText() {
        return rateLimitedMessage.isBlank() ? "認証コードの間違いが多すぎます。しばらく待ってから再試行してください。" : rateLimitedMessage;
    }

    private boolean isLockedOut(long discordUserId, Instant now) {
        // そのDiscordユーザーの失敗状態を取り出します。
        FailedAttemptState state = failedAttempts.get(discordUserId);

        // 記録がない、またはロック期限を過ぎているならロックされていません。
        if (state == null) {
            return false;
        }

        if (!state.lockedUntil().isAfter(now)) {
            failedAttempts.remove(discordUserId, state);
            return false;
        }

        // 失敗回数が上限以上ならロック中です。
        return state.count() >= maxInvalidAttempts;
    }

    private void recordFailedAttempt(long discordUserId, Instant now) {
        // Discordユーザーごとに失敗回数を数え、コードの総当たりをしにくくします。
        failedAttempts.compute(discordUserId, (id, current) -> {
            // 初回失敗、または前回のロック期限が切れている場合は1回目から数え直します。
            if (current == null || !current.lockedUntil().isAfter(now)) {
                return new FailedAttemptState(1, now.plus(lockoutDuration));
            }

            // ロック期限内の追加失敗なら、回数を1つ増やして期限も延長します。
            return new FailedAttemptState(current.count() + 1, now.plus(lockoutDuration));
        });
    }

    // Discordユーザーごとの失敗状態を表す小さなデータ入れ物です。
    private record FailedAttemptState(int count, Instant lockedUntil) {
    }
}
