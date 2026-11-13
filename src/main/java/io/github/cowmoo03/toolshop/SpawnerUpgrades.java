package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spawner claims and upgrades. A spawner works like vanilla whether or not it's claimed.
 * <ul>
 *   <li>Right-click a spawner to open its menu: claim it, upgrade your own, or take over someone else's (a takeover
 *   costs more; nobody gets the items).</li>
 *   <li>Claimed spawners show up in /toolshop (Your Spawners), where the owner can upgrade them from anywhere, turn
 *   them on/off or unclaim them. Only the owner (or an op) can break a claimed spawner; explosions can't.</li>
 * </ul>
 * Claiming and every tier need an mcMMO power level and items. Data lives in plugins/ToolShop/spawners.yml.
 */
public final class SpawnerUpgrades implements Listener {
    private static final String FILE = "spawners.yml";

    /** One step: power level needed (config scale), items. */
    public record Cost(int powerLevel, Map<Material, Integer> items) {}
    public record Tier(double speed, Cost cost) {}

    /** A spawner ToolShop knows about: claimed, upgraded, or both. */
    public static final class Record {
        public final String world;
        public final int x, y, z;
        public UUID owner;
        public String ownerName;
        public int tier;
        public boolean enabled = true;
        public long spawned;
        public String mob = "Unknown";
        int baseMin = -1, baseMax = -1;

        Record(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String key() { return key(world, x, y, z); }
        static String key(String world, int x, int y, int z) { return world + ";" + x + ";" + y + ";" + z; }
        public String where() { return x + ", " + y + ", " + z + (world.equals("world") ? "" : " (" + niceWorld(world) + ")"); }
        public boolean claimed() { return owner != null; }

        private static String niceWorld(String w) {
            if (w.endsWith("_nether")) return "Nether";
            if (w.endsWith("_the_end")) return "End";
            return w;
        }
    }

    private final ToolShopPlugin plugin;
    private final NamespacedKey legacyTierKey;
    private final NamespacedKey legacyBaseMinKey;
    private final NamespacedKey legacyBaseMaxKey;
    private final Map<String, Record> records = new HashMap<>();
    /** Spawners whose spawn was just cancelled this tick (off), so their delay is only reset once. */
    private final Set<String> pausing = new HashSet<>();
    private boolean dirty;

    private boolean enabled;
    private int maxClaims;
    private Cost claimCost;
    private Cost takeoverCost;
    private final List<Tier> tiers = new ArrayList<>();

    public SpawnerUpgrades(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.legacyTierKey = new NamespacedKey(plugin, "spawner_tier");
        this.legacyBaseMinKey = new NamespacedKey(plugin, "spawner_base_min");
        this.legacyBaseMaxKey = new NamespacedKey(plugin, "spawner_base_max");
        load();
        reload();
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 6000L, 6000L);
    }

    // ---------------------------------------------------------------- config

    public void reload() {
        tiers.clear();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("spawner-upgrades");
        ConfigurationSection d = plugin.getConfig().getDefaults() == null ? null
                : plugin.getConfig().getDefaults().getConfigurationSection("spawner-upgrades");
        enabled = c == null || c.getBoolean("enabled", true);
        maxClaims = Math.max(0, c == null ? 3 : c.getInt("max-claims", 3));
        claimCost = readCost(section(c, d, "claim"));
        takeoverCost = readCost(section(c, d, "takeover"));
        List<Map<?, ?>> raw = c == null ? List.of() : c.getMapList("tiers");
        List<Map<?, ?>> defaults = d == null ? List.of() : d.getMapList("tiers");
        if (raw.isEmpty()) raw = defaults;
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            Map<?, ?> def = i < defaults.size() ? defaults.get(i) : Map.of();
            double speed = entry.get("speed") instanceof Number n ? n.doubleValue() : 1.0;
            // A tier written by 2.10.0 has no power-level: take it from the defaults.
            Object pl = entry.containsKey("power-level") ? entry.get("power-level") : def.get("power-level");
            int power = pl instanceof Number n ? n.intValue() : 0;
            tiers.add(new Tier(Math.max(1.0, Math.min(10.0, speed)), new Cost(Math.max(0, power), items(entry.get("cost")))));
        }
    }

    private static ConfigurationSection section(ConfigurationSection c, ConfigurationSection d, String name) {
        ConfigurationSection s = c == null ? null : c.getConfigurationSection(name);
        return s != null ? s : d == null ? null : d.getConfigurationSection(name);
    }

    private Cost readCost(ConfigurationSection s) {
        if (s == null) return new Cost(0, Map.of());
        ConfigurationSection items = s.getConfigurationSection("cost");
        return new Cost(Math.max(0, s.getInt("power-level", 0)), items == null ? Map.of() : items(items.getValues(false)));
    }

    private Map<Material, Integer> items(Object raw) {
        Map<Material, Integer> cost = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Material mat = Material.matchMaterial(String.valueOf(e.getKey()));
                int amount = e.getValue() instanceof Number n ? n.intValue() : 0;
                if (mat != null && mat.isItem() && amount > 0) cost.put(mat, amount);
                else plugin.getLogger().warning("spawner-upgrades: unknown item '" + e.getKey() + "'");
            }
        }
        return cost;
    }

    public boolean enabled() { return enabled; }
    public List<Tier> tiers() { return tiers; }
    public int maxClaims() { return maxClaims; }
    public Cost claimCost() { return claimCost; }
    public Cost takeoverCost() { return takeoverCost; }

    /** Power level needed, on mcMMO's current scale (0 without mcMMO). */
    public int powerNeeded(Cost cost) {
        if (cost.powerLevel() <= 0 || plugin.mcmmo() == null || !plugin.mcmmo().available()) return 0;
        return cost.powerLevel() * plugin.levelScale();
    }

    /** "items + power level N". */
    public String describe(Cost cost) {
        String items = cost.items().isEmpty() ? "free" : Settings.describeCost(cost.items());
        int need = powerNeeded(cost);
        return need > 0 ? items + " + power level " + need : items;
    }

    /** "25% faster" for a speed of 1.25. */
    public static String faster(double speed) {
        long pct = Math.round((speed - 1) * 100);
        return speed >= 2 && pct % 100 == 0 ? Math.round(speed) + "x as fast" : pct + "% faster";
    }

    // ---------------------------------------------------------------- records

    public List<Record> claimsOf(UUID player) {
        List<Record> list = new ArrayList<>();
        for (Record r : records.values()) if (player.equals(r.owner)) list.add(r);
        list.sort(Comparator.comparing((Record r) -> r.world).thenComparingInt(r -> r.x).thenComparingInt(r -> r.z));
        return list;
    }

    public Record record(String key) { return records.get(key); }

    private Record recordAt(Block b) {
        return records.get(Record.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    /** The spawner's record, creating one (and taking over a 2.10.0 tier) if needed. */
    private Record recordFor(Block b, CreatureSpawner spawner) {
        Record r = recordAt(b);
        if (r == null) {
            r = new Record(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            PersistentDataContainer pdc = spawner.getPersistentDataContainer();
            r.tier = pdc.getOrDefault(legacyTierKey, PersistentDataType.INTEGER, 0);
            r.baseMin = pdc.getOrDefault(legacyBaseMinKey, PersistentDataType.INTEGER, -1);
            r.baseMax = pdc.getOrDefault(legacyBaseMaxKey, PersistentDataType.INTEGER, -1);
            if (r.tier > 0) records.put(r.key(), r); // upgraded under 2.10.0: keep it; otherwise only once claimed
        }
        r.mob = mobName(spawner.getSpawnedType());
        return r;
    }

    private void forgetIfEmpty(Record r) {
        if (!r.claimed() && r.tier == 0) records.remove(r.key());
    }

    private static String mobName(EntityType type) {
        if (type == null) return "Empty";
        String n = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ---------------------------------------------------------------- clicking a spawner

    /** Right-clicking a spawner opens its menu. Spawn eggs still change the mob; sneaking with a block still builds. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        if (!enabled || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;
        if (!(block.getState() instanceof CreatureSpawner spawner)) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR || !player.hasPermission("toolshop.use")) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().name().endsWith("_SPAWN_EGG")) return; // changing the mob: vanilla
        if (player.isSneaking() && !hand.isEmpty() && hand.getType().isBlock()) return; // building against it
        event.setCancelled(true);
        Record r = recordFor(block, spawner);
        ShopMenu menu = new ShopMenu(plugin);
        menu.showWorldSpawner(r);
        menu.render(player);
        player.openInventory(menu.getInventory());
    }

    /** Why the player can't claim (or take over) with this cost, or null. */
    public String claimProblem(Player player, Cost cost) {
        if (maxClaims > 0 && claimsOf(player.getUniqueId()).size() >= maxClaims) {
            return "You already have " + maxClaims + " spawners claimed (the most you can have). Unclaim one in /toolshop first.";
        }
        return costProblem(player, cost);
    }

    /** Why the player can't pay this, or null. */
    public String costProblem(Player player, Cost cost) {
        int need = powerNeeded(cost);
        if (need > 0) {
            int have = plugin.mcmmo().powerLevel(player);
            if (have < 0) return "Your skills are still loading. Try again in a moment.";
            if (have < need) return "That needs power level " + need + " (you're at " + have + ").";
        }
        if (player.getGameMode() == GameMode.CREATIVE) return null;
        List<String> missing = ShopListener.missingItems(player, cost.items());
        return missing.isEmpty() ? null : "You still need: " + String.join(", ", missing);
    }

    private void pay(Player player, Cost cost) {
        if (player.getGameMode() != GameMode.CREATIVE) ShopListener.take(player, cost.items());
    }

    // ---------------------------------------------------------------- actions (also used by the /toolshop dashboard)

    /** Claims an unclaimed spawner, or takes over someone else's. */
    public void claim(Player player, Record r, boolean takeover) {
        Cost cost = takeover ? takeoverCost : claimCost;
        String problem = takeover || !r.claimed() ? claimProblem(player, cost) : "Someone claimed it first.";
        if (problem == null && takeover && player.getUniqueId().equals(r.owner)) problem = "It's already yours.";
        if (problem != null) {
            fail(player, problem);
            return;
        }
        pay(player, cost);
        UUID oldOwner = r.owner;
        String oldName = r.ownerName;
        r.owner = player.getUniqueId();
        r.ownerName = player.getName();
        r.enabled = true;
        r.spawned = 0;
        records.put(r.key(), r);
        save();
        plugin.log(player, (takeover ? "SPAWNER_TAKEOVER from " + oldName : "SPAWNER_CLAIM") + " " + r.mob + " at "
                + r.world + " " + r.where() + ", paid " + describe(cost), "-");
        ok(player, (takeover ? "You took over this spawner from " + oldName : "You claimed this " + r.mob + " spawner")
                + ". Manage it in /toolshop.");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
        if (takeover && oldOwner != null) {
            Player old = Bukkit.getPlayer(oldOwner);
            if (old != null) {
                tell(old, player.getName() + " took over your " + r.mob + " spawner at " + r.where() + ".", NamedTextColor.RED);
            }
        }
    }

    /** Buys the next tier. Works from anywhere: the spawner's chunk is loaded to apply it. */
    public void upgrade(Player player, Record r) {
        if (!player.getUniqueId().equals(r.owner)) {
            fail(player, "Only the spawner's owner can upgrade it.");
            return;
        }
        if (r.tier >= tiers.size()) {
            fail(player, "This spawner is fully upgraded.");
            return;
        }
        Tier next = tiers.get(r.tier);
        String problem = costProblem(player, next.cost());
        if (problem != null) {
            fail(player, problem);
            return;
        }
        World world = Bukkit.getWorld(r.world);
        if (world == null) {
            fail(player, "That spawner's world isn't loaded.");
            return;
        }
        pay(player, next.cost());
        r.tier++;
        save();
        plugin.log(player, "SPAWNER_UPGRADE " + r.mob + " at " + r.world + " " + r.where() + " to tier " + r.tier
                + ", paid " + describe(next.cost()), "-");
        ok(player, "Spawner upgraded to Tier " + Upgrade.roman(r.tier) + ": it now spawns " + faster(next.speed()) + ".");
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        world.getChunkAtAsync(r.x >> 4, r.z >> 4).thenAccept(chunk -> applyTier(world.getBlockAt(r.x, r.y, r.z), r));
    }

    /** Sets the spawner's delays for its tier, from its original delays. */
    private void applyTier(Block block, Record r) {
        if (block.getType() != Material.SPAWNER || !(block.getState() instanceof CreatureSpawner spawner)) return;
        if (r.baseMin < 0 || r.baseMax < 0) {
            r.baseMin = spawner.getMinSpawnDelay();
            r.baseMax = spawner.getMaxSpawnDelay();
            dirty = true;
        }
        double speed = r.tier <= 0 ? 1.0 : tiers.get(Math.min(r.tier, tiers.size()) - 1).speed();
        int newMin = Math.max(20, (int) Math.round(r.baseMin / speed));
        int newMax = Math.max(newMin + 1, (int) Math.round(r.baseMax / speed));
        spawner.setMinSpawnDelay(Math.min(newMin, spawner.getMinSpawnDelay())); // never above the maximum
        spawner.setMaxSpawnDelay(newMax);
        spawner.setMinSpawnDelay(newMin);
        if (spawner.getDelay() > newMax) spawner.setDelay(newMax);
        spawner.update(true, false);
        if (plugin.settings().effects) {
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 20, 0.5, 0.5, 0.5, 0);
        }
    }

    public void toggle(Player player, Record r) {
        if (!player.getUniqueId().equals(r.owner)) return;
        r.enabled = !r.enabled;
        save();
        ok(player, "Your " + r.mob + " spawner is now " + (r.enabled ? "on." : "off: it won't spawn anything until you turn it back on."));
    }

    public void unclaim(Player player, Record r) {
        if (!player.getUniqueId().equals(r.owner)) return;
        r.owner = null;
        r.ownerName = null;
        r.enabled = true;
        forgetIfEmpty(r);
        save();
        plugin.log(player, "SPAWNER_UNCLAIM " + r.mob + " at " + r.world + " " + r.where(), "-");
        ok(player, "You unclaimed the " + r.mob + " spawner at " + r.where() + ". Its upgrades stay on it.");
    }

    // ---------------------------------------------------------------- spawning, protection

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(SpawnerSpawnEvent event) {
        CreatureSpawner spawner = event.getSpawner();
        if (spawner == null) return;
        Block block = spawner.getBlock();
        Record r = recordAt(block);
        if (r == null) return;
        if (!r.enabled) {
            event.setCancelled(true);
            // Wait a full delay before trying again, instead of every tick.
            if (pausing.add(r.key())) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pausing.remove(r.key());
                    if (block.getState() instanceof CreatureSpawner s) {
                        s.setDelay(Math.max(s.getMaxSpawnDelay(), 200));
                        s.update(true, false);
                    }
                });
            }
            return;
        }
        if (r.claimed()) {
            r.spawned++;
            dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) return;
        Record r = recordAt(block);
        if (r == null) return;
        Player player = event.getPlayer();
        if (r.claimed() && !player.getUniqueId().equals(r.owner) && !player.isOp()) {
            event.setCancelled(true);
            fail(player, "This spawner is claimed by " + r.ownerName + ". You can take it over instead (sneak + right-click).");
            return;
        }
        records.remove(r.key());
        save();
        if (r.claimed()) plugin.log(player, "SPAWNER_BROKEN " + r.mob + " at " + r.world + " " + r.where() + " (owner " + r.ownerName + ")", "-");
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> b.getType() == Material.SPAWNER && isClaimed(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> b.getType() == Material.SPAWNER && isClaimed(b));
    }

    private boolean isClaimed(Block b) {
        Record r = recordAt(b);
        return r != null && r.claimed();
    }

    // ---------------------------------------------------------------- saving

    private void load() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = yml.getConfigurationSection("spawners");
        if (all == null) return;
        for (String k : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(k);
            if (s == null) continue;
            Record r = new Record(s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
            String owner = s.getString("owner");
            try {
                r.owner = owner == null || owner.isEmpty() ? null : UUID.fromString(owner);
            } catch (IllegalArgumentException e) {
                r.owner = null;
            }
            r.ownerName = s.getString("owner-name");
            r.tier = s.getInt("tier");
            r.enabled = s.getBoolean("enabled", true);
            r.spawned = s.getLong("spawned");
            r.mob = s.getString("mob", "Unknown");
            r.baseMin = s.getInt("base-min", -1);
            r.baseMax = s.getInt("base-max", -1);
            records.put(r.key(), r);
        }
    }

    public void save() {
        dirty = false;
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Record r : records.values()) {
            String p = "spawners.s" + (i++) + ".";
            yml.set(p + "world", r.world);
            yml.set(p + "x", r.x);
            yml.set(p + "y", r.y);
            yml.set(p + "z", r.z);
            yml.set(p + "owner", r.owner == null ? "" : r.owner.toString());
            yml.set(p + "owner-name", r.ownerName);
            yml.set(p + "tier", r.tier);
            yml.set(p + "enabled", r.enabled);
            yml.set(p + "spawned", r.spawned);
            yml.set(p + "mob", r.mob);
            yml.set(p + "base-min", r.baseMin);
            yml.set(p + "base-max", r.baseMax);
        }
        try {
            yml.save(new File(plugin.getDataFolder(), FILE));
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save " + FILE + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- messages

    private static void tell(Player player, String message, NamedTextColor color) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, color)));
    }

    private static void ok(Player player, String message) {
        tell(player, message, NamedTextColor.GREEN);
    }

    private static void fail(Player player, String message) {
        tell(player, message, NamedTextColor.RED);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }
}
