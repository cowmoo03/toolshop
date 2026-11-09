package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Spawner upgrades: right-click a mob spawner to see its tier, sneak + right-click (twice, to confirm) to upgrade it.
 * Each tier shortens the spawn delay; the spawner's other limits (mobs nearby, player range) stay vanilla.
 * Anyone can upgrade any spawner.
 */
public final class SpawnerUpgrades implements Listener {
    private static final long CONFIRM_MS = 5000;

    public record Tier(double speed, int level, Map<Material, Integer> cost) {}

    private final ToolShopPlugin plugin;
    private final NamespacedKey tierKey;
    private final NamespacedKey baseMinKey;
    private final NamespacedKey baseMaxKey;
    /** Player -> spawner location they clicked once to upgrade, and when. */
    private final Map<UUID, Location> pendingAt = new HashMap<>();
    private final Map<UUID, Long> pendingSince = new HashMap<>();

    private boolean enabled;
    private String skill;
    private String skillName;
    private final List<Tier> tiers = new ArrayList<>();

    public SpawnerUpgrades(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.tierKey = new NamespacedKey(plugin, "spawner_tier");
        this.baseMinKey = new NamespacedKey(plugin, "spawner_base_min");
        this.baseMaxKey = new NamespacedKey(plugin, "spawner_base_max");
        reload();
    }

    public void reload() {
        tiers.clear();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("spawner-upgrades");
        enabled = c == null || c.getBoolean("enabled", true);
        String s = c == null ? "" : c.getString("skill", "");
        skill = s == null || s.isBlank() || s.equalsIgnoreCase("none") ? null : s.toUpperCase(Locale.ROOT);
        skillName = skill == null ? null : skill.charAt(0) + skill.substring(1).toLowerCase(Locale.ROOT);
        List<Map<?, ?>> raw = c == null ? List.of() : c.getMapList("tiers");
        for (Map<?, ?> entry : raw) {
            double speed = entry.get("speed") instanceof Number n ? n.doubleValue() : 1.0;
            int level = entry.get("level") instanceof Number n ? n.intValue() : 0;
            Map<Material, Integer> cost = new LinkedHashMap<>();
            if (entry.get("cost") instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Material mat = Material.matchMaterial(String.valueOf(e.getKey()));
                    int amount = e.getValue() instanceof Number n ? n.intValue() : 0;
                    if (mat != null && mat.isItem() && amount > 0) cost.put(mat, amount);
                    else plugin.getLogger().warning("spawner-upgrades: unknown item '" + e.getKey() + "'");
                }
            }
            tiers.add(new Tier(Math.max(1.0, Math.min(10.0, speed)), Math.max(0, level), cost));
        }
    }

    public boolean enabled() { return enabled && !tiers.isEmpty(); }
    public List<Tier> tiers() { return tiers; }
    public String skillName() { return skillName; }

    /** "+25%" for a speed of 1.25. */
    public static String faster(double speed) {
        long pct = Math.round((speed - 1) * 100);
        return speed >= 2 && pct % 100 == 0 ? Math.round(speed) + "x as fast" : pct + "% faster";
    }

    private int levelNeeded(Tier t) {
        return skill == null ? 0 : t.level() * plugin.levelScale();
    }

    // ---------------------------------------------------------------- clicking a spawner

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        if (!enabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;
        if (!(block.getState() instanceof CreatureSpawner spawner)) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR || !player.hasPermission("toolshop.use")) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        String handName = hand.getType().name();
        if (handName.endsWith("_SPAWN_EGG")) return; // changing the mob: vanilla
        if (!player.isSneaking()) {
            if (!hand.isEmpty() && hand.getType().isBlock()) return; // let them build against it
            showInfo(player, spawner);
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true); // sneaking: don't place a block
        upgrade(player, block, spawner);
    }

    private int tierOf(CreatureSpawner spawner) {
        return spawner.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
    }

    private static String mobName(CreatureSpawner spawner) {
        EntityType type = spawner.getSpawnedType();
        if (type == null) return "Empty";
        String n = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void showInfo(Player player, CreatureSpawner spawner) {
        int tier = tierOf(spawner);
        int max = tiers.size();
        tell(player, mobName(spawner) + " spawner: " + (tier == 0 ? "not upgraded"
                : "Tier " + Upgrade.roman(tier) + " (spawns " + faster(tiers.get(tier - 1).speed()) + ")"), NamedTextColor.GOLD);
        if (tier >= max) {
            tell(player, "It's fully upgraded.", NamedTextColor.GRAY);
            return;
        }
        Tier next = tiers.get(tier);
        String req = Settings.describeCost(next.cost());
        int need = levelNeeded(next);
        if (need > 0) req += " + " + skillName + " " + need;
        tell(player, "Next: Tier " + Upgrade.roman(tier + 1) + " (spawns " + faster(next.speed()) + ") for " + req
                + ". Sneak + right-click to upgrade.", NamedTextColor.YELLOW);
    }

    private void upgrade(Player player, Block block, CreatureSpawner spawner) {
        int tier = tierOf(spawner);
        if (tier >= tiers.size()) {
            tell(player, "This spawner is fully upgraded.", NamedTextColor.GRAY);
            return;
        }
        Tier next = tiers.get(tier);
        int need = levelNeeded(next);
        if (need > 0 && plugin.mcmmo().available()) {
            int have = plugin.mcmmo().level(player, skill);
            if (have < need) {
                fail(player, "Tier " + Upgrade.roman(tier + 1) + " needs " + skillName + " " + need + " (you're " + Math.max(0, have) + ").");
                return;
            }
        }
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        List<String> missing = creative ? List.of() : ShopListener.missingItems(player, next.cost());
        if (!missing.isEmpty()) {
            fail(player, "You still need: " + String.join(", ", missing));
            return;
        }

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean confirmed = block.getLocation().equals(pendingAt.get(id)) && now - pendingSince.getOrDefault(id, 0L) <= CONFIRM_MS;
        if (!confirmed) {
            pendingAt.put(id, block.getLocation());
            pendingSince.put(id, now);
            tell(player, "Upgrade to Tier " + Upgrade.roman(tier + 1) + " (spawns " + faster(next.speed()) + ") for "
                    + Settings.describeCost(next.cost()) + "? Sneak + right-click again to confirm.", NamedTextColor.YELLOW);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            return;
        }
        pendingAt.remove(id);
        pendingSince.remove(id);

        if (!creative) ShopListener.take(player, next.cost());
        PersistentDataContainer pdc = spawner.getPersistentDataContainer();
        int baseMin = pdc.getOrDefault(baseMinKey, PersistentDataType.INTEGER, spawner.getMinSpawnDelay());
        int baseMax = pdc.getOrDefault(baseMaxKey, PersistentDataType.INTEGER, spawner.getMaxSpawnDelay());
        pdc.set(baseMinKey, PersistentDataType.INTEGER, baseMin);
        pdc.set(baseMaxKey, PersistentDataType.INTEGER, baseMax);
        pdc.set(tierKey, PersistentDataType.INTEGER, tier + 1);
        int newMin = Math.max(20, (int) Math.round(baseMin / next.speed()));
        int newMax = Math.max(newMin + 1, (int) Math.round(baseMax / next.speed()));
        // Lower the minimum first so it never goes above the maximum.
        spawner.setMinSpawnDelay(Math.min(newMin, spawner.getMinSpawnDelay()));
        spawner.setMaxSpawnDelay(newMax);
        spawner.setMinSpawnDelay(newMin);
        if (spawner.getDelay() > newMax) spawner.setDelay(newMax);
        spawner.update(true, false);

        plugin.log(player, "SPAWNER " + mobName(spawner) + " at " + block.getWorld().getName() + " " + block.getX() + ","
                + block.getY() + "," + block.getZ() + " to tier " + (tier + 1) + ", paid "
                + (creative ? "nothing (creative)" : Settings.describeCost(next.cost())), "-");
        tell(player, "Spawner upgraded to Tier " + Upgrade.roman(tier + 1) + ": it now spawns " + faster(next.speed()) + ".",
                NamedTextColor.GREEN);
        if (plugin.settings().effects) {
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 20, 0.5, 0.5, 0.5, 0);
            block.getWorld().playSound(at, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        }
    }

    private static void tell(Player player, String message, NamedTextColor color) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, color)));
    }

    private static void fail(Player player, String message) {
        tell(player, message, NamedTextColor.RED);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }
}
