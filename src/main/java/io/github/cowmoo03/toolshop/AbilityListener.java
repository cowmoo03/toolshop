package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Tree felling for the Timber Axe and vein mining for the Vein Pickaxe. */
public final class AbilityListener implements Listener {
    private final ToolShopPlugin plugin;
    /** Players whose extra blocks are being broken right now, so those breaks don't trigger the ability again. */
    private final Set<UUID> busy = new HashSet<>();

    public AbilityListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (busy.contains(player.getUniqueId())) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        if (!player.hasPermission("toolshop.use")) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        ToolData data = plugin.toolData();
        ToolType type = data.getType(tool);
        if (type == null) return;

        Settings s = plugin.settings();
        if (s.requireSneak(type) && !player.isSneaking()) return;

        Block block = event.getBlock();
        boolean wouldTrigger = type == ToolType.TIMBER ? isLog(block.getType()) : s.veinOres.contains(block.getType());
        if (!wouldTrigger) return;

        long now = System.currentTimeMillis();
        if (data.getState(tool, now) != ToolState.ACTIVE) {
            player.sendActionBar(Component.text("Your " + type.displayName()
                    + " has expired. Renew it at /toolshop", NamedTextColor.RED));
            return;
        }

        if (type == ToolType.TIMBER) {
            fellTree(player, block);
        } else {
            mineVein(player, block);
        }
    }

    // ---------------------------------------------------------------- Timber

    private void fellTree(Player player, Block start) {
        Settings s = plugin.settings();
        int startX = start.getX();
        int startY = start.getY();
        int startZ = start.getZ();
        int r = s.timberMaxRadius;
        // Only logs at or above the one that was hit, connected through faces, edges or corners,
        // and within a few blocks sideways so it doesn't chain into neighbouring trees.
        List<Block> logs = collect(start, this::isLog, s.timberMaxLogs,
                b -> b.getY() >= startY && Math.abs(b.getX() - startX) <= r && Math.abs(b.getZ() - startZ) <= r);
        if (countNaturalFoliage(logs, s.timberMinLeaves) < s.timberMinLeaves) {
            return; // No natural leaves: probably a build, so only the one log breaks.
        }
        logs.remove(start);
        // Break the lowest and closest logs first.
        logs.sort(Comparator.comparingInt(Block::getY)
                .thenComparingDouble(b -> b.getLocation().distanceSquared(start.getLocation())));
        breakAll(player, logs, ToolType.TIMBER);
    }

    private boolean isLog(Material m) {
        if (!Tag.LOGS.isTagged(m)) return false;
        String name = m.name();
        // Stripped logs and bark blocks are almost always player builds.
        return !name.startsWith("STRIPPED_") && !name.endsWith("_WOOD") && !name.endsWith("_HYPHAE");
    }

    /** Counts natural leaves (and nether tree caps) touching the logs, stopping once {@code enough} are found. */
    private int countNaturalFoliage(List<Block> logs, int enough) {
        if (enough <= 0) return 0;
        Set<Block> found = new HashSet<>();
        for (Block log : logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = log.getRelative(dx, dy, dz);
                        if (isNaturalFoliage(n) && found.add(n) && found.size() >= enough) {
                            return found.size();
                        }
                    }
                }
            }
        }
        return found.size();
    }

    private boolean isNaturalFoliage(Block b) {
        Material m = b.getType();
        if (m == Material.NETHER_WART_BLOCK || m == Material.WARPED_WART_BLOCK || m == Material.SHROOMLIGHT) {
            return true; // caps of huge crimson and warped fungi
        }
        BlockData data = b.getBlockData();
        return data instanceof Leaves leaves && !leaves.isPersistent();
    }

    // ---------------------------------------------------------------- Vein

    private void mineVein(Player player, Block start) {
        Settings s = plugin.settings();
        String group = oreGroup(start.getType());
        List<Block> ores = collect(start,
                m -> s.veinOres.contains(m) && oreGroup(m).equals(group),
                s.veinMaxBlocks, b -> true);
        ores.remove(start);
        if (ores.isEmpty()) return;
        int broken = breakAll(player, ores, ToolType.VEIN);
        if (broken > 0 && s.veinExhaustionPerBlock > 0) {
            player.setExhaustion(Math.min(40f, player.getExhaustion() + broken * s.veinExhaustionPerBlock));
        }
    }

    /** Deepslate and normal versions of an ore count as the same vein. */
    private static String oreGroup(Material m) {
        return m.name().replace("DEEPSLATE_", "");
    }

    // ---------------------------------------------------------------- Shared

    /** Breadth-first search for connected blocks (26 neighbours) whose material matches. Includes {@code start}. */
    private static List<Block> collect(Block start, Predicate<Material> matches, int max, Predicate<Block> allowed) {
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && out.size() < max) {
            Block b = queue.poll();
            out.add(b);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (allowed.test(n) && seen.add(n) && matches.test(n.getType())) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Breaks each block as if the player broke it with the tool in their hand, so drops, Fortune,
     * Silk Touch, durability and other plugins' protections all behave normally.
     * Stops early if the tool breaks or leaves the player's hand.
     */
    private int breakAll(Player player, List<Block> blocks, ToolType type) {
        UUID id = player.getUniqueId();
        busy.add(id);
        int broken = 0;
        try {
            for (Block b : blocks) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (plugin.toolData().getType(hand) != type) break;
                if (b.getType().isAir()) continue;
                if (player.breakBlock(b)) broken++;
            }
        } finally {
            busy.remove(id);
        }
        return broken;
    }
}
