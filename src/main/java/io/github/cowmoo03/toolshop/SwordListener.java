package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Legendary sword abilities: Lifesteal (heal part of the damage dealt), Beheading (head drops), Bounty (more XP),
 * plus kill counting for the sword's rank and Last Stand for melee hits. Only the owner's melee hits and kills count.
 * Against players, every ability is multiplied by sword-pvp-multiplier.
 */
public final class SwordListener implements Listener {
    private static final Map<EntityType, Material> HEADS = Map.of(
            EntityType.ZOMBIE, Material.ZOMBIE_HEAD,
            EntityType.SKELETON, Material.SKELETON_SKULL,
            EntityType.CREEPER, Material.CREEPER_HEAD,
            EntityType.PIGLIN, Material.PIGLIN_HEAD,
            EntityType.WITHER_SKELETON, Material.WITHER_SKELETON_SKULL,
            EntityType.PLAYER, Material.PLAYER_HEAD);

    private final ToolShopPlugin plugin;

    public SwordListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    /** The owner's legendary sword in their main hand, or null. */
    private ItemStack sword(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        return lt.usableBy(hand, player) && lt.kind(hand) == ToolKind.SWORD ? hand : null;
    }

    /** The player who hit this entity in melee (not with an arrow), if its last damage was that. */
    private static Player meleeKiller(LivingEntity dead) {
        Player killer = dead.getKiller();
        if (killer == null) return null;
        EntityDamageEvent last = dead.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent by) || !killer.equals(by.getDamager())) return null;
        EntityDamageEvent.DamageCause cause = by.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return null;
        return killer;
    }

    // ---------------------------------------------------------------- Last Stand for hits

    /** A legendary tool kept at 1 durability by Last Stand can't be used to hit things until it's repaired. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHitAtLastStand(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        if (!lt.isLegendary(tool) || lt.tier(tool, Upgrade.LAST_STAND) <= 0) return;
        if (!(tool.getItemMeta() instanceof Damageable d)) return;
        if (tool.getType().getMaxDurability() - d.getDamage() <= 1) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Your " + lt.kind(tool).displayName()
                    + " needs repairing before it can be used again.", NamedTextColor.RED));
        }
    }

    // ---------------------------------------------------------------- Lifesteal

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand) return;
        if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) return;
        ItemStack sword = sword(player);
        if (sword == null) return;
        int tier = plugin.legendary().tier(sword, Upgrade.LIFESTEAL);
        if (tier <= 0) return;
        Settings s = plugin.settings();
        double share = Settings.forTier(s.lifestealPercent, tier) / 100.0;
        if (victim instanceof Player) share *= s.swordPvpMultiplier;
        // Only damage that actually landed counts (no healing off overkill on a nearly dead mob).
        double dealt = Math.min(event.getFinalDamage(), victim.getHealth());
        double heal = dealt * share;
        if (heal <= 0) return;
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue();
        if (player.getHealth() < cap) player.setHealth(Math.min(cap, player.getHealth() + heal));
    }

    // ---------------------------------------------------------------- Beheading, Bounty, kill count

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = meleeKiller(dead);
        if (killer == null || killer.equals(dead)) return;
        ItemStack sword = sword(killer);
        if (sword == null) return;
        Legendary lt = plugin.legendary();
        Settings s = plugin.settings();
        boolean pvp = dead instanceof Player;
        double mult = pvp ? s.swordPvpMultiplier : 1.0;

        plugin.registry().addBlocks(lt.id(sword), 1); // swords count kills

        int behead = lt.tier(sword, Upgrade.BEHEADING);
        Material head = HEADS.get(dead.getType());
        if (behead > 0 && head != null && mult > 0) {
            double chance = Settings.forTier(s.beheadingChance, behead) * mult / 100.0;
            boolean alreadyDropping = event.getDrops().stream().anyMatch(i -> i != null && i.getType() == head);
            if (!alreadyDropping && ThreadLocalRandom.current().nextDouble() < chance) {
                ItemStack drop = ItemStack.of(head);
                if (dead instanceof Player victim && drop.getItemMeta() instanceof SkullMeta meta) {
                    meta.setOwningPlayer(victim);
                    drop.setItemMeta(meta);
                }
                event.getDrops().add(drop);
                killer.playSound(killer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.6f);
                killer.sendActionBar(Component.text("Beheaded!", NamedTextColor.GOLD));
            }
        }

        int bounty = lt.tier(sword, Upgrade.BOUNTY);
        int xp = event.getDroppedExp();
        if (bounty > 0 && xp > 0 && mult > 0) {
            double extra = Settings.forTier(s.bountyPercent, bounty) * mult / 100.0;
            event.setDroppedExp((int) Math.round(xp * (1 + extra)));
        }
    }
}
