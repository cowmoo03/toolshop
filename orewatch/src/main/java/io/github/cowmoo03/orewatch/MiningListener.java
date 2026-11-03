package io.github.cowmoo03.orewatch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Counts filler and valuable ore veins as players mine, and raises alerts and find messages. */
public final class MiningListener implements Listener {
    private static final long VEIN_MEMORY_MS = 15 * 60_000L;
    private static final int VEIN_MEMORY_SIZE = 200;

    /** One vein a player is working on: every ore block mined nearby joins it. */
    private static final class Vein {
        final Rules.Resource resource;
        final UUID world;
        final Block first;
        int blocks;
        final Deque<long[]> positions = new ArrayDeque<>(); // x, y, z
        long lastTouched;

        Vein(Rules.Resource resource, Block first) {
            this.resource = resource;
            this.world = first.getWorld().getUID();
            this.first = first;
        }
    }

    private final OreWatchPlugin plugin;
    private final Map<UUID, Deque<Vein>> recentVeins = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();

    public MiningListener(OreWatchPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material m = event.getBlockPlaced().getType();
        if (plugin.rules().resourceOf(m) != null) plugin.placed().add(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material m = block.getType();
        Rules rules = plugin.rules();

        Rules.Resource resource = rules.resourceOf(m);
        boolean wasPlaced = resource != null && plugin.placed().remove(block);
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        if (player.hasPermission("orewatch.exempt")) return;

        UUID id = player.getUniqueId();
        if (resource == null) {
            String group = rules.fillerOf(m);
            if (group != null) plugin.stats().addFiller(id, player.getName(), group);
            return;
        }
        if (wasPlaced) return; // a player-placed ore (e.g. Silk Touch) isn't a find

        Vein vein = findVein(id, resource, block);
        boolean newVein = vein == null;
        if (newVein) {
            vein = new Vein(resource, block);
            Deque<Vein> veins = recentVeins.computeIfAbsent(id, k -> new ArrayDeque<>());
            veins.addFirst(vein);
            while (veins.size() > VEIN_MEMORY_SIZE) veins.removeLast();
        }
        vein.blocks++;
        vein.positions.add(new long[]{block.getX(), block.getY(), block.getZ()});
        vein.lastTouched = System.currentTimeMillis();
        plugin.stats().addOre(id, player.getName(), resource.id(), newVein);

        if (newVein) {
            scheduleFindMessage(player, vein);
            checkSuspicious(player, resource);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recentVeins.remove(event.getPlayer().getUniqueId());
    }

    /** A recent vein of the same resource with an ore block within vein-distance of this one, if any. */
    private Vein findVein(UUID player, Rules.Resource resource, Block block) {
        Deque<Vein> veins = recentVeins.get(player);
        if (veins == null) return null;
        long now = System.currentTimeMillis();
        int d = plugin.rules().veinDistance;
        UUID world = block.getWorld().getUID();
        Iterator<Vein> it = veins.iterator();
        while (it.hasNext()) {
            Vein v = it.next();
            if (now - v.lastTouched > VEIN_MEMORY_MS) {
                it.remove();
                continue;
            }
            if (v.resource != resource || !v.world.equals(world)) continue;
            for (long[] p : v.positions) {
                if (Math.abs(p[0] - block.getX()) <= d && Math.abs(p[1] - block.getY()) <= d
                        && Math.abs(p[2] - block.getZ()) <= d) {
                    return v;
                }
            }
        }
        return null;
    }

    private void scheduleFindMessage(Player player, Vein vein) {
        String name = player.getName();
        long delay = 20L * plugin.rules().notifyDelaySeconds;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Block b = vein.first;
            String where = b.getWorld().getName() + " " + b.getX() + ", " + b.getY() + ", " + b.getZ();
            String text = name + " found " + vein.resource.displayName().toLowerCase() + " ore (" + vein.blocks + ") at " + where;
            plugin.logLine("finds.log", text);
            if (!plugin.rules().notifyFinds) return;
            Component msg = OreWatchPlugin.PREFIX.append(Component.text(text, NamedTextColor.GRAY));
            for (Player staff : plugin.getServer().getOnlinePlayers()) {
                if (staff.hasPermission("orewatch.notify") && !plugin.stats().notifyOff(staff.getUniqueId())) {
                    staff.sendMessage(msg);
                }
            }
        }, delay);
    }

    /** Alerts staff if the player's last-24h rate for this resource is over the limit. */
    private void checkSuspicious(Player player, Rules.Resource resource) {
        Stats.PlayerData data = plugin.stats().get(player.getUniqueId());
        if (data == null) return;
        Counts day = data.recent(24);
        long veins = day.veins(resource.id());
        long filler = day.filler(resource.filler());
        double rate = Counts.rate(veins, filler);
        if (veins < resource.minVeins() || rate <= resource.maxPer1000()) return;

        String key = player.getUniqueId() + ":" + resource.id();
        long now = System.currentTimeMillis();
        Long last = lastAlert.get(key);
        if (last != null && now - last < plugin.rules().alertCooldownMs) return;
        lastAlert.put(key, now);

        String text = player.getName() + ": " + veins + " " + resource.displayName().toLowerCase() + " veins per "
                + filler + " " + resource.filler() + " blocks in 24h (" + OreWatchPlugin.formatRate(rate)
                + "/1000, limit " + OreWatchPlugin.formatRate(resource.maxPer1000()) + ")";
        plugin.logLine("alerts.log", text);
        plugin.getLogger().warning("Suspicious mining: " + text);
        Component msg = OreWatchPlugin.PREFIX
                .append(Component.text("⚠ Suspicious: ", NamedTextColor.RED))
                .append(Component.text(text + " ", NamedTextColor.YELLOW))
                .append(Component.text("[details]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/orewatch " + player.getName())));
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("orewatch.alerts")) staff.sendMessage(msg);
        }
    }
}
