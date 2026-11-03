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

    @Override
    public void onEnable() {
        retireOldConfig();
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadSettings();

        legendary = new Legendary(this);
        registry = new Registry(getDataFolder(), getLogger());
        mcmmo = new McMMOHook(this); // replaced once the server has finished starting (see below)
        shopListener = new ShopListener(this);

        getServer().getPluginManager().registerEvents(new AbilityListener(this), this);
        getServer().getPluginManager().registerEvents(new PerkListener(this), this);
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(new RepairGuardListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("toolshop", "Make and upgrade legendary tools", new ToolShopCommand(this)));

        // Connect to mcMMO after every plugin has enabled, so load order doesn't matter.
        getServer().getScheduler().runTask(this, () -> mcmmo = new McMMOHook(this));
        getServer().getScheduler().runTaskTimer(this, this::scanOnlinePlayers, 40L, SCAN_INTERVAL_TICKS);
        getServer().getScheduler().runTaskTimer(this, () -> registry.saveIfDirty(), SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    @Override
    public void onDisable() {
        if (registry != null) registry.save();
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

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
    }

    public Settings settings() { return settings; }
    public Legendary legendary() { return legendary; }
    public Registry registry() { return registry; }
    public McMMOHook mcmmo() { return mcmmo; }
    public ShopListener shopListener() { return shopListener; }

    /** Keeps legendary tool descriptions up to date and cleans up old 1.x rental upgrades. */
    private void scanOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] contents = inv.getContents();
            boolean strippedAny = false;
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (item == null || item.isEmpty()) continue;
                if (legendary.isLegacyRental(item)) {
                    legendary.stripLegacyRental(item);
                    inv.setItem(slot, item);
                    strippedAny = true;
                } else if (legendary.refresh(item)) {
                    inv.setItem(slot, item);
                }
            }
            if (strippedAny) {
                player.sendMessage(PREFIX.append(Component.text("ToolShop changed! Rented upgrades are gone; "
                        + "tools are now made legendary and upgraded for good. Open /toolshop to start.",
                        NamedTextColor.YELLOW)));
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
