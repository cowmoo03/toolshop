package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Drill and Vein Mining (pickaxe), Timber (axe) and Excavator (shovel). */
public final class AbilityListener implements Listener {
    private final ToolShopPlugin plugin;
    /** Players whose extra blocks are being broken right now, so those breaks don't trigger the ability again. */
    private final Set<UUID> busy = new HashSet<>();

    public AbilityListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBusy(Player player) {
        return busy.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (busy.contains(player.getUniqueId())) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        if (!player.hasPermission("toolshop.use")) return;

        Settings s = plugin.settings();
        if (s.requireSneak && !player.isSneaking()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        ToolKind kind = lt.kind(tool);
        if (kind == null || !lt.usableBy(tool, player)) return;

        Block block = event.getBlock();
        switch (kind) {
            case PICKAXE -> {
                int drill = lt.tier(tool, Upgrade.DRILL);
                int vein = lt.tier(tool, Upgrade.VEIN);
                boolean veinMode = vein > 0 && (drill == 0 || Legendary.MODE_VEIN.equals(lt.mode(tool)));
                if (veinMode) {
                    if (s.veinOres.contains(block.getType())) mineVein(player, block, vein);
                } else if (drill > 0 && isDrillable(block)) {
                    digArea(player, block, drill, this::isDrillable, Sound.BLOCK_DEEPSLATE_BREAK, 0f);
                }
            }
            case AXE -> {
                int timber = lt.tier(tool, Upgrade.TIMBER);
                if (timber > 0 && isLog(block.getType())) fellTree(player, block, timber);
            }
            case SHOVEL -> {
                int excavator = lt.tier(tool, Upgrade.EXCAVATOR);
                if (excavator > 0 && isShovelBlock(block)) {
                    digArea(player, block, excavator, this::isShovelBlock, Sound.BLOCK_ROOTED_DIRT_BREAK,
                            s.excavatorExhaustion);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Drill and Excavator

    /** Stone-type blocks the Drill may break: pickaxe blocks, not too hard, never containers or machines. */
    private boolean isDrillable(Block b) {
        Material m = b.getType();
        if (!Tag.MINEABLE_PICKAXE.isTagged(m)) return false;
        if (plugin.settings().drillNeverBreak.contains(m)) return false;
        float hardness = m.getHardness();
        if (hardness < 0 || hardness > plugin.settings().drillMaxHardness) return false;
        return !(b.getState(false) instanceof TileState);
    }

    private boolean isShovelBlock(Block b) {
        return Tag.MINEABLE_SHOVEL.isTagged(b.getType()) && !(b.getState(false) instanceof TileState);
    }

    /**
     * Tier 1: the hit block plus one above or below it: the other body block at body height, the block above
     * when hitting above your head, below when hitting below your feet (or the other side if that one is open).
     * Tier 2: 3x3. Tier 3: 5x5. The square faces the way the player is looking.
     */
    private void digArea(Player player, Block start, int tier, Predicate<Block> allowed, Sound sound, float exhaustion) {
        List<Block> area = new ArrayList<>();
        float pitch = player.getLocation().getPitch();
        boolean vertical = pitch > 45 || pitch < -45;
        if (tier <= 1) {
            BlockFace dir;
            int feetY = player.getLocation().getBlockY();
            int y = start.getY();
            if (vertical) dir = pitch > 0 ? BlockFace.DOWN : BlockFace.UP;
            else if (y >= feetY + 2) dir = BlockFace.UP;   // above your head: dig upward (stairs up)
            else if (y < feetY) dir = BlockFace.DOWN;      // below your feet: dig downward (stairs down)
            else dir = y == feetY ? BlockFace.UP : BlockFace.DOWN; // body height: the other body block
            Block partner = start.getRelative(dir);
            // Already dug out (or not stone): take the block on the other side so it still breaks 2.
            if (!allowed.test(partner)) partner = start.getRelative(dir.getOppositeFace());
            area.add(partner);
        } else {
            int r = tier - 1;
            BlockFace facing = player.getFacing();
            for (int a = -r; a <= r; a++) {
                for (int b = -r; b <= r; b++) {
                    if (a == 0 && b == 0) continue;
                    if (vertical) area.add(start.getRelative(a, 0, b));
                    else if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) area.add(start.getRelative(a, b, 0));
                    else area.add(start.getRelative(0, b, a));
                }
            }
        }
        area.removeIf(b -> !allowed.test(b));
        if (area.isEmpty()) return;
        int broken = breakAll(player, area);
        if (broken > 0) {
            playSound(start, sound, 0.8f);
            addExhaustion(player, broken * exhaustion);
        }
    }

    // ---------------------------------------------------------------- Vein Mining

    private void mineVein(Player player, Block start, int tier) {
        Settings s = plugin.settings();
        String group = oreGroup(start.getType());
        List<Block> ores = collect(start, m -> s.veinOres.contains(m) && oreGroup(m).equals(group),
                Settings.forTier(s.veinMaxBlocks, tier), b -> true);
        ores.remove(start);
        if (ores.isEmpty()) return;
        // Ore veins are natural, so each ore can give XP (placed ores never do: mcMMO tracks those itself).
        int broken = breakAll(player, ores, s.xpOnlyForHitBlock && !s.veinXpForAllOres);
        if (broken > 0) {
            playSound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
            addExhaustion(player, broken * s.veinExhaustion);
        }
    }

    /** Deepslate and normal versions of an ore count as the same vein. */
    private static String oreGroup(Material m) {
        return m.name().replace("DEEPSLATE_", "");
    }

    // ---------------------------------------------------------------- Timber

    private void fellTree(Player player, Block start, int tier) {
        Settings s = plugin.settings();
        int maxLogs = Settings.forTier(s.timberMaxLogs, tier);
        // Every connected log, above and below the one that was hit, closest first, so trees whose logs
        // touch fall together, capped per chop so tree farms can't be cleared in one swing.
        List<Block> logs = collect(start, this::isLog, maxLogs, b -> true);
        if (countNaturalFoliage(logs, s.timberMinLeaves) < s.timberMinLeaves) {
            // No natural leaves: usually a build, so only the one log breaks. The exception is the leftover
            // stump of a tree this axe already felled.
            if (isLeftoverStump(logs)) fellStump(player, start, logs);
            return;
        }
        if (logs.size() >= maxLogs) {
            player.sendActionBar(Component.text("Log limit reached (" + maxLogs
                    + "). Chop again for the rest.", NamedTextColor.YELLOW));
        }
        Set<Block> felled = new HashSet<>(logs);
        rememberFelled(felled);
        ItemStack axe = player.getInventory().getItemInMainHand().clone();
        boolean collectLeaves = tier >= s.timberSilkLeavesFromTier && axe.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0;

        logs.remove(start);
        logs.sort(Comparator.comparingInt(Block::getY)
                .thenComparingDouble(b -> b.getLocation().distanceSquared(start.getLocation())));
        breakAll(player, logs, timberNoXp());
        playSound(start, Sound.BLOCK_WOOD_BREAK, 0.6f);

        if (collectLeaves) {
            // Give the game a moment to work out which leaves lost their log, then sweep twice.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> clearLooseLeaves(felled, axe), 20L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> clearLooseLeaves(felled, axe), 60L);
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
            for (Block n : neighbours(log)) {
                if (isNaturalFoliage(n) && found.add(n) && found.size() >= enough) return found.size();
            }
        }
        return found.size();
    }

    private boolean isNaturalFoliage(Block b) {
        Material m = b.getType();
        if (m == Material.NETHER_WART_BLOCK || m == Material.WARPED_WART_BLOCK || m == Material.SHROOMLIGHT) {
            return true; // caps of huge crimson and warped fungi
        }
        return b.getBlockData() instanceof Leaves leaves && !leaves.isPersistent();
    }

    /**
     * Breaks leaves around the felled tree that are no longer attached to any log, using the Silk Touch axe,
     * so the leaf blocks themselves drop. Leaves still held up by a neighbouring tree are left alone, because
     * the game only marks unattached leaves with the maximum distance (7).
     */
    private void clearLooseLeaves(Set<Block> felledLogs, ItemStack axe) {
        if (felledLogs.isEmpty()) return;
        final int reach = 7;
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
        boolean effects = plugin.settings().effects;
        int cleared = 0;
        double sumX = 0, sumY = 0, sumZ = 0;
        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                for (int y = bottom; y <= top; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getBlockData() instanceof Leaves leaves && !leaves.isPersistent() && leaves.getDistance() >= 7) {
                        BlockData before = b.getBlockData();
                        b.breakNaturally(axe);
                        cleared++;
                        sumX += x; sumY += y; sumZ += z;
                        if (effects) {
                            world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                                    4, 0.3, 0.3, 0.3, 0, before);
                        }
                    }
                }
            }
        }
        if (effects && cleared > 0) {
            world.playSound(new Location(world, sumX / cleared + 0.5, sumY / cleared + 0.5, sumZ / cleared + 0.5),
                    Sound.BLOCK_AZALEA_LEAVES_BREAK, 1f, 0.9f);
        }
    }

    // ------------------------------------------------ Leftover stumps

    private static final int STUMP_REACH = 16;
    private static final int STUMP_MAX_LOGS = 16;
    private static final int REMEMBERED_COLUMNS = 20_000;

    /** For each column (world + x + z) where Timber felled logs, the lowest Y felled. In memory only. */
    private final Map<String, Integer> felledColumns = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > REMEMBERED_COLUMNS;
        }
    };

    private static String columnKey(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getZ();
    }

    private void rememberFelled(Set<Block> felled) {
        for (Block b : felled) felledColumns.merge(columnKey(b), b.getY(), Math::min);
    }

    private boolean isLeftoverStump(List<Block> logs) {
        if (logs.isEmpty() || logs.size() > STUMP_MAX_LOGS) return false;
        for (Block b : logs) {
            Integer felledY = felledColumns.get(columnKey(b));
            if (felledY != null && b.getY() < felledY && felledY - b.getY() <= STUMP_REACH) return true;
        }
        return false;
    }

    /** Timber logs give Woodcutting XP unless timber.xp-for-all-logs is off. */
    private boolean timberNoXp() {
        return plugin.settings().xpOnlyForHitBlock && !plugin.settings().timberXpForAllLogs;
    }

    private void fellStump(Player player, Block start, List<Block> logs) {
        List<Block> rest = new ArrayList<>(logs);
        rest.remove(start);
        rest.sort(Comparator.comparingInt(Block::getY));
        breakAll(player, rest, timberNoXp());
        playSound(start, Sound.BLOCK_WOOD_BREAK, 0.8f);
        for (Block b : logs) felledColumns.remove(columnKey(b));
    }

    // ---------------------------------------------------------------- Shared

    private static List<Block> neighbours(Block b) {
        List<Block> out = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) out.add(b.getRelative(dx, dy, dz));
                }
            }
        }
        return out;
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
            for (Block n : neighbours(b)) {
                if (allowed.test(n) && seen.add(n) && matches.test(n.getType())) queue.add(n);
            }
        }
        return out;
    }

    /**
     * Breaks each block as if the player broke it with the tool in their hand, so drops, enchantments,
     * durability, Magnet and other plugins' protections all behave normally. Each extra block is first marked
     * so mcMMO gives no XP for it (only the block the player hit counts). Stops if the tool leaves the hand.
     */
    private int breakAll(Player player, List<Block> blocks) {
        return breakAll(player, blocks, plugin.settings().xpOnlyForHitBlock);
    }

    /** Breaks the blocks as the player. noXp: the extra blocks give no mcMMO XP. */
    private int breakAll(Player player, List<Block> blocks, boolean noXp) {
        UUID id = player.getUniqueId();
        ItemStack tool = player.getInventory().getItemInMainHand();
        String toolId = plugin.legendary().id(tool);
        boolean effects = plugin.settings().effects;
        busy.add(id);
        int broken = 0;
        try {
            for (Block b : blocks) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!plugin.legendary().isLegendary(hand) || !toolId.equals(plugin.legendary().id(hand))) break;
                if (b.getType().isAir()) continue;
                BlockData before = b.getBlockData();
                if (noXp) plugin.mcmmo().markNoXp(b);
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

    private void addExhaustion(Player player, float amount) {
        if (amount > 0) player.setExhaustion(Math.min(40f, player.getExhaustion() + amount));
    }

    private void playSound(Block at, Sound sound, float pitch) {
        if (!plugin.settings().effects) return;
        at.getWorld().playSound(at.getLocation().add(0.5, 0.5, 0.5), sound, 1f, pitch);
    }
}
