package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        boolean wouldTrigger = switch (type) {
            case TIMBER -> isLog(block.getType());
            case VEIN -> s.veinOres.contains(block.getType());
            case EXCAVATOR -> isShovelBlock(block.getType());
        };
        if (!wouldTrigger) return;

        long now = System.currentTimeMillis();
        if (data.getState(tool, now) != ToolState.ACTIVE) {
            player.sendActionBar(Component.text("Your " + type.displayName()
                    + " has expired. Renew it at /toolshop", NamedTextColor.RED));
            return;
        }

        switch (type) {
            case TIMBER -> fellTree(player, block);
            case VEIN -> mineVein(player, block);
            case EXCAVATOR -> excavate(player, block);
        }
    }

    // ---------------------------------------------------------------- Timber

    private void fellTree(Player player, Block start) {
        Settings s = plugin.settings();
        int startX = start.getX();
        int startZ = start.getZ();
        int r = s.timberMaxRadius; // 0 = no sideways limit
        // Every connected log, above and below the one that was hit, so chopping anywhere fells the
        // whole tree, and trees whose logs touch come down together. The closest logs are found first,
        // and max-logs caps how many go per chop so big tree farms can't be cleared in one swing.
        List<Block> logs = collect(start, this::isLog, s.timberMaxLogs,
                b -> r <= 0 || (Math.abs(b.getX() - startX) <= r && Math.abs(b.getZ() - startZ) <= r));
        if (countNaturalFoliage(logs, s.timberMinLeaves) < s.timberMinLeaves) {
            return; // No natural leaves: probably a build, so only the one log breaks.
        }
        if (logs.size() >= s.timberMaxLogs) {
            player.sendActionBar(Component.text("Log limit reached (" + s.timberMaxLogs
                    + "). Chop again for the rest.", NamedTextColor.YELLOW));
        }
        // Remember where each trunk stood (logs sitting on the ground, not on another log) and its wood type,
        // before anything is broken, so every felled tree gets its own sapling back.
        Map<Block, Material> trunkBase = new HashMap<>();
        for (Block b : logs) {
            if (!isLog(b.getRelative(0, -1, 0).getType())) trunkBase.put(b, b.getType());
        }
        Set<Block> felled = new HashSet<>(logs);
        // Leaves are broken with a copy of the axe, so Silk Touch drops the leaf blocks
        // and Fortune boosts sapling and apple drops, just like breaking them by hand.
        ItemStack axe = player.getInventory().getItemInMainHand().clone();

        logs.remove(start);
        // Break the lowest and closest logs first.
        logs.sort(Comparator.comparingInt(Block::getY)
                .thenComparingDouble(b -> b.getLocation().distanceSquared(start.getLocation())));
        breakAll(player, logs, ToolType.TIMBER);
        playSound(start, Sound.BLOCK_WOOD_BREAK, 0.6f);

        // The log that was hit only breaks after this event finishes, so replant one tick later.
        if (s.timberReplant) {
            plugin.getServer().getScheduler().runTask(plugin, () -> replant(trunkBase));
        }
        if (s.timberClearLeaves) {
            // Give the game a moment to work out which leaves lost their log, then sweep twice.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> clearLooseLeaves(felled, axe), 20L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> clearLooseLeaves(felled, axe), 60L);
        }
    }

    /**
     * Breaks leaves around the felled tree that are no longer attached to any log, so they drop
     * right away instead of decaying over several minutes. They're broken with the axe's enchantments:
     * Silk Touch drops the leaf blocks themselves, otherwise saplings, sticks and apples drop as usual.
     * Leaves still held up by a neighbouring tree are left alone, because the game marks only
     * unattached leaves with the maximum distance (7).
     */
    private void clearLooseLeaves(Set<Block> felledLogs, ItemStack axe) {
        if (felledLogs.isEmpty()) return;
        final int reach = 7; // leaves can't be further than this from their log
        Block any = felledLogs.iterator().next();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Block b : felledLogs) {
            minX = Math.min(minX, b.getX()); maxX = Math.max(maxX, b.getX());
            minY = Math.min(minY, b.getY()); maxY = Math.max(maxY, b.getY());
            minZ = Math.min(minZ, b.getZ()); maxZ = Math.max(maxZ, b.getZ());
        }
        var world = any.getWorld();
        int bottom = Math.max(world.getMinHeight(), minY - 1);
        int top = Math.min(world.getMaxHeight() - 1, maxY + reach);
        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                for (int y = bottom; y <= top; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getBlockData() instanceof Leaves leaves
                            && !leaves.isPersistent()
                            && leaves.getDistance() >= 7) {
                        b.breakNaturally(axe);
                    }
                }
            }
        }
    }

    /** Plants the matching sapling (or fungus) where the trunk stood, if the ground allows it. */
    private void replant(Map<Block, Material> trunkBase) {
        for (Map.Entry<Block, Material> entry : trunkBase.entrySet()) {
            Block spot = entry.getKey();
            Material sapling = saplingFor(entry.getValue());
            if (sapling == null || !spot.getType().isAir()) continue;
            boolean fungus = sapling == Material.CRIMSON_FUNGUS || sapling == Material.WARPED_FUNGUS;
            Material ground = spot.getRelative(0, -1, 0).getType();
            boolean groundOk = fungus
                    ? ground == Material.CRIMSON_NYLIUM || ground == Material.WARPED_NYLIUM
                    : Tag.DIRT.isTagged(ground);
            if (groundOk) spot.setType(sapling);
        }
    }

    /** OAK_LOG -> OAK_SAPLING, CRIMSON_STEM -> CRIMSON_FUNGUS, MANGROVE_LOG -> MANGROVE_PROPAGULE. */
    private static Material saplingFor(Material log) {
        String name = log.name();
        String candidate;
        if (name.equals("MANGROVE_LOG")) {
            candidate = "MANGROVE_PROPAGULE";
        } else if (name.endsWith("_STEM")) {
            candidate = name.replace("_STEM", "_FUNGUS");
        } else if (name.endsWith("_LOG")) {
            candidate = name.replace("_LOG", "_SAPLING");
        } else {
            return null;
        }
        Material m = Material.matchMaterial(candidate);
        return (m != null && m.isBlock()) ? m : null;
    }

    // ---------------------------------------------------------------- Excavator

    private boolean isShovelBlock(Material m) {
        return Tag.MINEABLE_SHOVEL.isTagged(m);
    }

    /** Digs the 3x3 square around the block, facing the way the player is looking. */
    private void excavate(Player player, Block center) {
        float pitch = player.getLocation().getPitch();
        BlockFace facing = player.getFacing();
        List<Block> area = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block n;
                if (pitch > 45 || pitch < -45) {
                    n = center.getRelative(a, 0, b);           // looking down or up: flat square
                } else if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
                    n = center.getRelative(a, b, 0);           // wall facing north/south
                } else {
                    n = center.getRelative(0, b, a);           // wall facing east/west
                }
                if (isShovelBlock(n.getType())) area.add(n);
            }
        }
        if (area.isEmpty()) return;
        int broken = breakAll(player, area, ToolType.EXCAVATOR);
        if (broken > 0) playSound(center, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8f);
        float cost = plugin.settings().excavatorExhaustionPerBlock;
        if (broken > 0 && cost > 0) {
            player.setExhaustion(Math.min(40f, player.getExhaustion() + broken * cost));
        }
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
        if (broken > 0) playSound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
        if (broken > 0 && s.veinExhaustionPerBlock > 0) {
            player.setExhaustion(Math.min(40f, player.getExhaustion() + broken * s.veinExhaustionPerBlock));
        }
    }

    /** Deepslate and normal versions of an ore count as the same vein. */
    private static String oreGroup(Material m) {
        return m.name().replace("DEEPSLATE_", "");
    }

    // ---------------------------------------------------------------- Shared

    /** Plays a sound at the block for everyone nearby, if effects are on. */
    private void playSound(Block at, Sound sound, float pitch) {
        if (!plugin.settings().effects) return;
        at.getWorld().playSound(at.getLocation().add(0.5, 0.5, 0.5), sound, 1f, pitch);
    }

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
        boolean effects = plugin.settings().effects;
        try {
            for (Block b : blocks) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (plugin.toolData().getType(hand) != type) break;
                if (b.getType().isAir()) continue;
                BlockData before = b.getBlockData();
                if (player.breakBlock(b)) {
                    broken++;
                    if (effects) {
                        // Break particles, visible to everyone (vanilla hides them from the breaker).
                        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                                12, 0.3, 0.3, 0.3, 0, before);
                    }
                }
            }
        } finally {
            busy.remove(id);
        }
        return broken;
    }
}
