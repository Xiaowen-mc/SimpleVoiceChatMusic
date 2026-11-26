package dev.klash.simpleVoiceChatMusic.web;

import de.maxhenkel.voicechat.api.Group;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页/基岩玩家的“收听组”绑定。
 *
 * <p>基岩玩家（Geyser）没有 SVC 连接，无法通过 SVC 判断所在语音组；
 * 用 {@code /music listen <组>} 绑定一个组后，播放相关指令以该组为准。</p>
 */
public final class WebListenerRegistry {

    private static final Map<UUID, String> BOUND = new ConcurrentHashMap<>();

    private WebListenerRegistry() {
    }

    public static void bind(Player player, Group group) {
        BOUND.put(player.getUniqueId(), VoiceGroups.roomId(group));
    }

    public static void clear(Player player) {
        BOUND.remove(player.getUniqueId());
    }

    public static Group groupOf(Player player) {
        String room = BOUND.get(player.getUniqueId());
        Group bound = room == null ? null : VoiceGroups.find(room);

        return bound;
    }

    /** 绑定到该组的全部玩家 UUID（含离线，调用方自行过滤） */
    public static java.util.Set<UUID> listenerIds(Group group) {
        String id = VoiceGroups.roomId(group);
        java.util.Set<UUID> result = new java.util.HashSet<>();

        BOUND.forEach((uuid, room) -> {
            if (id.equals(room)) {
                result.add(uuid);
            }
        });

        return result;
    }

    /** 该组是否还有网页/基岩听众（用于阻止空组清理） */
    public static boolean hasListener(Group group) {
        String id = VoiceGroups.roomId(group);
        return BOUND.containsValue(id);
    }

    public static boolean isListening(Player player, Group group) {
        String room = BOUND.get(player.getUniqueId());
        return room != null && room.equals(VoiceGroups.roomId(group));
    }

    public static boolean isBound(Player player) {
        return BOUND.containsKey(player.getUniqueId());
    }
}
