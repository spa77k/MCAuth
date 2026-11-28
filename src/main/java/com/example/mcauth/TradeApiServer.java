package com.example.mcauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * プロフィールページからオークション・依頼を操作するための HTTP 窓口です。
 *
 * <p>呼び出し元（smp-api）は Bearer トークンを付け、GET で連携済みの一覧、POST で取引を依頼します。
 * 取引処理は Bukkit のメインスレッドで実行し、HTTP のスレッドは最大 5 秒だけ待ちます。</p>
 */
final class TradeApiServer {
    // トークンが短いと総当たりされやすいため、32文字以上を必須にします。
    static final int MIN_TOKEN_LENGTH = 32;

    private static final String ENDPOINT = "/v1/trade";
    private static final int MAX_BODY_BYTES = 4096;
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 5L;
    private static final Pattern DISCORD_ID = Pattern.compile("\\d{17,20}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MCAuthPlugin plugin;
    private final String token;
    private final byte[] tokenBytes;
    private final int port;
    private final MainThread mainThread;

    private HttpServer server;
    private ExecutorService executor;

    /** Bukkit のメインスレッドで処理を実行する手段です。テストでは同じスレッドで実行します。 */
    @FunctionalInterface
    interface MainThread {
        void run(Runnable task);
    }

    TradeApiServer(MCAuthPlugin plugin, String token, int port) {
        this(plugin, token, port, task -> Bukkit.getScheduler().runTask(plugin, task));
    }

    TradeApiServer(MCAuthPlugin plugin, String token, int port, MainThread mainThread) {
        this.plugin = plugin;
        this.token = token;
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        this.port = port;
        this.mainThread = mainThread;
    }

    /** 窓口を起動できる長さのトークンか。 */
    static boolean tokenLongEnough(String token) {
        return token != null && token.length() >= MIN_TOKEN_LENGTH;
    }

    void start() throws IOException {
        if (!tokenLongEnough(token)) {
            throw new IllegalArgumentException("Trade API token must be at least " + MIN_TOKEN_LENGTH + " characters");
        }
        if (server != null) {
            return;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        ExecutorService createdExecutor = Executors.newFixedThreadPool(4, runnable -> {
            // サーバー停止時に残らないよう、デーモンスレッドにします。
            Thread thread = new Thread(runnable, "mcauth-trade-api");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(createdExecutor);
        created.createContext(ENDPOINT, this::handle);
        created.start();
        server = created;
        executor = createdExecutor;
    }

    void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
    }

    /** 実際に待ち受けているポートです。起動前は -1 を返します。 */
    int port() {
        HttpServer current = server;
        return current == null ? -1 : current.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) {
        try {
            if (!authorized(exchange)) {
                send(exchange, 401, error("unauthorized"));
                return;
            }
            if (!ENDPOINT.equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, error("not_found"));
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "GET" -> handleGet(exchange);
                case "POST" -> handlePost(exchange);
                default -> send(exchange, 405, error("method_not_allowed"));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "取引窓口の処理に失敗しました", exception);
            try {
                send(exchange, 503, error("unavailable"));
            } catch (RuntimeException | IOException ignored) {
                // 応答を書けないほど壊れている場合は、接続を閉じるだけにします。
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "取引窓口の応答に失敗しました", exception);
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null || headers.size() != 1) {
            return false;
        }
        String value = headers.get(0).trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] provided = value.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        // 文字列比較ではなく定数時間比較にして、応答時間からトークンを推測されにくくします。
        return MessageDigest.isEqual(tokenBytes, provided);
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        String discordId = queryParameter(exchange.getRequestURI().getRawQuery(), "discord");
        if (discordId == null || !DISCORD_ID.matcher(discordId).matches()) {
            send(exchange, 400, error("invalid_request"));
            return;
        }
        Map<String, Object> view = queryOnMainThread(discordId);
        if (view == null) {
            send(exchange, 503, error("unavailable"));
            return;
        }
        send(exchange, 200, MAPPER.valueToTree(view));
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        JsonNode request = readBody(exchange);
        if (request == null) {
            send(exchange, 400, error("invalid_request"));
            return;
        }
        JsonNode discord = request.get("discord");
        JsonNode operation = request.get("op");
        if (discord == null || !discord.isTextual() || !DISCORD_ID.matcher(discord.asText()).matches()
                || operation == null || !operation.isTextual()) {
            send(exchange, 400, error("invalid_request"));
            return;
        }
        JsonNode numbersNode = request.get("numbers");
        if (numbersNode == null || !numbersNode.isArray()) {
            send(exchange, 400, error("invalid_request"));
            return;
        }
        int[] numbers = numbers(operation.asText(), numbersNode);
        if (numbers == null) {
            send(exchange, 400, error("invalid_request"));
            return;
        }
        Object result = operationOnMainThread(discord.asText(), operation.asText(), numbers);
        if (result == null) {
            send(exchange, 503, error("unavailable"));
            return;
        }
        String message = result instanceof String text ? text : "取引に失敗しました。時間をおいて再度お試しください。";
        ObjectNode response = MAPPER.createObjectNode();
        response.put("ok", isSuccess(message));
        response.put("message", message);
        send(exchange, 200, response);
    }

    /** 本文を読み、4KiB を超えるか JSON でなければ null を返します。 */
    private static JsonNode readBody(HttpExchange exchange) {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                return null;
            }
            JsonNode parsed = MAPPER.readTree(body);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /** op ごとに決まった個数の整数だけを受け付けます。合わなければ null を返します。 */
    static int[] numbers(String operation, JsonNode values) {
        int expected = switch (operation) {
            case "bid", "photo" -> 2;
            case "sell" -> 4;
            case "cancel", "accept" -> 1;
            default -> -1;
        };
        if (expected < 0 || values.size() != expected) {
            return null;
        }
        int[] numbers = new int[expected];
        for (int index = 0; index < expected; index++) {
            JsonNode value = values.get(index);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
                return null;
            }
            numbers[index] = value.asInt();
        }
        return numbers;
    }

    /**
     * 旧市場パネルと同じ判定です。
     * 「出品を取り消しました」で始まる応答だけを、取り消し成功として追加しています。
     */
    static boolean isSuccess(String message) {
        return message.equals(PhotoPrizes.PAID)
                || message.equals(PhotoPrizes.ALREADY_PAID)
                || message.startsWith("入札しました")
                || message.startsWith("即決価格で落札")
                || message.startsWith("出品しました")
                || message.startsWith("出品を取り消しました")
                || (message.startsWith("依頼「") && message.endsWith("」を受諾しました。"));
    }

    private Map<String, Object> queryOnMainThread(String discordId) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        if (!schedule(() -> plugin.tradeQuery(discordId, future::complete))) {
            return null;
        }
        try {
            return future.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            plugin.getLogger().warning("取引一覧の取得が5秒以内に終わりませんでした");
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "取引一覧の取得に失敗しました", exception);
            return null;
        }
    }

    private Object operationOnMainThread(String discordId, String operation, int[] numbers) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!schedule(() -> plugin.tradeOperation(discordId, operation, numbers, future::complete))) {
            return null;
        }
        try {
            // 応答に null を入れると「利用できない」の意味になるため、失敗時は文字列に置き換えます。
            return future.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            plugin.getLogger().warning("取引の処理が5秒以内に終わりませんでした");
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "取引の処理に失敗しました", exception);
            return "取引に失敗しました。時間をおいて再度お試しください。";
        }
    }

    private boolean schedule(Runnable task) {
        try {
            mainThread.run(task);
            return true;
        } catch (RuntimeException exception) {
            // プラグイン停止中など、メインスレッドへ渡せないときは利用不可として扱います。
            return false;
        }
    }

    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (!URLDecoder.decode(key, StandardCharsets.UTF_8).equals(name)) {
                continue;
            }
            return URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static ObjectNode error(String code) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", code);
        return node;
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
