package com.example.mcauth;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Paper が最初に読み込むプラグイン本体です。
// Minecraft 側の接続チェック、認証コード発行、ホワイトリスト追加を担当します。
public final class MCAuthPlugin extends JavaPlugin implements Listener {
    // 認証コードは推測されにくい乱数で作ります。
    private static final SecureRandom RANDOM = new SecureRandom();

    // 設定ミスでコードが短すぎたり長すぎたりしないよう、許可する範囲を決めています。
    private static final int MIN_CODE_LENGTH = 4;
    private static final int MAX_CODE_LENGTH = 8;

    // key: 認証コード, value: そのコードで認証される予定のMinecraftプレイヤー情報。
    // Discord にコードが投稿されたとき、このMapから探します。
    private final Map<String, PendingVerification> pendingCodes = new ConcurrentHashMap<>();

    // key: Minecraft UUID, value: 現在発行中の認証コード。
    // 同じプレイヤーが何度も接続しても、コードが無限に増えないようにします。
    private final Map<UUID, String> pendingCodesByUuid = new ConcurrentHashMap<>();

    // 認証済みプレイヤーを data.yml に保存・読み込みする担当です。
    private VerificationStore store;

    // Discord Bot を起動し、認証チャンネルのメッセージを監視する担当です。
    private DiscordVerificationBot discordBot;

    // 認証コードの有効期限です。config.yml の auth.code-expire-seconds から読みます。
    private Duration codeLifetime;

    // 認証コードの桁数です。デフォルトは6桁です。
    private int codeLength;

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

        // 保存済みの認証データを data.yml から読み込みます。
        store = new VerificationStore(this);
        store.load();

        // 設定値を読み込みます。危険な値にならないよう、最低値や範囲を補正しています。
        codeLifetime = Duration.ofSeconds(Math.max(1, getConfig().getLong("auth.code-expire-seconds", 300)));
        codeLength = clamp(getConfig().getInt("auth.code-length", 6), MIN_CODE_LENGTH, MAX_CODE_LENGTH);
        codeUpperBound = (int) Math.pow(10, codeLength);
        kickMessage = getConfig().getString("messages.kick", "Discord認証が必要です。\n認証チャンネルに次のコードを送信してください: {code}");
        verifiedMessage = getConfig().getString("messages.verified", "{player} を認証し、ホワイトリストに追加しました。");

        // このクラスのイベント処理メソッドを Paper に登録します。
        Bukkit.getPluginManager().registerEvents(this, this);

        // data.yml に保存済みの人を、サーバー起動時にもう一度ホワイトリストへ反映します。
        syncAuthenticatedWhitelist();

        // Discord Bot を起動します。Token 未設定なら警告を出して起動しません。
        startDiscordBot();
    }

    @Override
    public void onDisable() {
        // サーバー停止・プラグイン無効化時に Discord Bot を停止します。
        if (discordBot != null) {
            discordBot.stop();
            discordBot = null;
        }

        // 未認証コードは一時データなので、プラグイン停止時に破棄します。
        pendingCodes.clear();
        pendingCodesByUuid.clear();
    }

    @EventHandler
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        // プレイヤーのUUIDを取得します。名前変更されてもUUIDは基本的に変わりません。
        UUID uuid = event.getUniqueId();

        // 既に認証済みなら、ログインを妨げません。
        if (store.isAuthenticated(uuid)) {
            return;
        }

        // 古いコードを消してから、このプレイヤー用のコードを作ります。
        cleanupExpiredCodes();
        String code = createCode(event.getName(), uuid);

        // config.yml のメッセージ内にある {code} と {player} を実際の値に置き換えます。
        String message = kickMessage
                .replace("{code}", code)
                .replace("{player}", event.getName());

        // 未認証なのでログインを拒否し、キック画面に認証コードを表示します。
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text(message));
    }

    boolean verifyCode(String code, MessageChannel channel, String discordUserId, String discordUserName) {
        // 同じDiscordアカウントで複数のMinecraftアカウントを認証しにくくします。
        if (store.isDiscordUserAuthenticated(discordUserId)) {
            return false;
        }

        // コードは1回使ったら消します。成功・失敗を問わず再利用されないようにするためです。
        PendingVerification verification = consumeCode(code);

        // 存在しないコード、または期限切れコードなら認証失敗です。
        if (verification == null || verification.isExpired(Instant.now())) {
            return false;
        }

        // ホワイトリスト変更やファイル保存は Bukkit のメインスレッドで実行します。
        Bukkit.getScheduler().runTask(this, () -> {
            // Minecraft UUID と Discord ユーザー情報を data.yml に保存します。
            store.authenticate(verification.uuid(), verification.playerName(), discordUserId, discordUserName);

            // Paper/Minecraft 標準のホワイトリストへ追加します。
            addToWhitelist(verification.uuid());

            // 認証成功メッセージを作り、空文字でなければDiscordへ送信します。
            String message = verifiedMessage
                    .replace("{player}", verification.playerName())
                    .replace("{uuid}", verification.uuid().toString())
                    .replace("{discord}", discordUserName);
            if (!message.isBlank()) {
                channel.sendMessage(message).queue();
            }
        });
        return true;
    }

    private void startDiscordBot() {
        // Discord Bot Token と認証チャンネルIDを config.yml から読みます。
        String token = getConfig().getString("discord.token", "");
        String channelIdText = getConfig().getString("discord.channel-id", "");

        // Token が未設定のままなら Bot は起動しません。
        if (token.isBlank() || token.equals("PUT_DISCORD_BOT_TOKEN_HERE")) {
            getLogger().warning("Discord bot token is not configured. Set discord.token in config.yml.");
            return;
        }

        // Discord のチャンネルIDは数字なので、文字列から long に変換します。
        long channelId;
        try {
            channelId = Long.parseUnsignedLong(channelIdText);
        } catch (NumberFormatException exception) {
            getLogger().warning("Discord channel id is invalid. Set discord.channel-id in config.yml.");
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
                getConfig().getString("messages.rate-limited", "認証コードの間違いが多すぎます。しばらく待ってから再試行してください。")
        );

        // Discord へ接続します。
        discordBot.start(token);
    }

    private void syncAuthenticatedWhitelist() {
        // data.yml に保存されている認証済みプレイヤー全員をホワイトリストへ反映します。
        for (Map.Entry<UUID, AuthenticatedPlayer> entry : store.entries()) {
            addToWhitelist(entry.getKey());
        }
    }

    AuthenticatedPlayer revokeByDiscordUserId(String discordUserId) {
        // Discord ID に紐づく認証を取り消します。
        AuthenticatedPlayer revoked = store.deauthenticateByDiscordUserId(discordUserId);
        if (revoked == null) {
            return null;
        }
        // ホワイトリスト変更はメインスレッドで行います。
        Bukkit.getScheduler().runTask(this, () -> removeFromWhitelist(revoked.uuid()));
        return revoked;
    }

    private void addToWhitelist(UUID uuid) {
        // UUID から OfflinePlayer を取得します。オフラインでもホワイトリスト追加できます。
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);

        // まだホワイトリストに入っていない場合だけ追加します。
        if (!player.isWhitelisted()) {
            player.setWhitelisted(true);
        }

        // whitelist.json の内容をサーバーに再読み込みさせます。
        Bukkit.reloadWhitelist();
    }

    private void removeFromWhitelist(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        if (player.isWhitelisted()) {
            player.setWhitelisted(false);
        }
        Bukkit.reloadWhitelist();
    }

    private synchronized String createCode(String playerName, UUID uuid) {
        // 既にこのUUID向けの有効なコードがあるなら、それを再利用します。
        String existingCode = pendingCodesByUuid.get(uuid);
        if (existingCode != null) {
            PendingVerification existingVerification = pendingCodes.get(existingCode);
            if (existingVerification != null && !existingVerification.isExpired(Instant.now())) {
                return existingCode;
            }
        }

        // 1人の未認証プレイヤーにつき有効なコードは1つだけにして、接続連打でメモリが増えるのを防ぎます。
        removeCodeForUuid(uuid);

        // コード衝突に備えて最大100回まで作り直します。
        for (int attempts = 0; attempts < 100; attempts++) {
            // 例: codeLength が6なら 000000 から 999999 の文字列を作ります。
            String code = String.format("%0" + codeLength + "d", RANDOM.nextInt(codeUpperBound));

            // このコードが誰のものか、有効期限はいつまでかを記録します。
            PendingVerification verification = new PendingVerification(uuid, playerName, Instant.now().plus(codeLifetime));

            // まだ使われていないコードなら保存して返します。
            if (pendingCodes.putIfAbsent(code, verification) == null) {
                pendingCodesByUuid.put(uuid, code);
                return code;
            }
        }

        throw new IllegalStateException("Failed to allocate a verification code");
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

    private synchronized void cleanupExpiredCodes() {
        // 現在時刻を基準に、期限切れコードをまとめて削除します。
        Instant now = Instant.now();
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

    private void removeCodeForUuid(UUID uuid) {
        // UUID から現在のコードを探し、両方のMapから削除します。
        String code = pendingCodesByUuid.remove(uuid);
        if (code != null) {
            pendingCodes.remove(code);
        }
    }

    private int clamp(int value, int min, int max) {
        // value を min 以上 max 以下に丸めます。
        return Math.max(min, Math.min(max, value));
    }
}
