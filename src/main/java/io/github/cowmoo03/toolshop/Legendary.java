package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads and writes the legendary data stored on a tool (owner, ID, kind, upgrade tiers, pickaxe mode)
 * and keeps its name and description up to date.
 */
public final class Legendary {
    public static final String MODE_DRILL = "drill";
    public static final String MODE_VEIN = "vein";

    private final ToolShopPlugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey modeKey;
    private final NamespacedKey savesKey;
    private final Map<Upgrade, NamespacedKey> tierKeys = new EnumMap<>(Upgrade.class);
    /** Keys used by ToolShop 1.x rentals, so old tools can be cleaned up. */
    private final NamespacedKey legacyType;
    private final NamespacedKey legacyExpires;
    private final NamespacedKey legacyFlags;
    private final NamespacedKey legacyId;

    public Legendary(ToolShopPlugin plugin) {
        this.plugin = plugin;
        idKey = new NamespacedKey(plugin, "lt_id");
        ownerKey = new NamespacedKey(plugin, "lt_owner");
        kindKey = new NamespacedKey(plugin, "lt_kind");
        modeKey = new NamespacedKey(plugin, "lt_mode");
        savesKey = new NamespacedKey(plugin, "lt_soul_saves");
        for (Upgrade u : Upgrade.values()) tierKeys.put(u, new NamespacedKey(plugin, "lt_up_" + u.id().replace('-', '_')));
        legacyType = new NamespacedKey(plugin, "type");
        legacyExpires = new NamespacedKey(plugin, "expires");
        legacyFlags = new NamespacedKey(plugin, "flags");
        legacyId = new NamespacedKey(plugin, "id");
    }

    // ------------------------------------------------------------------ reading

    /** The kind of legendary tool this is, or null if it isn't one. */
    public ToolKind kind(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(idKey, PersistentDataType.STRING)) return null;
        ToolKind kind = ToolKind.fromId(pdc.get(kindKey, PersistentDataType.STRING));
        return (kind != null && kind.materials().contains(item.getType())) ? kind : null;
    }

    public boolean isLegendary(ItemStack item) {
        return kind(item) != null;
    }

    public String id(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public UUID owner(ItemStack item) {
        String s = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** All upgrade tiers on this item added up (Drill III + Magnet I = 4). */
    public int totalTiers(ItemStack item) {
        int sum = 0;
        for (Upgrade u : Upgrade.values()) sum += tier(item, u);
        return sum;
    }

    public int tier(ItemStack item, Upgrade upgrade) {
        Integer t = item.getItemMeta().getPersistentDataContainer().get(tierKeys.get(upgrade), PersistentDataType.INTEGER);
        return t == null ? 0 : t;
    }

    public String mode(ItemStack item) {
        String m = item.getItemMeta().getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        return MODE_VEIN.equals(m) ? MODE_VEIN : MODE_DRILL;
    }

    /** Soulbound saves left on this tool. */
    public int saves(ItemStack item) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(savesKey, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }

    /** Most Soulbound saves this tool can hold at its current tier. */
    public int maxSaves(ItemStack item) {
        int tier = tier(item, Upgrade.SOULBOUND);
        return tier <= 0 ? 0 : Settings.forTier(plugin.settings().soulboundSaves, tier);
    }

    public void setSaves(ItemStack item, int saves) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(savesKey, PersistentDataType.INTEGER, Math.max(0, saves));
        item.setItemMeta(meta);
    }

    /** True if this is still the owner's current legendary tool (not retired by a newer one). */
    public boolean isCurrent(ItemStack item) {
        ToolKind kind = kind(item);
        UUID owner = kind == null ? null : owner(item);
        return owner != null && Objects.equals(plugin.registry().activeId(owner, kind), id(item));
    }

    /** True if this player may use this tool's abilities and upgrades right now. */
    public boolean usableBy(ItemStack item, Player player) {
        return isLegendary(item) && player.getUniqueId().equals(owner(item)) && isCurrent(item);
    }

    // ------------------------------------------------------------------ writing

    /** Turns a plain tool into a new legendary tool owned by the player. Returns its new ID. */
    public String create(ItemStack item, Player owner, ToolKind kind) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, id);
        pdc.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        pdc.set(kindKey, PersistentDataType.STRING, kind.id());
        pdc.set(modeKey, PersistentDataType.STRING, MODE_DRILL);
        for (NamespacedKey k : tierKeys.values()) pdc.remove(k);
        pdc.remove(savesKey);
        item.setItemMeta(meta);
        return id;
    }

    /** Sets an upgrade's tier. Setting Soulbound also refills its saves to the new tier's maximum. */
    public void setTier(ItemStack item, Upgrade upgrade, int tier) {
        ItemMeta meta = item.getItemMeta();
        if (tier <= 0) meta.getPersistentDataContainer().remove(tierKeys.get(upgrade));
        else meta.getPersistentDataContainer().set(tierKeys.get(upgrade), PersistentDataType.INTEGER, tier);
        item.setItemMeta(meta);
        if (upgrade == Upgrade.SOULBOUND) setSaves(item, maxSaves(item));
    }

    public void setMode(ItemStack item, String mode) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode);
        item.setItemMeta(meta);
    }

    /** Removes all legendary data, name and description, leaving a plain tool (enchantments and damage stay). */
    public void strip(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(idKey);
        pdc.remove(ownerKey);
        pdc.remove(kindKey);
        pdc.remove(modeKey);
        pdc.remove(savesKey);
        for (NamespacedKey k : tierKeys.values()) pdc.remove(k);
        meta.lore(null);
        meta.itemName(null);
        meta.setEnchantmentGlintOverride(null);
        item.setItemMeta(meta);
    }

    // ------------------------------------------------------------------ old 1.x rentals

    public boolean isLegacyRental(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(legacyExpires, PersistentDataType.LONG);
    }

    /** Removes a ToolShop 1.x rental upgrade, leaving a normal tool. */
    public void stripLegacyRental(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(legacyType);
        pdc.remove(legacyExpires);
        pdc.remove(legacyFlags);
        pdc.remove(legacyId);
        meta.lore(null);
        meta.setEnchantmentGlintOverride(null);
        meta.setUnbreakable(false);
        item.setItemMeta(meta);
    }

    // ------------------------------------------------------------------ name and description

    /**
     * Updates the tool's name and description. Returns true if anything changed, so the caller
     * knows to write the item back. Blocks broken are shown rounded, so this rarely changes while mining
     * (changing the held item makes the hand bob).
     */
    /** Upgrades that were replaced (2.12.0): their old key, and what they became (same tier). */
    private static final Map<String, Upgrade> RENAMED = Map.of(
            "lt_up_replant", Upgrade.GROWTH_AURA,
            "lt_up_impaler", Upgrade.TIDAL_PULL,
            "lt_up_heavy_bolts", Upgrade.BLAST_BOLTS,
            "lt_up_safe_landing", Upgrade.CRASH_GUARD);

    /** Moves a replaced upgrade's tier onto its replacement. */
    private void migrateRenamed(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean changed = false;
        for (Map.Entry<String, Upgrade> e : RENAMED.entrySet()) {
            NamespacedKey old = new NamespacedKey(plugin, e.getKey());
            Integer t = pdc.get(old, PersistentDataType.INTEGER);
            if (t == null) continue;
            pdc.remove(old);
            NamespacedKey now = tierKeys.get(e.getValue());
            int have = pdc.getOrDefault(now, PersistentDataType.INTEGER, 0);
            pdc.set(now, PersistentDataType.INTEGER, Math.min(e.getValue().maxTier(), Math.max(have, t)));
            changed = true;
        }
        if (changed) item.setItemMeta(meta);
    }

    public boolean refresh(ItemStack item) {
        ToolKind kind = kind(item);
        if (kind == null) return false;
        migrateRenamed(item);
        Settings s = plugin.settings();
        String id = id(item);
        long blocks = plugin.registry().blocks(id);
        UUID owner = owner(item);
        OfflinePlayer op = owner == null ? null : Bukkit.getOfflinePlayer(owner);
        String ownerName = op == null || op.getName() == null ? "unknown" : op.getName();
        boolean current = isCurrent(item);

        List<Component> lore = new ArrayList<>();
        lore.add(line("★ " + s.rankFor(kind, blocks) + "  ·  " + roundCount(blocks) + " " + kind.countWord(blocks),
                NamedTextColor.GOLD));
        lore.add(line("Owner: " + ownerName, NamedTextColor.GRAY));
        if (!current) {
            lore.add(line("RETIRED: replaced by a newer legendary tool", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true));
        }
        lore.add(Component.empty());

        boolean any = false;
        for (Upgrade u : Upgrade.values()) {
            int t = tier(item, u);
            if (t <= 0) continue;
            any = true;
            String suffix = "";
            if (u == Upgrade.SOULBOUND) {
                suffix = "  (" + saves(item) + "/" + maxSaves(item) + " saves)";
            } else if (u == Upgrade.FIREPROOF || u == Upgrade.RECOVERY) {
                int secs = Settings.forTier(u == Upgrade.FIREPROOF ? s.fireproofSeconds : s.recoverySeconds, t);
                suffix = "  (" + (secs % 60 == 0 ? (secs / 60) + " min" : secs + "s") + ")";
            }
            if (u == Upgrade.DRILL || u == Upgrade.VEIN) {
                boolean both = tier(item, Upgrade.DRILL) > 0 && tier(item, Upgrade.VEIN) > 0;
                if (both && mode(item).equals(u == Upgrade.DRILL ? MODE_DRILL : MODE_VEIN)) suffix = "  ◀ active";
            }
            lore.add(line("• " + u.withTier(t) + suffix, NamedTextColor.AQUA));
        }
        if (!any) lore.add(line("No upgrades yet", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        String synergy = SkillSynergy.describe(plugin, item, kind);
        if (synergy != null) lore.add(line("⚡ " + synergy, NamedTextColor.LIGHT_PURPLE));
        lore.add(line("Upgrade at /toolshop", NamedTextColor.DARK_GRAY));

        Component name = Component.text(kind.displayName(), current ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);

        ItemMeta meta = item.getItemMeta();
        Boolean glint = current ? Boolean.TRUE : null;
        Boolean oldGlint = meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : null;
        Component oldName = meta.hasItemName() ? meta.itemName() : null;
        if (Objects.equals(meta.lore(), lore) && Objects.equals(oldName, name) && Objects.equals(oldGlint, glint)) {
            return false;
        }
        meta.lore(lore);
        // itemName is the base name: an anvil rename still shows instead, which is fine.
        meta.itemName(name);
        meta.setEnchantmentGlintOverride(glint);
        item.setItemMeta(meta);
        return true;
    }

    /** 950 -> "950", 1234 -> "1.2k", 25300 -> "25k", 1500000 -> "1.5M". */
    public static String roundCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 10_000) return String.format("%.1fk", n / 1000.0);
        if (n < 1_000_000) return (n / 1000) + "k";
        return String.format("%.1fM", n / 1_000_000.0);
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
