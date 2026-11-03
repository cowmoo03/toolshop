package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Magnet, Durability Saver, Last Stand, Fireproof, Soulbound, low-durability warnings and block counting. */
public final class PerkListener implements Listener {
    private static final long WARNING_COOLDOWN_MS = 30_000;

    private final ToolShopPlugin plugin;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    public PerkListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    private static int remaining(ItemStack item) {
        if (!(item.getItemMeta() instanceof Damageable d)) return Integer.MAX_VALUE;
        return item.getType().getMaxDurability() - d.getDamage();
    }

    // ------------------------------------------------------------------ Last Stand: refuse to work at 1 durability

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreakAtLastStand(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        if (!lt.isLegendary(tool) || lt.tier(tool, Upgrade.LAST_STAND) <= 0) return;
        if (remaining(tool) <= 1) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Your " + lt.kind(tool).displayName()
                    + " needs repairing before it can be used again.", NamedTextColor.RED));
        }
    }

    // ------------------------------------------------------------------ Durability Saver, Last Stand, warnings

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        Legendary lt = plugin.legendary();
        if (!lt.isLegendary(item)) return;
        Player player = event.getPlayer();

        int saver = lt.usableBy(item, player) ? lt.tier(item, Upgrade.DURABILITY) : 0;
        if (saver > 0 && ThreadLocalRandom.current().nextInt(100)
                < Settings.forTier(plugin.settings().durabilityChance, saver)) {
            event.setCancelled(true);
            return;
        }

        int left = remaining(item);
        int after = left - event.getDamage();
        if (lt.tier(item, Upgrade.LAST_STAND) > 0 && after < 1) {
            // Never let it break: stop at exactly 1 durability.
            event.setDamage(Math.max(0, left - 1));
            after = 1;
            player.sendActionBar(Component.text("Last Stand saved your " + lt.kind(item).displayName()
                    + ". Repair it to keep using it!", NamedTextColor.GOLD));
            return;
        }

        int max = item.getType().getMaxDurability();
        int percent = plugin.settings().lowDurabilityPercent;
        if (percent > 0 && max > 0 && after * 100 <= (long) max * percent) {
            long now = System.currentTimeMillis();
            Long last = lastWarning.get(player.getUniqueId());
            if (last == null || now - last > WARNING_COOLDOWN_MS) {
                lastWarning.put(player.getUniqueId(), now);
                player.sendActionBar(Component.text("⚠ Your " + lt.kind(item).displayName() + " is at "
                        + Math.max(0, after) + "/" + max + " durability!", NamedTextColor.RED));
            }
        }
    }

    // ------------------------------------------------------------------ Magnet

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        if (!lt.usableBy(tool, player) || lt.tier(tool, Upgrade.MAGNET) <= 0) return;
        Iterator<Item> it = event.getItems().iterator();
        while (it.hasNext()) {
            Item drop = it.next();
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(drop.getItemStack());
            if (leftover.isEmpty()) {
                it.remove(); // all of it went into the inventory, so don't spawn it
            } else {
                drop.setItemStack(leftover.values().iterator().next());
            }
        }
    }

    // ------------------------------------------------------------------ Fireproof

    @EventHandler(ignoreCancelled = true)
    public void onDroppedItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.LAVA && cause != EntityDamageEvent.DamageCause.FIRE
                && cause != EntityDamageEvent.DamageCause.FIRE_TICK) return;
        if (isFireproof(item.getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDroppedItemBurn(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item && isFireproof(item.getItemStack())) event.setCancelled(true);
    }

    private boolean isFireproof(ItemStack stack) {
        Legendary lt = plugin.legendary();
        return lt.isLegendary(stack) && lt.tier(stack, Upgrade.FIREPROOF) > 0;
    }

    // ------------------------------------------------------------------ Soulbound

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        Legendary lt = plugin.legendary();
        Player player = event.getEntity();
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (lt.isLegendary(drop) && lt.tier(drop, Upgrade.SOULBOUND) > 0
                    && player.getUniqueId().equals(lt.owner(drop))) {
                it.remove();
                event.getItemsToKeep().add(drop);
            }
        }
    }

    // ------------------------------------------------------------------ Block counting (for ranks)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void countBlock(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        if (lt.usableBy(tool, player)) plugin.registry().addBlocks(lt.id(tool), 1);
    }
}
