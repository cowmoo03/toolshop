package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/** The upgrades the shop sells. */
public enum ToolType {
    TIMBER("timber", "Timber Axe", Material.NETHERITE_AXE, NamedTextColor.GOLD,
            "Sneak + break a log to fell the whole tree"),
    VEIN("vein", "Vein Pickaxe", Material.NETHERITE_PICKAXE, NamedTextColor.AQUA,
            "Sneak + break an ore to mine the whole vein"),
    EXCAVATOR("excavator", "Excavator Shovel", Material.NETHERITE_SHOVEL, NamedTextColor.GREEN,
            "Sneak + dig to clear a 3x3 area");

    private final String id;
    private final String displayName;
    private final Material material;
    private final NamedTextColor color;
    private final String howTo;

    ToolType(String id, String displayName, Material material, NamedTextColor color, String howTo) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.color = color;
        this.howTo = howTo;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public Material material() { return material; }
    public NamedTextColor color() { return color; }
    public String howTo() { return howTo; }

    /** Lower-case name of the base tool, e.g. "netherite axe". */
    public String baseToolName() {
        return material.name().toLowerCase().replace('_', ' ');
    }

    public static ToolType fromId(String id) {
        if (id == null) return null;
        for (ToolType t : values()) {
            if (t.id.equalsIgnoreCase(id)) return t;
        }
        return null;
    }
}
