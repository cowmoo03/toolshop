package io.github.cowmoo03.toolshop;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ToolShopPlugin extends JavaPlugin {
    public static final Component PREFIX = Component.text("[ToolShop] ", NamedTextColor.GOLD);
    private static final long SCAN_INTERVAL_TICKS = 20L * 30;
    private static final long SAVE_INTERVAL_TICKS = 20L * 60;

    private Settings settings;
    private Legendary legendary;
    private Registry registry;
    private McMMOHook mcmmo;
    private ShopListener shopListener;
    private RecoveryListener recovery;
    private SkillsListener skills;
    private FlightCharm flight;
    private SpawnerUpgrades spawners;

    @Override
    public void onEnable() {
        retireOldConfig();
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        replaceOutdatedAnnouncement();
        saveConfig();
        reloadSettings();

        legendary = new Legendary(this);
        registry = new Registry(getDataFolder(), getLogger());
        mcmmo = new McMMOHook(this); // replaced once the server has finished starting (see below)
        shopListener = new ShopListener(this);

        getServer().getPluginManager().registerEvents(new AbilityListener(this), this);
        getServer().getPluginManager().registerEvents(new PerkListener(this), this);
        getServer().getPluginManager().registerEvents(new SwordListener(this), this);
        getServer().getPluginManager().registerEvents(new HoeListener(this), this);
        getServer().getPluginManager().registerEvents(new RangedListener(this), this);
        getServer().getPluginManager().registerEvents(new ElytraListener(this), this);
        recovery = new RecoveryListener(this);
        getServer().getPluginManager().registerEvents(recovery, this);
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(new RepairGuardListener(this), this);
        getServer().getPluginManager().registerEvents(new PushedLogListener(this), this);
        getServer().getPluginManager().registerEvents(new AnnouncementListener(this), this);
        skills = new SkillsListener(this);
        getServer().getPluginManager().registerEvents(skills, this);
        flight = new FlightCharm(this);
        getServer().getPluginManager().registerEvents(flight, this);
        spawners = new SpawnerUpgrades(this);
        getServer().getPluginManager().registerEvents(spawners, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
        {
            event.registrar().register("toolshop", "Make and upgrade legendary tools", new ToolShopCommand(this));
            event.registrar().register("skills", "Your mcMMO skills and abilities", java.util.List.of("skill"),
                    new SkillsCommand(this));
        });

        // Connect to mcMMO after every plugin has enabled, so load order doesn't matter.
        getServer().getScheduler().runTask(this, () -> mcmmo = new McMMOHook(this));
        getServer().getScheduler().runTaskTimer(this, this::scanOnlinePlayers, 40L, SCAN_INTERVAL_TICKS);
        getServer().getScheduler().runTaskTimer(this, () -> registry.saveIfDirty(), SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    @Override
    public void onDisable() {
        if (registry != null) registry.save();
        if (recovery != null) recovery.shutdown();
        if (flight != null) flight.shutdown();
        if (spawners != null) spawners.save();
    }

    /** ToolShop 1.x used a different config (rental prices). Keep it as a backup and start fresh. */
    private void retireOldConfig() {
        File config = new File(getDataFolder(), "config.yml");
        if (!config.exists()) return;
        try {
            String text = Files.readString(config.toPath());
            if (text.contains("duration-days:")) {
                File backup = new File(getDataFolder(), "config-1.x-backup.yml");
                Files.move(config.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("Moved the old ToolShop 1.x config to config-1.x-backup.yml and created a new one.");
            }
        } catch (IOException e) {
            getLogger().warning("Couldn't check the old config: " + e.getMessage());
        }
    }

    /** 2.2.0 shipped an announcement that mentioned the old rentals; swap it for the current default text. */
    private void replaceOutdatedAnnouncement() {
        java.util.List<String> lines = getConfig().getStringList("join-announcement.lines");
        if (lines.stream().anyMatch(l -> l.contains("Rented tool upgrades are gone")) && getConfig().getDefaults() != null) {
            getConfig().set("join-announcement.lines", getConfig().getDefaults().getStringList("join-announcement.lines"));
            getLogger().info("Updated the join announcement text (removed the old rental line).");
        }
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        if (flight != null) flight.reload();
        if (spawners != null) spawners.reload();
    }

    public Settings settings() { return settings; }
    public Legendary legendary() { return legendary; }
    public Registry registry() { return registry; }
    public McMMOHook mcmmo() { return mcmmo; }
    public ShopListener shopListener() { return shopListener; }
    public SkillsListener skills() { return skills; }
    public FlightCharm flight() { return flight; }
    public SpawnerUpgrades spawners() { return spawners; }

    /**
     * mcMMO level an upgrade tier needs. The config uses the 0-100 (Standard) scale; with mcMMO in Retro Mode
     * (0-1000) it's multiplied by 10 so the same progress is required either way. For upgrades that fit every
     * tool (Magnet, Soulbound...), mcmmo.skill-multiplier then raises or lowers it for one tool's skill; a tool's own
     * ability (Drill, Timber...) has its own entry in the config and isn't multiplied. Rounded to the nearest 10.
     */
    public int requiredLevel(Upgrade upgrade, int tier, ToolKind kind) {
        int base = settings.tier(upgrade, tier).level() * levelScale();
        if (kind == null || upgrade.onlyFor() != null) return base;
        double mult = settings.skillMultiplier.getOrDefault(kind.mcmmoSkill().toLowerCase(java.util.Locale.ROOT), 1.0);
        if (mult == 1.0) return base;
        long scaled = Math.round(base * mult);
        return (int) (base >= 10 ? Math.round(scaled / 10.0) * 10 : scaled);
    }

    public int levelScale() {
        String scale = getConfig().getString("mcmmo.level-scale", "auto");
        if (scale.equalsIgnoreCase("auto")) return mcmmo != null && mcmmo.retroMode() ? 10 : 1;
        try {
            return Math.max(1, Integer.parseInt(scale.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Keeps legendary tool descriptions up to date and cleans up old 1.x rental upgrades. */
    private void scanOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] contents = inv.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (item == null || item.isEmpty()) continue;
                if (legendary.isLegacyRental(item)) {
                    legendary.stripLegacyRental(item); // quietly turn old 1.x rentals back into normal tools
                    inv.setItem(slot, item);
                } else if (legendary.refresh(item)) {
                    inv.setItem(slot, item);
                }
            }
        }
    }

    /** Appends one line to plugins/ToolShop/purchases.log. */
    public void log(Player player, String action, String toolId) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + " | " + player.getName() + " (" + player.getUniqueId() + ")"
                + " | " + action + " | tool " + toolId + System.lineSeparator();
        try {
            Path file = getDataFolder().toPath().resolve("purchases.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("Could not write purchases.log: " + e.getMessage());
        }
        getLogger().info(line.strip());
    }
}
