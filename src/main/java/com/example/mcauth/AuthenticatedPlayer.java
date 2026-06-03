package com.example.mcauth;

import java.util.UUID;

// 認証済みプレイヤー1人分の保存データです。
// record は「値を入れるだけの小さなクラス」を短く書くためのJava機能です。
record AuthenticatedPlayer(UUID uuid, String playerName, String discordUserId, String discordUserName) {
}
