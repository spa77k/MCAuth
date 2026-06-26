package com.example.mcauth;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

// 認証済みプレイヤーを plugins/MCAuth/data.yml に保存・読み込みするクラスです。
final class VerificationStore {
    // ログ出力やプラグインフォルダ取得に使います。
    private final JavaPlugin plugin;

    // 保存先ファイルです。実際には plugins/MCAuth/data.yml になります。
    private final File file;

    // key: Minecraft UUID, value: 認証済みプレイヤー情報。
    // サーバー起動中はこのMapを見て認証済みか判定します。
    private final Map<UUID, AuthenticatedPlayer> authenticatedPlayers = new HashMap<>();

    VerificationStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    synchronized void load() {
        // 再読み込み時に古いメモリ上のデータが残らないよう、最初に空にします。
        authenticatedPlayers.clear();

        // data.yml がまだ無い場合は、保存済み認証者なしとして扱います。
        if (!file.exists()) {
            return;
        }

        // Bukkit の YamlConfiguration を使って data.yml を読みます。
        FileConfiguration data = YamlConfiguration.loadConfiguration(file);

        // data.yml の authenticated: 以下が認証済みプレイヤー一覧です。
        ConfigurationSection section = data.getConfigurationSection("authenticated");
        if (section == null) {
            return;
        }

        // authenticated: の直下には Minecraft UUID が並びます。
        for (String key : section.getKeys(false)) {
            try {
                // YAML上の文字列UUIDを Java の UUID 型へ変換します。
                UUID uuid = UUID.fromString(key);

                // UUID、Minecraft名、Discord情報をまとめてメモリに載せます。
                authenticatedPlayers.put(uuid, new AuthenticatedPlayer(
                        uuid,
                        section.getString(key + ".name", "Unknown"),
                        section.getString(key + ".discord-user-id", ""),
                        section.getString(key + ".discord-user-name", "")
                ));
            } catch (IllegalArgumentException exception) {
                // UUID形式でないキーがあっても、プラグイン全体は止めずにその行だけ無視します。
                plugin.getLogger().warning("Ignoring invalid UUID in data.yml: " + key);
            }
        }
    }

    synchronized boolean isAuthenticated(UUID uuid) {
        // Minecraft UUID がMapにあれば認証済みです。
        return authenticatedPlayers.containsKey(uuid);
    }

    synchronized boolean isDiscordUserAuthenticated(String discordUserId) {
        // 古いdata.ymlなどでDiscord IDが空の場合は、重複チェック対象にしません。
        if (discordUserId.isBlank()) {
            return false;
        }

        // 既に同じDiscordユーザーIDで認証済みのMinecraftアカウントがあるか探します。
        return authenticatedPlayers.values().stream()
                .anyMatch(player -> player.discordUserId().equals(discordUserId));
    }

    synchronized void authenticate(UUID uuid, String playerName, String discordUserId, String discordUserName) {
        // Minecraft UUID と Discord ID を一緒に保存して、誰が認証したか後から確認できるようにします。
        authenticatedPlayers.put(uuid, new AuthenticatedPlayer(uuid, playerName, discordUserId, discordUserName));

        // メモリ上だけでなく data.yml にも書き込みます。
        save();
    }

    synchronized AuthenticatedPlayer deauthenticateByDiscordUserId(String discordUserId) {
        // Discord ID が一致する認証済みエントリを探して削除します。
        UUID found = null;
        for (Map.Entry<UUID, AuthenticatedPlayer> entry : authenticatedPlayers.entrySet()) {
            if (entry.getValue().discordUserId().equals(discordUserId)) {
                found = entry.getKey();
                break;
            }
        }
        if (found == null) {
            return null;
        }
        AuthenticatedPlayer removed = authenticatedPlayers.remove(found);
        save();
        return removed;
    }

    synchronized Set<Map.Entry<UUID, AuthenticatedPlayer>> entries() {
        // 外部からMap本体を書き換えられないよう、コピーした読み取り専用Setを返します。
        return Collections.unmodifiableSet(Set.copyOf(authenticatedPlayers.entrySet()));
    }

    private void save() {
        // 新しいYAMLを作り、現在の認証済みプレイヤーをすべて書き込みます。
        FileConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, AuthenticatedPlayer> entry : authenticatedPlayers.entrySet()) {
            AuthenticatedPlayer player = entry.getValue();
            String path = "authenticated." + entry.getKey();

            // 保存形式:
            // authenticated.<UUID>.name
            // authenticated.<UUID>.discord-user-id
            // authenticated.<UUID>.discord-user-name
            data.set(path + ".name", player.playerName());
            data.set(path + ".discord-user-id", player.discordUserId());
            data.set(path + ".discord-user-name", player.discordUserName());
        }

        try {
            // plugins/MCAuth/data.yml に保存します。
            data.save(file);
        } catch (IOException exception) {
            // ファイル権限などで保存できない場合はログに出します。
            plugin.getLogger().log(Level.SEVERE, "Failed to save data.yml", exception);
        }
    }
}
