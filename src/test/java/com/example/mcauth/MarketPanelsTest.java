package com.example.mcauth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarketPanelsTest {
    private static final long NOW = 1_800_000_000_000L;

    private static Map<String, String> request(String id, String title, String description) {
        return Map.of("id", id, "title", title, "reward", "490", "expires", String.valueOf(NOW + 4 * 86_400_000L),
                "minStars", "0", "itemDelivery", "true", "description", description);
    }

    @Test void listShowsNameOnBoldLineAndKeyFactsWithoutDescription() {
        String line = MarketPanels.line(request("56", "ダイヤモンドを24個集めて", "説明文"), false);
        long seconds = (NOW + 4 * 86_400_000L) / 1000L;
        assertEquals("**`#56` ダイヤモンドを24個集めて**\n報酬 490S ・ 終了 <t:" + seconds + ":R> ・ アイテム納品あり", line);
        assertFalse(line.contains("説明文"));
    }

    @Test void auctionLineShowsBuyoutOnlyWhenSet() {
        Map<String, String> listing = Map.of("id", "68", "item", "完全身代わりの護符", "amount", "1",
                "minBid", "750", "buyout", "0", "expires", String.valueOf(NOW));
        assertEquals("**`#68` 完全身代わりの護符 ×1**\n最低入札 750S ・ 終了 <t:" + NOW / 1000L + ":R>",
                MarketPanels.line(listing, true));
    }

    @Test void detailAddsDescriptionBelowSummary() {
        String detail = MarketPanels.detail(request("56", "ダイヤ", "納品ボックスに入れてください。"), false);
        assertEquals(MarketPanels.line(request("56", "ダイヤ", ""), false) + "\n\n納品ボックスに入れてください。", detail);
    }

    @Test void pickerListsEachRecordWithPriceAndRemainingTime() {
        var menu = MarketPanels.picker(List.of(request("56", "ダイヤモンドを24個集めて", ""),
                request("54", "あ".repeat(120), "")), false, NOW);
        assertEquals("market:pick:request", menu.getId());
        assertEquals("56", menu.getOptions().get(0).getValue());
        assertEquals("#56 ダイヤモンドを24個集めて", menu.getOptions().get(0).getLabel());
        assertEquals("報酬 490S ・ 残り4日", menu.getOptions().get(0).getDescription());
        assertEquals(100, menu.getOptions().get(1).getLabel().length());
    }

    @Test void remainingUsesLargestUnit() {
        assertEquals("残り2日", MarketPanels.remaining(NOW + 50 * 3_600_000L, NOW));
        assertEquals("残り5時間", MarketPanels.remaining(NOW + 5 * 3_600_000L + 1, NOW));
        assertEquals("残り30分", MarketPanels.remaining(NOW + 30 * 60_000L, NOW));
        assertEquals("まもなく終了", MarketPanels.remaining(NOW - 1, NOW));
    }
}
