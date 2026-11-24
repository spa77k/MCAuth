package com.example.mcauth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 連携・解除のたびに、サーバーログへ1行ずつ記録されることを確かめます。
class LinkEventLogTest {
    @TempDir Path directory;

    private final List<String> lines = new ArrayList<>();

    private Logger capturingLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { lines.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    private VerificationStore store() {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        when(owner.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new VerificationStore(owner);
    }

    private MCAuthPlugin plugin(VerificationStore store) throws Exception {
        MCAuthPlugin plugin = mock(MCAuthPlugin.class, CALLS_REAL_METHODS);
        doReturn(capturingLogger()).when(plugin).getLogger();
        set(plugin, "store", store);
        set(plugin, "pendingCodes", new HashMap<>());
        set(plugin, "pendingCodesByUuid", new HashMap<>());
        set(plugin, "verifiedMessage", "");
        return plugin;
    }

    private void set(MCAuthPlugin plugin, String name, Object value) throws Exception {
        var field = MCAuthPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private List<String> linkEvents() {
        return lines.stream().filter(line -> line.startsWith("Link event:")).toList();
    }

    @Test void formatsOneLineAndReplacesWhitespace() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String line = MCAuthPlugin.linkEventLine("unlink",
                new AuthenticatedPlayer(uuid, "Steve", "123", "bad\nname here"));
        assertEquals("Link event: reason=unlink player=Steve uuid=00000000-0000-0000-0000-000000000001"
                + " discord_id=123 discord_name=bad_name_here", line);
        assertEquals("Link event: reason=link player=- uuid=" + uuid + " discord_id=123 discord_name=-",
                MCAuthPlugin.linkEventLine("link", new AuthenticatedPlayer(uuid, "", "123", null)));
    }

    @Test void logsLinkOnlyWhenSaved() throws Exception {
        try (VerificationStore store = store()) {
            MCAuthPlugin plugin = plugin(store);
            UUID uuid = UUID.randomUUID();
            @SuppressWarnings("unchecked")
            var pending = (java.util.Map<String, PendingVerification>) field(plugin, "pendingCodes");
            pending.put("1234", new PendingVerification(uuid, "Steve", Instant.now().plusSeconds(60)));
            pending.put("5678", new PendingVerification(UUID.randomUUID(), "Alex", Instant.now().plusSeconds(60)));
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                        .when(scheduler).runTask(eq(plugin), any(Runnable.class));
                plugin.verifyCode("1234", "123", "alice", reply -> {});
                // 同じDiscordで別のMinecraftを連携しようとしても、連携ログは出しません。
                plugin.verifyCode("5678", "123", "alice", reply -> {});
            }
            assertEquals(List.of("Link event: reason=link player=Steve uuid=" + uuid
                    + " discord_id=123 discord_name=alice"), linkEvents());
        }
    }

    @Test void logsUnlinkLeftAndStartupCheckWithStoredNames() throws Exception {
        try (VerificationStore store = store()) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            store.authenticateIfAvailable(a, "Aaa", "1", "aaa");
            store.authenticateIfAvailable(b, "Bbb", "2", "bbb");
            store.authenticateIfAvailable(c, "Ccc", "3", "ccc");
            MCAuthPlugin plugin = plugin(store);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                        .when(scheduler).runTask(eq(plugin), any(Runnable.class));
                plugin.unlinkDiscordUser("1", reply -> {});
                plugin.revokeByDiscordUserId("2");
                plugin.reconcileDiscordMembership(id -> CompletableFuture.completedFuture(!id.equals("3")));
                // 未連携の人の解除はログに出しません。
                plugin.unlinkDiscordUser("1", reply -> {});
                plugin.revokeByDiscordUserId("999");
            }
            assertEquals(List.of(
                    "Link event: reason=unlink player=Aaa uuid=" + a + " discord_id=1 discord_name=aaa",
                    "Link event: reason=left player=Bbb uuid=" + b + " discord_id=2 discord_name=bbb",
                    "Link event: reason=startup-check player=Ccc uuid=" + c + " discord_id=3 discord_name=ccc"
            ), linkEvents());
        }
    }

    private Object field(MCAuthPlugin plugin, String name) throws Exception {
        var field = MCAuthPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(plugin);
    }
}
