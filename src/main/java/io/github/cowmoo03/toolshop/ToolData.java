package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads and writes the upgrade data stored on a tool.
 * Everything lives in the item's persistent data, so it survives restarts, chests and anvils,
 * and the expiry is an absolute timestamp, so it keeps counting while the player is offline.
 */
public final class ToolData {
    public static final long HOUR = 3_600_000L;
    public static final long DAY = 24 * HOUR;

    /** Warning flags, so each message is only sent once per rental period. */
    public static final int WARNED_DAY = 1;
    public static final int WARNED_HOUR = 2;
    public static final int NOTIFIED_EXPIRED = 4;
    public static final int NOTIFIED_LAPSED = 8;

    private final ToolShopPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey expiresKey;
    private final NamespacedKey idKey;
    private final NamespacedKey flagsKey;

    public ToolData(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "type");
        this.expiresKey = new NamespacedKey(plugin, "expires");
        this.idKey = new NamespacedKey(plugin, "id");
        this.flagsKey = new NamespacedKey(plugin, "flags");
    }

    /** The upgrade on this item, or null if it is a normal item. */
    public ToolType getType(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        ToolType type = ToolType.fromId(pdc.get(typeKey, PersistentDataType.STRING));
        // Only trust the tag on the matching tool material.
        return (type != null && item.getType() == type.material()) ? type : null;
    }

    public long getExpires(ItemStack item) {
        Long v = item.getItemMeta().getPersistentDataContainer().get(expiresKey, PersistentDataType.LONG);
        return v == null ? 0L : v;
    }

    public String getId(ItemStack item) {
        String v = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return v == null ? "?" : v;
    }

    public int getFlags(ItemStack item) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(flagsKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public void setFlags(ItemStack item, int flags) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(flagsKey, PersistentDataType.INTEGER, flags);
        item.setItemMeta(meta);
    }

    public ToolState getState(ItemStack item, long now) {
        long expires = getExpires(item);
        if (now < expires) return ToolState.ACTIVE;
        if (now < expires + plugin.settings().graceMs) return ToolState.GRACE;
        return ToolState.LAPSED;
    }

    /** True if the item is an upgraded tool whose ability currently works. */
    public boolean isActive(ItemStack item, long now) {
        return getType(item) != null && getState(item, now) == ToolState.ACTIVE;
    }

    /** Marks the item as an upgraded tool that is active until {@code expires}. */
    public void activate(ItemStack item, ToolType type, long expires) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(typeKey, PersistentDataType.STRING, type.id());
        pdc.set(expiresKey, PersistentDataType.LONG, expires);
        pdc.set(flagsKey, PersistentDataType.INTEGER, 0);
        if (!pdc.has(idKey, PersistentDataType.STRING)) {
            pdc.set(idKey, PersistentDataType.STRING, UUID.randomUUID().toString().substring(0, 8));
        }
        item.setItemMeta(meta);
        refresh(item, System.currentTimeMillis());
    }

    /**
     * Updates the description, glint and unbreakable flag to match the current state.
     * Returns true if anything changed (so the caller knows to write the item back).
     */
    public boolean refresh(ItemStack item, long now) {
        ToolType type = getType(item);
        if (type == null) return false;
        Settings s = plugin.settings();
        ToolState state = getState(item, now);
        long expires = getExpires(item);

        List<Component> lore = new ArrayList<>();
        lore.add(line(type.displayName(), type.color()).decoration(TextDecoration.BOLD, true));
        lore.add(line(type.howTo(), NamedTextColor.GRAY));
        switch (state) {
            case ACTIVE -> {
                long left = expires - now;
                NamedTextColor c = left < HOUR ? NamedTextColor.RED
                        : left < DAY ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
                lore.add(line("Expires in " + formatDuration(left), c));
                lore.add(line("Renew early at /toolshop", NamedTextColor.DARK_GRAY));
            }
            case GRACE -> {
                lore.add(line("EXPIRED", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
                lore.add(line("Renew within " + formatDuration(expires + s.graceMs - now) + " for "
                        + s.currencyAmount(s.renewPrice(type)), NamedTextColor.YELLOW));
                lore.add(line("at /toolshop", NamedTextColor.YELLOW));
            }
            case LAPSED -> {
                lore.add(line("EXPIRED", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
                lore.add(line("Re-upgrade at /toolshop for " + s.currencyAmount(s.upgradePrice(type)),
                        NamedTextColor.GRAY));
            }
        }

        Boolean glint = state == ToolState.ACTIVE ? Boolean.TRUE : null;
        boolean unbreakable = s.unbreakableWhileActive && state == ToolState.ACTIVE;

        ItemMeta meta = item.getItemMeta();
        Boolean oldGlint = meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : null;
        if (Objects.equals(meta.lore(), lore)
                && Objects.equals(oldGlint, glint)
                && meta.isUnbreakable() == unbreakable) {
            return false;
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(glint);
        meta.setUnbreakable(unbreakable);
        item.setItemMeta(meta);
        return true;
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** "6d 23h", "5h 12m" or "42m". */
    public static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long minutes = (ms + 59_999) / 60_000; // round up so "0m" only shows at the very end
        long days = minutes / (24 * 60);
        long hours = (minutes / 60) % 24;
        long mins = minutes % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}
