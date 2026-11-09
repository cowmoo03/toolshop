package io.github.cowmoo03.toolshop;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Legendary elytra: Boost (sneak while gliding), Rocket Saver, Safe Landing, and counting blocks flown. */
public final class ElytraListener implements Listener {
    /** Fastest a Boost can push you, in blocks per tick (a firework-boosted glide is about 1.7). */
    private static final double MAX_SPEED = 2.2;
    /** A landing within this long after gliding stops still counts as a gliding landing. */
    private static final long LANDING_GRACE_MS = 2000;

    private final ToolShopPlugin plugin;
    /** Boosts used since the player last stood on the ground. */
    private final Map<UUID, Integer> boostsUsed = new HashMap<>();
    private final Map<UUID, Long> lastBoost = new HashMap<>();
    private final Map<UUID, Long> stoppedGliding = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final Map<UUID, Double> distanceCarry = new HashMap<>();

    public ElytraListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** The owner's legendary elytra in their chest slot, or null. */
    private ItemStack elytra(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        Legendary lt = plugin.legendary();
        return chest != null && lt.usableBy(chest, player) && lt.kind(chest) == ToolKind.ELYTRA ? chest : null;
    }

    // ---------------------------------------------------------------- Boost

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking() || !player.isGliding()) return;
        ItemStack elytra = elytra(player);
        if (elytra == null) return;
        int tier = plugin.legendary().tier(elytra, Upgrade.BOOST);
        if (tier <= 0) return;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - lastBoost.getOrDefault(id, 0L) < 500) return;
        int max = Settings.forTier(plugin.settings().boostCharges, tier);
        int used = boostsUsed.getOrDefault(id, 0);
        if (used >= max) {
            player.sendActionBar(Component.text("No boosts left. Land to refill them.", NamedTextColor.RED));
            return;
        }
        boostsUsed.put(id, used + 1);
        lastBoost.put(id, now);
        Vector dir = player.getLocation().getDirection().normalize();
        Vector v = player.getVelocity().add(dir.multiply(plugin.settings().boostStrength));
        if (v.length() > MAX_SPEED) v = v.normalize().multiply(MAX_SPEED);
        player.setVelocity(v);
        int left = max - used - 1;
        player.sendActionBar(Component.text("Boost! " + left + " left", NamedTextColor.AQUA));
        if (plugin.settings().effects) {
            player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(), 12, 0.2, 0.2, 0.2, 0.05);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.2f);
        }
    }

    // ---------------------------------------------------------------- Rocket Saver

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRocket(PlayerElytraBoostEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack elytra = elytra(player);
        if (elytra == null) return;
        int tier = plugin.legendary().tier(elytra, Upgrade.ROCKET_SAVER);
        if (tier > 0 && ThreadLocalRandom.current().nextInt(100) < Settings.forTier(plugin.settings().rocketSaverChance, tier)) {
            event.setShouldConsume(false);
        }
    }

    // ---------------------------------------------------------------- Safe Landing

    @EventHandler
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player && !event.isGliding()) {
            stoppedGliding.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.FLY_INTO_WALL && cause != EntityDamageEvent.DamageCause.FALL) return;
        ItemStack elytra = elytra(player);
        if (elytra == null || plugin.legendary().tier(elytra, Upgrade.SAFE_LANDING) <= 0) return;
        if (cause == EntityDamageEvent.DamageCause.FALL) {
            boolean gliding = player.isGliding()
                    || System.currentTimeMillis() - stoppedGliding.getOrDefault(player.getUniqueId(), 0L) <= LANDING_GRACE_MS;
            if (!gliding) return;
        }
        event.setCancelled(true);
    }

    // ---------------------------------------------------------------- every second: refills and blocks flown

    private void tick() {
        Legendary lt = plugin.legendary();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!player.isGliding() && player.isOnGround()) boostsUsed.remove(id);
            ItemStack elytra = player.isGliding() ? elytra(player) : null;
            if (elytra == null) {
                lastPos.remove(id);
                continue;
            }
            Location now = player.getLocation();
            Location before = lastPos.put(id, now);
            if (before == null || !before.getWorld().equals(now.getWorld())) continue;
            double moved = before.distance(now);
            if (moved > 200) continue; // teleported
            double total = distanceCarry.getOrDefault(id, 0.0) + moved;
            long whole = (long) total;
            distanceCarry.put(id, total - whole);
            if (whole > 0) plugin.registry().addBlocks(lt.id(elytra), whole);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        boostsUsed.remove(id);
        lastBoost.remove(id);
        stoppedGliding.remove(id);
        lastPos.remove(id);
        distanceCarry.remove(id);
    }
}
