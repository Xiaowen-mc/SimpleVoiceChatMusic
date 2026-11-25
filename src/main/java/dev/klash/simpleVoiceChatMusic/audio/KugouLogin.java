package dev.klash.simpleVoiceChatMusic.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酷狗 / 酷狗概念版登录凭证（token + userid）。
 */
public final class KugouLogin {

    public record Account(String cookie, String nickname, long savedAt) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<KugouApi.Platform, Account> ACCOUNTS = new ConcurrentHashMap<>();

    private KugouLogin() {
    }

    public static File file(KugouApi.Platform platform) {
        try {
            String name = platform == KugouApi.Platform.CONCEPT
                    ? "kugouconceptlogin.json"
                    : "kugoulogin.json";
            return new File(SimpleVoiceChatMusic.get().getDataFolder(), name);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void load() {
        for (KugouApi.Platform platform : KugouApi.Platform.values()) {
            File file = file(platform);

            if (file == null || !file.isFile()) {
                continue;
            }

            try {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                Account account = GSON.fromJson(json, Account.class);

                if (account != null && account.cookie() != null && account.cookie().contains("token=")) {
                    ACCOUNTS.put(platform, account);
                    KugouApi.setCookie(platform, account.cookie());
                    SimpleVoiceChatMusic.LOGGER.info("Loaded " + platform.id + " login.");
                }
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("Could not load " + platform.id + " login: " + e.getMessage());
            }
        }
    }

    public static void save(KugouApi.Platform platform, String cookie, @Nullable String nickname) {
        Account account = new Account(cookie, nickname == null ? "" : nickname, System.currentTimeMillis());
        ACCOUNTS.put(platform, account);
        KugouApi.setCookie(platform, cookie);

        try {
            File file = file(platform);

            if (file == null) {
                return;
            }

            if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
                file.getParentFile().mkdirs();
            }

            Files.writeString(file.toPath(), GSON.toJson(account), StandardCharsets.UTF_8);
            SimpleVoiceChatMusic.LOGGER.info("Saved " + platform.id + " login"
                    + (account.nickname().isBlank() ? "" : " for " + account.nickname()));
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not save " + platform.id + " login: " + e.getMessage());
        }
    }

    public static void clear(KugouApi.Platform platform) {
        ACCOUNTS.remove(platform);
        KugouApi.setCookie(platform, "");

        try {
            File file = file(platform);

            if (file != null) {
                Files.deleteIfExists(file.toPath());
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not delete " + platform.id + " login: " + e.getMessage());
        }
    }

    public static boolean isLoggedIn(KugouApi.Platform platform) {
        return ACCOUNTS.containsKey(platform);
    }

    public static String displayName(KugouApi.Platform platform) {
        Account account = ACCOUNTS.get(platform);

        if (account == null || account.nickname().isBlank()) {
            return platform.id;
        }

        return account.nickname();
    }
}
