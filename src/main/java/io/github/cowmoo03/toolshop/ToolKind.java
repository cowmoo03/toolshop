package io.github.cowmoo03.toolshop;

import org.bukkit.Material;

import java.util.Set;

/** The tool types that can become legendary. Each player can own one of each. */
public enum ToolKind {
    PICKAXE("pickaxe", "Legendary Pickaxe", "MINING", "Mining",
            Set.of(Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE)),
    AXE("axe", "Legendary Axe", "WOODCUTTING", "Woodcutting",
            Set.of(Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE)),
    SHOVEL("shovel", "Legendary Shovel", "EXCAVATION", "Excavation",
            Set.of(Material.IRON_SHOVEL, Material.DIAMOND_SHOVEL, Material.NETHERITE_SHOVEL)),
    SWORD("sword", "Legendary Sword", "SWORDS", "Swords",
            Set.of(Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)),
    HOE("hoe", "Legendary Hoe", "HERBALISM", "Herbalism",
            Set.of(Material.IRON_HOE, Material.DIAMOND_HOE, Material.NETHERITE_HOE)),
    TRIDENT("trident", "Legendary Trident", "TRIDENTS", "Tridents", Set.of(Material.TRIDENT)),
    CROSSBOW("crossbow", "Legendary Crossbow", "CROSSBOWS", "Crossbows", Set.of(Material.CROSSBOW)),
    ELYTRA("elytra", "Legendary Elytra", "ACROBATICS", "Acrobatics", Set.of(Material.ELYTRA));

    private final String id;
    private final String displayName;
    private final String mcmmoSkill;
    private final String skillName;
    private final Set<Material> materials;

    ToolKind(String id, String displayName, String mcmmoSkill, String skillName, Set<Material> materials) {
        this.id = id;
        this.displayName = displayName;
        this.mcmmoSkill = mcmmoSkill;
        this.skillName = skillName;
        this.materials = materials;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    /** mcMMO's name for the skill that unlocks this tool's upgrades. */
    public String mcmmoSkill() { return mcmmoSkill; }
    /** Player-facing skill name, e.g. "Mining". */
    public String skillName() { return skillName; }
    public Set<Material> materials() { return materials; }

    /** Weapons rank up by kills instead of blocks. */
    public boolean countsKills() { return this == SWORD || this == TRIDENT || this == CROSSBOW; }

    /** What the rank counts: "kills", "blocks flown" or "blocks". */
    public String countWord(long n) {
        if (countsKills()) return n == 1 ? "kill" : "kills";
        if (this == ELYTRA) return n == 1 ? "block flown" : "blocks flown";
        return n == 1 ? "block" : "blocks";
    }

    /** What to hold to make one: "an iron, diamond or netherite hoe", "a trident", "an elytra". */
    public String hint() {
        return switch (this) {
            case TRIDENT -> "a trident";
            case CROSSBOW -> "a crossbow";
            case ELYTRA -> "an elytra";
            default -> "an iron, diamond or netherite " + id;
        };
    }

    /** The kind of tool this material is, if it's iron, diamond or netherite; otherwise null. */
    public static ToolKind of(Material material) {
        for (ToolKind k : values()) {
            if (k.materials.contains(material)) return k;
        }
        return null;
    }

    public static ToolKind fromId(String id) {
        if (id == null) return null;
        for (ToolKind k : values()) {
            if (k.id.equalsIgnoreCase(id)) return k;
        }
        return null;
    }
}
