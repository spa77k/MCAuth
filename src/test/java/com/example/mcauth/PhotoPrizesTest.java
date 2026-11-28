package com.example.mcauth;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhotoPrizesTest {
    @TempDir
    Path directory;

    private final AuthenticatedPlayer player = new AuthenticatedPlayer(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "Steve", "123456789012345678", "steve");

    private final List<VerificationStore> opened = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void close() {
        opened.forEach(VerificationStore::close);
    }

    private VerificationStore store() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        VerificationStore store = new VerificationStore(plugin);
        opened.add(store);
        return store;
    }

    @Test
    void paysOncePerWeekAndRecordsTheLog() throws Exception {
        List<Double> deposits = new ArrayList<>();
        Path log = directory.resolve("prizes.log");
        PhotoPrizes prizes = new PhotoPrizes(store(), (uuid, amount) -> deposits.add(amount), log, Logger.getAnonymousLogger());

        assertEquals(PhotoPrizes.PAID, prizes.pay(player, 20261130, 500));
        assertEquals(PhotoPrizes.ALREADY_PAID, prizes.pay(player, 20261130, 500));
        // DBを開き直しても払い済みのまま。
        PhotoPrizes reopened = new PhotoPrizes(store(), (uuid, amount) -> deposits.add(amount), log, Logger.getAnonymousLogger());
        assertEquals(PhotoPrizes.ALREADY_PAID, reopened.pay(player, 20261130, 300));
        assertEquals(PhotoPrizes.PAID, reopened.pay(player, 20261207, 300));
        assertEquals(List.of(500.0, 300.0), deposits);

        List<String> lines = Files.readAllLines(log);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("\"kind\":\"start\""));
        assertTrue(lines.get(1).contains("\"kind\":\"photo\",\"money\":500,\"player\":\"Steve\""));
        assertTrue(lines.get(1).contains("\"week\":20261130"));
        assertTrue(TradeApiServer.isSuccess(PhotoPrizes.PAID));
        assertTrue(TradeApiServer.isSuccess(PhotoPrizes.ALREADY_PAID));
    }

    @Test
    void releasesTheClaimWhenTheDepositFails() {
        boolean[] fail = {true};
        PhotoPrizes prizes = new PhotoPrizes(store(), (uuid, amount) -> {
            if (fail[0]) throw new IllegalStateException("no economy");
            return true;
        }, directory.resolve("prizes.log"), Logger.getAnonymousLogger());
        assertNull(prizes.pay(player, 20261130, 500));
        fail[0] = false;
        assertEquals(PhotoPrizes.PAID, prizes.pay(player, 20261130, 500));
    }

    @Test
    void rejectsOutOfRangeAmounts() {
        PhotoPrizes prizes = new PhotoPrizes(store(), (uuid, amount) -> true,
                directory.resolve("prizes.log"), Logger.getAnonymousLogger());
        assertFalse(TradeApiServer.isSuccess(prizes.pay(player, 20261130, 0)));
        assertFalse(TradeApiServer.isSuccess(prizes.pay(player, 20261130, PhotoPrizes.MAX_AMOUNT + 1)));
        assertFalse(TradeApiServer.isSuccess(prizes.pay(player, 1, 500)));
        assertNotNull(TradeApiServer.numbers("photo", new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode().add(20261130).add(500)));
    }
}
