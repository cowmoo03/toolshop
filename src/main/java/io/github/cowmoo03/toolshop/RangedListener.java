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
 * Legendary trident (Tidal Pull, Storm Caller) and crossbow (Arrow Saver, Volley, Blast Bolts), plus kill counting for
 * both. Against players, their effects are multiplied by sword-pvp-multiplier.
 */
public final class RangedListener implements Listener {
    private final ToolShopPlugin plugin;
    /** On arrows from a legendary crossbow: the crossbow's id, its Blast Bolts tier, and whether it's a Volley arrow. */
    private final NamespacedKey arrowTool;
    private final NamespacedKey arrowHeavy;
    private final NamespacedKey arrowVolley;
    private final Map<UUID, Long> stormReadyAt = new HashMap<>();
    /** On arrows from a legendary bow: Seeker and Frost Arrows tiers. */
    private final NamespacedKey arrowSeeker;
    private final NamespacedKey arrowFrost;
    /** Seeker arrows still in flight, and how many ticks they've been steered. */
    private final Map<AbstractArrow, Integer> seeking = new HashMap<>();

    public RangedListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.arrowTool = new NamespacedKey(plugin, "arrow_tool");
        this.arrowHeavy = new NamespacedKey(plugin, "arrow_blast");
        this.arrowVolley = new NamespacedKey(plugin, "arrow_volley");
        this.arrowSeeker = new NamespacedKey(plugin, "arrow_seeker");
        this.arrowFrost = new NamespacedKey(plugin, "arrow_frost");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::steerSeekers, 1L, 1L);
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

        int pull = lt.tier(trident, Upgrade.TIDAL_PULL);
        if (thrown && pull > 0 && mult > 0 && !(victim instanceof org.bukkit.entity.Boss)) {
            Player puller = owner;
            double strength = Settings.forTier(s.tidalPullStrength, pull) / 10.0 * mult;
            // Next tick, after the hit's own knockback.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!victim.isValid() || victim.isDead() || !victim.getWorld().equals(puller.getWorld())) return;
                Vector toward = puller.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
                if (toward.lengthSquared() < 9) return; // already close
                victim.setVelocity(toward.normalize().multiply(strength).setY(0.35));
            });
        }

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
        int heavy = lt.tier(bow, Upgrade.BLAST_BOLTS);
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

    /** Volley arrows at reduced strength on players. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        if (!pdc.has(arrowTool, PersistentDataType.STRING) || !pdc.has(arrowVolley, PersistentDataType.BYTE)) return;
        double mult = pvp(event.getEntity());
        if (mult != 1.0) event.setDamage(event.getDamage() * mult);
    }

    /** Blast Bolts: the arrow bursts where it lands, hurting mobs around it. Never breaks blocks or items. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArrowLand(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        int blast = pdc.getOrDefault(arrowHeavy, PersistentDataType.INTEGER, 0);
        if (blast <= 0 || !(arrow.getShooter() instanceof Player shooter)) return;
        pdc.remove(arrowHeavy); // once per arrow
        Settings s = plugin.settings();
        Entity direct = event.getHitEntity();
        Location at = direct != null ? direct.getLocation().add(0, direct.getHeight() / 2, 0) : arrow.getLocation();
        double radius = s.blastBoltsRadius;
        double damage = Settings.forTier(s.blastBoltsDamage, blast);
        if (s.effects) {
            at.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, at, 1);
            at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f);
        }
        DamageSource source = DamageSource.builder(DamageType.PLAYER_EXPLOSION).withCausingEntity(shooter)
                .withDirectEntity(shooter).withDamageLocation(at).build();
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof LivingEntity target) || e.equals(direct) || e.equals(shooter) || e instanceof ArmorStand) continue;
            if (e instanceof org.bukkit.entity.Tameable pet && shooter.equals(pet.getOwner())) continue;
            if (target.getLocation().distanceSquared(at) > radius * radius) continue;
            double mult = pvp(target);
            if (mult <= 0) continue;
            target.damage(damage * mult, source);
        }
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
            if (id != null && !id.equals(plugin.registry().activeId(killer.getUniqueId(), ToolKind.CROSSBOW))
                    && !id.equals(plugin.registry().activeId(killer.getUniqueId(), ToolKind.BOW))) id = null;
        } else if (damager.equals(killer)) {
            ItemStack trident = legendary(killer.getInventory().getItemInMainHand(), killer, ToolKind.TRIDENT);
            if (trident != null) id = plugin.legendary().id(trident);
        }
        if (id != null) plugin.registry().addBlocks(id, 1);
    }

    // ---------------------------------------------------------------- bow: Quick Draw, Seeker, Frost Arrows

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = legendary(event.getBow(), player, ToolKind.BOW);
        if (bow == null) return;
        Legendary lt = plugin.legendary();
        Settings s = plugin.settings();
        if (player.getGameMode() != GameMode.CREATIVE && lt.tier(bow, Upgrade.LAST_STAND) > 0
                && bow.getItemMeta() instanceof Damageable d && bow.getType().getMaxDurability() - d.getDamage() <= 1) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Your Legendary Bow needs repairing before it can be used again.",
                    NamedTextColor.RED));
            return;
        }
        if (!(event.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof Trident) return;
        PersistentDataContainer pdc = arrow.getPersistentDataContainer();
        pdc.set(arrowTool, PersistentDataType.STRING, lt.id(bow));

        int quick = lt.tier(bow, Upgrade.QUICK_DRAW);
        float force = event.getForce();
        if (quick > 0 && force > 0.05f) {
            double floor = Settings.forTier(s.quickDrawPower, quick) / 100.0;
            if (force < floor) {
                arrow.setVelocity(arrow.getVelocity().multiply(floor / force));
                if (floor >= 1.0) arrow.setCritical(true);
            }
        }
        int seeker = lt.tier(bow, Upgrade.SEEKER);
        if (seeker > 0) {
            pdc.set(arrowSeeker, PersistentDataType.INTEGER, seeker);
            seeking.put(arrow, 0);
        }
        int frost = lt.tier(bow, Upgrade.FROST_ARROWS);
        if (frost > 0) pdc.set(arrowFrost, PersistentDataType.INTEGER, frost);
    }

    /** Every tick: nudge Seeker arrows toward the nearest hostile mob ahead of them, for up to 3 seconds. */
    private void steerSeekers() {
        if (seeking.isEmpty()) return;
        Settings s = plugin.settings();
        var it = seeking.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            AbstractArrow arrow = e.getKey();
            int age = e.getValue() + 1;
            if (!arrow.isValid() || arrow.isInBlock() || arrow.isOnGround() || age > 60) {
                it.remove();
                continue;
            }
            e.setValue(age);
            if (age < 3) continue; // let it leave the bow first
            int tier = arrow.getPersistentDataContainer().getOrDefault(arrowSeeker, PersistentDataType.INTEGER, 0);
            if (tier <= 0) {
                it.remove();
                continue;
            }
            Vector vel = arrow.getVelocity();
            double speed = vel.length();
            if (speed < 0.3) continue;
            double radius = Settings.forTier(s.seekerRadius, tier);
            Location from = arrow.getLocation();
            Vector dir = vel.clone().normalize();
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity n : arrow.getWorld().getNearbyEntities(from, radius, radius, radius)) {
                if (!(n instanceof org.bukkit.entity.Enemy) || !(n instanceof LivingEntity target) || target.isDead()) continue;
                Vector to = target.getEyeLocation().toVector().subtract(from.toVector());
                double dist = to.length();
                if (dist > radius || dist < 0.5 || to.normalize().dot(dir) < 0.5) continue; // only ahead of the arrow
                if (dist < bestDist) {
                    best = target;
                    bestDist = dist;
                }
            }
            if (best == null) continue;
            double turn = Settings.forTier(s.seekerTurn, tier) / 100.0;
            Vector want = best.getLocation().add(0, best.getHeight() * 0.6, 0).toVector().subtract(from.toVector()).normalize();
            arrow.setVelocity(dir.multiply(1 - turn).add(want.multiply(turn)).normalize().multiply(speed));
        }
    }

    /** Frost Arrows: Slowness II on hit (shorter on players). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrostHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof AbstractArrow arrow) || !(event.getEntity() instanceof LivingEntity target)) return;
        int frost = arrow.getPersistentDataContainer().getOrDefault(arrowFrost, PersistentDataType.INTEGER, 0);
        if (frost <= 0 || target instanceof ArmorStand) return;
        double mult = pvp(target);
        int ticks = (int) Math.round(Settings.forTier(plugin.settings().frostSeconds, frost) * 20 * mult);
        if (ticks <= 0) return;
        target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, ticks, 1));
        if (plugin.settings().effects) {
            target.getWorld().spawnParticle(org.bukkit.Particle.SNOWFLAKE, target.getLocation().add(0, target.getHeight() / 2, 0),
                    12, 0.3, 0.4, 0.3, 0.02);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stormReadyAt.remove(event.getPlayer().getUniqueId());
    }
}
