package io.github.cowmoo03.toolshop;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
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
    private Method getTracker;
    private Method setIneligible;
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
            Class<?> main = Class.forName("com.gmail.nossr50.mcMMO", true, loader);
            getTracker = main.getMethod("getUserBlockTracker");
            Class<?> tracker = Class.forName("com.gmail.nossr50.util.blockmeta.UserBlockTracker", true, loader);
            setIneligible = tracker.getMethod("setIneligible", Block.class);
            available = true;
            log.info("Hooked into mcMMO " + mcmmo.getPluginMeta().getVersion() + ".");
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
}
