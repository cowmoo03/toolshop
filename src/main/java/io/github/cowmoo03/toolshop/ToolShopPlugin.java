package io.github.cowmoo03.toolshop;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ToolShopPlugin extends JavaPlugin {
    public static final Component PREFIX = Component.text("[ToolShop] ", NamedTextColor.GOLD);
    private static final long SCAN_INTERVAL_TICKS = 20L * 30; // every 30 seconds

    private Settings settings;
    private ToolData toolData;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        toolData = new ToolData(this);

        getServer().getPluginManager().registerEvents(new AbilityListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("toolshop", "Buy and renew upgraded tools", new ToolShopCommand(this)));

        getServer().getScheduler().runTaskTimer(this, this::scanOnlinePlayers, 40L, SCAN_INTERVAL_TICKS);
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
    }

    public Settings settings() { return settings; }
    public ToolData toolData() { return toolData; }

    /** Keeps the countdown text up to date and sends expiry warnings for tools in online players' inventories. */
    private void scanOnlinePlayers() {
        long now = System.currentTimeMillis();
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] contents = inv.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                ToolType type = toolData.getType(item);
                if (type == null) continue;
                boolean changed = notify(player, item, type, now);
                changed |= toolData.refresh(item, now);
                if (changed) inv.setItem(slot, item);
            }
        }
    }

    /** Sends each warning once. Returns true if the item's flags changed. */
    private boolean notify(Player player, ItemStack item, ToolType type, long now) {
        int flags = toolData.getFlags(item);
        int newFlags = flags;
        String message = null;
        NamedTextColor color = NamedTextColor.YELLOW;
        long left = toolData.getExpires(item) - now;

        switch (toolData.getState(item, now)) {
            case ACTIVE -> {
                if (left <= ToolData.HOUR && (flags & ToolData.WARNED_HOUR) == 0) {
                    message = "Your " + type.displayName() + " expires in " + ToolData.formatDuration(left)
                            + ". Renew it at /toolshop.";
                    color = NamedTextColor.RED;
                    newFlags |= ToolData.WARNED_DAY | ToolData.WARNED_HOUR;
                } else if (left <= ToolData.DAY && (flags & ToolData.WARNED_DAY) == 0) {
                    message = "Your " + type.displayName() + " expires in " + ToolData.formatDuration(left)
                            + ". Renew it at /toolshop.";
                    newFlags |= ToolData.WARNED_DAY;
                }
            }
            case GRACE -> {
                if ((flags & ToolData.NOTIFIED_EXPIRED) == 0) {
                    message = "Your " + type.displayName() + " has expired. Renew within "
                            + ToolData.formatDuration(left + settings.graceMs) + " for "
                            + settings.currencyAmount(settings.renewPrice(type)) + " at /toolshop.";
                    color = NamedTextColor.RED;
                    newFlags |= ToolData.WARNED_DAY | ToolData.WARNED_HOUR | ToolData.NOTIFIED_EXPIRED;
                }
            }
            case LAPSED -> {
                if ((flags & ToolData.NOTIFIED_LAPSED) == 0) {
                    message = "The renewal window for your " + type.displayName()
                            + " has ended. Re-upgrading now costs "
                            + settings.currencyAmount(settings.upgradePrice(type)) + ".";
                    color = NamedTextColor.GRAY;
                    newFlags = ToolData.WARNED_DAY | ToolData.WARNED_HOUR
                            | ToolData.NOTIFIED_EXPIRED | ToolData.NOTIFIED_LAPSED;
                }
            }
        }

        if (message != null) player.sendMessage(PREFIX.append(Component.text(message, color)));
        if (newFlags == flags) return false;
        toolData.setFlags(item, newFlags);
        return true;
    }

    /** Appends one line to plugins/ToolShop/purchases.log. */
    public void logPurchase(Player player, String action, ToolType type, int price, String toolId) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + " | " + player.getName() + " (" + player.getUniqueId() + ")"
                + " | " + action + " | " + type.displayName()
                + " | paid " + settings.currencyAmount(price)
                + " | tool " + toolId + System.lineSeparator();
        try {
            Path file = getDataFolder().toPath().resolve("purchases.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("Could not write purchases.log: " + e.getMessage());
        }
        getLogger().info(line.strip());
    }
}
