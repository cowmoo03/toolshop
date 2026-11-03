package io.github.cowmoo03.toolshop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Remembers each player's current legendary tool of each kind, and how many blocks each tool has broken.
 * Stored in plugins/ToolShop/players.yml. Only the tool whose ID is recorded here works, so making a new
 * legendary tool retires the old one, wherever it is.
 */
public final class Registry {
    private final File file;
    private final Logger log;
    private final Map<UUID, EnumMap<ToolKind, String>> active = new HashMap<>();
    private final Map<String, Long> blocks = new HashMap<>();
    private boolean dirty;

    public Registry(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "players.yml");
        this.log = log;
        load();
    }

    public String activeId(UUID player, ToolKind kind) {
        EnumMap<ToolKind, String> m = active.get(player);
        return m == null ? null : m.get(kind);
    }

    public void setActive(UUID player, ToolKind kind, String toolId) {
        active.computeIfAbsent(player, k -> new EnumMap<>(ToolKind.class)).put(kind, toolId);
        dirty = true;
        save(); // rare and important, save right away
    }

    public long blocks(String toolId) {
        return blocks.getOrDefault(toolId, 0L);
    }

    public void addBlocks(String toolId, long n) {
        blocks.merge(toolId, n, Long::sum);
        dirty = true;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = y.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    ConfigurationSection s = players.getConfigurationSection(key);
                    if (s == null) continue;
                    for (String kindKey : s.getKeys(false)) {
                        ToolKind kind = ToolKind.fromId(kindKey);
                        if (kind != null) {
                            active.computeIfAbsent(id, k -> new EnumMap<>(ToolKind.class)).put(kind, s.getString(kindKey));
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // not a UUID
                }
            }
        }
        ConfigurationSection b = y.getConfigurationSection("blocks");
        if (b != null) {
            for (String toolId : b.getKeys(false)) blocks.put(toolId, b.getLong(toolId));
        }
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        active.forEach((player, kinds) ->
                kinds.forEach((kind, toolId) -> y.set("players." + player + "." + kind.id(), toolId)));
        blocks.forEach((toolId, n) -> y.set("blocks." + toolId, n));
        try {
            file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            log.warning("Couldn't save players.yml: " + e.getMessage());
        }
    }
}
