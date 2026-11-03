package io.github.cowmoo03.orewatch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Counts filler and valuable ore veins as players mine, works out whether each vein was buried (reached only by the
 * player's own digging) or sitting in a cave, spots breakthroughs into natural caves, and raises alerts and find
 * messages.
 */
public final class MiningListener implements Listener {
    private static final long VEIN_MEMORY_MS = 15 * 60_000L;
    private static final int VEIN_MEMORY_SIZE = 200;
    private static final int DUG_MEMORY_SIZE = 6000;
    private static final int BREAKTHROUGH_MEMORY_SIZE = 50;
    /** Openings this close (blocks) and this recent count as the same breakthrough. */
    private static final int BREAKTHROUGH_DEDUPE_DISTANCE = 8;
    private static final long BREAKTHROUGH_DEDUPE_MS = 120_000L;
    /** How far the cave check looks from the opening. */
    private static final int CAVE_SCAN_RADIUS = 6;
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /** One vein a player is working on: every ore block mined nearby joins it. */
    private static final class Vein {
        final Rules.Resource resource;
        final UUID world;
        final Block first;
        final long foundAt = System.currentTimeMillis();
        boolean buried;
        int blocks;
        final Deque<long[]> positions = new ArrayDeque<>(); // x, y, z
        long lastTouched;

        Vein(Rules.Resource resource, Block first) {
            this.resource = resource;
            this.world = first.getWorld().getUID();
            this.first = first;
        }
    }

    /** A spot where a player tunneled into a natural cave. */
    private static final class Breakthrough {
        final int x, y, z;
        final long time;
        boolean credited;

        Breakthrough(int x, int y, int z, long time) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }
    }

    /** What we remember about one player's recent mining. */
    private static final class Session {
        UUID world;
        /** Blocks the player broke: packed position -> time, oldest first. */
        final LinkedHashMap<Long, Long> dug = new LinkedHashMap<>();
        final Deque<Vein> veins = new ArrayDeque<>();
        final Deque<Breakthrough> breakthroughs = new ArrayDeque<>();
        long lastActive;
    }

    private final OreWatchPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
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
        long now = System.currentTimeMillis();
        Session s = session(id, block.getWorld(), now);

        if (rules.breakthroughsEnabled) checkBreakthrough(player, s, block, now);

        if (resource == null || wasPlaced) { // a player-placed ore (e.g. Silk Touch) isn't a find
            if (resource == null) {
                String group = rules.fillerOf(m);
                if (group != null) plugin.stats().addFiller(id, player.getName(), group);
            }
            markDug(s, block, now);
            return;
        }

        Vein vein = findVein(s, resource, block, now);
        boolean newVein = vein == null;
        if (newVein) {
            vein = new Vein(resource, block);
            vein.buried = isBuried(s, block, now);
            s.veins.addFirst(vein);
            while (s.veins.size() > VEIN_MEMORY_SIZE) s.veins.removeLast();
        }
        markDug(s, block, now);
        vein.blocks++;
        vein.positions.add(new long[]{block.getX(), block.getY(), block.getZ()});
        vein.lastTouched = now;
        plugin.stats().addOre(id, player.getName(), resource.id(), newVein, vein.buried);

        if (newVein) {
            scheduleFindMessage(player, vein);
            if (vein.buried) checkSuspicious(player, resource);
            else if (rules.breakthroughsEnabled && rules.breakthroughResources.contains(resource.id())) {
                creditBreakthrough(player, s, block, now);
            }
        }
    }

    /** Forgets players who haven't mined for a while. Called from the plugin's save timer. */
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - Math.max(plugin.rules().dugMemoryMs, VEIN_MEMORY_MS);
        sessions.values().removeIf(s -> s.lastActive < cutoff);
        long alertCutoff = System.currentTimeMillis() - Math.max(plugin.rules().alertCooldownMs, 60_000L);
        lastAlert.values().removeIf(t -> t < alertCutoff);
    }

    // ------------------------------------------------------------------ dug memory

    private Session session(UUID player, World world, long now) {
        Session s = sessions.computeIfAbsent(player, k -> new Session());
        if (!world.getUID().equals(s.world)) {
            s.world = world.getUID();
            s.dug.clear();
            s.breakthroughs.clear();
        }
        s.lastActive = now;
        long memory = plugin.rules().dugMemoryMs;
        Iterator<Long> times = s.dug.values().iterator();
        while (times.hasNext()) {
            long t = times.next();
            if (now - t <= memory && s.dug.size() <= DUG_MEMORY_SIZE) break;
            times.remove();
        }
        return s;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static void markDug(Session s, Block b, long now) {
        long key = pack(b.getX(), b.getY(), b.getZ());
        s.dug.remove(key); // re-insert so it moves to the newest end
        s.dug.put(key, now);
    }

    private boolean isDug(Session s, int x, int y, int z, long now) {
        Long t = s.dug.get(pack(x, y, z));
        return t != null && now - t <= plugin.rules().dugMemoryMs;
    }

    /**
     * True if every open face of the block (air, water, lava, or anything see-through like glow lichen) is space the
     * player dug out themselves: they reached it by tunneling, not by spotting it in a cave.
     */
    private boolean isBuried(Session s, Block b, long now) {
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (n.getType().isOccluding()) continue;
            if (!isDug(s, n.getX(), n.getY(), n.getZ(), now)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ breakthroughs

    /**
     * A breakthrough: the player is standing in their own tunnel and breaks a block that opens onto a real natural
     * cave (covered overhead, at least min-cave-size air blocks).
     */
    private void checkBreakthrough(Player player, Session s, Block block, long now) {
        Block feet = player.getLocation().getBlock();
        boolean inTunnel = isDug(s, feet.getX(), feet.getY(), feet.getZ(), now)
                || isDug(s, feet.getX(), feet.getY() + 1, feet.getZ(), now);
        if (!inTunnel) return;

        Block opening = null;
        for (BlockFace f : FACES) {
            Block n = block.getRelative(f);
            if (!n.getType().isAir() || isDug(s, n.getX(), n.getY(), n.getZ(), now)) continue;
            if (n.getY() >= n.getWorld().getHighestBlockYAt(n.getX(), n.getZ())) continue; // open sky, not a cave
            opening = n;
            break;
        }
        if (opening == null) return;

        for (Breakthrough b : s.breakthroughs) {
            if (now - b.time > BREAKTHROUGH_DEDUPE_MS) break;
            if (Math.abs(b.x - block.getX()) <= BREAKTHROUGH_DEDUPE_DISTANCE
                    && Math.abs(b.y - block.getY()) <= BREAKTHROUGH_DEDUPE_DISTANCE
                    && Math.abs(b.z - block.getZ()) <= BREAKTHROUGH_DEDUPE_DISTANCE) {
                return;
            }
        }
        if (!isCave(s, opening, now)) return;

        s.breakthroughs.addFirst(new Breakthrough(block.getX(), block.getY(), block.getZ(), now));
        while (s.breakthroughs.size() > BREAKTHROUGH_MEMORY_SIZE) s.breakthroughs.removeLast();
        plugin.stats().addEvent(player.getUniqueId(), player.getName(), Counts.BREAKTHROUGHS);
    }

    /** Small flood fill: does this air lead into at least min-cave-size natural air blocks? */
    private boolean isCave(Session s, Block start, long now) {
        int need = plugin.rules().breakthroughMinCave;
        Set<Long> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(pack(start.getX(), start.getY(), start.getZ()));
        int found = 0;
        while (!queue.isEmpty() && seen.size() < need * 8) {
            Block b = queue.poll();
            if (++found >= need) return true;
            for (BlockFace f : FACES) {
                Block n = b.getRelative(f);
                if (Math.abs(n.getX() - start.getX()) > CAVE_SCAN_RADIUS
                        || Math.abs(n.getY() - start.getY()) > CAVE_SCAN_RADIUS
                        || Math.abs(n.getZ() - start.getZ()) > CAVE_SCAN_RADIUS) continue;
                if (!seen.add(pack(n.getX(), n.getY(), n.getZ()))) continue;
                if (!n.getType().isAir() || isDug(s, n.getX(), n.getY(), n.getZ(), now)) continue;
                queue.add(n);
            }
        }
        return false;
    }

    /** A cave vein found soon after and near a breakthrough marks that breakthrough as productive. */
    private void creditBreakthrough(Player player, Session s, Block block, long now) {
        Rules rules = plugin.rules();
        long r2 = (long) rules.findRadius * rules.findRadius;
        for (Breakthrough b : s.breakthroughs) {
            if (now - b.time > rules.findWindowMs) break;
            if (b.credited) continue;
            long dx = b.x - block.getX(), dy = b.y - block.getY(), dz = b.z - block.getZ();
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            b.credited = true;
            plugin.stats().addEvent(player.getUniqueId(), player.getName(), Counts.PRODUCTIVE);
            checkBreakthroughAlert(player);
            return;
        }
    }

    private void checkBreakthroughAlert(Player player) {
        Stats.PlayerData data = plugin.stats().get(player.getUniqueId());
        if (data == null) return;
        Rules rules = plugin.rules();
        Counts day = data.recent(24);
        long total = day.breakthroughs();
        double pct = day.productivePercent();
        if (total < rules.minBreakthroughs || pct <= rules.maxProductivePercent) return;
        if (onCooldown(player.getUniqueId() + ":breakthroughs")) return;

        String text = player.getName() + ": " + day.productive() + " of " + total
                + " cave breakthroughs in 24h led straight to a valuable find (" + Math.round(pct) + "%, limit "
                + Math.round(rules.maxProductivePercent) + "%)";
        alert(player, text);
    }

    // ------------------------------------------------------------------ veins, finds, alerts

    /** A recent vein of the same resource with an ore block within vein-distance of this one, if any. */
    private Vein findVein(Session s, Rules.Resource resource, Block block, long now) {
        int d = plugin.rules().veinDistance;
        UUID world = block.getWorld().getUID();
        Iterator<Vein> it = s.veins.iterator();
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
        UUID id = player.getUniqueId();
        long delay = 20L * plugin.rules().notifyDelaySeconds;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Block b = vein.first;
            plugin.stats().addFind(id, name, new Stats.Find(vein.foundAt, b.getWorld().getName(), b.getX(), b.getY(),
                    b.getZ(), vein.resource.id(), vein.blocks, vein.buried));
            String where = b.getWorld().getName() + " " + b.getX() + ", " + b.getY() + ", " + b.getZ();
            String text = name + " found " + vein.resource.displayName().toLowerCase() + " ore (" + vein.blocks + ", "
                    + (vein.buried ? "buried" : "cave") + ") at " + where;
            plugin.logLine("finds.log", text);
            if (!plugin.rules().notifyFinds) return;
            Component msg = OreWatchPlugin.PREFIX.append(Component.text(text + " ", NamedTextColor.GRAY))
                    .append(Component.text("[tp]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/orewatch tp " + name + " id:" + vein.foundAt)));
            for (Player staff : plugin.getServer().getOnlinePlayers()) {
                if (staff.hasPermission("orewatch.notify") && !plugin.stats().notifyOff(staff.getUniqueId())) {
                    staff.sendMessage(msg);
                }
            }
        }, delay);
    }

    /** Alerts staff if the player's last-24h rate of buried veins for this resource is over the limit. */
    private void checkSuspicious(Player player, Rules.Resource resource) {
        Stats.PlayerData data = plugin.stats().get(player.getUniqueId());
        if (data == null) return;
        Counts day = data.recent(24);
        long buried = day.buried(resource.id());
        long filler = day.filler(resource.filler());
        double rate = Counts.rate(buried, filler);
        if (buried < resource.minVeins() || rate <= resource.maxPer1000()) return;
        if (onCooldown(player.getUniqueId() + ":" + resource.id())) return;

        String text = player.getName() + ": " + buried + " buried " + resource.displayName().toLowerCase()
                + " veins per " + filler + " " + resource.filler() + " blocks in 24h ("
                + OreWatchPlugin.formatRate(rate) + "/1000, limit " + OreWatchPlugin.formatRate(resource.maxPer1000()) + ")";
        alert(player, text);
    }

    private boolean onCooldown(String key) {
        long now = System.currentTimeMillis();
        Long last = lastAlert.get(key);
        if (last != null && now - last < plugin.rules().alertCooldownMs) return true;
        lastAlert.put(key, now);
        return false;
    }

    private void alert(Player player, String text) {
        plugin.logLine("alerts.log", text);
        plugin.getLogger().warning("Suspicious mining: " + text);
        Component msg = OreWatchPlugin.PREFIX
                .append(Component.text("⚠ Suspicious: ", NamedTextColor.RED))
                .append(Component.text(text + " ", NamedTextColor.YELLOW))
                .append(Component.text("[details]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/orewatch " + player.getName())))
                .append(Component.text(" "))
                .append(Component.text("[finds]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/orewatch finds " + player.getName())));
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("orewatch.alerts")) staff.sendMessage(msg);
        }
    }
}
