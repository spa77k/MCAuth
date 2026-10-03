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

    synchronized boolean isDiscordUserAuthenticated(String discordUserId) {
        // Discord IDが空の場合は、重複チェック対象にしません。
        if (discordUserId.isBlank()) {
            return false;
        }

        // 既に同じDiscordユーザーIDで認証済みのMinecraftアカウントがあるか探します。
        return exists("SELECT 1 FROM authenticated_players WHERE discord_user_id = ?", discordUserId);
    }

    synchronized boolean authenticateIfAvailable(UUID uuid, String playerName, String discordUserId, String discordUserName) {
        // 確認と保存を同じロック内で行い、同じDiscord IDの二重認証を防ぎます。
        if (isDiscordUserAuthenticated(discordUserId)) {
            return false;
        }

        // Minecraft UUID と Discord ID を一緒に保存して、誰が認証したか後から確認できるようにします。
        try (PreparedStatement statement = connection().prepareStatement(
                "INSERT OR REPLACE INTO authenticated_players (uuid, name, discord_user_id, discord_user_name) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, discordUserId);
            statement.setString(4, discordUserName);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save authentication", exception);
        }
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
