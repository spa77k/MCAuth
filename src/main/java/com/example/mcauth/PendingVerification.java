package com.example.mcauth;

import java.time.Instant;
import java.util.UUID;

// まだDiscord認証が終わっていない、一時的な認証コードの情報です。
// このデータはメモリ上だけにあり、サーバー再起動や期限切れで消えます。
record PendingVerification(UUID uuid, String playerName, Instant expiresAt) {
    // 期限切れコードは認証に使わせず、総当たりや古いコードの再利用を防ぎます。
    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
