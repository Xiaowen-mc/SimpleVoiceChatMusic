package dev.klash.simpleVoiceChatMusic.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import de.maxhenkel.voicechat.api.Group;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内置网页收听服务。
 *
 * <p>给基岩/网页玩家提供只读收听：房间对应 SVC 语音组，音频直接 302 到当前歌曲的直链。</p>
 *
 * <pre>
 * GET /              网页播放器
 * GET /api/rooms     房间列表
 * GET /api/now       当前歌曲（?room=组id）
 * GET /media         302 到当前歌曲音频（?room=组id）
 * </pre>
 */
public final class WebPlayerServer {

    /** 每个房间的播放实例：{ identHash, startedAt, lastPosition } */
    private static final Map<String, long[]> PLAYBACK = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile HttpServer server;
    private static volatile String publicUrl = "";
    private static volatile String accessToken = "";
    private static volatile int sessionMinutes = 120;
    private static volatile byte[] sessionSecret = new byte[32];
    private static volatile boolean secureCookies = false;
    private static volatile String startError = "";

    private WebPlayerServer() {
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static String publicUrl() {
        return publicUrl;
    }

    public static String startError() {
        return startError;
    }

    public static void start(SimpleVoiceChatMusic plugin) {
        if (server != null) {
            return;
        }

        if (!plugin.getConfig().getBoolean("web.enabled", true)) {
            startError = "web.enabled=false";
            plugin.getLogger().info("Web player disabled by config (web.enabled=false)");
            return;
        }

        int port = plugin.getConfig().getInt("web.port", 14514);
        plugin.getLogger().info("Web player config: enabled=true, port=" + port
                + ", ssl=" + plugin.getConfig().getBoolean("web.ssl.enabled", false));
        accessToken = plugin.getConfig().getString("web.token", "");
        accessToken = accessToken == null ? "" : accessToken.trim();

        if (accessToken.isEmpty()) {
            // 默认开启防盗：自动生成并写回配置
            byte[] random = new byte[16];
            new java.security.SecureRandom().nextBytes(random);
            accessToken = java.util.HexFormat.of().formatHex(random);
            plugin.getConfig().set("web.token", accessToken);
            plugin.saveConfig();
            plugin.getLogger().info("Generated web access token: " + accessToken);
        }
        sessionMinutes = Math.max(5, plugin.getConfig().getInt("web.session-minutes", 120));
        secureCookies = plugin.getConfig().getBoolean("web.ssl.enabled", false);
        new java.security.SecureRandom().nextBytes(sessionSecret);
        String configured = plugin.getConfig().getString("web.public-url", "");

        if (configured != null && !configured.isBlank()) {
            publicUrl = configured.trim();
        } else {
            String host = plugin.getServer().getIp();

            if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
                try {
                    host = java.net.InetAddress.getLocalHost().getHostAddress();
                } catch (Exception e) {
                    host = "127.0.0.1";
                }
            }

            publicUrl = (secureCookies ? "https://" : "http://") + host + ":" + port;
            plugin.getLogger().warning("web.public-url is empty, using " + publicUrl
                    + " (set web.public-url for a public domain/IP)");
        }

        while (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }

        try {
            HttpServer created;

            if (secureCookies) {
                created = createHttpsServer(plugin, port);
            } else {
                created = HttpServer.create(new InetSocketAddress(port), 0);
            }
            created.createContext("/", WebPlayerServer::handle);
            created.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "SVCMusicWeb");
                thread.setDaemon(true);
                return thread;
            }));
            created.start();
            server = created;
            plugin.getLogger().info("Web player listening on " + publicUrl
                    + (accessToken.isEmpty() ? " (no token)" : " (token required)"));
        } catch (Exception e) {
            startError = e.getClass().getSimpleName() + ": " + e.getMessage();
            plugin.getLogger().warning("Could not start web player: " + startError);
        }
    }

    public static void stop() {
        HttpServer current = server;
        server = null;

        if (current != null) {
            current.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws java.io.IOException {
        try {
            securityHeaders(exchange);

            if (!accessToken.isEmpty() && !authorized(exchange)) {
                deny(exchange);
                return;
            }

            String path = exchange.getRequestURI().getPath();

            switch (path) {
                case "/", "/index.html" -> writeText(exchange, 200, "text/html; charset=utf-8", page());
                case "/api/rooms" -> writeText(exchange, 200, "application/json; charset=utf-8", rooms());
                case "/api/now" -> writeText(exchange, 200, "application/json; charset=utf-8",
                        now(query(exchange).get("room")));
                case "/api/default" -> writeText(exchange, 200, "application/json; charset=utf-8",
                        defaultInfo());
                case "/media" -> media(exchange);
                default -> writeText(exchange, 404, "text/plain; charset=utf-8", "not found");
            }
        } catch (Exception e) {
            writeText(exchange, 500, "application/json; charset=utf-8",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static HttpServer createHttpsServer(SimpleVoiceChatMusic plugin, int port) throws Exception {
        String keystorePath = plugin.getConfig().getString("web.ssl.keystore", "");
        String password = plugin.getConfig().getString("web.ssl.password", "");

        if (keystorePath == null || keystorePath.isBlank()) {
            throw new IllegalStateException("web.ssl.enabled=true but web.ssl.keystore is empty");
        }

        java.io.File file = new java.io.File(keystorePath);

        if (!file.isAbsolute()) {
            file = new java.io.File(plugin.getDataFolder(), keystorePath);
        }

        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            keyStore.load(in, password == null ? new char[0] : password.toCharArray());
        }

        javax.net.ssl.KeyManagerFactory factory =
                javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password == null ? new char[0] : password.toCharArray());

        javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);

        com.sun.net.httpserver.HttpsServer https =
                com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(port), 0);
        https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(context));
        return https;
    }

    private static void securityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add("Referrer-Policy", "no-referrer");
        headers.add("X-Frame-Options", "DENY");
        headers.add("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src *");
    }

    private static boolean authorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();

        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("token=")) {
                    String value = java.net.URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);

                    if (java.security.MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                            accessToken.getBytes(StandardCharsets.UTF_8))) {
                        issueCookie(exchange);
                        return true;
                    }
                }
            }
        }

        String cookie = exchange.getRequestHeaders().getFirst("Cookie");

        if (cookie == null) {
            return false;
        }

        for (String part : cookie.split(";")) {
            String trimmed = part.trim();

            if (trimmed.startsWith("svcm_session=")) {
                return verifySession(trimmed.substring("svcm_session=".length()));
            }
        }

        return false;
    }

    private static void issueCookie(HttpExchange exchange) {
        long expires = System.currentTimeMillis() + sessionMinutes * 60_000L;
        String value = expires + "." + sign(String.valueOf(expires));
        String cookie = "svcm_session=" + value + "; Path=/; Max-Age=" + (sessionMinutes * 60)
                + "; HttpOnly; SameSite=Lax" + (secureCookies ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private static boolean verifySession(String value) {
        int dot = value.indexOf('.');

        if (dot <= 0) {
            return false;
        }

        try {
            long expires = Long.parseLong(value.substring(0, dot));
            String sign = value.substring(dot + 1);

            if (expires < System.currentTimeMillis()) {
                return false;
            }

            return java.security.MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8),
                    sign(String.valueOf(expires)).getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String sign(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(sessionSecret, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static void deny(HttpExchange exchange) throws java.io.IOException {
        writeText(exchange, 401, "text/html; charset=utf-8",
                "<!doctype html><meta charset=utf-8><title>需要访问令牌</title>"
                        + "<body style='font-family:system-ui;background:#0b1020;color:#e6f2ff;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh'>"
                        + "<div style='text-align:center'><h2>🔒 需要访问令牌</h2>"
                        + "<p style='color:#8aa2c2'>请使用带 <code>?token=...</code> 的完整链接打开本页</p>"
                        + "<p style='color:#5f7290;font-size:13px'>令牌由服主在 config.yml 的 web.token 设置</p></div>");
    }

    /** 生成带令牌的收听链接 */
    public static String link(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        String base = publicUrl + normalized;

        if (accessToken.isEmpty()) {
            return base;
        }

        return base + (base.contains("?") ? "&" : "?") + "token="
                + java.net.URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
    }

    private static String defaultInfo() {
        Group group = VoiceGroups.defaultGroup();

        if (group == null) {
            return "{\"error\":\"no default group\"}";
        }

        return now(VoiceGroups.roomId(group));
    }

    private static String rooms() {
        JsonArray array = new JsonArray();

        for (Group group : VoiceGroups.all()) {
            GroupManager manager = VoiceGroups.managerOf(group);
            AudioTrack track = manager == null ? null : manager.getPlayer().getPlayingTrack();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", VoiceGroups.roomId(group));
            entry.addProperty("name", group.getName());
            entry.addProperty("playing", track != null);
            String[] display = track == null ? new String[]{"", ""} : display(track);
            entry.addProperty("title", display[0]);
            entry.addProperty("author", display[1]);
            array.add(entry);
        }

        JsonObject root = new JsonObject();
        root.add("rooms", array);
        return root.toString();
    }

    private static String now(String roomId) {
        GroupManager manager = find(roomId);
        Group group = VoiceGroups.find(roomId);

        if (group == null) {
            return "{\"error\":\"room not found\"}";
        }

        if (manager == null) {
            JsonObject idle = new JsonObject();
            idle.addProperty("id", VoiceGroups.roomId(group));
            idle.addProperty("name", group.getName());
            idle.addProperty("playing", false);
            idle.addProperty("paused", false);
            idle.addProperty("positionMs", 0);
            idle.addProperty("durationMs", 0);
            return idle.toString();
        }

        AudioTrack track = manager.getPlayer().getPlayingTrack();
        JsonObject root = new JsonObject();
        root.addProperty("id", VoiceGroups.roomId(manager.getGroup()));
        root.addProperty("name", manager.getGroup().getName());
        root.addProperty("playing", track != null);
        root.addProperty("paused", manager.getPlayer().isPaused());
        root.addProperty("positionMs", track == null ? 0 : track.getPosition());
        root.addProperty("durationMs", track == null ? 0 : track.getDuration());

        if (track != null) {
            String[] display = display(track);
            root.addProperty("title", display[0]);
            root.addProperty("author", display[1]);
            String uri = track.getInfo().uri;
            root.addProperty("direct", uri != null
                    && (uri.startsWith("http://") || uri.startsWith("https://")));
            // 每次播放实例都不同，用于网页判断“换歌/重播”
            root.addProperty("startedAt", startedAt(manager.getGroup().getId().toString(), track));
        }

        return root.toString();
    }

    private static void media(HttpExchange exchange) throws java.io.IOException {
        GroupManager manager = find(query(exchange).get("room"));
        AudioTrack track = manager == null ? null : manager.getPlayer().getPlayingTrack();

        if (track == null || track.getInfo().uri == null) {
            writeText(exchange, 404, "text/plain; charset=utf-8", "no track");
            return;
        }

        exchange.getResponseHeaders().add("Location", track.getInfo().uri);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /** 取显示用歌名/作者：lavaplayer 只知道直链，真实信息在 TrackMetadata */
    private static String[] display(AudioTrack track) {
        String title = track.getInfo().title;
        String author = track.getInfo().author;

        if (bad(title)) {
            var meta = dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.get(track.getInfo().uri);

            if (meta == null) {
                meta = dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.get(track.getIdentifier());
            }

            if (meta != null) {
                title = meta.title();
                author = meta.author();
            }
        }

        if (bad(title)) {
            title = "未知歌曲";
        }

        if (author == null || author.isBlank() || author.equalsIgnoreCase("unknown artist")) {
            author = "";
        }

        return new String[]{title, author};
    }

    private static boolean bad(String value) {
        return value == null || value.isBlank()
                || value.equalsIgnoreCase("unknown title")
                || value.equalsIgnoreCase("unknown");
    }

    /** 同一首歌的 startedAt 保持稳定；换歌或重播（位置回跳）时才更新 */
    private static long startedAt(String room, AudioTrack track) {
        long now = System.currentTimeMillis();
        long position = Math.max(0, track.getPosition());
        long ident = (track.getIdentifier() + "|" + track.getInfo().uri).hashCode();
        long[] state = PLAYBACK.get(room);

        if (state == null || state[0] != ident || position + 2000 < state[2]) {
            state = new long[]{ident, now - position, position};
            PLAYBACK.put(room, state);
        } else {
            state[2] = position;
        }

        return state[1];
    }

    private static GroupManager find(String roomId) {
        Group group = VoiceGroups.find(roomId);
        return group == null ? null : VoiceGroups.managerOf(group);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();

        if (raw == null) {
            return result;
        }

        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');

            if (equals > 0) {
                result.put(java.net.URLDecoder.decode(part.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }

        return result;
    }

    private static void writeText(HttpExchange exchange, int status, String type, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String page() {
        try (InputStream in = WebPlayerServer.class.getResourceAsStream("/web/index.html")) {
            return in == null ? "web player missing" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "web player error: " + e.getMessage();
        }
    }
}
