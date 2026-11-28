package com.example.mcauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 写真館（Discord のスクショの週間ランキング）の賞金を、連携済みの本人の所持金へ入れます。
 *
 * <p>同じ週・同じ Discord ID には1回しか払いません。払った記録は経済の集計が読む
 * {@code prizes.log}（1行1件の JSON）にも残します。</p>
 */
final class PhotoPrizes {
    static final int MAX_AMOUNT = 1000;
    static final String PAID = "写真館の賞金を払いました。";
    static final String ALREADY_PAID = "写真館の賞金は払い済みです。";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 所持金へ入れる手段です。経済の提供元が無い・失敗したら false を返します。 */
    @FunctionalInterface
    interface Wallet {
        boolean deposit(UUID uuid, double amount) throws Exception;
    }

    private final VerificationStore store;
    private final Wallet wallet;
    private final Path log;
    private final Logger logger;

    PhotoPrizes(VerificationStore store, Wallet wallet, Path log, Logger logger) {
        this.store = store;
        this.wallet = wallet;
        this.log = log;
        this.logger = logger;
        // 集計は start 行より後の区間だけを記録しきれたものとして扱います。
        ObjectNode start = MAPPER.createObjectNode();
        start.put("at", now());
        start.put("kind", "start");
        append(start);
    }

    /** 応答の文言を返します。今は払えない（あとで払い直せる）ときは null を返します。 */
    String pay(AuthenticatedPlayer player, int week, int amount) {
        if (amount < 1 || amount > MAX_AMOUNT || week < 20260101 || week > 99991231) {
            return "賞金の額が正しくありません。";
        }
        if (!store.claimPhotoPrize(week, player.discordUserId(), player.uuid(), amount)) {
            return ALREADY_PAID;
        }
        boolean deposited;
        try {
            deposited = wallet.deposit(player.uuid(), amount);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "写真館の賞金を所持金へ入れられませんでした", exception);
            deposited = false;
        }
        if (!deposited) {
            store.releasePhotoPrize(week, player.discordUserId());
            return null;
        }
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("at", now());
        entry.put("kind", "photo");
        entry.put("money", amount);
        entry.put("player", player.playerName());
        entry.put("uuid", player.uuid().toString());
        entry.put("discord", player.discordUserId());
        entry.put("week", week);
        append(entry);
        return PAID;
    }

    private static String now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private void append(ObjectNode entry) {
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, MAPPER.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            // 書けなくても支払いは取り消さず、警告だけ残します。
            logger.log(Level.WARNING, "prizes.log に書き込めませんでした", exception);
        }
    }
}
