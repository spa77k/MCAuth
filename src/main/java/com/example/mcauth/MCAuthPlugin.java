package com.example.mcauth;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.function.Consumer;

// Paper が最初に読み込むプラグイン本体です。
// Minecraft 側の接続チェック、認証コード発行、認証済みプレイヤーのDB登録を担当します。
public final class MCAuthPlugin extends JavaPlugin implements Listener {
    // 認証コードは推測されにくい乱数で作ります。
    private static final SecureRandom RANDOM = new SecureRandom();

    // 設定ミスでコードが短すぎたり長すぎたりしないよう、許可する範囲を決めています。
    private static final int MIN_CODE_LENGTH = 4;
    private static final int MAX_CODE_LENGTH = 8;
    private static final String DISCORD_TOKEN_ENV = "MCAUTH_DISCORD_TOKEN";
    private static final String DISCORD_CHANNEL_ID_ENV = "MCAUTH_DISCORD_CHANNEL_ID";
    private static final String DISCORD_VERIFIED_ROLE_ID_ENV = "MCAUTH_DISCORD_VERIFIED_ROLE_ID";
    private static final String ALREADY_LINKED_DISCORD_MESSAGE =
            "このDiscordアカウントは、すでに別のMinecraftアカウントと連携済みです。切り替えるには、先に /unlink で解除してください。";
    private static final String LOCKDOWN_MESSAGE =
            "認証システムを起動できなかったため、現在は入場できません。管理者にお問い合わせください。";

    // key: 認証コード, value: そのコードで認証される予定のMinecraftプレイヤー情報。
    // Discord にコードが投稿されたとき、このMapから探します。
    private final Map<String, PendingVerification> pendingCodes = new HashMap<>();

    // key: Minecraft UUID, value: 現在発行中の認証コード。
    // 同じプレイヤーが何度も接続しても、コードが無限に増えないようにします。
    private final Map<UUID, String> pendingCodesByUuid = new HashMap<>();

    // 起動に失敗したときに true にし、サーバー再起動まで全員の入場を拒否します。
    private volatile boolean lockdown;

    // 認証済みプレイヤーを mcauth.db（SQLite）に保存・参照する担当です。
    private VerificationStore store;

    // Discord Bot を起動し、認証チャンネルのメッセージを監視する担当です。
    private DiscordVerificationBot discordBot;

    // 認証コードの有効期限です。config.yml の auth.code-expire-seconds から読みます。
    private Duration codeLifetime;

    // 認証コードの桁数です。デフォルトは4桁です。
    private int codeLength;

    // キック画面でコードを区切る桁数です。0なら区切りません。
    private int codeGroupSize;

    // RANDOM.nextInt(...) に渡す上限値です。6桁なら 1,000,000 になります。
    private int codeUpperBound;

    // 未認証プレイヤーをキックするときに表示する文章です。
    private String kickMessage;

    // Discordで認証成功時に送る文章です。
    private String verifiedMessage;

    @Override
    public void onEnable() {
        // config.yml がまだ存在しない場合、src/main/resources/config.yml をコピーして作ります。
        saveDefaultConfig();

        // このクラスのイベント処理メソッドを Paper に登録します。
        // 起動に失敗しても入場チェックが外れないよう、最初に登録します。
        Bukkit.getPluginManager().registerEvents(this, this);

        try {
            setUp();
        } catch (RuntimeException exception) {
            // プラグインを止めると誰でも入れてしまうため、止めずに全員の入場を拒否し続けます。
            getLogger().log(Level.SEVERE, "MCAuth failed to start. All players will be denied until the server restarts.", exception);
            enterLockdown();
        }
    }

    private void setUp() {
        // 認証データのDB（mcauth.db）を開きます。開けないと入場判定ができないため、例外にして全員拒否にします。
        store = new VerificationStore(this);
        store.load();

        // 設定値を読み込みます。危険な値にならないよう、最低値や範囲を補正しています。
        codeLifetime = Duration.ofSeconds(Math.max(1, getConfig().getLong("auth.code-expire-seconds", 300)));
        codeLength = clamp(getConfig().getInt("auth.code-length", 4), MIN_CODE_LENGTH, MAX_CODE_LENGTH);
        codeGroupSize = Math.max(0, getConfig().getInt("auth.code-group-size", 3));
        codeUpperBound = powerOfTen(codeLength);
        kickMessage = getConfig().getString("messages.kick", "Discord認証が必要です。\n1. Discordの認証チャンネルで「認証コードを入力」ボタンを押す\n2. 次のコードを入力する: {code}\n3. 認証完了のメッセージが出たら、もう一度接続する");
        verifiedMessage = getConfig().getString("messages.verified", "{player} を認証しました。");

        // Discord Bot を起動します。Token 未設定なら警告を出して起動しません。
        startDiscordBot();
    }

    void enterLockdown() {
        lockdown = true;
        // 起動途中で Bot が動き出していたら止めます。認証を受け付けても保存できないためです。
        if (discordBot != null) {
            discordBot.stop();
            discordBot = null;
        }
        // /reload などで接続中の人がいる場合も、全員切断します。
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            player.kick(Component.text(LOCKDOWN_MESSAGE));
        }
    }

    @Override
    public void onDisable() {
        // サーバー停止・プラグイン無効化時に Discord Bot を停止します。
        if (discordBot != null) {
            discordBot.stop();
            discordBot = null;
        }

        // 未認証コードは一時データなので、プラグイン停止時に破棄します。
        clearPendingCodes();

        // DB接続を閉じます。
        if (store != null) {
            store.close();
        }
    }

    @EventHandler
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        // プレイヤーのUUIDを取得します。名前変更されてもUUIDは基本的に変わりません。
        UUID uuid = event.getUniqueId();

        // 起動に失敗しているときは、認証済みかどうかに関係なく全員拒否します。
        if (lockdown) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(LOCKDOWN_MESSAGE));
            return;
        }

        // 既に認証済みなら、ログインを妨げません。
        // DBを読めないときは、未認証の人を通さないよう接続を拒否します。
        try {
            if (store.isAuthenticated(uuid)) {
                return;
            }
        } catch (IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Failed to check authentication", exception);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("認証情報を確認できませんでした。管理者にお問い合わせください。"));
            return;
        }

        // 古いコードを消してから、このプレイヤー用のコードを作ります。
        // 例外のまま抜けると Paper は接続を許可してしまうため、作れなかったときは拒否します。
        String code;
        try {
            code = createCode(event.getName(), uuid);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Failed to create verification code", exception);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("認証コードを発行できませんでした。しばらく待ってから再接続してください。"));
            return;
        }

        // config.yml のメッセージ内にある {code} と {player} を実際の値に置き換えます。
        String message = kickMessage
                .replace("{code}", CodeFormat.display(code, codeGroupSize))
                .replace("{player}", event.getName());

        // 未認証なのでログインを拒否し、キック画面に認証コードを表示します。
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text(message));
    }

    boolean verifyCode(String code, String discordUserId, String discordUserName, Consumer<String> reply) {
        // 同じDiscordアカウントで複数のMinecraftアカウントを認証しにくくします。
        // 連携済みの人には、コードの誤りではないことを伝えます。コードは消費せず、失敗回数にも数えません。
        try {
            if (store.isDiscordUserAuthenticated(discordUserId)) {
                reply.accept(ALREADY_LINKED_DISCORD_MESSAGE);
                return true;
            }
        } catch (IllegalStateException exception) {
            // DBを読めない場合は、コードの誤りとは区別して本人に伝えます。失敗回数には数えません。
            getLogger().log(Level.SEVERE, "Failed to check Discord user", exception);
            reply.accept("認証を確認できませんでした。管理者にお問い合わせください。");
            return true;
        }

        // コードは1回使ったら消します。成功・失敗を問わず再利用されないようにするためです。
        PendingVerification verification = consumeCode(code);
        Instant now = Instant.now();

        // 存在しないコード、または期限切れコードなら認証失敗です。
        if (verification == null || verification.isExpired(now)) {
            return false;
        }

        // ホワイトリスト変更やファイル保存は Bukkit のメインスレッドで実行します。
        Bukkit.getScheduler().runTask(this,
                () -> completeVerification(verification, discordUserId, discordUserName, reply));
        return true;
    }

    private void completeVerification(
            PendingVerification verification,
            String discordUserId,
            String discordUserName,
            Consumer<String> reply
    ) {
        try {
            switch (store.authenticateIfAvailable(
                    verification.uuid(), verification.playerName(), discordUserId, discordUserName)) {
                case DISCORD_ALREADY_LINKED -> {
                    reply.accept(ALREADY_LINKED_DISCORD_MESSAGE);
                    return;
                }
                case PLAYER_ALREADY_LINKED -> {
                    reply.accept("このMinecraftアカウントは、すでに別のDiscordアカウントと連携済みです。");
                    return;
                }
                case SAVED -> {
                }
            }
        } catch (IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Failed to save authentication", exception);
            reply.accept("認証を保存できませんでした。管理者にお問い合わせください。");
            return;
        }

        // 設定されていれば、Discordサーバーに参加するためのロールを付けます。
        if (discordBot != null) {
            discordBot.grantVerifiedRole(discordUserId);
        }

        String message = verifiedMessage
                .replace("{player}", verification.playerName())
                .replace("{uuid}", verification.uuid().toString())
                .replace("{discord}", discordUserName);
        reply.accept(message.isBlank() ? "認証が完了しました。" : message);
    }

    private void startDiscordBot() {
        // 環境変数を優先し、未設定なら config.yml へフォールバックします。
        String token = environmentOrConfig(DISCORD_TOKEN_ENV, "discord.token", "");
        String channelIdText = environmentOrConfig(DISCORD_CHANNEL_ID_ENV, "discord.channel-id", "").trim();

        // Token が未設定のままなら Bot は起動しません。
        if (token.isBlank() || token.equals("PUT_DISCORD_BOT_TOKEN_HERE")) {
            getLogger().warning("Discord bot token is not configured. Set MCAUTH_DISCORD_TOKEN or discord.token in config.yml.");
            return;
        }

        // Discord のチャンネルIDは数字なので、文字列から long に変換します。
        long channelId;
        try {
            channelId = Long.parseUnsignedLong(channelIdText);
        } catch (NumberFormatException exception) {
            getLogger().warning("Discord channel id is invalid. Set MCAUTH_DISCORD_CHANNEL_ID or discord.channel-id in config.yml.");
            return;
        }

        // Bot 本体を作成し、設定値を渡します。
        discordBot = new DiscordVerificationBot(
                this,
                channelId,
                codeLength,
                Math.max(1, getConfig().getInt("auth.max-invalid-attempts", 5)),
                Duration.ofSeconds(Math.max(1, getConfig().getLong("auth.lockout-seconds", 60))),
                getConfig().getString("messages.invalid-code", "認証コードが無効、または期限切れです。"),
                getConfig().getString("messages.rate-limited", "認証コードの間違いが多すぎます。しばらく待ってから再試行してください。"),
                getConfig().getString("messages.panel", "Minecraftサーバーに接続すると表示される認証コードを、下のボタンから入力してください。"),
                parseOptionalId(environmentOrConfig(DISCORD_VERIFIED_ROLE_ID_ENV, "discord.verified-role-id", ""))
        );

        // Discord へ接続します。
        discordBot.start(token);
    }

    private String environmentOrConfig(String environmentName, String configPath, String defaultValue) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        return getConfig().getString(configPath, defaultValue);
    }

    private long parseOptionalId(String text) {
        // 空なら「設定なし」として0を返します。
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(trimmed);
        } catch (NumberFormatException exception) {
            getLogger().warning("Discord verified role id is invalid. Set MCAUTH_DISCORD_VERIFIED_ROLE_ID or discord.verified-role-id in config.yml.");
            return 0;
        }
    }

    void unlinkDiscordUser(String discordUserId, Consumer<String> reply) {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                AuthenticatedPlayer revoked = revokeOnMainThread(discordUserId, true);
                // 本人が解除したときは、認証で付けたロールも外します。
                if (revoked != null && discordBot != null) {
                    discordBot.removeVerifiedRole(discordUserId);
                }
                reply.accept(revoked == null
                        ? "連携済みのMinecraftアカウントはありません。"
                        : "Minecraftとの連携を解除しました。再連携するには、Minecraftサーバーへ接続して新しい認証コードを取得してください。");
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Failed to unlink Minecraft account", exception);
                reply.accept("連携解除を完了できませんでした。管理者にお問い合わせください。");
            }
        });
    }

    void revokeByDiscordUserId(String discordUserId) {
        // 認証の確定と解除をメインスレッドで順番に処理します。
        Bukkit.getScheduler().runTask(this, () -> revokeOnMainThread(discordUserId, false));
    }

    private AuthenticatedPlayer revokeOnMainThread(String discordUserId, boolean disconnect) {
        // Discord ID に紐づく認証を取り消します。
        AuthenticatedPlayer revoked = store.deauthenticateByDiscordUserId(discordUserId);
        if (revoked == null) {
            return null;
        }
        removeCodeForUuid(revoked.uuid());
        if (disconnect) {
            org.bukkit.entity.Player player = Bukkit.getPlayer(revoked.uuid());
            if (player != null) {
                player.kick(Component.text("Discordとの連携を解除しました。再接続してDiscord認証を行ってください。"));
            }
        }
        return revoked;
    }

    private synchronized String createCode(String playerName, UUID uuid) {
        Instant now = Instant.now();
        cleanupExpiredCodes(now);

        // 既にこのUUID向けの有効なコードがあるなら、それを再利用します。
        String existingCode = pendingCodesByUuid.get(uuid);
        if (existingCode != null) {
            PendingVerification existingVerification = pendingCodes.get(existingCode);
            if (existingVerification != null && !existingVerification.isExpired(now)) {
                return existingCode;
            }
        }

        // 1人の未認証プレイヤーにつき有効なコードは1つだけにして、接続連打でメモリが増えるのを防ぎます。
        removeCodeForUuid(uuid);

        // コード衝突に備えて最大100回まで作り直します。
        for (int attempts = 0; attempts < 100; attempts++) {
            // 例: codeLength が6なら 000000 から 999999 の文字列を作ります。
            String code = nextCode();

            // このコードが誰のものか、有効期限はいつまでかを記録します。
            PendingVerification verification = new PendingVerification(uuid, playerName, now.plus(codeLifetime));

            // まだ使われていないコードなら保存して返します。
            if (pendingCodes.putIfAbsent(code, verification) == null) {
                pendingCodesByUuid.put(uuid, code);
                return code;
            }
        }

        throw new IllegalStateException("Failed to allocate a verification code");
    }

    private String nextCode() {
        String value = Integer.toString(RANDOM.nextInt(codeUpperBound));
        return "0".repeat(codeLength - value.length()) + value;
    }

    private synchronized PendingVerification consumeCode(String code) {
        // Discord に投稿されたコードを取り出し、同時に pendingCodes から削除します。
        PendingVerification verification = pendingCodes.remove(code);
        if (verification != null) {
            // UUID 側の逆引きMapからも消して、内部状態をそろえます。
            pendingCodesByUuid.remove(verification.uuid(), code);
        }
        return verification;
    }

    private void cleanupExpiredCodes(Instant now) {
        // 現在時刻を基準に、期限切れコードをまとめて削除します。
        Iterator<Map.Entry<String, PendingVerification>> iterator = pendingCodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingVerification> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                // iterator.remove() を使うと、ループ中でも安全にMapから削除できます。
                iterator.remove();
                pendingCodesByUuid.remove(entry.getValue().uuid(), entry.getKey());
            }
        }
    }

    private synchronized void removeCodeForUuid(UUID uuid) {
        // UUID から現在のコードを探し、両方のMapから削除します。
        String code = pendingCodesByUuid.remove(uuid);
        if (code != null) {
            pendingCodes.remove(code);
        }
    }

    private synchronized void clearPendingCodes() {
        pendingCodes.clear();
        pendingCodesByUuid.clear();
    }

    private static int powerOfTen(int exponent) {
        int result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= 10;
        }
        return result;
    }

    private static int clamp(int value, int min, int max) {
        // value を min 以上 max 以下に丸めます。
        return Math.max(min, Math.min(max, value));
    }
}
