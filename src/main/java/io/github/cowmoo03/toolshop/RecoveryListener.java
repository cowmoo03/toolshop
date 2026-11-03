package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

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
 *   <li>Tier 1+: a dropped tool glows through walls and stays extra time before despawning; on death the owner is
 *   told where it dropped.</li>
 *   <li>Tier 2+: explosions and cactus can't destroy it.</li>
 *   <li>Tier 3: if it falls into the void it goes back to the owner (inventory, then ender chest, then at their
 *   feet; if they're offline, when they next join).</li>
 * </ul>
 * The extra time counts game ticks while the item's chunk is loaded, like Fireproof.
 */
public final class RecoveryListener implements Listener {
    private static final long TASK_PERIOD_TICKS = 5L;

    private final ToolShopPlugin plugin;
    /** Ticks of extra time left, stored on the item entity so it survives chunk unloads and restarts. */
    private final NamespacedKey extraLeftKey;
    /** Dropped Recovery tools in loaded chunks. */
    private final Map<UUID, Item> tracked = new HashMap<>();
    private final File returnsFile;
    /** Owner -> tools that fell into the void while the owner was offline. */
    private final Map<UUID, List<ItemStack>> pendingReturns = new HashMap<>();

    public RecoveryListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.extraLeftKey = new NamespacedKey(plugin, "lt_recovery_left");
        this.returnsFile = new File(plugin.getDataFolder(), "returns.yml");
        loadReturns();
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
            if (e instanceof Item item && tier(item) > 0) tracked.put(item.getUniqueId(), item);
        }
    }

    private void start(Item item) {
        if (tier(item) <= 0) return;
        item.setGlowing(true);
        tracked.put(item.getUniqueId(), item);
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
                    + plugin.legendary().kind(item.getItemStack()).displayName() + " was about to despawn at "
                    + where(item.getLocation()) + ". Recovery keeps it for " + formatSeconds(seconds) + " more.",
                    NamedTextColor.GOLD)));
        }
    }

    private void tick() {
        Iterator<Item> it = tracked.values().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (!item.isValid()) { // picked up, removed or its chunk unloaded
                it.remove();
                continue;
            }
            int tier = tier(item);
            if (tier <= 0) {
                it.remove();
                continue;
            }
            if (tier >= 3 && item.getLocation().getY() < item.getWorld().getMinHeight()) {
                it.remove();
                ItemStack stack = item.getItemStack();
                item.remove();
                returnToOwner(stack);
                continue;
            }
            Integer left = item.getPersistentDataContainer().get(extraLeftKey, PersistentDataType.INTEGER);
            if (left == null) continue;
            left -= (int) TASK_PERIOD_TICKS;
            if (left <= 0) {
                it.remove();
                item.remove();
            } else {
                item.getPersistentDataContainer().set(extraLeftKey, PersistentDataType.INTEGER, left);
            }
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
                player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Your " + name + " dropped at "
                        + where(at) + ". It's glowing, and Recovery keeps it " + formatSeconds(seconds)
                        + " longer than normal items.", NamedTextColor.GOLD)));
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
