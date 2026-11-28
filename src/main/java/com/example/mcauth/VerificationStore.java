package com.example.mcauth;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

// 認証済みプレイヤーを plugins/MCAuth/mcauth.db（SQLite）に保存・参照するクラスです。
// Minecraft標準のホワイトリスト（whitelist.json）とは独立して、入場可否の元データになります。
final class VerificationStore implements AutoCloseable {
    // ログ出力やプラグインフォルダ取得に使います。
    private final JavaPlugin plugin;

    // 保存先ファイルです。実際には plugins/MCAuth/mcauth.db になります。
    private final File file;

    // SQLite への接続です。最初に使うときに開き、close() で閉じます。
    private Connection connection;

    VerificationStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mcauth.db");
    }

    // DBファイルとテーブルを用意します。開けない場合は例外にして、呼び出し側でプラグインを止められるようにします。
    synchronized void load() {
        connection();
    }

    synchronized boolean isAuthenticated(UUID uuid) {
        // Minecraft UUID が登録されていれば認証済みです。
        return exists("SELECT 1 FROM authenticated_players WHERE uuid = ?", uuid.toString());
    }

    synchronized List<String> authenticatedDiscordUserIds() {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT discord_user_id FROM authenticated_players");
             ResultSet result = statement.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (result.next()) {
                ids.add(result.getString("discord_user_id"));
            }
            return List.copyOf(ids);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read linked Discord users", exception);
        }
    }

    synchronized boolean isDiscordUserAuthenticated(String discordUserId) {
        // Discord IDが空の場合は、重複チェック対象にしません。
        if (discordUserId.isBlank()) {
            return false;
        }

        // 既に同じDiscordユーザーIDで認証済みのMinecraftアカウントがあるか探します。
        return exists("SELECT 1 FROM authenticated_players WHERE discord_user_id = ?", discordUserId);
    }

    synchronized AuthenticatedPlayer findByDiscordUserId(String discordUserId) {
        try (PreparedStatement statement = connection().prepareStatement(
                "SELECT uuid, name, discord_user_id, discord_user_name FROM authenticated_players WHERE discord_user_id = ?")) {
            statement.setString(1, discordUserId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new AuthenticatedPlayer(UUID.fromString(result.getString("uuid")),
                        result.getString("name"), result.getString("discord_user_id"),
                        result.getString("discord_user_name"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read linked Minecraft player", exception);
        }
    }

    synchronized AuthenticationResult authenticateIfAvailable(UUID uuid, String playerName, String discordUserId, String discordUserName) {
        // 確認と保存を同じロック内で行い、同じDiscord IDの二重認証を防ぎます。
        if (isDiscordUserAuthenticated(discordUserId)) {
            return AuthenticationResult.DISCORD_ALREADY_LINKED;
        }

        // 既に別のDiscordと連携済みのMinecraftアカウントは上書きしません。
        // 上書きすると、元のDiscordユーザーが /unlink できないままロールだけ残るためです。
        if (isAuthenticated(uuid)) {
            return AuthenticationResult.PLAYER_ALREADY_LINKED;
        }

        // Minecraft UUID と Discord ID を一緒に保存して、誰が認証したか後から確認できるようにします。
        try (PreparedStatement statement = connection().prepareStatement(
                "INSERT INTO authenticated_players (uuid, name, discord_user_id, discord_user_name) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, discordUserId);
            statement.setString(4, discordUserName);
            statement.executeUpdate();
            return AuthenticationResult.SAVED;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save authentication", exception);
        }
    }

    /** 写真館の賞金を払う権利を取ります。同じ週・同じDiscord IDが既にあれば false です。 */
    synchronized boolean claimPhotoPrize(int week, String discordUserId, UUID uuid, int amount) {
        try (PreparedStatement statement = connection().prepareStatement(
                "INSERT OR IGNORE INTO photo_prizes (week, discord_user_id, uuid, amount, paid_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, week);
            statement.setString(2, discordUserId);
            statement.setString(3, uuid.toString());
            statement.setInt(4, amount);
            statement.setLong(5, System.currentTimeMillis());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save photo prize", exception);
        }
    }

    /** 所持金へ入れられなかったときに、取った権利を戻します。 */
    synchronized void releasePhotoPrize(int week, String discordUserId) {
        try (PreparedStatement statement = connection().prepareStatement(
                "DELETE FROM photo_prizes WHERE week = ? AND discord_user_id = ?")) {
            statement.setInt(1, week);
            statement.setString(2, discordUserId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to release photo prize", exception);
        }
    }

    // 認証の保存結果です。
    enum AuthenticationResult {
        SAVED,
        DISCORD_ALREADY_LINKED,
        PLAYER_ALREADY_LINKED
    }

    synchronized AuthenticatedPlayer deauthenticateByDiscordUserId(String discordUserId) {
        try {
            Connection connection = connection();
            AuthenticatedPlayer player;

            // 削除前に、通知や切断に使う認証情報を取り出します。
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, name, discord_user_id, discord_user_name FROM authenticated_players WHERE discord_user_id = ?")) {
                statement.setString(1, discordUserId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    player = new AuthenticatedPlayer(
                            UUID.fromString(result.getString("uuid")),
                            result.getString("name"),
                            result.getString("discord_user_id"),
                            result.getString("discord_user_name")
                    );
                }
            }

            // 削除に失敗した場合は例外にして、解除成功として扱わせません。
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM authenticated_players WHERE discord_user_id = ?")) {
                statement.setString(1, discordUserId);
                statement.executeUpdate();
            }
            return player;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save unlink operation", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to close mcauth.db: " + exception.getMessage());
        }
        connection = null;
    }

    private boolean exists(String sql, String value) {
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read mcauth.db", exception);
        }
    }

    private Connection connection() {
        if (connection != null) {
            return connection;
        }
        try {
            // Paper に同梱されている SQLite ドライバーを使います。
            File folder = file.getParentFile();
            if (folder != null) {
                folder.mkdirs();
            }
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement statement = opened.createStatement()) {
                // 保存形式:
                // uuid: Minecraft UUID（主キー）
                // name: 認証時のMinecraft名
                // discord_user_id: 認証したDiscordユーザーID（1人1アカウントにするため重複不可）
                // discord_user_name: 認証時のDiscord名
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS authenticated_players ("
                        + "uuid TEXT PRIMARY KEY, "
                        + "name TEXT NOT NULL, "
                        + "discord_user_id TEXT NOT NULL UNIQUE, "
                        + "discord_user_name TEXT NOT NULL)");
                // 写真館の賞金の支払い済み記録です。週とDiscord IDで二重払いを防ぎます。
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS photo_prizes ("
                        + "week INTEGER NOT NULL, "
                        + "discord_user_id TEXT NOT NULL, "
                        + "uuid TEXT NOT NULL, "
                        + "amount INTEGER NOT NULL, "
                        + "paid_at INTEGER NOT NULL, "
                        + "PRIMARY KEY (week, discord_user_id))");
            } catch (SQLException exception) {
                opened.close();
                throw exception;
            }
            connection = opened;
            return connection;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to open mcauth.db", exception);
        }
    }
}
