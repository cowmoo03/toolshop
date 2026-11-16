package io.github.cowmoo03.toolshop;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Empowered: a percent boost to what the item's main enchantment does, instead of extra enchant levels.
 * <ul>
 *   <li>Pickaxe, axe, shovel, hoe: mining speed while held.</li>
 *   <li>Sword, trident: melee damage (and thrown trident damage).</li>
 *   <li>Bow, crossbow: arrow damage.</li>
 *   <li>Armor: less damage taken, per piece worn.</li>
 *   <li>Elytra: chance to use no durability while gliding.</li>
 * </ul>
 * Weapon bonuses are scaled by sword-pvp-multiplier against players.
 */
public final class EmpoweredListener implements Listener {
    private static final Set<ToolKind> TOOLS = EnumSet.of(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.SHOVEL, ToolKind.HOE);
    private static final Set<ToolKind> ARMOR = EnumSet.of(ToolKind.HELMET, ToolKind.CHESTPLATE, ToolKind.LEGGINGS, ToolKind.BOOTS);
    /** Damage that armor doesn't protect from in vanilla either. */
    private static final Set<String> UNBLOCKABLE = Set.of("VOID", "KILL", "SUICIDE", "STARVATION", "WORLD_BORDER", "CUSTOM");
    /** Most damage Empowered armor can take off, all pieces together. */
    private static final int MAX_ARMOR_PERCENT = 60;

    private final ToolShopPlugin plugin;
    private final NamespacedKey speedKey;
    private final NamespacedKey arrowKey;

    public EmpoweredListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.speedKey = new NamespacedKey(plugin, "empowered_speed");
        this.arrowKey = new NamespacedKey(plugin, "arrow_empowered");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    /** Empowered tier of this item, if it's the player's own legendary item of one of these kinds; 0 otherwise. */
    private int tier(Player p, ItemStack item, Set<ToolKind> kinds) {
        if (item == null || item.isEmpty()) return 0;
        Legendary lt = plugin.legendary();
        ToolKind k = lt.kind(item);
        if (k == null || !kinds.contains(k) || !lt.usableBy(item, p)) return 0;
        return lt.tier(item, Upgrade.EMPOWERED);
    }

    private double pvp(Entity victim) {
        return victim instanceof Player ? plugin.settings().swordPvpMultiplier : 1.0;
    }

    // ---------------------------------------------------------------- mining speed

    private void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) update(p);
    }

    private void update(Player p) {
        if (!p.isOnline()) return;
        int t = tier(p, p.getInventory().getItemInMainHand(), TOOLS);
        double amount = t > 0 ? Settings.forTier(plugin.settings().empoweredPercent, t) / 100.0 : 0;
        AttributeInstance inst = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(speedKey);
        if (existing != null && existing.getAmount() == amount) return;
        if (existing != null) inst.removeModifier(speedKey);
        if (amount != 0) {
            inst.addTransientModifier(new AttributeModifier(speedKey, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                    EquipmentSlotGroup.ANY));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> update(p));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> update(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        AttributeInstance inst = event.getPlayer().getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (inst != null && inst.getModifier(speedKey) != null) inst.removeModifier(speedKey);
    }

    // ---------------------------------------------------------------- weapons

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player p) || !(event.getProjectile() instanceof AbstractArrow arrow)) return;
        int t = tier(p, event.getBow(), EnumSet.of(ToolKind.BOW, ToolKind.CROSSBOW));
        if (t > 0) {
            arrow.getPersistentDataContainer().set(arrowKey, PersistentDataType.INTEGER,
                    Settings.forTier(plugin.settings().empoweredPercent, t));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Entity d = event.getDamager();
        int pct = 0;
        if (d instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            int t = tier(p, p.getInventory().getItemInMainHand(), EnumSet.of(ToolKind.SWORD, ToolKind.TRIDENT));
            if (t > 0) pct = Settings.forTier(plugin.settings().empoweredPercent, t);
        } else if (d instanceof Trident tr && tr.getShooter() instanceof Player p) {
            int t = tier(p, tr.getItemStack(), EnumSet.of(ToolKind.TRIDENT));
            if (t > 0) pct = Settings.forTier(plugin.settings().empoweredPercent, t);
        } else if (d instanceof AbstractArrow arrow) {
            Integer v = arrow.getPersistentDataContainer().get(arrowKey, PersistentDataType.INTEGER);
            if (v != null) pct = v;
        }
        if (pct <= 0) return;
        event.setDamage(event.getDamage() * (1 + pct / 100.0 * pvp(event.getEntity())));
    }

    // ---------------------------------------------------------------- armor

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || UNBLOCKABLE.contains(event.getCause().name())) return;
        int pct = 0;
        for (ItemStack piece : p.getInventory().getArmorContents()) {
            int t = tier(p, piece, ARMOR);
            if (t > 0) pct += Settings.forTier(plugin.settings().empoweredArmorPercent, t);
        }
        if (pct <= 0) return;
        event.setDamage(event.getDamage() * (1 - Math.min(MAX_ARMOR_PERCENT, pct) / 100.0));
    }

    // ---------------------------------------------------------------- elytra

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWear(PlayerItemDamageEvent event) {
        int t = tier(event.getPlayer(), event.getItem(), EnumSet.of(ToolKind.ELYTRA));
        if (t <= 0) return;
        int chance = Settings.forTier(plugin.settings().empoweredElytraPercent, t);
        if (ThreadLocalRandom.current().nextInt(100) < chance) event.setCancelled(true);
    }
}
