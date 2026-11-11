package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Switches off mcMMO's Tree Feller, which does the same job as the Legendary Axe's Timber upgrade. mcMMO fires an
 * ability event just before a super ability starts; this cancels it for Woodcutting (Tree Feller). Axes' Skull
 * Splitter is untouched. Registered by reflection so ToolShop still runs without mcMMO.
 */
public final class TreeFellerBlocker implements Listener {
    private static final String EVENT = "com.gmail.nossr50.events.skills.abilities.McMMOPlayerAbilityActivateEvent";
    private static final long MESSAGE_COOLDOWN_MS = 30_000;

    private final ToolShopPlugin plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private Method getSkill;
    private Method getPlayer;

    private TreeFellerBlocker(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    /** Hooks into mcMMO if it's there. Returns false (and does nothing) otherwise. */
    @SuppressWarnings("unchecked")
    public static boolean register(ToolShopPlugin plugin) {
        Plugin mcmmo = plugin.getServer().getPluginManager().getPlugin("mcMMO");
        if (mcmmo == null || !mcmmo.isEnabled()) return false;
        TreeFellerBlocker blocker = new TreeFellerBlocker(plugin);
        try {
            Class<?> raw = Class.forName(EVENT, true, mcmmo.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) return false;
            Class<? extends Event> type = (Class<? extends Event>) raw;
            blocker.getSkill = raw.getMethod("getSkill");
            blocker.getPlayer = raw.getMethod("getPlayer");
            plugin.getServer().getPluginManager().registerEvent(type, blocker, EventPriority.HIGH,
                    (listener, event) -> blocker.handle(type, event), plugin, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Couldn't hook mcMMO's ability event to switch off Tree Feller: " + e);
            return false;
        }
    }

    private void handle(Class<? extends Event> type, Event event) {
        if (!type.isInstance(event) || !(event instanceof Cancellable cancellable)) return;
        if (!plugin.settings().disableTreeFeller) return;
        try {
            if (!"WOODCUTTING".equals(String.valueOf(getSkill.invoke(event)))) return;
            cancellable.setCancelled(true);
            if (getPlayer.invoke(event) instanceof Player player) {
                long now = System.currentTimeMillis();
                if (now - lastMessage.getOrDefault(player.getUniqueId(), 0L) > MESSAGE_COOLDOWN_MS) {
                    lastMessage.put(player.getUniqueId(), now);
                    player.sendActionBar(Component.text("Tree Feller is replaced by Timber on the Legendary Axe (/toolshop).",
                            NamedTextColor.YELLOW));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Leave mcMMO alone if anything about its event changed.
        }
    }
}
