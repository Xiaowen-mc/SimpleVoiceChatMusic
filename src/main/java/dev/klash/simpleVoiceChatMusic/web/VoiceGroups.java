package dev.klash.simpleVoiceChatMusic.web;

import de.maxhenkel.voicechat.api.Group;
import dev.klash.simpleVoiceChatMusic.VoiceChatPlugin;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语音组枚举：优先用 SVC API 的全部组（包含尚未播放过的持久组）。
 */
public final class VoiceGroups {

    private VoiceGroups() {
    }

    public static List<Group> all() {
        List<Group> groups = new ArrayList<>();

        if (VoiceChatPlugin.voicechatServerApi != null) {
            try {
                groups.addAll(VoiceChatPlugin.voicechatServerApi.getGroups());
            } catch (Exception ignored) {
                // 回退到已加载的播放组
            }
        }

        if (groups.isEmpty()) {
            for (GroupManager manager : MusicManager.getInstance().groups()) {
                groups.add(manager.getGroup());
            }
        }

        return groups;
    }

    public static Group find(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String needle = token.trim().toLowerCase(Locale.ROOT);

        if (needle.equals("music") || needle.equals("default") || needle.equals("默认")) {
            return defaultGroup();
        }

        while (needle.startsWith("=")) {
            needle = needle.substring(1);
        }

        for (Group group : all()) {
            String id = group.getId().toString().toLowerCase(Locale.ROOT);

            while (id.startsWith("=")) {
                id = id.substring(1);
            }

            if (id.equals(needle)
                    || group.getName() != null && group.getName().toLowerCase(Locale.ROOT).equals(needle)) {
                return group;
            }
        }

        return null;
    }

    /** 默认语音组（config: music.default-group，默认「音乐」） */
    public static Group defaultGroup() {
        String wanted = "音乐";

        try {
            String configured = dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic.get()
                    .getConfig().getString("music.default-group", "音乐");
            if (configured != null && !configured.isBlank()) {
                wanted = configured.trim();
            }
        } catch (Throwable ignored) {
            // 用默认值
        }

        for (Group group : all()) {
            if (group.getName() != null && group.getName().equalsIgnoreCase(wanted)) {
                return group;
            }
        }

        List<Group> groups = all();
        return groups.isEmpty() ? null : groups.get(0);
    }

    /** 网页用的房间 ID：去掉 SVC 组 ID 可能带的前导 '=' */
    public static String roomId(Group group) {
        String id = group.getId().toString();

        while (id.startsWith("=")) {
            id = id.substring(1);
        }

        return id;
    }

    public static GroupManager managerOf(Group group) {
        for (GroupManager manager : MusicManager.getInstance().groups()) {
            if (manager.getGroup().getId().equals(group.getId())) {
                return manager;
            }
        }

        return null;
    }
}
