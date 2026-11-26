package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.web.VoiceGroups;
import dev.klash.simpleVoiceChatMusic.web.WebListenerRegistry;
import dev.klash.simpleVoiceChatMusic.web.WebPlayerServer;
import de.maxhenkel.voicechat.api.Group;
import org.bukkit.entity.Player;

/**
 * 网页收听：给基岩/网页玩家选择要收听的语音组。
 *
 * <pre>
 * /music listen            列出所有房间
 * /music listen &lt;房间&gt;   获取该房间的网页链接
 * /music listen off        停止网页收听
 * </pre>
 */
public class ListenCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        if (!WebPlayerServer.isRunning()) {
            Messages.send(player, "web.disabled");
            return 1;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("off")) {
            WebListenerRegistry.clear(player);
            Messages.send(player, "web.off");
            return 0;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            Messages.send(player, "web.list-header");

            for (Group group : VoiceGroups.all()) {
                String url = WebPlayerServer.link(isDefault(group) ? "/"
                        : "/?room=" + java.net.URLEncoder.encode(
                        VoiceGroups.roomId(group), java.nio.charset.StandardCharsets.UTF_8));

                Messages.send(player, "web.room-line",
                        Ph.raw("name", group.getName()),
                        Ph.raw("url", url));
            }

            return 0;
        }

        Group group = VoiceGroups.find(args[0]);

        if (group == null) {
            Messages.send(player, "web.room-not-found");
            return 1;
        }

        WebListenerRegistry.bind(player, group);

        Messages.send(player, "web.url",
                Ph.raw("name", group.getName()),
                Ph.raw("url", WebPlayerServer.link(isDefault(group) ? "/"
                        : "/?room=" + java.net.URLEncoder.encode(
                        VoiceGroups.roomId(group), java.nio.charset.StandardCharsets.UTF_8))));
        return 0;
    }

    private boolean isDefault(Group group) {
        Group def = VoiceGroups.defaultGroup();
        return def != null && def.getId().equals(group.getId());
    }

}
