package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Legendary trident (Impaler, Storm Caller) and crossbow (Arrow Saver, Volley, Heavy Bolts), plus kill counting for
 * both. Against players, damage bonuses are multiplied by sword-pvp-multiplier.
 */
public final class RangedListener implements Listener {
    private final ToolShopPlugin plugin;
    /** On arrows from a legendary crossbow: the crossbow's id, its Heavy Bolts tier, and whether it's a Volley arrow. */
    private final NamespacedKey arrowTool;
    private final NamespacedKey arrowHeavy;
    private final NamespacedKey arrowVolley;
    private final Map<UUID, Long> stormReadyAt = new HashMap<>();

    public RangedListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.arrowTool = new NamespacedKey(plugin, "arrow_tool");
        this.arrowHeavy = new NamespacedKey(plugin, "arrow_heavy");
        this.arrowVolley = new NamespacedKey(plugin, "arrow_volley");
    }

    private double pvp(Entity victim) {
        return victim instanceof Player ? plugin.settings().swordPvpMultiplier : 1.0;
    }

    /** The owner's legendary item of this kind, or null. */
    private ItemStack legendary(ItemStack item, Player owner, ToolKind kind) {
        Legendary lt = plugin.legendary();
        return item != null && lt.usableBy(item, owner) && lt.kind(item) == kind ? item : null;
    }

    /** For a thrown legendary trident: its item (null if it isn't one, or its thrower doesn't own it). */
    private ItemStack thrownTrident(Trident trident) {
        if (!(trident.getShooter() instanceof Player owner)) return null;
        return legendary(trident.getItemStack(), owner, ToolKind.TRIDENT);
    }

    // ---------------------------------------------------------------- Impaler and Storm Caller

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTridentHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand) return;
        ItemStack trident = null;
        Player owner = null;
        boolean thrown = false;
        if (event.getDamager() instanceof Trident t) {
            trident = thrownTrident(t);
            owner = t.getShooter() instanceof Player p ? p : null;
            thrown = true;
        } else if (event.getDamager() instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            trident = legendary(p.getInventory().getItemInMainHand(), p, ToolKind.TRIDENT);
            owner = p;
        }
        if (trident == null || owner == null || owner.equals(victim)) return;
        Legendary lt = plugin.legendary();
        Settings s = plugin.settings();
        double mult = pvp(victim);

        int impaler = lt.tier(trident, Upgrade.IMPALER);
        if (impaler > 0 && mult > 0) event.setDamage(event.getDamage() + Settings.forTier(s.impalerDamage, impaler) * mult);

        int storm = lt.tier(trident, Upgrade.STORM_CALLER);
        if (!thrown || storm <= 0 || mult <= 0) return;
        long now = System.currentTimeMillis();
        if (stormReadyAt.getOrDefault(owner.getUniqueId(), 0L) > now) return;
        stormReadyAt.put(owner.getUniqueId(), now + Settings.forTier(s.stormCallerCooldown, storm) * 1000L);
        Player caster = owner;
        double damage = s.stormCallerDamage * mult;
        // Next tick, so the lightning isn't swallowed by the hit's own damage cooldown.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!victim.isValid() || victim.isDead()) return;
            Location at = victim.getLocation();
            victim.getWorld().strikeLightningEffect(at);
            victim.setNoDamageTicks(0);
            if (damage > 0) {
                victim.damage(damage, DamageSource.builder(DamageType.LIGHTNING_BOLT).withCausingEntity(caster).build());
            }
            victim.setFireTicks(Math.max(victim.getFireTicks(), 80));
        });
    }

    // ---------------------------------------------------------------- Crossbow shots

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = legendary(event.getBow(), player, ToolKind.CROSSBOW);
        if (bow == null) return;
        Legendary lt = plugin.legendary();
        Settings s = plugin.settings();

        if (player.getGameMode() != GameMode.CREATIVE && lt.tier(bow, Upgrade.LAST_STAND) > 0
                && bow.getItemMeta() instanceof Damageable d && bow.getType().getMaxDurability() - d.getDamage() <= 1) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Your Legendary Crossbow needs repairing before it can be used again.",
                    NamedTextColor.RED));
            return;
        }
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;

        String id = lt.id(bow);
        int heavy = lt.tier(bow, Upgrade.HEAVY_BOLTS);
        tag(arrow, id, heavy, false);
        // Multishot's side arrows can't be picked up; only the main arrow gets Arrow Saver and Volley.
        if (arrow.getPickupStatus() != AbstractArrow.PickupStatus.ALLOWED) return;

        int saver = lt.tier(bow, Upgrade.ARROW_SAVER);
        ItemStack ammo = event.getConsumable();
        if (saver > 0 && ammo != null && player.getGameMode() != GameMode.CREATIVE
                && ThreadLocalRandom.current().nextInt(100) < Settings.forTier(s.arrowSaverChance, saver)) {
            ItemStack back = ammo.clone();
            back.setAmount(1);
            player.getInventory().addItem(back).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            arrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY); // no picking up a second copy
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.6f);
        }

        int volley = lt.tier(bow, Upgrade.VOLLEY);
        if (volley <= 0) return;
        int extra = Settings.forTier(s.volleyArrows, volley);
        Vector velocity = arrow.getVelocity();
        double speed = velocity.length();
        Location from = arrow.getLocation();
        for (int i = 1; i <= extra; i++) {
            double degrees = ((i + 1) / 2) * 6.0 * (i % 2 == 1 ? 1 : -1); // 6, -6, 12, -12...
            Vector dir = velocity.clone().rotateAroundY(Math.toRadians(degrees)).normalize();
            Arrow extraArrow = player.getWorld().spawnArrow(from, dir, (float) speed, 1f);
            extraArrow.setShooter(player);
            extraArrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
            extraArrow.setDamage(arrow.getDamage());
            extraArrow.setCritical(arrow.isCritical());
            extraArrow.setPierceLevel(arrow.getPierceLevel());
            extraArrow.setFireTicks(arrow.getFireTicks());
            extraArrow.setShotFromCrossbow(true);
            tag(extraArrow, id, heavy, true);
        }
    }

    private void tag(AbstractArrow arrow, String toolId, int heavy, boolean volley) {
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        if (toolId != null) pdc.set(arrowTool, PersistentDataType.STRING, toolId);
        pdc.set(arrowHeavy, PersistentDataType.INTEGER, heavy);
        if (volley) pdc.set(arrowVolley, PersistentDataType.BYTE, (byte) 1);
    }

    /** Heavy Bolts, and Volley arrows at reduced strength on players. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        if (!pdc.has(arrowTool, PersistentDataType.STRING)) return;
        double mult = pvp(event.getEntity());
        double factor = 1.0;
        int heavy = pdc.getOrDefault(arrowHeavy, PersistentDataType.INTEGER, 0);
        if (heavy > 0) factor += Settings.forTier(plugin.settings().heavyBoltsPercent, heavy) / 100.0 * mult;
        if (pdc.has(arrowVolley, PersistentDataType.BYTE)) factor *= mult;
        if (factor != 1.0) event.setDamage(event.getDamage() * factor);
    }

    // ---------------------------------------------------------------- kill count

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) return;
        if (!(dead.getLastDamageCause() instanceof EntityDamageByEntityEvent by)) return;
        Entity damager = by.getDamager();
        String id = null;
        if (damager instanceof Trident t && killer.equals(t.getShooter())) {
            ItemStack trident = thrownTrident(t);
            if (trident != null) id = plugin.legendary().id(trident);
        } else if (damager instanceof AbstractArrow arrow && killer.equals(arrow.getShooter())) {
            id = arrow.getPersistentDataContainer().get(arrowTool, PersistentDataType.STRING);
            // Only count it while that crossbow is still the player's current one.
            if (id != null && !id.equals(plugin.registry().activeId(killer.getUniqueId(), ToolKind.CROSSBOW))) id = null;
        } else if (damager.equals(killer)) {
            ItemStack trident = legendary(killer.getInventory().getItemInMainHand(), killer, ToolKind.TRIDENT);
            if (trident != null) id = plugin.legendary().id(trident);
        }
        if (id != null) plugin.registry().addBlocks(id, 1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stormReadyAt.remove(event.getPlayer().getUniqueId());
    }
}
