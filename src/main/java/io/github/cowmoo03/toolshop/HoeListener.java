package io.github.cowmoo03.toolshop;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Legendary hoe: Harvest (area harvest of grown crops), Replant and Tiller (area tilling). */
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

    // ---------------------------------------------------------------- Replant

    /** Runs before Magnet (HIGH) so the seed is taken from the drops before they go into the inventory. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        BlockState state = event.getBlockState();
        if (!isGrownCrop(state.getBlockData())) return;
        ItemStack hoe = hoe(player);
        if (hoe == null || plugin.legendary().tier(hoe, Upgrade.REPLANT) <= 0) return;
        Material crop = state.getType();
        Material seed = SEEDS.get(crop);
        Block block = event.getBlock();
        Material soil = block.getRelative(BlockFace.DOWN).getType();
        if (crop == Material.NETHER_WART ? soil != Material.SOUL_SAND : soil != Material.FARMLAND) return;

        if (!takeSeed(event.getItems(), seed) && !player.getInventory().removeItem(ItemStack.of(seed)).isEmpty()) {
            return; // no seed anywhere
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Material below = block.getRelative(BlockFace.DOWN).getType();
            boolean soilOk = crop == Material.NETHER_WART ? below == Material.SOUL_SAND : below == Material.FARMLAND;
            if (block.getType().isAir() && soilOk) {
                block.setType(crop); // a fresh crop at age 0
            } else {
                // Something else is there now (or the soil changed): give the seed back.
                player.getInventory().addItem(ItemStack.of(seed)).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
        });
    }

    /** Takes one seed out of the dropped items. */
    private static boolean takeSeed(List<Item> drops, Material seed) {
        Iterator<Item> it = drops.iterator();
        while (it.hasNext()) {
            Item drop = it.next();
            ItemStack stack = drop.getItemStack();
            if (stack.getType() != seed) continue;
            if (stack.getAmount() <= 1) {
                it.remove();
            } else {
                stack.setAmount(stack.getAmount() - 1);
                drop.setItemStack(stack);
            }
            return true;
        }
        return false;
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
