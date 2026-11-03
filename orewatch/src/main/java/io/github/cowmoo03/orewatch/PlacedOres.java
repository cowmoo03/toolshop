package io.github.cowmoo03.orewatch;

import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Locations of valuable ore blocks that players placed (e.g. after mining them with Silk Touch), so breaking them
 * again isn't counted as a find. Saved to plugins/OreWatch/placed.yml.
 */
public final class PlacedOres {
    private static final int MAX = 50_000;

    private final File file;
    private final Logger log;
    private final Set<String> placed = new LinkedHashSet<>();
    private boolean dirty;

    public PlacedOres(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "placed.yml");
        this.log = log;
        if (file.exists()) placed.addAll(YamlConfiguration.loadConfiguration(file).getStringList("placed"));
    }

    private static String key(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    public void add(Block b) {
        placed.add(key(b));
        if (placed.size() > MAX) {
            var it = placed.iterator();
            it.next();
            it.remove(); // forget the oldest
        }
        dirty = true;
    }

    /** Removes the block if it was player-placed. Returns true if it was. */
    public boolean remove(Block b) {
        boolean was = placed.remove(key(b));
        if (was) dirty = true;
        return was;
    }

    public void saveIfDirty() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("placed", new ArrayList<>(placed));
        try {
            file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            log.warning("Couldn't save placed.yml: " + e.getMessage());
        }
    }
}
