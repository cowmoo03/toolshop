package io.github.cowmoo03.toolshop;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Legendary armor: Night Vision and Water Breathing (helmet), Vitality (chestplate), Swiftness (leggings), Spring and
 * Lava Walker (boots), and a full-set bonus (Regeneration after 10 s without being hurt). Effects and attribute
 * bonuses are refreshed every second and removed as soon as the piece comes off. Attribute bonuses are transient,
 * so they're never saved onto the player.
 */
public final class ArmorListener implements Listener {
    private static final int EFFECT_TICKS = 400; // refreshed every second; long enough that Night Vision never flickers
    private static final long SET_REGEN_AFTER_MS = 10_000;
    private static final long LAVA_REVERT_TICKS = 100;

    private final ToolShopPlugin plugin;
    private final NamespacedKey vitalityKey;
    private final NamespacedKey swiftKey;
    private final NamespacedKey springKey;
    private final NamespacedKey springFallKey;
    /** Effects this listener gave each player, so only those are taken away. */
    private final Map<UUID, Set<String>> given = new HashMap<>();
    private final Map<UUID, Long> lastHurt = new HashMap<>();
    /** Lava turned to obsidian by Lava Walker -> when to turn it back. */
    private final Map<Block, Long> hardened = new HashMap<>();
    private long ticks;

    public ArmorListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.vitalityKey = new NamespacedKey(plugin, "armor_vitality");
        this.swiftKey = new NamespacedKey(plugin, "armor_swiftness");
        this.springKey = new NamespacedKey(plugin, "armor_spring");
        this.springFallKey = new NamespacedKey(plugin, "armor_spring_fall");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** The player's legendary piece of this kind, if they're wearing it and own it. */
    private ItemStack worn(Player player, ToolKind kind) {
        ItemStack item = switch (kind) {
            case HELMET -> player.getInventory().getHelmet();
            case CHESTPLATE -> player.getInventory().getChestplate();
            case LEGGINGS -> player.getInventory().getLeggings();
            case BOOTS -> player.getInventory().getBoots();
            default -> null;
        };
        Legendary lt = plugin.legendary();
        return item != null && lt.usableBy(item, player) && lt.kind(item) == kind ? item : null;
    }

    private int tier(Player player, ToolKind kind, Upgrade u) {
        ItemStack item = worn(player, kind);
        return item == null ? 0 : plugin.legendary().tier(item, u);
    }

    // ---------------------------------------------------------------- every second

    private void tick() {
        ticks++;
        Settings s = plugin.settings();
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.isDead()) continue;
            Set<String> mine = given.computeIfAbsent(p.getUniqueId(), k -> new java.util.HashSet<>());
            effect(p, mine, "nv", PotionEffectType.NIGHT_VISION, 0, tier(p, ToolKind.HELMET, Upgrade.NIGHT_VISION) > 0);
            effect(p, mine, "wb", PotionEffectType.WATER_BREATHING, 0, tier(p, ToolKind.HELMET, Upgrade.WATER_BREATHING) > 0);

            boolean fullSet = s.armorSetBonus && worn(p, ToolKind.HELMET) != null && worn(p, ToolKind.CHESTPLATE) != null
                    && worn(p, ToolKind.LEGGINGS) != null && worn(p, ToolKind.BOOTS) != null;
            boolean calm = now - lastHurt.getOrDefault(p.getUniqueId(), 0L) >= SET_REGEN_AFTER_MS;
            effect(p, mine, "regen", PotionEffectType.REGENERATION, 0, fullSet && calm);

            int vit = tier(p, ToolKind.CHESTPLATE, Upgrade.VITALITY);
            modifier(p, Attribute.MAX_HEALTH, vitalityKey, vit > 0 ? Settings.forTier(s.vitalityHealth, vit) : 0,
                    AttributeModifier.Operation.ADD_NUMBER);
            int swift = tier(p, ToolKind.LEGGINGS, Upgrade.SWIFTNESS);
            modifier(p, Attribute.MOVEMENT_SPEED, swiftKey, swift > 0 ? Settings.forTier(s.swiftnessPercent, swift) / 100.0 : 0,
                    AttributeModifier.Operation.ADD_SCALAR);
            int spring = tier(p, ToolKind.BOOTS, Upgrade.SPRING);
            modifier(p, Attribute.JUMP_STRENGTH, springKey, spring > 0 ? Settings.forTier(s.springPercent, spring) / 100.0 : 0,
                    AttributeModifier.Operation.ADD_SCALAR);
            modifier(p, Attribute.SAFE_FALL_DISTANCE, springFallKey, spring, AttributeModifier.Operation.ADD_NUMBER);
            if (!hardened.isEmpty() && tier(p, ToolKind.BOOTS, Upgrade.LAVA_WALKER) > 0) keepUnder(p);
        }
        revertLava(false);
    }

    /** Gives (or keeps topped up) an effect while {@code on}, and removes it once it's off, if this listener gave it. */
    private void effect(Player p, Set<String> mine, String id, PotionEffectType type, int amp, boolean on) {
        PotionEffect current = p.getPotionEffect(type);
        if (on) {
            // Don't override a stronger or longer effect the player got elsewhere (a potion, a beacon).
            if (current != null && !mine.contains(id) && (current.getAmplifier() > amp || current.getDuration() > EFFECT_TICKS)) return;
            // Regeneration heals on a timer tied to its remaining duration, so it's only topped up once it runs low
            // (re-adding it every second would heal far faster than Regeneration I).
            boolean regen = type == PotionEffectType.REGENERATION;
            boolean low = current == null || (regen ? current.getDuration() <= 25 : current.getDuration() < EFFECT_TICKS - 40);
            if (low) p.addPotionEffect(new PotionEffect(type, regen ? 200 : EFFECT_TICKS, amp, true, false, true));
            mine.add(id);
        } else if (mine.remove(id) && current != null && current.isAmbient() && current.getDuration() <= EFFECT_TICKS) {
            p.removePotionEffect(type);
        }
    }

    /** Keeps exactly one transient modifier of this key at this amount (0 = none). */
    private static void modifier(Player p, Attribute attribute, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(key);
        if (existing != null && existing.getAmount() == amount) return;
        if (existing != null) inst.removeModifier(key);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
        if (attribute == Attribute.MAX_HEALTH && p.getHealth() > inst.getValue()) p.setHealth(inst.getValue());
    }

    // ---------------------------------------------------------------- hits taken, set bonus timer

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || event.getFinalDamage() <= 0) return;
        lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
        Legendary lt = plugin.legendary();
        for (ToolKind kind : EnumSet.of(ToolKind.HELMET, ToolKind.CHESTPLATE, ToolKind.LEGGINGS, ToolKind.BOOTS)) {
            ItemStack item = worn(p, kind);
            if (item != null) plugin.registry().addBlocks(lt.id(item), 1);
        }
    }

    // ---------------------------------------------------------------- Lava Walker

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        Player p = event.getPlayer();
        if (p.isSneaking() || p.isFlying() || p.getGameMode() == GameMode.SPECTATOR) return;
        if (tier(p, ToolKind.BOOTS, Upgrade.LAVA_WALKER) <= 0) return;
        Block under = to.getBlock().getRelative(BlockFace.DOWN);
        long revertAt = ticks + LAVA_REVERT_TICKS / 20;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 5) continue;
                Block b = under.getRelative(dx, 0, dz);
                if (b.getType() != Material.LAVA || !(b.getBlockData() instanceof Levelled l) || l.getLevel() != 0) continue;
                if (!b.getRelative(BlockFace.UP).getType().isAir()) continue;
                b.setType(Material.OBSIDIAN, false);
                hardened.put(b, revertAt);
            }
        }
        keepUnder(p);
    }

    /** Keeps the hardened lava around a player from turning back while they're on it (even standing still). */
    private void keepUnder(Player p) {
        Block under = p.getLocation().getBlock().getRelative(BlockFace.DOWN);
        long revertAt = ticks + LAVA_REVERT_TICKS / 20;
        for (Map.Entry<Block, Long> e : hardened.entrySet()) {
            Block b = e.getKey();
            if (b.getWorld().equals(under.getWorld()) && Math.abs(b.getX() - under.getX()) <= 2
                    && Math.abs(b.getZ() - under.getZ()) <= 2 && Math.abs(b.getY() - under.getY()) <= 1) {
                e.setValue(Math.max(e.getValue(), revertAt));
            }
        }
    }

    /** Turns Lava Walker's obsidian back into lava (all of it when {@code all}). */
    private void revertLava(boolean all) {
        Iterator<Map.Entry<Block, Long>> it = hardened.entrySet().iterator();
        List<Block> back = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<Block, Long> e = it.next();
            if (all || e.getValue() <= ticks) {
                back.add(e.getKey());
                it.remove();
            }
        }
        for (Block b : back) if (b.getType() == Material.OBSIDIAN) b.setType(Material.LAVA);
    }

    /** Lava Walker's temporary obsidian drops nothing (no free obsidian). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (hardened.remove(event.getBlock()) != null) event.setDropItems(false);
    }

    // ---------------------------------------------------------------- cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        clear(p);
        lastHurt.remove(p.getUniqueId());
    }

    private void clear(Player p) {
        Set<String> mine = given.remove(p.getUniqueId());
        if (mine != null) {
            effect(p, mine, "nv", PotionEffectType.NIGHT_VISION, 0, false);
            effect(p, mine, "wb", PotionEffectType.WATER_BREATHING, 0, false);
            effect(p, mine, "regen", PotionEffectType.REGENERATION, 0, false);
        }
        modifier(p, Attribute.MAX_HEALTH, vitalityKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        modifier(p, Attribute.MOVEMENT_SPEED, swiftKey, 0, AttributeModifier.Operation.ADD_SCALAR);
        modifier(p, Attribute.JUMP_STRENGTH, springKey, 0, AttributeModifier.Operation.ADD_SCALAR);
        modifier(p, Attribute.SAFE_FALL_DISTANCE, springFallKey, 0, AttributeModifier.Operation.ADD_NUMBER);
    }

    public void shutdown() {
        revertLava(true);
        for (Player p : plugin.getServer().getOnlinePlayers()) clear(p);
    }
}
