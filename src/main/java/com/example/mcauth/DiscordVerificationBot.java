package com.example.mcauth;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

// Discord 側の処理を担当するクラスです。
// 指定チャンネルに投稿された認証コードを読み、Minecraft 側のプラグイン本体へ渡します。
final class DiscordVerificationBot extends ListenerAdapter {
    // Minecraft 側の処理を呼び出すために保持します。
    private final MCAuthPlugin plugin;

    // 認証コードを受け付けるDiscordチャンネルIDです。
    private final long channelId;

    // 認証コードとして扱う文字列パターンです。例: 6桁なら \d{6}
    private final Pattern codePattern;

    // 何回コードを間違えたら一時ロックするかです。
    private final int maxInvalidAttempts;

    // 一時ロックの長さです。
    private final Duration lockoutDuration;

    // コードが間違っていたときにDiscordへ送る文です。
    private final String invalidCodeMessage;

    // 一時ロック中にDiscordへ送る文です。
    private final String rateLimitedMessage;

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
            String rateLimitedMessage
    ) {
        // コンストラクタでは、MCAuthPlugin から受け取った設定をフィールドに保存します。
        this.plugin = plugin;
        this.channelId = channelId;
        this.codePattern = Pattern.compile("\\d{" + codeLength + "}");
        this.maxInvalidAttempts = maxInvalidAttempts;
        this.lockoutDuration = lockoutDuration;
        this.invalidCodeMessage = invalidCodeMessage;
        this.rateLimitedMessage = rateLimitedMessage;
    }

    void start(String token) {
        try {
            // Token を使って Discord Bot としてログインします。
            jda = JDABuilder.createDefault(token)
                    // メッセージ本文を読むために必要な権限です。Discord Developer Portal 側でも有効化が必要です。
                    // GUILD_MEMBERS は Privileged Intent のため、Developer Portal での有効化も必要です。
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                    // このクラスの onMessageReceived が呼ばれるように登録します。
                    .addEventListeners(this)
                    .build();
        } catch (RuntimeException exception) {
            // Token が間違っている、ネットワークに繋がらない等の場合はここに来ます。
            plugin.getLogger().log(Level.SEVERE, "Failed to start Discord bot", exception);
        }
    }

    void stop() {
        // プラグイン停止時に Discord との接続を閉じます。
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        // Botの発言、または認証チャンネル以外の発言は無視します。
        if (event.getAuthor().isBot() || event.getChannel().getIdLong() != channelId) {
            return;
        }

        // Discordメッセージ本文を取得し、前後の空白を消します。
        String content = event.getMessage().getContentRaw().trim();

        // 認証コードの形式に合わない投稿は無視します。
        if (!codePattern.matcher(content).matches()) {
            return;
        }

        // 失敗回数制限は Discord ユーザーID単位で行います。
        long discordUserId = event.getAuthor().getIdLong();

        // ロック中なら認証処理に進まず、待つようにメッセージを返します。
        if (isLockedOut(discordUserId)) {
            if (!rateLimitedMessage.isBlank()) {
                event.getChannel().sendMessage(rateLimitedMessage).queue();
            }
            return;
        }

        // Minecraft 側のプラグイン本体に、コードとDiscordユーザー情報を渡して認証します。
        boolean accepted = plugin.verifyCode(
                content,
                event.getChannel(),
                event.getAuthor().getId(),
                event.getAuthor().getName()
        );

        // 認証成功なら、そのDiscordユーザーの失敗回数をリセットします。
        if (accepted) {
            failedAttempts.remove(discordUserId);
            return;
        }

        // 認証失敗なら失敗回数を増やします。
        recordFailedAttempt(discordUserId);

        // 無効なコードだったことをDiscordへ返します。
        if (!invalidCodeMessage.isBlank()) {
            event.getChannel().sendMessage(invalidCodeMessage).queue();
        }
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        // Discordサーバーを退出したユーザーの認証を取り消します。
        plugin.revokeByDiscordUserId(event.getUser().getId());
    }

    private boolean isLockedOut(long discordUserId) {
        // そのDiscordユーザーの失敗状態を取り出します。
        FailedAttemptState state = failedAttempts.get(discordUserId);

        // 記録がない、またはロック期限を過ぎているならロックされていません。
        if (state == null || state.lockedUntil().isBefore(Instant.now())) {
            return false;
        }

        // 失敗回数が上限以上ならロック中です。
        return state.count() >= maxInvalidAttempts;
    }

    private void recordFailedAttempt(long discordUserId) {
        // Discordユーザーごとに失敗回数を数え、コードの総当たりをしにくくします。
        failedAttempts.compute(discordUserId, (id, current) -> {
            Instant now = Instant.now();

            // 初回失敗、または前回のロック期限が切れている場合は1回目から数え直します。
            if (current == null || current.lockedUntil().isBefore(now)) {
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
