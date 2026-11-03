package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recovery, the netherite-only safety upgrade (netherite never burns, so Fireproof does nothing for it).
 * <ul>
 *   <li>Tier 1+: a dropped tool glows through walls for its owner only and stays extra time before despawning; on
 *   death the owner is told where it dropped.</li>
 *   <li>Tier 2+: explosions and cactus can't destroy it.</li>
 *   <li>Tier 3: if it falls into the void it goes back to the owner (inventory, then ender chest, then at their
 *   feet; if they're offline, when they next join).</li>
 *   <li>Tier 4 (Tracker): the owner always knows where it is: the death message gives its coordinates, the action
 *   bar shows direction and distance while it's on the ground (even far away), and /toolshop find lists it.</li>
 * </ul>
 * The extra time counts game ticks while the item's chunk is loaded, like Fireproof.
 * <p>
 * Minecraft has no per-player glow, so the glow is a glowing copy of the tool (an item display entity) that follows
 * the dropped item and is shown only to the owner. It's never saved; it's recreated when the item's chunk loads.
 */
public final class RecoveryListener implements Listener {
    private static final long TASK_PERIOD_TICKS = 5L;
    /** Dropped items render a little above their position. */
    private static final double MARKER_HEIGHT = 0.5;
    private static final float MARKER_SCALE = 1.8f;
    /** Tier that adds the Tracker. */
    public static final int TRACKER_TIER = 4;
    /** Only show the action bar when the owner is farther than this (blocks). */
    private static final double TRACKER_MIN_DISTANCE = 4;

    /** Where a dropped Tracker tool was last seen, kept while its chunk is unloaded. */
    public record Spot(UUID owner, String toolName, UUID world, String worldName, double x, double y, double z) {
        String coords() {
            return (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
        }
    }

    private final ToolShopPlugin plugin;
    /** Ticks of extra time left, stored on the item entity so it survives chunk unloads and restarts. */
    private final NamespacedKey extraLeftKey;
    /** Dropped Recovery tools in loaded chunks. */
    private final Map<UUID, Item> tracked = new HashMap<>();
    /** Item entity id -> the glowing copy only its owner can see. */
    private final Map<UUID, ItemDisplay> markers = new HashMap<>();
    private final File returnsFile;
    /** Owner -> tools that fell into the void while the owner was offline. */
    private final Map<UUID, List<ItemStack>> pendingReturns = new HashMap<>();
    /** Item entity id -> last known spot of a dropped Tracker tool (loaded or not). */
    private final Map<UUID, Spot> spots = new HashMap<>();
    private final File spotsFile;
    private int ticks;

    public RecoveryListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.extraLeftKey = new NamespacedKey(plugin, "lt_recovery_left");
        this.returnsFile = new File(plugin.getDataFolder(), "returns.yml");
        this.spotsFile = new File(plugin.getDataFolder(), "dropped.yml");
        loadReturns();
        loadSpots();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TASK_PERIOD_TICKS, TASK_PERIOD_TICKS);
    }

    private int tier(ItemStack stack) {
        Legendary lt = plugin.legendary();
        return lt.isLegendary(stack) ? lt.tier(stack, Upgrade.RECOVERY) : 0;
    }

    private int tier(Item item) {
        return tier(item.getItemStack());
    }

    // ------------------------------------------------------------------ dropped: glow + track

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        start(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        start(event.getItemDrop());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e instanceof Item item) start(item);
        }
    }

    private void start(Item item) {
        if (tier(item) <= 0) return;
        item.setGlowing(false); // 2.3.0 made the real item glow for everyone
        tracked.put(item.getUniqueId(), item);
        // Spawning entities during a chunk load or spawn event can be unsafe, so do it on the next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (item.isValid() && tracked.containsKey(item.getUniqueId())) ensureMarker(item);
        });
    }

    private void ensureMarker(Item item) {
        ItemDisplay existing = markers.get(item.getUniqueId());
        if (existing != null && existing.isValid()) return;
        ItemStack look = item.getItemStack().clone();
        look.setAmount(1);
        ItemDisplay marker = item.getWorld().spawn(item.getLocation().add(0, MARKER_HEIGHT, 0), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setItemStack(look);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(MARKER_SCALE, MARKER_SCALE, MARKER_SCALE), new AxisAngle4f()));
            d.setGlowing(true);
            d.setGlowColorOverride(Color.AQUA);
            // Display entities only render within view-range x 64 blocks (scaled by the client's Entity Distance).
            // 4 = about 256 blocks, so the owner can spot it from the surface or across a cave system.
            d.setViewRange(4.0f);
            d.setTeleportDuration((int) TASK_PERIOD_TICKS);
        });
        markers.put(item.getUniqueId(), marker);
        Player owner = owner(item.getItemStack());
        if (owner != null) owner.showEntity(plugin, marker);
    }

    private void removeMarker(UUID itemId) {
        ItemDisplay marker = markers.remove(itemId);
        if (marker != null) marker.remove();
    }

    /** Removes every glowing copy (on plugin disable). */
    public void shutdown() {
        markers.values().forEach(Entity::remove);
        markers.clear();
        saveSpots();
    }

    // ------------------------------------------------------------------ extra time before despawning

    /**
     * Vanilla despawns items after 5 minutes. The first time a Recovery tool reaches that, we cancel it (which resets
     * the item's age, giving at most another 5 minutes) and count down the tier's extra time ourselves.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        int tier = tier(item);
        if (tier <= 0) return;
        if (item.getPersistentDataContainer().has(extraLeftKey, PersistentDataType.INTEGER)) return; // already had it
        int seconds = Settings.forTier(plugin.settings().recoverySeconds, tier);
        event.setCancelled(true);
        item.getPersistentDataContainer().set(extraLeftKey, PersistentDataType.INTEGER, seconds * 20);
        tracked.put(item.getUniqueId(), item);
        Player owner = owner(item.getItemStack());
        if (owner != null) {
            owner.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Your "
                    + plugin.legendary().kind(item.getItemStack()).displayName() + " was about to despawn"
                    + (tier >= TRACKER_TIER ? " at " + where(item.getLocation()) : "") + ". Recovery keeps it for "
                    + formatSeconds(seconds) + " more.",
                    NamedTextColor.GOLD)));
        }
    }

    private void tick() {
        ticks++;
        Iterator<Item> it = tracked.values().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (!item.isValid()) { // picked up, removed or its chunk unloaded
                it.remove();
                removeMarker(item.getUniqueId());
                Location last = item.getLocation();
                boolean unloaded = !last.getWorld().isChunkLoaded(last.getBlockX() >> 4, last.getBlockZ() >> 4);
                if (!unloaded) spots.remove(item.getUniqueId()); // really gone; an unloaded one keeps its spot
                continue;
            }
            int tier = tier(item);
            if (tier <= 0) {
                it.remove();
                removeMarker(item.getUniqueId());
                spots.remove(item.getUniqueId());
                continue;
            }
            if (tier >= TRACKER_TIER) {
                Location l = item.getLocation();
                spots.put(item.getUniqueId(), new Spot(plugin.legendary().owner(item.getItemStack()),
                        plugin.legendary().kind(item.getItemStack()).displayName(), l.getWorld().getUID(),
                        l.getWorld().getName(), l.getX(), l.getY(), l.getZ()));
            }
            ItemDisplay marker = markers.get(item.getUniqueId());
            if (marker != null && marker.isValid()) {
                Location to = item.getLocation().add(0, MARKER_HEIGHT, 0);
                if (to.distanceSquared(marker.getLocation()) > 0.0025) marker.teleport(to);
            }
            if (tier >= 3 && item.getLocation().getY() < item.getWorld().getMinHeight()) {
                it.remove();
                removeMarker(item.getUniqueId());
                spots.remove(item.getUniqueId());
                ItemStack stack = item.getItemStack();
                item.remove();
                // Next tick: giving it back may drop an item, which would change `tracked` while we loop over it.
                plugin.getServer().getScheduler().runTask(plugin, () -> returnToOwner(stack));
                continue;
            }
            Integer left = item.getPersistentDataContainer().get(extraLeftKey, PersistentDataType.INTEGER);
            if (left == null) continue;
            left -= (int) TASK_PERIOD_TICKS;
            if (left <= 0) {
                it.remove();
                removeMarker(item.getUniqueId());
                spots.remove(item.getUniqueId());
                item.remove();
            } else {
                item.getPersistentDataContainer().set(extraLeftKey, PersistentDataType.INTEGER, left);
            }
        }
        if (ticks % 4 == 0) showTrackers(); // once a second
    }

    // ------------------------------------------------------------------ Tracker (tier 4)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        spots.remove(event.getItem().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        spots.remove(event.getItem().getUniqueId());
    }

    /** Action bar for each owner: direction, distance and coordinates of their nearest dropped Tracker tool. */
    private void showTrackers() {
        if (spots.isEmpty()) return;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            Spot best = null;
            double bestDist = Double.MAX_VALUE;
            for (Spot spot : spots.values()) {
                if (!p.getUniqueId().equals(spot.owner())) continue;
                double d = spot.world().equals(p.getWorld().getUID())
                        ? p.getLocation().distance(new Location(p.getWorld(), spot.x(), spot.y(), spot.z()))
                        : Double.MAX_VALUE / 2;
                if (best == null || d < bestDist) {
                    best = spot;
                    bestDist = d;
                }
            }
            if (best == null || bestDist <= TRACKER_MIN_DISTANCE) continue;
            p.sendActionBar(Component.text(trackerLine(p, best), NamedTextColor.AQUA));
        }
    }

    private static String trackerLine(Player p, Spot spot) {
        if (!spot.world().equals(p.getWorld().getUID())) {
            return "⌖ Your " + spot.toolName() + " is in " + spot.worldName() + " at " + spot.coords();
        }
        Location from = p.getLocation();
        double dy = spot.y() - from.getY();
        String height = Math.abs(dy) < 3 ? "" : " · " + Math.round(Math.abs(dy)) + (dy < 0 ? " below" : " above");
        long dist = Math.round(from.distance(new Location(p.getWorld(), spot.x(), spot.y(), spot.z())));
        return "⌖ " + spot.toolName() + " " + arrow(from, spot) + " " + dist + "m" + height + " · " + spot.coords();
    }

    /** Arrow pointing from where the player is facing toward the spot. */
    private static String arrow(Location from, Spot spot) {
        double dx = spot.x() - from.getX();
        double dz = spot.z() - from.getZ();
        if (dx * dx + dz * dz < 4) return spot.y() < from.getY() ? "↓" : "↑";
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz)); // Minecraft yaw: 0 = south (+Z), 90 = west (-X)
        double rel = ((targetYaw - from.getYaw()) % 360 + 540) % 360 - 180; // 0 = straight ahead, + = to the right
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int idx = (int) Math.round(rel / 45.0);
        return arrows[((idx % 8) + 8) % 8];
    }

    /** The player's dropped Tracker tools, for /toolshop find. */
    public List<String> find(Player p) {
        List<String> out = new ArrayList<>();
        for (Spot spot : spots.values()) {
            if (p.getUniqueId().equals(spot.owner())) out.add(trackerLine(p, spot));
        }
        return out;
    }

    private void loadSpots() {
        if (!spotsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(spotsFile);
        for (String key : y.getKeys(false)) {
            try {
                org.bukkit.configuration.ConfigurationSection c = y.getConfigurationSection(key);
                if (c == null) continue;
                spots.put(UUID.fromString(key), new Spot(UUID.fromString(c.getString("owner", "")),
                        c.getString("tool", "tool"), UUID.fromString(c.getString("world", "")),
                        c.getString("world-name", "?"), c.getDouble("x"), c.getDouble("y"), c.getDouble("z")));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    private void saveSpots() {
        YamlConfiguration y = new YamlConfiguration();
        spots.forEach((id, spot) -> {
            if (spot.owner() == null) return;
            String b = id.toString();
            y.set(b + ".owner", spot.owner().toString());
            y.set(b + ".tool", spot.toolName());
            y.set(b + ".world", spot.world().toString());
            y.set(b + ".world-name", spot.worldName());
            y.set(b + ".x", spot.x());
            y.set(b + ".y", spot.y());
            y.set(b + ".z", spot.z());
        });
        try {
            plugin.getDataFolder().mkdirs();
            y.save(spotsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save dropped.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ explosions and cactus (tier 2+)

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.CONTACT) return;
        if (tier(item) >= 2) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ death: tell the owner, void deaths (tier 3)

    @EventHandler(priority = EventPriority.HIGHEST) // after Soulbound (HIGH) has kept what it saves
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        Player player = event.getEntity();
        Location at = player.getLocation();
        boolean inVoid = at.getY() < at.getWorld().getMinHeight();
        Legendary lt = plugin.legendary();
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            int tier = tier(drop);
            if (tier <= 0 || !player.getUniqueId().equals(lt.owner(drop))) continue;
            String name = lt.kind(drop).displayName();
            if (inVoid && tier >= 3) {
                // Items dropped below the world are deleted straight away, so keep it like Soulbound does.
                it.remove();
                event.getItemsToKeep().add(drop);
                player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Recovery pulled your " + name
                        + " out of the void. It's still in your inventory.", NamedTextColor.GOLD)));
            } else {
                int seconds = Settings.forTier(plugin.settings().recoverySeconds, tier);
                player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Your " + name + " dropped"
                        + (tier >= TRACKER_TIER ? " at " + where(at) : "")
                        + ". It glows for you (only you can see it), and Recovery keeps it " + formatSeconds(seconds)
                        + " longer than normal items." + (tier >= TRACKER_TIER ? " Your action bar points to it." : ""),
                        NamedTextColor.GOLD)));
            }
        }
    }

    // ------------------------------------------------------------------ giving it back

    private Player owner(ItemStack stack) {
        UUID id = plugin.legendary().owner(stack);
        return id == null ? null : plugin.getServer().getPlayer(id);
    }

    private void returnToOwner(ItemStack stack) {
        Legendary lt = plugin.legendary();
        UUID ownerId = lt.owner(stack);
        if (ownerId == null) return;
        if (!lt.isCurrent(stack)) return; // a retired tool isn't worth saving
        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner == null) {
            pendingReturns.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(stack);
            saveReturns();
            return;
        }
        give(owner, stack, "fell into the void");
    }

    private void give(Player owner, ItemStack stack, String what) {
        String name = plugin.legendary().kind(stack).displayName();
        String where;
        if (owner.getInventory().addItem(stack).isEmpty()) {
            where = "your inventory";
        } else if (owner.getEnderChest().addItem(stack).isEmpty()) {
            where = "your ender chest (your inventory was full)";
        } else {
            owner.getWorld().dropItem(owner.getLocation(), stack);
            where = "your feet (your inventory and ender chest were full)";
        }
        owner.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Your " + name + " " + what
                + ". Recovery brought it back to " + where + ".", NamedTextColor.GOLD)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (Map.Entry<UUID, ItemDisplay> e : markers.entrySet()) {
            Item item = tracked.get(e.getKey());
            if (item != null && item.isValid() && joined.getUniqueId().equals(plugin.legendary().owner(item.getItemStack()))) {
                joined.showEntity(plugin, e.getValue());
            }
        }
        List<ItemStack> items = pendingReturns.remove(event.getPlayer().getUniqueId());
        if (items == null) return;
        saveReturns();
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (ItemStack stack : items) {
                if (player.isOnline()) give(player, stack, "fell into the void while you were away");
                else pendingReturns.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(stack);
            }
            if (!player.isOnline()) saveReturns();
        }, 40L);
    }

    private void loadReturns() {
        if (!returnsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(returnsFile);
        for (String key : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                List<ItemStack> items = new ArrayList<>();
                for (Object o : y.getList(key, List.of())) if (o instanceof ItemStack s) items.add(s);
                if (!items.isEmpty()) pendingReturns.put(id, items);
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
    }

    private void saveReturns() {
        YamlConfiguration y = new YamlConfiguration();
        pendingReturns.forEach((id, items) -> y.set(id.toString(), items));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(returnsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save returns.yml: " + e.getMessage());
        }
    }

    private static String where(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private static String formatSeconds(int seconds) {
        if (seconds % 60 == 0) {
            int m = seconds / 60;
            return m + (m == 1 ? " minute" : " minutes");
        }
        return seconds + " seconds";
    }
}
