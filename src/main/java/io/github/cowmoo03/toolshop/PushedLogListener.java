package io.github.cowmoo03.toolshop;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * mcMMO's ExploitFix.PistonCheating marks every block a piston moves as giving no XP, which also stops tree farms
 * that push grown logs. Before the push this notes which logs still gave XP (grew there, not placed by a player);
 * once mcMMO has marked their new spots, it lets them give XP again. Placed logs stay marked.
 */
public final class PushedLogListener implements Listener {
    /**
     * mcMMO marks the new spots on the next tick. A pushed block is a moving piston for about 3 ticks before it
     * lands, so this starts checking after mcMMO and keeps checking each tick until the log is there.
     */
    private static final long FIRST_CHECK_TICKS = 2L;
    private static final int MAX_CHECKS = 10;

    private final ToolShopPlugin plugin;

    public PushedLogListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        remember(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        remember(event.getBlocks(), event.getDirection());
    }

    private void remember(List<Block> moved, BlockFace direction) {
        if (!plugin.settings().pushedLogsGiveXp || !plugin.mcmmo().available() || moved.isEmpty()) return;
        List<Block> targets = new ArrayList<>();
        List<Material> types = new ArrayList<>();
        for (Block block : moved) {
            Material type = block.getType();
            if (!Tag.LOGS.isTagged(type)) continue;
            if (plugin.mcmmo().givesNoXp(block)) continue; // placed by a player (or already marked): stays without XP
            targets.add(block.getRelative(direction));
            types.add(type);
        }
        if (targets.isEmpty()) return;
        for (int i = 0; i < targets.size(); i++) waitForLog(targets.get(i), types.get(i), MAX_CHECKS);
    }

    /** Once the pushed log has landed, lets it give XP again. Gives up if something else ends up there. */
    private void waitForLog(Block target, Material type, int checksLeft) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Material now = target.getType();
            if (now == type) {
                plugin.mcmmo().markGivesXp(target);
            } else if (now == Material.MOVING_PISTON && checksLeft > 1) {
                waitForLog(target, type, checksLeft - 1);
            }
        }, checksLeft == MAX_CHECKS ? FIRST_CHECK_TICKS : 1L);
    }
}
