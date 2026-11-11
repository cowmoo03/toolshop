package io.github.cowmoo03.toolshop;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Legendary hoe: Harvest (area harvest of grown crops), Growth Aura (crops nearby grow faster) and Tiller (area tilling). */
public final class HoeListener implements Listener {
    /** Crop block -> the item that plants it. */
    private static final Map<Material, Material> SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART);
    private static final Set<Material> TILLABLE = Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.DIRT_PATH);

    private final ToolShopPlugin plugin;
    /** Players whose extra crops are being broken right now, so those breaks don't start another harvest. */
    private final Set<UUID> busy = new HashSet<>();

    public HoeListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::growthTick, 20L, 20L);
    }

    /** The owner's legendary hoe in their main hand, or null. */
    private ItemStack hoe(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        return lt.usableBy(hand, player) && lt.kind(hand) == ToolKind.HOE ? hand : null;
    }

    private static boolean isGrownCrop(BlockData data) {
        return SEEDS.containsKey(data.getMaterial()) && data instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    private boolean mayUseAbility(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR || !player.hasPermission("toolshop.use")) return false;
        return !plugin.settings().requireSneak || player.isSneaking();
    }

    // ---------------------------------------------------------------- Harvest

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (busy.contains(player.getUniqueId()) || !mayUseAbility(player)) return;
        ItemStack hoe = hoe(player);
        if (hoe == null) return;
        int tier = plugin.legendary().tier(hoe, Upgrade.HARVEST);
        Block start = event.getBlock();
        if (tier <= 0 || !isGrownCrop(start.getBlockData())) return;

        List<Block> crops = new ArrayList<>();
        for (int dx = -tier; dx <= tier; dx++) {
            for (int dz = -tier; dz <= tier; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block b = start.getRelative(dx, 0, dz);
                if (isGrownCrop(b.getBlockData())) crops.add(b);
            }
        }
        if (crops.isEmpty()) return;
        String toolId = plugin.legendary().id(hoe);
        boolean noXp = !plugin.settings().harvestXpForAllCrops;
        boolean effects = plugin.settings().effects;
        busy.add(player.getUniqueId());
        try {
            for (Block b : crops) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!plugin.legendary().isLegendary(hand) || !toolId.equals(plugin.legendary().id(hand))) break;
                BlockData before = b.getBlockData();
                if (noXp) plugin.mcmmo().markNoXp(b);
                if (player.breakBlock(b) && effects) {
                    b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.3, 0.5), 6, 0.3, 0.2, 0.3, 0, before);
                }
            }
        } finally {
            busy.remove(player.getUniqueId());
        }
        if (effects) start.getWorld().playSound(start.getLocation(), Sound.BLOCK_CROP_BREAK, 1f, 0.8f);
    }

    // ---------------------------------------------------------------- Growth Aura

    /** Every second: crops around a player holding a hoe with Growth Aura may grow a stage. */
    private void growthTick() {
        Settings settings = plugin.settings();
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            ItemStack hoe = hoe(player);
            if (hoe == null) continue;
            int tier = plugin.legendary().tier(hoe, Upgrade.GROWTH_AURA);
            if (tier <= 0) continue;
            int r = Settings.forTier(settings.growthAuraRadius, tier);
            int chance = Settings.forTier(settings.growthAuraChance, tier);
            Block center = player.getLocation().getBlock();
            int grown = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        Block b = center.getRelative(dx, dy, dz);
                        if (!SEEDS.containsKey(b.getType())) continue;
                        if (!(b.getBlockData() instanceof Ageable age) || age.getAge() >= age.getMaximumAge()) continue;
                        if (rng.nextInt(100) >= chance) continue;
                        age.setAge(age.getAge() + 1);
                        b.setBlockData(age);
                        if (settings.effects && grown++ < 6) {
                            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.4, 0.5), 2, 0.25, 0.2, 0.25, 0);
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Tiller

    @EventHandler(priority = EventPriority.HIGH)
    public void onTill(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        Player player = event.getPlayer();
        if (!mayUseAbility(player)) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !TILLABLE.contains(clicked.getType()) || !clicked.getRelative(BlockFace.UP).getType().isAir()) return;
        ItemStack hoe = hoe(player);
        if (hoe == null) return;
        int tier = plugin.legendary().tier(hoe, Upgrade.TILLER);
        if (tier <= 0) return;

        int tilled = 0;
        for (int dx = -tier; dx <= tier; dx++) {
            for (int dz = -tier; dz <= tier; dz++) {
                if (dx == 0 && dz == 0) continue; // vanilla tills the one you clicked
                Block b = clicked.getRelative(dx, 0, dz);
                if (!TILLABLE.contains(b.getType()) || !b.getRelative(BlockFace.UP).getType().isAir()) continue;
                b.setType(Material.FARMLAND);
                tilled++;
            }
        }
        if (tilled == 0) return;
        if (player.getGameMode() != GameMode.CREATIVE) {
            // One durability per four extra blocks, so a big till doesn't wreck the hoe.
            player.damageItemStack(EquipmentSlot.HAND, (tilled + 3) / 4);
        }
        if (plugin.settings().effects) {
            clicked.getWorld().playSound(clicked.getLocation(), Sound.ITEM_HOE_TILL, 1f, 0.9f);
        }
    }
}
