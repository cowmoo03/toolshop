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
    HARVEST("harvest", "Harvest", ToolKind.HOE, 3, "Sneak + break a grown crop to harvest the grown crops around it"),
    REPLANT("replant", "Replant", ToolKind.HOE, 1, "Crops you harvest with the hoe plant themselves again"),
    TILLER("tiller", "Tiller", ToolKind.HOE, 3, "Sneak + till to till an area of dirt"),
    IMPALER("impaler", "Impaler", ToolKind.TRIDENT, 3, "Extra trident damage against every mob, not just sea creatures"),
    STORM_CALLER("storm-caller", "Storm Caller", ToolKind.TRIDENT, 3, "A thrown trident calls lightning on what it hits, in any weather"),
    ARROW_SAVER("arrow-saver", "Arrow Saver", ToolKind.CROSSBOW, 3, "Chance to get the arrow back when you shoot"),
    VOLLEY("volley", "Volley", ToolKind.CROSSBOW, 3, "Shoots extra arrows in a spread"),
    HEAVY_BOLTS("heavy-bolts", "Heavy Bolts", ToolKind.CROSSBOW, 3, "Arrows from the crossbow hit harder"),
    BOOST("boost", "Boost", ToolKind.ELYTRA, 3, "Sneak while gliding for a burst of speed"),
    ROCKET_SAVER("rocket-saver", "Rocket Saver", ToolKind.ELYTRA, 3, "Chance for a firework rocket not to be used up"),
    SAFE_LANDING("safe-landing", "Safe Landing", ToolKind.ELYTRA, 1, "No damage from crashing into walls or landing while gliding"),
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
            return switch (kind) {
                case SWORD -> "Chance for a hit to use no durability";
                case TRIDENT -> "Chance for a hit or throw to use no durability";
                case CROSSBOW -> "Chance for a shot to use no durability";
                case ELYTRA -> "Chance for gliding to use no durability";
                default -> "Chance for a block to use no durability";
            };
        }
        if (this == LAST_STAND && (kind == ToolKind.SWORD || kind == ToolKind.TRIDENT)) {
            return "Stops at 1 durability instead of breaking (can't hit until repaired)";
        }
        if (this == LAST_STAND && kind == ToolKind.CROSSBOW) {
            return "Stops at 1 durability instead of breaking (can't shoot until repaired)";
        }
        if (this == LAST_STAND) return "Stops at 1 durability instead of breaking (can't mine until repaired)";
        return summary;
    }

    /** True if this upgrade can go on a tool of this kind and material. */
    public boolean appliesTo(ToolKind kind, Material material) {
        if (onlyFor != null && onlyFor != kind) return false;
        // Magnet is for blocks you break.
        if (this == MAGNET && kind != ToolKind.PICKAXE && kind != ToolKind.AXE && kind != ToolKind.SHOVEL
                && kind != ToolKind.HOE) return false;
        if (this == LAST_STAND && kind == ToolKind.ELYTRA) return false; // elytras already stop at 1 durability
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
