package com.example.mcauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeApiServerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String DISCORD_ID = "123456789012345678";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @FunctionalInterface
    private interface Operation {
        Object apply(String discordId, String operation, int[] numbers);
    }

    private MCAuthPlugin plugin;
    private TradeApiServer server;
    private HttpClient client;
    private String base;
    private volatile Function<String, Map<String, Object>> query;
    private volatile Operation operation;

    @BeforeEach
    void setUp() throws IOException {
        query = id -> Map.of("linked", false);
        operation = (id, op, numbers) -> "出品しました";
        plugin = mock(MCAuthPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        doAnswer(call -> {
            Consumer<Map<String, Object>> reply = call.getArgument(1);
            reply.accept(query.apply(call.getArgument(0)));
            return null;
        }).when(plugin).tradeQuery(anyString(), any());
        doAnswer(call -> {
            Consumer<Object> reply = call.getArgument(3);
            reply.accept(operation.apply(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(plugin).tradeOperation(anyString(), anyString(), any(int[].class), any());

        server = new TradeApiServer(plugin, TOKEN, 0, Runnable::run);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> get(String token, String queryString) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create(base + "/v1/trade" + (queryString == null ? "" : "?" + queryString)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/v1/trade"))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    @Test void rejectsMissingOrWrongBearerTokenWith401() throws Exception {
        assertEquals(401, get(null, "discord=" + DISCORD_ID).statusCode());
        assertEquals(401, get("wrong-token", "discord=" + DISCORD_ID).statusCode());
        assertEquals(401, get(TOKEN + "x", "discord=" + DISCORD_ID).statusCode());
        assertEquals(401, post(null, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[1]}").statusCode());
        assertEquals(401, post("wrong-token", "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[1]}").statusCode());
    }

    @Test void neverStartsWithAShortToken() throws Exception {
        assertFalse(TradeApiServer.tokenLongEnough(null));
        assertFalse(TradeApiServer.tokenLongEnough(""));
        assertFalse(TradeApiServer.tokenLongEnough("a".repeat(TradeApiServer.MIN_TOKEN_LENGTH - 1)));
        assertTrue(TradeApiServer.tokenLongEnough("a".repeat(TradeApiServer.MIN_TOKEN_LENGTH)));

        TradeApiServer shortToken = new TradeApiServer(plugin, "a".repeat(TradeApiServer.MIN_TOKEN_LENGTH - 1), 0);
        assertThrows(IllegalArgumentException.class, shortToken::start);
        assertEquals(-1, shortToken.port());
    }

    @Test void returnsLinkedFalseForUnlinkedDiscordUser() throws Exception {
        HttpResponse<String> response = get(TOKEN, "discord=" + DISCORD_ID);
        assertEquals(200, response.statusCode());
        assertEquals("{\"linked\":false}", response.body());
    }

    @Test void returnsEverySectionForLinkedDiscordUser() throws Exception {
        query = id -> {
            Map<String, Object> auction = new LinkedHashMap<>();
            auction.put("listings", List.of(Map.of("id", "1", "item", "ダイヤ", "amount", "3")));
            auction.put("staged", List.of(Map.of("id", "2", "item", "剣", "amount", "1")));
            auction.put("mine", List.of(Map.of("id", "3", "status", "ACTIVE")));
            auction.put("bids", List.of(Map.of("id", "4", "leading", "true")));
            auction.put("vault", List.of(Map.of("id", "5", "reason", "SOLD")));
            Map<String, Object> requests = new LinkedHashMap<>();
            requests.put("open", List.of(Map.of("id", "6", "title", "ダイヤを集めて")));
            requests.put("mine", List.of(Map.of("id", "7", "status", "OPEN")));
            requests.put("accepted", List.of(Map.of("id", "8", "requester", "みんな")));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("linked", true);
            view.put("auction", auction);
            view.put("requests", requests);
            return view;
        };
        JsonNode body = json(get(TOKEN, "discord=" + DISCORD_ID));
        assertTrue(body.get("linked").asBoolean());
        assertEquals("1", body.get("auction").get("listings").get(0).get("id").asText());
        assertEquals("2", body.get("auction").get("staged").get(0).get("id").asText());
        assertEquals("3", body.get("auction").get("mine").get(0).get("id").asText());
        assertEquals("4", body.get("auction").get("bids").get(0).get("id").asText());
        assertEquals("5", body.get("auction").get("vault").get(0).get("id").asText());
        assertEquals("6", body.get("requests").get("open").get(0).get("id").asText());
        assertEquals("7", body.get("requests").get("mine").get(0).get("id").asText());
        assertEquals("8", body.get("requests").get("accepted").get(0).get("id").asText());
        assertTrue(body.get("auction").isObject());
        assertTrue(body.get("requests").isObject());
    }

    @Test void keepsSectionsNullWhenTheirPluginsAreDisabled() throws Exception {
        query = id -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("linked", true);
            view.put("auction", null);
            view.put("requests", null);
            return view;
        };
        JsonNode body = json(get(TOKEN, "discord=" + DISCORD_ID));
        assertTrue(body.get("linked").asBoolean());
        assertTrue(body.get("auction").isNull());
        assertTrue(body.get("requests").isNull());
    }

    @Test void passesEachOperationThroughWithItsNumbers() throws Exception {
        List<String> calls = new ArrayList<>();
        operation = (id, op, numbers) -> {
            calls.add(id + ":" + op + ":" + Arrays.toString(numbers));
            return "依頼「ダイヤ」を受諾しました。";
        };
        assertEquals(200, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\",\"numbers\":[7,100]}").statusCode());
        assertEquals(200, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"sell\",\"numbers\":[3,50,200,24]}").statusCode());
        assertEquals(200, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[9]}").statusCode());
        assertEquals(200, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"accept\",\"numbers\":[5]}").statusCode());
        assertEquals(List.of(
                DISCORD_ID + ":bid:[7, 100]",
                DISCORD_ID + ":sell:[3, 50, 200, 24]",
                DISCORD_ID + ":cancel:[9]",
                DISCORD_ID + ":accept:[5]"
        ), calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "入札しました。",
            "即決価格で落札しました。",
            "出品しました。",
            "出品を取り消しました。品物はゲーム内の保管庫で受け取れます。",
            "依頼「ダイヤを集めて」を受諾しました。"
    })
    void marksKnownSuccessMessagesAsOk(String message) throws Exception {
        operation = (id, op, numbers) -> message;
        JsonNode body = json(post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[1]}"));
        assertTrue(body.get("ok").asBoolean(), message);
        assertEquals(message, body.get("message").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "この出品は見つかりません。",
            "自分の出品ではありません。",
            "この出品はすでに終了しています。",
            "入札が入っているため取り消せません。",
            "出品を取り消せませんでした。",
            "この機能は現在利用できません。"
    })
    void keepsFailuresAsNotOk(String message) throws Exception {
        operation = (id, op, numbers) -> message;
        JsonNode body = json(post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[1]}"));
        assertFalse(body.get("ok").asBoolean(), message);
        assertEquals(message, body.get("message").asText());
    }

    @Test void recognisesTheSameSuccessMessagesAsTheOldPanels() {
        assertTrue(TradeApiServer.isSuccess("入札しました。"));
        assertTrue(TradeApiServer.isSuccess("即決価格で落札しました。"));
        assertTrue(TradeApiServer.isSuccess("出品しました。"));
        assertTrue(TradeApiServer.isSuccess("出品を取り消しました。品物はゲーム内の保管庫で受け取れます。"));
        assertTrue(TradeApiServer.isSuccess("依頼「ダイヤ」を受諾しました。"));
        assertFalse(TradeApiServer.isSuccess("出品を取り消せませんでした。"));
        assertFalse(TradeApiServer.isSuccess("依頼「ダイヤ」を受諾できませんでした。"));
    }

    @Test void tellsUnlinkedUsersToFinishMinecraftVerification() throws Exception {
        operation = (id, op, numbers) -> "Minecraftアカウントの認証を完了してください。";
        JsonNode body = json(post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\",\"numbers\":[7,100]}"));
        assertFalse(body.get("ok").asBoolean());
        assertEquals("Minecraftアカウントの認証を完了してください。", body.get("message").asText());
    }

    @Test void rejectsInvalidRequestsWith400() throws Exception {
        assertEquals(400, get(TOKEN, null).statusCode());
        assertEquals(400, get(TOKEN, "discord=").statusCode());
        assertEquals(400, get(TOKEN, "discord=123").statusCode());
        assertEquals(400, get(TOKEN, "discord=abcdefghijklmnopqr").statusCode());
        assertEquals(400, get(TOKEN, "discord=" + "1".repeat(21)).statusCode());

        assertEquals(400, post(TOKEN, "{").statusCode());
        assertEquals(400, post(TOKEN, "[]").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"refund\",\"numbers\":[1]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"123\",\"op\":\"bid\",\"numbers\":[1,2]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\"}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\",\"numbers\":[1]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\",\"numbers\":[1,2,3]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\",\"numbers\":[1,\"2\"]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\",\"numbers\":[1,2.5]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"bid\",\"numbers\":[1,99999999999]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"sell\",\"numbers\":[1,2,3]}").statusCode());
        assertEquals(400, post(TOKEN, "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[]}").statusCode());

        String oversized = "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[1],\"pad\":\""
                + "a".repeat(5000) + "\"}";
        assertEquals(400, post(TOKEN, oversized).statusCode());

        // 不正な入力ではメインスレッドの処理を呼びません。
        org.mockito.Mockito.verify(plugin, org.mockito.Mockito.never())
                .tradeOperation(anyString(), anyString(), any(int[].class), any());
    }

    @Test void returns503WhenLockedDownOrStopped() throws Exception {
        query = id -> null;
        operation = (id, op, numbers) -> null;

        HttpResponse<String> unavailableGet = get(TOKEN, "discord=" + DISCORD_ID);
        assertEquals(503, unavailableGet.statusCode());
        assertEquals("unavailable", json(unavailableGet).get("error").asText());

        HttpResponse<String> unavailablePost = post(TOKEN,
                "{\"discord\":\"" + DISCORD_ID + "\",\"op\":\"cancel\",\"numbers\":[1]}");
        assertEquals(503, unavailablePost.statusCode());
        assertEquals("unavailable", json(unavailablePost).get("error").asText());

        // メインスレッドへ渡せない（停止中）ときも503にします。
        TradeApiServer stopped = new TradeApiServer(plugin, TOKEN, 0, task -> {
            throw new IllegalStateException("plugin disabled");
        });
        stopped.start();
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + stopped.port() + "/v1/trade?discord=" + DISCORD_ID))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(503, response.statusCode());
        } finally {
            stopped.stop();
        }
    }
}
