package io.github.cowmoo03.toolshop;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-language descriptions of mcMMO's skills and abilities for the /skills window.
 * Unlock levels come from mcMMO's own skillranks.yml at runtime (see {@link McMMOHook#unlockLevels}); the numbers
 * here are only fallbacks (Standard scale) in case that file can't be read.
 */
public final class SkillCatalog {

    public enum Kind { SUPER, PASSIVE }

    /**
     * One ability. {@code rankKey} is its name in skillranks.yml ("SuperBreaker"), or null for abilities that are
     * available from the start.
     */
    public record Ability(String rankKey, String name, Kind kind, String description, String howTo, int fallbackUnlock) {}

    /**
     * One skill. {@code id} is mcMMO's skill name. {@code parents} is set for child skills (no XP of their own).
     * {@code tool} links the skill to the ToolShop tool whose upgrades it unlocks.
     */
    public record Skill(String id, String name, String iconName, String xpFrom, List<String> parents, ToolKind tool,
                        List<Ability> abilities) {
        public Material icon() {
            Material m = Material.matchMaterial(iconName);
            return m == null ? Material.BOOK : m;
        }

        public boolean isChild() {
            return !parents.isEmpty();
        }
    }

    private static Ability sup(String key, String name, String desc, String how) {
        return new Ability(key, name, Kind.SUPER, desc, how, 5);
    }

    private static Ability pas(String key, String name, String desc, int fallback) {
        return new Ability(key, name, Kind.PASSIVE, desc, null, fallback);
    }

    private static Ability limitBreak(String skill) {
        return pas(skill + "LimitBreak", "Limit Break", "Extra damage against players wearing good armor (PvP only).", 10);
    }

    public static final Map<String, Skill> SKILLS = new LinkedHashMap<>();

    /** Rows of the main window: label + skill ids. */
    public static final List<Map.Entry<String, List<String>>> GROUPS = List.of(
            Map.entry("Gathering", List.of("mining", "woodcutting", "excavation", "herbalism", "fishing")),
            Map.entry("Combat", List.of("swords", "axes", "unarmed", "archery", "crossbows", "tridents", "maces", "spears")),
            Map.entry("Survival & Crafting", List.of("acrobatics", "taming", "repair", "salvage", "smelting", "alchemy")));

    private static void add(Skill s) {
        SKILLS.put(s.id(), s);
    }

    static {
        add(new Skill("mining", "Mining", "DIAMOND_PICKAXE", "Mining stone and ores", List.of(), ToolKind.PICKAXE, List.of(
                sup("SuperBreaker", "Super Breaker", "Mine much faster, with a chance of triple drops.",
                        "Right-click with a pickaxe to ready it, then mine."),
                pas("DoubleDrops", "Double Drops", "Chance for ores to drop double.", 1),
                pas("BlastMining", "Blast Mining", "Set off TNT from a distance by right-clicking it with a pickaxe. "
                        + "You get more ore and less rubble.", 10),
                pas("BiggerBombs", "Bigger Bombs", "Your TNT explodes in a bigger radius.", 10),
                pas("DemolitionsExpertise", "Demolitions Expertise", "Take less damage from your own TNT.", 10),
                pas("MotherLode", "Mother Lode", "Chance for ores to drop triple.", 100))));

        add(new Skill("woodcutting", "Woodcutting", "DIAMOND_AXE", "Chopping logs", List.of(), ToolKind.AXE, List.of(
                sup("TreeFeller", "Tree Feller", "Chop down a whole tree at once.",
                        "Right-click with an axe to ready it, then chop a log."),
                pas("HarvestLumber", "Harvest Lumber", "Chance for logs to drop double.", 1),
                pas("LeafBlower", "Leaf Blower", "Break leaves instantly with an axe.", 15),
                pas("KnockOnWood", "Knock On Wood", "Tree Feller also drops saplings, apples and more.", 30),
                pas("CleanCuts", "Clean Cuts", "Chance for logs to drop triple.", 100))));

        add(new Skill("excavation", "Excavation", "DIAMOND_SHOVEL", "Digging dirt, sand, gravel and clay", List.of(),
                ToolKind.SHOVEL, List.of(
                sup("GigaDrillBreaker", "Giga Drill Breaker", "Dig much faster, with triple drops and treasure chance.",
                        "Right-click with a shovel to ready it, then dig."),
                pas("Archaeology", "Archaeology", "Find treasure while digging. Better treasure as you level up.", 1))));

        add(new Skill("herbalism", "Herbalism", "DIAMOND_HOE", "Harvesting crops and plants", List.of(), ToolKind.HOE, List.of(
                sup("GreenTerra", "Green Terra", "Triple crop drops, and right-clicking turns blocks mossy or grassy.",
                        "Right-click with a hoe to ready it, then harvest."),
                pas("DoubleDrops", "Double Drops", "Chance for crops to drop double.", 1),
                pas("FarmersDiet", "Farmer's Diet", "Farmed food fills more hunger.", 20),
                pas("GreenThumb", "Green Thumb", "Crops replant themselves when you harvest them (seeds needed).", 25),
                pas("VerdantBounty", "Verdant Bounty", "Chance for crops to drop triple.", 100))));

        add(new Skill("fishing", "Fishing", "FISHING_ROD", "Catching fish", List.of(), null, List.of(
                pas("TreasureHunter", "Treasure Hunter", "Fish up treasure. Better treasure as you level up.", 1),
                pas("MasterAngler", "Master Angler", "Fish bite faster, especially when fishing from a boat.", 1),
                pas("IceFishing", "Ice Fishing", "Fish through ice in cold biomes.", 5),
                pas("Shake", "Shake", "Hook a mob with your rod to shake an item off it.", 15),
                pas("MagicHunter", "Magic Hunter", "Treasure can come enchanted.", 20),
                pas("FishermansDiet", "Fisherman's Diet", "Fish fills more hunger.", 20))));

        add(new Skill("swords", "Swords", "DIAMOND_SWORD", "Hitting mobs and players with a sword", List.of(), ToolKind.SWORD, List.of(
                sup("SerratedStrikes", "Serrated Strikes", "Your hits also damage enemies around your target and can make them bleed.",
                        "Right-click with a sword to ready it, then attack."),
                pas("Rupture", "Rupture", "Hits can make enemies bleed.", 1),
                pas("CounterAttack", "Counter Attack", "Chance to reflect part of the damage when you're hit.", 20),
                pas("Stab", "Stab", "Bonus sword damage.", 75),
                limitBreak("Swords"))));

        add(new Skill("axes", "Axes", "IRON_AXE", "Hitting mobs and players with an axe", List.of(), null, List.of(
                sup("SkullSplitter", "Skull Splitter", "Your hits also damage enemies around your target.",
                        "Right-click with an axe to ready it, then attack."),
                pas("CriticalStrikes", "Critical Strikes", "Chance to deal double damage.", 1),
                pas("ArmorImpact", "Armor Impact", "Your hits wear down armor faster.", 1),
                pas("AxeMastery", "Axe Mastery", "Bonus axe damage.", 5),
                pas("GreaterImpact", "Greater Impact", "Extra damage and knockback against enemies without armor.", 25),
                limitBreak("Axes"))));

        add(new Skill("unarmed", "Unarmed", "LEATHER", "Fighting with your fists", List.of(), null, List.of(
                sup("Berserk", "Berserk", "+50% damage, and break dirt, sand and other soft blocks instantly.",
                        "Right-click with an empty hand to ready it."),
                pas("SteelArmStyle", "Steel Arm Style", "Bonus fist damage.", 1),
                pas("ArrowDeflect", "Arrow Deflect", "Chance to deflect arrows while empty-handed.", 20),
                pas("Disarm", "Disarm", "Chance to knock the item out of a player's hand (PvP).", 25),
                pas("IronGrip", "Iron Grip", "Harder for others to disarm you.", 60),
                limitBreak("Unarmed"))));

        add(new Skill("archery", "Archery", "BOW", "Hitting mobs and players with a bow", List.of(), null, List.of(
                pas("SkillShot", "Skill Shot", "Bonus bow damage.", 1),
                pas("ArrowRetrieval", "Arrow Retrieval", "Chance to get your arrows back from mobs you kill.", 1),
                new Ability(null, "Daze", Kind.PASSIVE, "Chance to stun players you shoot (PvP).", null, 0),
                limitBreak("Archery"))));

        add(new Skill("crossbows", "Crossbows", "CROSSBOW", "Hitting mobs and players with a crossbow", List.of(), ToolKind.CROSSBOW, List.of(
                pas("PoweredShot", "Powered Shot", "Bonus crossbow damage.", 1),
                pas("TrickShot", "Trick Shot", "Arrows bounce off walls when they hit at a steep angle.", 5),
                limitBreak("Crossbows"))));

        add(new Skill("tridents", "Tridents", "TRIDENT", "Hitting mobs and players with a trident", List.of(), ToolKind.TRIDENT, List.of(
                pas("Impale", "Impale", "Bonus trident damage.", 5),
                limitBreak("Tridents"))));

        add(new Skill("maces", "Maces", "MACE", "Hitting mobs and players with a mace", List.of(), null, List.of(
                pas("Cripple", "Cripple", "Chance to slow down your target.", 5),
                pas("Crush", "Crush", "Bonus mace damage.", 10),
                limitBreak("Maces"))));

        add(new Skill("spears", "Spears", "IRON_SPEAR", "Hitting mobs and players with a spear", List.of(), null, List.of(
                pas("Momentum", "Momentum", "Chance for a short speed boost when you attack.", 1),
                pas("SpearMastery", "Spear Mastery", "Bonus spear damage.", 5),
                limitBreak("Spears"))));

        add(new Skill("acrobatics", "Acrobatics", "FEATHER", "Taking fall damage and dodging attacks", List.of(), ToolKind.ELYTRA, List.of(
                new Ability(null, "Roll", Kind.PASSIVE, "Chance to take no fall damage. Sneak as you land for a "
                        + "Graceful Roll, which works twice as well.", null, 0),
                pas("Dodge", "Dodge", "Chance to take half damage from attacks.", 1))));

        add(new Skill("taming", "Taming", "BONE", "Taming animals and fighting alongside wolves", List.of(), null, List.of(
                pas("CallOfTheWild", "Call of the Wild", "Sneak and left-click holding bones, raw fish or apples to "
                        + "summon a wolf, cat or horse.", 1),
                pas("BeastLore", "Beast Lore", "Hit a pet with a bone to see its health and owner.", 1),
                pas("EnvironmentallyAware", "Environmentally Aware", "Your wolves avoid cactus and lava and take no fall damage.", 10),
                pas("Gore", "Gore", "Wolves can land critical bites that make enemies bleed.", 15),
                pas("Pummel", "Pummel", "Wolves can knock enemies back.", 20),
                pas("FastFoodService", "Fast Food Service", "Wolves heal when they attack.", 20),
                pas("ThickFur", "Thick Fur", "Wolves take less damage and resist fire.", 25),
                pas("HolyHound", "Holy Hound", "Wolves are healed by magic and poison.", 35),
                pas("ShockProof", "Shock Proof", "Wolves take much less damage from explosions.", 50),
                pas("SharpenedClaws", "Sharpened Claws", "Wolves deal bonus damage.", 75))));

        add(new Skill("repair", "Repair", "IRON_BLOCK", "Repairing items on an iron block", List.of(), null, List.of(
                new Ability(null, "Repair", Kind.PASSIVE, "Right-click an iron block holding a damaged tool or armor "
                        + "to fix it with its material (iron ingots, diamonds...).", null, 0),
                pas("RepairMastery", "Repair Mastery", "Repairs restore more durability.", 1),
                pas("ArcaneForging", "Arcane Forging", "Better chance to keep enchantments when repairing.", 10),
                pas("SuperRepair", "Super Repair", "Chance to repair twice as much.", 40))));

        add(new Skill("salvage", "Salvage", "GOLD_BLOCK", null, List.of("Fishing", "Repair"), null, List.of(
                pas("ScrapCollector", "Scrap Collector", "Right-click a gold block holding a tool or armor to break it "
                        + "back into materials.", 1),
                pas("ArcaneSalvage", "Arcane Salvaging", "Get enchanted books back from salvaged items.", 10))));

        add(new Skill("smelting", "Smelting", "FURNACE", null, List.of("Mining", "Repair"), null, List.of(
                pas("FuelEfficiency", "Fuel Efficiency", "Fuel burns longer in furnaces.", 10),
                pas("UnderstandingTheArt", "Second Smelt & Flux Mining", "Chance for double furnace output, and for "
                        + "ores to come out already smelted when you mine them.", 10))));

        add(new Skill("alchemy", "Alchemy", "BREWING_STAND", "Brewing potions", List.of(), null, List.of(
                pas("Catalysis", "Catalysis", "Brew potions faster. The higher your level, the faster.", 0),
                pas("Concoctions", "Concoctions", "Unlocks more potion ingredients as you level up.", 0))));
    }

    private SkillCatalog() {}
}
