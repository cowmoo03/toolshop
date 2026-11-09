package io.github.cowmoo03.toolshop;

import org.bukkit.Material;

/** Everything that can be bought for a legendary tool. */
public enum Upgrade {
    DRILL("drill", "Drill", ToolKind.PICKAXE, 3, "Sneak + mine to break an area of stone"),
    VEIN("vein", "Vein Mining", ToolKind.PICKAXE, 3, "Sneak + mine an ore to break the whole vein"),
    TIMBER("timber", "Timber", ToolKind.AXE, 3, "Sneak + chop to fell the whole tree"),
    EXCAVATOR("excavator", "Excavator", ToolKind.SHOVEL, 3, "Sneak + dig to clear an area"),
    LIFESTEAL("lifesteal", "Lifesteal", ToolKind.SWORD, 3, "Heals you for part of the damage you deal"),
    BEHEADING("beheading", "Beheading", ToolKind.SWORD, 3, "Chance for a mob's (or player's) head when you kill it"),
    BOUNTY("bounty", "Bounty", ToolKind.SWORD, 3, "Kills drop more XP orbs"),
    MAGNET("magnet", "Magnet", null, 1, "Drops from blocks you break go straight into your inventory"),
    DURABILITY("durability", "Durability Saver", null, 3, "Chance for a block or hit to use no durability"),
    LAST_STAND("last-stand", "Last Stand", null, 1, "Stops at 1 durability instead of breaking"),
    FIREPROOF("fireproof", "Fireproof", null, 3, "Survives lava and fire for a while when dropped"),
    RECOVERY("recovery", "Recovery", null, 3, "A dropped tool glows for you, lasts longer, and survives explosions and the void"),
    SOULBOUND("soulbound", "Soulbound", null, 3, "Saves the tool when you die, a set number of times");

    private final String id;
    private final String displayName;
    private final ToolKind onlyFor;
    private final int maxTier;
    private final String summary;

    Upgrade(String id, String displayName, ToolKind onlyFor, int maxTier, String summary) {
        this.id = id;
        this.displayName = displayName;
        this.onlyFor = onlyFor;
        this.maxTier = maxTier;
        this.summary = summary;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public int maxTier() { return maxTier; }
    /** The one tool kind this upgrade is for (Drill, Timber...), or null if it fits every tool. */
    public ToolKind onlyFor() { return onlyFor; }
    public String summary() { return summary; }

    /** The summary worded for this tool (swords hit things instead of breaking blocks). */
    public String summary(ToolKind kind) {
        if (kind == null) return summary;
        if (this == DURABILITY) {
            return kind == ToolKind.SWORD ? "Chance for a hit to use no durability" : "Chance for a block to use no durability";
        }
        if (this == LAST_STAND && kind == ToolKind.SWORD) {
            return "Stops at 1 durability instead of breaking (can't hit until repaired)";
        }
        if (this == LAST_STAND) return "Stops at 1 durability instead of breaking (can't mine until repaired)";
        return summary;
    }

    /** True if this upgrade can go on a tool of this kind and material. */
    public boolean appliesTo(ToolKind kind, Material material) {
        if (onlyFor != null && onlyFor != kind) return false;
        if (this == MAGNET && kind == ToolKind.SWORD) return false; // Magnet is for blocks you break
        boolean netherite = material.name().startsWith("NETHERITE_");
        // Netherite items never burn anyway, so Fireproof would do nothing for them; Recovery is their version.
        if (this == FIREPROOF) return !netherite;
        if (this == RECOVERY) return netherite;
        return true;
    }

    /** "Drill II", or just "Magnet" for single-tier upgrades. */
    public String withTier(int tier) {
        return maxTier == 1 ? displayName : displayName + " " + roman(tier);
    }

    public static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    public static Upgrade fromId(String id) {
        if (id == null) return null;
        for (Upgrade u : values()) {
            if (u.id.equalsIgnoreCase(id) || u.name().equalsIgnoreCase(id)) return u;
        }
        return null;
    }
}
