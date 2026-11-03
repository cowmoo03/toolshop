package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/**
 * Shows the update announcement from config.yml once per player: the first time they join after its ID changes.
 */
public final class AnnouncementListener implements Listener {
    private final ToolShopPlugin plugin;

    public AnnouncementListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Settings s = plugin.settings();
        if (!s.announcementEnabled || s.announcementId.isEmpty() || s.announcementLines.isEmpty()) return;
        UUID id = event.getPlayer().getUniqueId();
        if (s.announcementId.equals(plugin.registry().seenAnnouncement(id))) return;

        // A short delay so it isn't buried under other join messages.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) return; // left already; show it next time
            MiniMessage mm = MiniMessage.miniMessage();
            for (String line : s.announcementLines) {
                try {
                    player.sendMessage(mm.deserialize(line));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Bad formatting in join-announcement line: " + line);
                }
            }
            plugin.registry().markAnnouncementSeen(id, s.announcementId);
        }, 20L * s.announcementDelaySeconds);
    }
}
