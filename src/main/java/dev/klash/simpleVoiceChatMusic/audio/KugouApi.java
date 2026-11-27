package dev.klash.simpleVoiceChatMusic.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 酷狗音乐 / 酷狗概念版接口。
 *
 * <p>搜索走 {@code songsearch.kugou.com}；播放地址走 {@code trackercdn.kugou.com/v5/url}，
 * 需要先用 {@code userservice.kugou.com/risk/v3/r_register_dev} 注册设备拿到 dfid；
 * 扫码登录走 {@code login-user.kugou.com/v2/qrcode} + {@code v2/get_userinfo_qrcode}。</p>
 */
public final class KugouApi {

    public record Song(String identifier, String title, String artist, long durationMs, boolean vip) {
        public String displayName() {
            return artist == null || artist.isBlank() ? title : artist + " - " + title;
        }
    }

    public record Qr(String key, String url) {
    }

    public record QrPoll(int status, @Nullable String token, @Nullable String userid, @Nullable String detail) {
        public boolean waiting() {
            return status == 1;
        }

        public boolean scanned() {
            return status == 2;
        }

        public boolean success() {
            return status == 4;
        }

        public boolean expired() {
            return status == 0;
        }

        public boolean failed() {
            return status != 0 && status != 1 && status != 2 && status != 4;
        }
    }

    public enum Platform {
        STANDARD("kugou", 1005, 1005, 20489, 3116, 11440,
                "OIlwieks28dk2k092lksi2UIkp", "57ae12eb6890223e355ccfcb74edf70d",
                "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB",
                151369488, 2, "463467626,350369493,788954147"),
        CONCEPT("kugouconcept", 3116, 3116, 11440, 3116, 11440,
                "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA", "185672dd44712f60bb1736df5a377e82",
                "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB",
                967177915, 411, "356753938,823673182,967485191");

        final String id;
        final int qrAppid;
        final int appid;
        final int clientver;
        final int liteAppid;
        final int liteClientver;
        final String signSalt;
        final String keySalt;
        final String publicKey;
        final int pageId;
        final int pid;
        final String ppageId;

        Platform(String id, int qrAppid, int appid, int clientver, int liteAppid, int liteClientver,
                 String signSalt, String keySalt, String publicKey, int pageId, int pid, String ppageId) {
            this.id = id;
            this.qrAppid = qrAppid;
            this.appid = appid;
            this.clientver = clientver;
            this.liteAppid = liteAppid;
            this.liteClientver = liteClientver;
            this.signSalt = signSalt;
            this.keySalt = keySalt;
            this.publicKey = publicKey;
            this.pageId = pageId;
            this.pid = pid;
            this.ppageId = ppageId;
        }

        public static Platform parse(String raw) {
            String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "kugou", "kg", "酷狗" -> STANDARD;
                case "kugouconcept", "kugou-concept", "kgc", "酷狗概念版", "概念版" -> CONCEPT;
                default -> null;
            };
        }
    }

    /** 设备注册后缓存的 dfid / mid */
    private static final Map<String, String[]> DEVICE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 搜索结果的元数据（用于聊天栏显示歌名/作者） */
    private static final Map<String, Song> META = new java.util.concurrent.ConcurrentHashMap<>();

    /** 酷狗歌词缓存：null 表示还没取 */
    private static final Map<String, List<NetEaseApi.LyricLine>> LYRICS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> FETCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 登录后的 Cookie（token/userid） */
    private static final Map<String, String> COOKIE = new java.util.concurrent.ConcurrentHashMap<>();

    private KugouApi() {
    }

    // ---------------------------------------------------------------- search

    public static List<Song> search(String keyword, int limit, int page) throws IOException {
        return search(keyword, limit, page, Platform.STANDARD);
    }

    public static List<Song> search(String keyword, int limit, int page, Platform platform) throws IOException {
        String url = "https://songsearch.kugou.com/song_search_v2?keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&page=" + Math.max(1, page)
                + "&pagesize=" + Math.max(1, limit)
                + "&platform=WebFilter&userid=-1&clientver=2000&iscorrection=1&privilege_filter=0";

        String body = httpGet(url, null);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject data = objectOf(root, "data");
        JsonArray lists = arrayOf(data, "lists");
        List<Song> songs = new ArrayList<>();

        if (lists == null) {
            return songs;
        }

        for (JsonElement element : lists) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String hash = stringOf(item, "FileHash");
            String title = stringOf(item, "SongName");
            if (hash.isEmpty() || title.isEmpty()) {
                continue;
            }
            long albumId = longOf(item, "AlbumID");
            long audioId = longOf(item, "AlbumAudioID");
            long duration = longOf(item, "Duration") * 1000L;
            String scheme = platform == Platform.CONCEPT ? "kugouconcept://" : "kugou://";
            boolean vip = longOf(item, "PayType") == 3 || longOf(item, "Price") > 0;
            Song song = new Song(scheme + hash + "/" + albumId + "/" + audioId,
                    title, stringOf(item, "SingerName"), duration, vip);
            META.put(song.identifier(), song);
            songs.add(song);
        }

        return songs;
    }

    @Nullable
    public static Song metaFor(String identifier) {
        return identifier == null ? null : META.get(identifier);
    }

    /**
     * 酷狗歌词（LRC），null 表示正在异步获取。
     */
    @Nullable
    public static List<NetEaseApi.LyricLine> lyricsFor(String identifier) {
        return lyricsFor(identifier, 0L);
    }

    /**
     * 酷狗歌词（LRC），null 表示正在异步获取。
     *
     * <p>会按歌曲时长挑选最匹配的歌词候选，避免拿到现场版/片段版导致不同步。</p>
     */
    @Nullable
    public static List<NetEaseApi.LyricLine> lyricsFor(String identifier, long durationMs) {
        List<NetEaseApi.LyricLine> cached = LYRICS.get(identifier);

        if (cached != null) {
            return cached;
        }

        if (FETCHING.add(identifier)) {
            Thread thread = new Thread(() -> {
                try {
                    String hash = identifier
                            .replace("kugouconcept://", "")
                            .replace("kugou://", "")
                            .split("/")[0];

                    String search = httpGet("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi"
                            + "&keyword=&duration=" + (durationMs > 0 ? durationMs : "")
                            + "&hash=" + urlEncode(hash), null);
                    JsonObject root = JsonParser.parseString(search).getAsJsonObject();
                    JsonArray candidates = arrayOf(root, "candidates");

                    if (candidates == null || candidates.isEmpty()) {
                        LYRICS.put(identifier, List.of());
                        return;
                    }

                    java.util.List<JsonObject> ordered = new java.util.ArrayList<>();

                    for (JsonElement element : candidates) {
                        if (element.isJsonObject()) {
                            ordered.add(element.getAsJsonObject());
                        }
                    }

                    if (durationMs > 0) {
                        ordered.sort(java.util.Comparator.comparingLong(candidate ->
                                Math.abs(longOf(candidate, "duration") - durationMs)));
                    }

                    List<NetEaseApi.LyricLine> best = List.of();

                    for (int i = 0; i < Math.min(3, ordered.size()); i++) {
                        JsonObject candidate = ordered.get(i);
                        String id = stringOf(candidate, "id");
                        String accesskey = stringOf(candidate, "accesskey");

                        if (id.isEmpty() || accesskey.isEmpty()) {
                            continue;
                        }

                        String download = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id="
                                + urlEncode(id) + "&accesskey=" + urlEncode(accesskey)
                                + "&fmt=lrc&charset=utf8", null);
                        JsonObject lyricRoot = JsonParser.parseString(download).getAsJsonObject();
                        String content = stringOf(lyricRoot, "content");

                        if (content.isEmpty()) {
                            continue;
                        }

                        String lrc = new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
                        List<NetEaseApi.LyricLine> parsed = NetEaseApi.parseLyric(lrc);

                        if (parsed.size() > best.size()) {
                            best = parsed;
                        }

                        if (best.size() >= 8) {
                            break;
                        }
                    }

                    LYRICS.put(identifier, best);
                } catch (Exception e) {
                    LYRICS.put(identifier, List.of());
                } finally {
                    FETCHING.remove(identifier);
                }
            }, "SVC-Music-KugouLyrics");
            thread.setDaemon(true);
            thread.start();
        }

        return null;
    }

    // ------------------------------------------------------------------ play

    @Nullable
    public static String songUrl(String identifier) throws IOException {
        boolean concept = identifier.startsWith("kugouconcept://") || identifier.startsWith("kgc://");
        Platform platform = concept ? Platform.CONCEPT : Platform.STANDARD;
        String clean = identifier.replace("kugouconcept://", "").replace("kugou://", "").replace("kgc://", "");
        String[] parts = clean.split("/");
        String hash = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";
        long albumId = parts.length > 1 ? parseLong(parts[1]) : 0L;
        long audioId = parts.length > 2 ? parseLong(parts[2]) : 0L;

        if (hash.isEmpty()) {
            return null;
        }

        String[] device = device(platform);
        String dfid = device[0];
        String mid = device[1];
        long clienttime = System.currentTimeMillis() / 1000L;
        String userid = cookieValue(platform, "userid", "0");
        String token = cookieValue(platform, "token", "");

        // 登录后 token 属于扫码时的 appid（1001/3116），取流必须用同一个 appid，否则 20018
        int playAppid = platform.appid;
        String key = md5(hash + platform.keySalt + playAppid + mid + userid);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("album_id", Long.toString(albumId));
        params.put("area_code", "1");
        params.put("hash", hash);
        params.put("ssa_flag", "is_fromtrack");
        params.put("version", "11430");
        params.put("page_id", Integer.toString(platform.pageId));
        params.put("quality", "128");
        params.put("album_audio_id", Long.toString(audioId));
        params.put("behavior", "play");
        params.put("pid", Integer.toString(platform.pid));
        params.put("cmd", "26");
        params.put("pidversion", "3001");
        params.put("IsFreePart", "0");
        params.put("ppage_id", platform.ppageId);
        params.put("cdnBackup", "1");
        params.put("module", "");
        params.put("clientver", "11430");
        params.put("appid", Integer.toString(playAppid));
        params.put("dfid", dfid);
        params.put("mid", mid);
        params.put("uuid", "-");
        params.put("clienttime", Long.toString(clienttime));
        params.put("key", key);

        if (!token.isEmpty()) {
            params.put("token", token);
            params.put("userid", userid);
        }

        params.put("signature", androidSignature(params, "", platform.signSalt));

        String url = "https://trackercdn.kugou.com/v5/url?" + query(params);
        String cookieHeader = token.isEmpty() ? "" : "token=" + token + "; userid=" + userid;
        String body = httpGet(url, headers(dfid, mid, clienttime, cookieHeader));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray urls = arrayOf(root, "url");

        if (urls == null || urls.isEmpty()) {
            log("v5/url empty for " + identifier + " [token=" + (token.isEmpty() ? "none" : "set")
                    + ", userid=" + userid + ", appid=" + playAppid + "]: " + preview(body));

            String fallback = webPlayUrl(platform, playAppid, hash, albumId, dfid, mid, userid, token);
            if (fallback != null) {
                return fallback;
            }
            return null;
        }

        String best = null;
        for (JsonElement element : urls) {
            String candidate = element.getAsString();
            if (candidate != null && !candidate.isBlank()) {
                best = candidate;
            }
        }

        return best;
    }

    // ------------------------------------------------------------------- qr

    public static Qr qrKey(Platform platform) throws IOException {
        String[] device = device(platform);
        long clienttime = System.currentTimeMillis() / 1000L;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("appid", Integer.toString(platform.qrAppid));
        params.put("clientver", Integer.toString(platform.clientver));
        params.put("plat", "4");
        params.put("type", "1");
        params.put("srcappid", "2919");
        params.put("qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid="
                + platform.qrAppid + "&");
        params.put("dfid", device[0]);
        params.put("uuid", "-");
        params.put("mid", device[1]);
        params.put("clienttime", Long.toString(clienttime));
        params.put("signature", webSignature(params));

        String body = httpGet("https://login-user.kugou.com/v2/qrcode?" + query(params),
                headers(device[0], device[1], clienttime));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String key = stringOf(objectOf(root, "data"), "qrcode");

        if (key.isEmpty()) {
            throw new IOException("Kugou qrKey failed: " + preview(body));
        }

        return new Qr(key, "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" + key);
    }

    public static QrPoll qrPoll(Platform platform, String key) throws IOException {
        String[] device = device(platform);
        long clienttime = System.currentTimeMillis() / 1000L;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("appid", Integer.toString(platform.qrAppid));
        params.put("clientver", Integer.toString(platform.clientver));
        params.put("plat", "4");
        params.put("srcappid", "2919");
        params.put("qrcode", key);
        params.put("dfid", device[0]);
        params.put("uuid", "-");
        params.put("mid", device[1]);
        params.put("clienttime", Long.toString(clienttime));
        params.put("signature", webSignature(params));

        String body = httpGet("https://login-user.kugou.com/v2/get_userinfo_qrcode?" + query(params),
                headers(device[0], device[1], clienttime));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject data = objectOf(root, "data");
        int status = (int) longOf(data, "status");

        if (status == 4) {
            String token = stringOf(data, "token");
            String userid = stringOf(data, "userid");
            return new QrPoll(4, token, userid, null);
        }

        return new QrPoll(status, null, null, preview(body));
    }

    // --------------------------------------------------------------- device

    private static synchronized String[] device(Platform platform) throws IOException {
        String[] cached = DEVICE.get(platform.id);
        if (cached != null) {
            return cached;
        }

        String[] stored = loadDevice(platform);
        if (stored != null) {
            DEVICE.put(platform.id, stored);
            return stored;
        }

        String guid = UUID.randomUUID().toString();
        String mid = new java.math.BigInteger(md5(guid), 16).toString();
        String key = randomKey();
        String aesKey = md5(key).substring(0, 16);
        String iv = md5(key).substring(16, 32);

        JsonObject data = new JsonObject();
        data.addProperty("availableRamSize", 4983533568L);
        data.addProperty("batteryLevel", 100);
        data.addProperty("brand", "Redmi");
        data.addProperty("device", "marble");
        data.addProperty("manufacturer", "Xiaomi");
        data.addProperty("uuid", guid);
        data.addProperty("imei", guid);

        String plain = data.toString();
        String encrypted = encryptAes(plain, aesKey, iv);
        String p = encryptRsa("{\"aes\":\"" + key + "\",\"uid\":0,\"token\":\"\"}", platform.publicKey);

        long clienttime = System.currentTimeMillis() / 1000L;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("part", "1");
        params.put("platid", "1");
        params.put("p", p);
        params.put("appid", Integer.toString(platform.appid));
        params.put("clientver", Integer.toString(platform.clientver));
        params.put("dfid", "-");
        params.put("uuid", "-");
        params.put("mid", mid);
        params.put("clienttime", Long.toString(clienttime));
        params.put("signature", androidSignature(params, encrypted, platform.signSalt));

        byte[] raw = httpPost("https://userservice.kugou.com/risk/v3/r_register_dev?" + query(params),
                encrypted, headers("-", mid, clienttime));

        String decrypted = decryptAes(Base64.getEncoder().encodeToString(raw), aesKey, iv);
        JsonObject root = JsonParser.parseString(decrypted).getAsJsonObject();
        String dfid = stringOf(objectOf(root, "data"), "dfid");

        if (dfid.isEmpty()) {
            // 风控/限流时重试一次（新的 guid）
            if (platform == Platform.STANDARD || platform == Platform.CONCEPT) {
                try {
                    DEVICE.remove(platform.id);
                    return registerRetry(platform);
                } catch (Exception ignored) {
                    // 继续走下面的失败处理
                }
            }

            if (platform == Platform.CONCEPT) {
                // 概念版注册偶发空结果时复用标准版设备（dfid 是设备级）
                return device(Platform.STANDARD);
            }
            throw new IOException("Kugou register_dev failed: " + preview(decrypted));
        }

        String[] result = {dfid, mid};
        DEVICE.put(platform.id, result);
        saveDevice(platform, result);
        if (SimpleVoiceChatMusic.LOGGER != null) {
            SimpleVoiceChatMusic.LOGGER.info("Kugou device registered (" + platform.id + ")");
        }
        return result;
    }

    /** 网页版取播放地址（v5/url 失败时兜底） */
    @Nullable
    private static String webPlayUrl(Platform platform, int playAppid, String hash, long albumId,
                                     String dfid, String mid, String userid, String token) {
        try {
            long clienttime = System.currentTimeMillis() / 1000L;
            Map<String, String> params = new LinkedHashMap<>();
            params.put("r", "play/getdata");
            params.put("hash", hash.toUpperCase(Locale.ROOT));
            params.put("album_id", Long.toString(albumId));
            params.put("mid", mid);
            params.put("dfid", dfid);
            params.put("platid", "4");
            params.put("appid", Integer.toString(playAppid));
            params.put("clientver", Integer.toString(platform.clientver));
            params.put("uuid", "-");
            params.put("clienttime", Long.toString(clienttime));
            params.put("signature", md5("NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt" + sortedParams(params)
                    + "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"));

            StringBuilder cookie = new StringBuilder("mid=" + mid + "; dfid=" + dfid);
            if (!token.isEmpty()) {
                cookie.append("; token=").append(token).append("; userid=").append(userid);
            }

            String body = httpGet("https://wwwapi.kugou.com/yy/index.php?" + query(params),
                    new okhttp3.Headers.Builder()
                            .add("User-Agent", "Mozilla/5.0")
                            .add("Cookie", cookie.toString())
                            .build());
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject data = objectOf(root, "data");
            String url = stringOf(data, "play_url");

            if (url.isEmpty()) {
                log("web play/getdata empty: " + preview(body));
                return null;
            }

            return url;
        } catch (Exception e) {
            log("web play/getdata failed: " + e.getMessage());
            return null;
        }
    }

    private static void log(String message) {
        if (SimpleVoiceChatMusic.LOGGER != null) {
            SimpleVoiceChatMusic.LOGGER.warning("[Kugou] " + message);
        }
    }

    private static String[] registerRetry(Platform platform) throws IOException {
        java.io.File file = deviceFile();
        if (file != null && file.isFile()) {
            String[] stored = loadDevice(platform);
            if (stored != null) {
                DEVICE.put(platform.id, stored);
                return stored;
            }
        }
        throw new IOException("Kugou device registration rate limited");
    }

    private static java.io.File deviceFile() {
        try {
            return new java.io.File(SimpleVoiceChatMusic.get().getDataFolder(), "kugou-device.json");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String[] loadDevice(Platform platform) {
        java.io.File file = deviceFile();

        if (file == null || !file.isFile()) {
            return null;
        }

        try {
            String json = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject entry = objectOf(root, platform.id);

            if (entry == null) {
                return null;
            }

            String dfid = stringOf(entry, "dfid");
            String mid = stringOf(entry, "mid");

            if (dfid.isEmpty() || mid.isEmpty()) {
                return null;
            }

            return new String[]{dfid, mid};
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveDevice(Platform platform, String[] value) {
        java.io.File file = deviceFile();

        if (file == null) {
            return;
        }

        try {
            JsonObject root = new JsonObject();

            if (file.isFile()) {
                root = JsonParser.parseString(java.nio.file.Files.readString(file.toPath(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("dfid", value[0]);
            entry.addProperty("mid", value[1]);
            root.add(platform.id, entry);

            if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
                file.getParentFile().mkdirs();
            }

            java.nio.file.Files.writeString(file.toPath(), root.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 缓存失败不影响使用
        }
    }

    // ---------------------------------------------------------------- login store

    public static void setCookie(Platform platform, String cookie) {
        COOKIE.put(platform.id, cookie == null ? "" : cookie);
    }

    public static String cookie(Platform platform) {
        return COOKIE.getOrDefault(platform.id, "");
    }

    private static String cookieValue(Platform platform, String name, String fallback) {
        String cookie = cookie(platform);

        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && trimmed.substring(0, equals).equalsIgnoreCase(name)) {
                return trimmed.substring(equals + 1);
            }
        }

        return fallback;
    }

    // ----------------------------------------------------------------- helpers

    private static String webSignature(Map<String, String> params) {
        String salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
        return md5(salt + sortedParams(params) + salt);
    }

    private static String androidSignature(Map<String, String> params, String data, String salt) {
        return md5(salt + sortedParams(params) + (data == null ? "" : data) + salt);
    }

    private static String sortedParams(Map<String, String> params) {
        List<String> entries = new ArrayList<>();
        params.forEach((key, value) -> entries.add(key + "=" + value));
        entries.sort(String::compareTo);
        return String.join("", entries);
    }

    private static String query(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        params.forEach((key, value) -> {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(urlEncode(key)).append('=').append(urlEncode(value));
        });
        return builder.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static okhttp3.Headers headers(String dfid, String mid, long clienttime) {
        return headers(dfid, mid, clienttime, "");
    }

    private static okhttp3.Headers headers(String dfid, String mid, long clienttime, String cookie) {
        okhttp3.Headers.Builder builder = new okhttp3.Headers.Builder()
                .add("User-Agent", "Mozilla/5.0")
                .add("dfid", dfid)
                .add("mid", mid)
                .add("clienttime", Long.toString(clienttime))
                .add("x-router", "trackercdn.kugou.com");

        if (cookie != null && !cookie.isBlank()) {
            builder.add("Cookie", cookie);
        }

        return builder.build();
    }

    private static String httpGet(String url, @Nullable okhttp3.Headers headers) throws IOException {
        OkHttpClient client = client();
        Request.Builder builder = new Request.Builder().url(url).get();
        if (headers != null) {
            builder.headers(headers);
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            return body == null ? "" : body.string();
        }
    }

    private static byte[] httpPost(String url, String body, okhttp3.Headers headers) throws IOException {
        OkHttpClient client = client();
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8),
                        okhttp3.MediaType.parse("text/plain;charset=UTF-8")))
                .headers(headers)
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            return responseBody == null ? new byte[0] : responseBody.bytes();
        }
    }

    private static volatile OkHttpClient HTTP;

    private static OkHttpClient client() {
        if (HTTP == null) {
            synchronized (KugouApi.class) {
                if (HTTP == null) {
                    PluginSettings settings = PluginSettings.get();
                    long connect = settings == null ? 8000 : settings.httpConnectTimeoutMs();
                    long read = settings == null ? 15000 : settings.httpReadTimeoutMs();
                    HTTP = new OkHttpClient.Builder()
                            .connectTimeout(connect, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .readTimeout(read, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build();
                }
            }
        }
        return HTTP;
    }

    private static String encryptAes(String text, String key, String iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static String decryptAes(String base64, String key, String iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(base64)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static String encryptRsa(String text, String base64Key) throws IOException {
        try {
            byte[] der = Base64.getDecoder().decode(base64Key);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return hex(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return hex(digest);
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static String randomKey() {
        String chars = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder builder = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            builder.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return builder.toString();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static JsonObject objectOf(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonArray arrayOf(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(key);
    }

    private static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long longOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String preview(String body) {
        return body == null || body.length() <= 200 ? body : body.substring(0, 200);
    }
}
