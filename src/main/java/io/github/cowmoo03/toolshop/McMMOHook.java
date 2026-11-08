package io.github.cowmoo03.toolshop;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Optional link to mcMMO, done through reflection so ToolShop builds and runs without it.
 * Uses mcMMO's public API: ExperienceAPI.getLevel(Player, String) for skill levels, and
 * mcMMO.getUserBlockTracker().setIneligible(Block) to mark blocks as giving no XP
 * (mcMMO only gives XP for blocks that aren't marked, the same check it uses for player-placed blocks).
 */
public final class McMMOHook {
    private final Logger log;
    private Method getLevel;
    private Method getXp;
    private Method getXpToNext;
    private Method getPowerLevel;
    /** True when mcMMO uses Retro Mode (levels 0-1000 instead of 0-100). */
    private boolean retroMode;
    /** "mining.superbreaker" -> the level each rank unlocks at, for the scale mcMMO is using. */
    private final Map<String, List<Integer>> rankLevels = new HashMap<>();
    private Method getTracker;
    private Method setIneligible;
    private Method setEligible;
    private Method isIneligible;
    private boolean available;
    private boolean warnedLevelError;

    public McMMOHook(Plugin owner) {
        this.log = owner.getLogger();
        Plugin mcmmo = owner.getServer().getPluginManager().getPlugin("mcMMO");
        if (mcmmo == null || !mcmmo.isEnabled()) {
            log.info("mcMMO not found: upgrades won't need skill levels.");
            return;
        }
        try {
            ClassLoader loader = mcmmo.getClass().getClassLoader();
            Class<?> api = Class.forName("com.gmail.nossr50.api.ExperienceAPI", true, loader);
            getLevel = api.getMethod("getLevel", Player.class, String.class);
            getXp = api.getMethod("getXP", Player.class, String.class);
            getXpToNext = api.getMethod("getXPToNextLevel", Player.class, String.class);
            getPowerLevel = api.getMethod("getPowerLevel", Player.class);
            Class<?> main = Class.forName("com.gmail.nossr50.mcMMO", true, loader);
            getTracker = main.getMethod("getUserBlockTracker");
            Class<?> tracker = Class.forName("com.gmail.nossr50.util.blockmeta.UserBlockTracker", true, loader);
            setIneligible = tracker.getMethod("setIneligible", Block.class);
            setEligible = tracker.getMethod("setEligible", Block.class);
            isIneligible = tracker.getMethod("isIneligible", Block.class);
            available = true;
            loadRanks(mcmmo);
            log.info("Hooked into mcMMO " + mcmmo.getPluginMeta().getVersion() + (retroMode ? " (Retro Mode)" : "") + ".");
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warning("mcMMO is installed but its API couldn't be reached (" + e + "). "
                    + "Upgrades won't need skill levels.");
        }
    }

    public boolean available() {
        return available;
    }

    /** The player's level in an mcMMO skill, or -1 if it can't be read right now (e.g. data still loading). */
    public int level(Player player, String skill) {
        if (!available) return -1;
        try {
            Object result = getLevel.invoke(null, player, skill);
            return result instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedLevelError) {
                warnedLevelError = true;
                log.warning("Couldn't read an mcMMO level (" + e.getCause() + "). Will keep trying quietly.");
            }
            return -1;
        }
    }

    /** XP into the current level, or -1 if unknown (or a child skill, which has no XP). */
    public int xp(Player player, String skill) {
        return callInt(getXp, player, skill);
    }

    /** XP needed for the whole current level, or -1 if unknown. */
    public int xpToNext(Player player, String skill) {
        return callInt(getXpToNext, player, skill);
    }

    public int powerLevel(Player player) {
        if (!available) return -1;
        try {
            Object result = getPowerLevel.invoke(null, player);
            return result instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private int callInt(Method m, Player player, String skill) {
        if (!available) return -1;
        try {
            Object result = m.invoke(null, player, skill);
            return result instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    public boolean retroMode() {
        return retroMode;
    }

    /**
     * Levels at which each rank of an ability unlocks (rank 1 first), on the scale mcMMO is using, or null if
     * mcMMO doesn't list it.
     */
    public List<Integer> unlockLevels(String skill, String ability) {
        return rankLevels.get(skill.toLowerCase(Locale.ROOT) + "." + ability.toLowerCase(Locale.ROOT));
    }

    /** Reads Retro Mode from mcMMO's config.yml and each ability's unlock levels from its skillranks.yml. */
    private void loadRanks(Plugin mcmmo) {
        File config = new File(mcmmo.getDataFolder(), "config.yml");
        retroMode = !config.exists() || YamlConfiguration.loadConfiguration(config).getBoolean("General.RetroMode.Enabled", true);
        YamlConfiguration ranks;
        File file = new File(mcmmo.getDataFolder(), "skillranks.yml");
        if (file.exists()) {
            ranks = YamlConfiguration.loadConfiguration(file);
        } else {
            InputStream in = mcmmo.getResource("skillranks.yml");
            if (in == null) return;
            ranks = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        String scale = retroMode ? "RetroMode" : "Standard";
        for (String skill : ranks.getKeys(false)) {
            ConfigurationSection s = ranks.getConfigurationSection(skill);
            if (s == null) continue;
            for (String ability : s.getKeys(false)) {
                ConfigurationSection levels = s.getConfigurationSection(ability + "." + scale);
                if (levels == null) continue;
                List<Integer> list = new ArrayList<>();
                for (int rank = 1; levels.contains("Rank_" + rank); rank++) list.add(levels.getInt("Rank_" + rank));
                if (!list.isEmpty()) rankLevels.put(skill.toLowerCase(Locale.ROOT) + "." + ability.toLowerCase(Locale.ROOT), list);
            }
        }
    }

    /** Marks a block so mcMMO gives no XP when it's broken. */
    public void markNoXp(Block block) {
        if (!available) return;
        try {
            Object tracker = getTracker.invoke(null);
            if (tracker != null) setIneligible.invoke(tracker, block);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Worst case the player gets a little extra XP.
        }
    }

    /** True if mcMMO gives no XP for this block (placed by a player, or marked by an ability or a piston). */
    public boolean givesNoXp(Block block) {
        if (!available) return false;
        try {
            Object tracker = getTracker.invoke(null);
            return tracker != null && Boolean.TRUE.equals(isIneligible.invoke(tracker, block));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** Lets mcMMO give XP for this block again, as if it grew there. */
    public void markGivesXp(Block block) {
        if (!available) return;
        try {
            Object tracker = getTracker.invoke(null);
            if (tracker != null) setEligible.invoke(tracker, block);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Worst case the log gives no XP, like before.
        }
    }
}
