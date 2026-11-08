package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The /toolshop window. The first page shows the player's three legendary tools, the "Make it Legendary" star
 * (which needs a plain iron/diamond/netherite tool in hand) and extras like the Flight Charm. Clicking a legendary
 * tool that's in their inventory opens its upgrades. Every purchase needs a second click to confirm.
 */
public final class ShopMenu implements InventoryHolder {
    public static final int TOOL_SLOT = 4;
    /** Home page: one slot per tool kind (pickaxe, axe, shovel, sword) and the star. */
    private static final int[] HOME_TOOL_SLOTS = {10, 12, 14, 16};
    public static final int CREATE_SLOT = 30;
    public static final int FLIGHT_SLOT = 32;
    public static final int CATALOG_SLOT = 40;
    /** All-upgrades page: each tool's own abilities on rows 2-3, the upgrades every tool can get on row 4. */
    private static final Map<Upgrade, Integer> CATALOG_SLOTS = Map.ofEntries(
            Map.entry(Upgrade.DRILL, 10), Map.entry(Upgrade.VEIN, 11), Map.entry(Upgrade.TIMBER, 13),
            Map.entry(Upgrade.EXCAVATOR, 15), Map.entry(Upgrade.LIFESTEAL, 21), Map.entry(Upgrade.BEHEADING, 22),
            Map.entry(Upgrade.BOUNTY, 23), Map.entry(Upgrade.MAGNET, 28), Map.entry(Upgrade.DURABILITY, 29),
            Map.entry(Upgrade.LAST_STAND, 30), Map.entry(Upgrade.FIREPROOF, 32), Map.entry(Upgrade.RECOVERY, 33),
            Map.entry(Upgrade.SOULBOUND, 34));
    public static final int BACK_SLOT = 36;
    public static final int MODE_SLOT = 40;
    public static final int RECHARGE_SLOT = 42;
    public static final int INFO_SLOT = 36;
    private static final int[] UPGRADE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33};
    public static final long CONFIRM_WINDOW_MS = 5000;

    /** What a slot does. */
    public sealed interface Action permits Create, Buy, ToggleMode, Recharge, Open, Back, Retire, Catalog {}
    public record Catalog() implements Action {}
    public record Retire(ToolKind kind) implements Action {}
    public record Open(ToolKind kind) implements Action {}
    public record Back() implements Action {}
    public record Create(ToolKind kind) implements Action {}
    public record Buy(Upgrade upgrade) implements Action {}
    public record ToggleMode() implements Action {}
    public record Recharge() implements Action {}

    private final ToolShopPlugin plugin;
    private final Inventory inventory;
    private final Map<Integer, Action> actions = new HashMap<>();
    private Action pending;
    private long pendingUntil;
    /** null: the tools page; otherwise the upgrades page for this tool kind. */
    private ToolKind page;
    /** True while the all-upgrades page is showing. */
    private boolean catalog;

    public ShopMenu(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, 45, Component.text("Tool Shop"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Action actionAt(int slot) {
        return actions.get(slot);
    }

    public boolean isAwaitingConfirm(Action action, long now) {
        return action.equals(pending) && now < pendingUntil;
    }

    public void askConfirm(Action action, long now) {
        pending = action;
        pendingUntil = now + CONFIRM_WINDOW_MS;
    }

    public void clearConfirm() {
        pending = null;
    }

    /** The tool kind whose upgrades are showing, or null on the tools page. */
    public ToolKind page() {
        return page;
    }

    public void setPage(ToolKind kind) {
        page = kind;
        catalog = false;
        pending = null;
    }

    /** Shows the page with every upgrade in the shop. */
    public void showCatalog() {
        page = null;
        catalog = true;
        pending = null;
    }

    /**
     * Inventory slot of the player's own current legendary tool of this kind (main hand first, then the rest of the
     * inventory and the off hand), or -1 if they aren't carrying it.
     */
    public static int findTool(ToolShopPlugin plugin, Player player, ToolKind kind) {
        Legendary lt = plugin.legendary();
        PlayerInventory inv = player.getInventory();
        int held = inv.getHeldItemSlot();
        if (lt.usableBy(inv.getItem(held), player) && lt.kind(inv.getItem(held)) == kind) return held;
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && lt.usableBy(item, player) && lt.kind(item) == kind) return slot;
        }
        return -1;
    }

    public void render(Player player) {
        actions.clear();
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        if (page != null && findTool(plugin, player, page) < 0) page = null; // tool left the inventory
        if (catalog) renderCatalog(player);
        else if (page == null) renderHome(player);
        else renderTool(player, page);
    }

    /** The tools page: the player's three legendary tools and the star. */
    private void renderHome(Player player) {
        inventory.setItem(INFO_SLOT, infoButton());
        actions.put(CATALOG_SLOT, new Catalog());
        inventory.setItem(CATALOG_SLOT, icon(Material.ENCHANTED_BOOK, text("All Upgrades", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true), List.of(
                text("Every upgrade in the Tool Shop:", NamedTextColor.GRAY),
                text("what it does, the levels and items it needs.", NamedTextColor.GRAY),
                Component.empty(),
                text("Click to see them", NamedTextColor.GREEN)), false));
        Legendary lt = plugin.legendary();
        ToolKind[] kinds = ToolKind.values();
        for (int k = 0; k < kinds.length && k < HOME_TOOL_SLOTS.length; k++) {
            ToolKind kind = kinds[k];
            int slot = HOME_TOOL_SLOTS[k];
            int invSlot = findTool(plugin, player, kind);
            if (invSlot >= 0) {
                ItemStack tool = player.getInventory().getItem(invSlot);
                lt.refresh(tool);
                ItemStack shown = tool.clone();
                ItemMeta meta = shown.getItemMeta();
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Component.empty());
                lore.add(text("Click to see upgrades", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                shown.setItemMeta(meta);
                inventory.setItem(slot, shown);
                actions.put(slot, new Open(kind));
            } else if (plugin.registry().activeId(player.getUniqueId(), kind) != null) {
                Retire retire = new Retire(kind);
                boolean confirming = isAwaitingConfirm(retire, System.currentTimeMillis());
                actions.put(slot, retire);
                List<Component> lore = new ArrayList<>(List.of(
                        text("Not in your inventory.", NamedTextColor.RED),
                        text("Carry it with you to see and buy its upgrades.", NamedTextColor.GRAY),
                        Component.empty(),
                        text("Lost it for good? You can retire it:", NamedTextColor.GRAY),
                        text("it stops working wherever it is (even in a", NamedTextColor.GRAY),
                        text("chest or ender chest) and its upgrades are gone.", NamedTextColor.GRAY),
                        text("Making a new one retires it anyway.", NamedTextColor.DARK_GRAY),
                        Component.empty()));
                lore.add(confirming
                        ? text("CLICK AGAIN TO RETIRE IT FOREVER", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                        : text("Click to retire it", NamedTextColor.YELLOW));
                inventory.setItem(slot, icon(confirming ? Material.BARRIER : plainIcon(kind),
                        text(kind.displayName(), NamedTextColor.GRAY), lore, confirming));
            } else {
                inventory.setItem(slot, icon(plainIcon(kind), text("No " + kind.displayName() + " yet",
                        NamedTextColor.GRAY), List.of(
                        text("Hold an iron, diamond or netherite " + kind.id(), NamedTextColor.GRAY),
                        text("and click the star below to make it legendary.", NamedTextColor.GRAY)), false));
            }
        }

        if (plugin.flight() != null && plugin.flight().enabled()) inventory.setItem(FLIGHT_SLOT, flightButton(player));

        ItemStack hand = player.getInventory().getItemInMainHand();
        ToolKind plainKind = ToolKind.of(hand.getType());
        if (plainKind != null && !lt.isLegendary(hand)) {
            Create create = new Create(plainKind);
            actions.put(CREATE_SLOT, create);
            inventory.setItem(CREATE_SLOT, createButton(player, plainKind, isAwaitingConfirm(create, System.currentTimeMillis())));
        } else if (plainKind != null && !lt.usableBy(hand, player)) {
            boolean yours = player.getUniqueId().equals(lt.owner(hand));
            inventory.setItem(CREATE_SLOT, icon(Material.NETHER_STAR, text(yours ? "Retired tool" : "Not your tool",
                    NamedTextColor.RED), List.of(text(yours
                            ? "You've made a newer legendary tool of this type,"
                            : "The tool in your hand belongs to someone else.", NamedTextColor.GRAY),
                    text(yours ? "so the one in your hand no longer works." : "Only its owner can use or upgrade it.",
                            NamedTextColor.GRAY)), false));
        } else {
            inventory.setItem(CREATE_SLOT, icon(Material.NETHER_STAR, text("Make a tool Legendary", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true), List.of(
                    text(plainKind != null ? "The tool in your hand is already legendary." : "Hold a tool to make it legendary:",
                            NamedTextColor.YELLOW),
                    text("an iron, diamond or netherite pickaxe,", NamedTextColor.GRAY),
                    text("axe, shovel or sword, then open /toolshop again.", NamedTextColor.GRAY)), false));
        }
    }

    /** Every upgrade in the shop, with what each tier does, the levels it needs and its items. */
    private void renderCatalog(Player player) {
        actions.put(BACK_SLOT, new Back());
        inventory.setItem(BACK_SLOT, icon(Material.ARROW, text("Back", NamedTextColor.YELLOW),
                List.of(text("Back to your tools", NamedTextColor.GRAY)), false));
        inventory.setItem(4, icon(Material.ENCHANTED_BOOK, text("All Upgrades", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true), List.of(
                text("Top: each tool's own ability.", NamedTextColor.GRAY),
                text("Bottom: upgrades every tool can get.", NamedTextColor.GRAY),
                text("Levels are in the tool's mcMMO skill.", NamedTextColor.GRAY),
                text("Buy them by clicking your tool on the first page.", NamedTextColor.DARK_GRAY)), false));
        inventory.setItem(31, icon(Material.CRAFTING_TABLE, text("Every tool can get these", NamedTextColor.YELLOW),
                List.of(text("Pickaxe, axe, shovel and sword", NamedTextColor.GRAY),
                        text("(Magnet: not on swords)", NamedTextColor.DARK_GRAY)), false));
        for (Upgrade u : Upgrade.values()) {
            Integer slot = CATALOG_SLOTS.get(u);
            if (slot != null) inventory.setItem(slot, catalogIcon(player, u));
        }
    }

    private ItemStack catalogIcon(Player player, Upgrade u) {
        Settings s = plugin.settings();
        boolean levels = plugin.mcmmo().available() && s.requireLevels;
        List<Component> lore = new ArrayList<>();
        lore.add(text(u.summary(), NamedTextColor.GRAY));
        if (u.onlyFor() != null) lore.add(text(u.onlyFor().displayName().replace("Legendary ", "") + " only", NamedTextColor.DARK_AQUA));
        if (u == Upgrade.FIREPROOF) lore.add(text("Iron and diamond tools only (netherite can't burn)", NamedTextColor.DARK_AQUA));
        if (u == Upgrade.RECOVERY) lore.add(text("Netherite tools only", NamedTextColor.DARK_AQUA));
        ToolKind[] kinds = u.onlyFor() != null ? new ToolKind[]{u.onlyFor()}
                : java.util.Arrays.stream(ToolKind.values()).filter(k -> appliesToKind(u, k)).toArray(ToolKind[]::new);
        if (u == Upgrade.MAGNET) lore.add(text("Not on swords", NamedTextColor.DARK_AQUA));
        for (int t = 1; t <= u.maxTier(); t++) {
            lore.add(Component.empty());
            String detail = tierDetail(u, t);
            String head = u.maxTier() > 1 ? Upgrade.roman(t) + (detail != null ? ": " + detail : "") : (detail != null ? detail : "");
            if (!head.isEmpty()) lore.add(text(head, NamedTextColor.WHITE));
            if (levels) {
                List<String> parts = new ArrayList<>();
                boolean allOk = true;
                java.util.Set<Integer> distinct = new java.util.HashSet<>();
                for (ToolKind k : kinds) distinct.add(plugin.requiredLevel(u, t, k));
                for (ToolKind k : kinds) {
                    int need = plugin.requiredLevel(u, t, k);
                    int have = plugin.mcmmo().level(player, k.mcmmoSkill());
                    if (have < need) allOk = false;
                    parts.add(k.skillName() + " " + need);
                }
                String line = kinds.length > 1 && distinct.size() == 1
                        ? "Level " + distinct.iterator().next() + " in the tool's skill"
                        : String.join(" · ", parts);
                lore.add(text("  " + line, allOk ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            Map<Material, Integer> cost = s.tier(u, t).cost();
            lore.add(text("  " + (cost.isEmpty() ? "No items" : Settings.describeCost(cost)), NamedTextColor.GRAY));
        }
        return icon(iconFor(u), text(u.displayName(), NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), lore, false);
    }

    /** The Flight Charm: what it does, the recipe, and whether this player has unlocked crafting it. */
    private ItemStack flightButton(Player player) {
        FlightCharm fc = plugin.flight();
        List<Component> lore = new ArrayList<>();
        lore.add(text("Right-click to fly for " + FlightCharm.durationText(fc.flightSeconds()) + ", for building.", NamedTextColor.GRAY));
        lore.add(text("Leaving " + fc.maxDistance() + " blocks of where you took off", NamedTextColor.GRAY));
        lore.add(text("pauses it until you come back.", NamedTextColor.GRAY));
        lore.add(text("Fighting a player ends it. No fall damage.", NamedTextColor.GRAY));
        lore.add(text("One flight at a time; each charm is used up.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("Craft: " + fc.recipeText(), NamedTextColor.WHITE));
        lore.add(text("(feathers in the corners, diamond in the middle)", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        int need = fc.requiredPowerLevel();
        boolean unlocked;
        if (need <= 0) {
            unlocked = true;
            lore.add(text("✔ Anyone can craft it", NamedTextColor.GREEN));
        } else {
            int have = plugin.mcmmo().powerLevel(player);
            unlocked = have >= need;
            lore.add(text((unlocked ? "✔ " : "🔒 ") + "Needs power level " + need + " (you: " + (have < 0 ? "?" : have) + ")",
                    unlocked ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(text(unlocked ? "Unlocked: it's in your recipe book." : "Power level is all your skill levels added up.",
                    NamedTextColor.GRAY));
        }
        lore.add(text("Anyone can use a charm they're given.", NamedTextColor.DARK_GRAY));
        return icon(Material.FEATHER, text("Flight Charm", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true),
                lore, unlocked);
    }

    /** The upgrades page for one of the player's legendary tools (anywhere in their inventory). */
    private void renderTool(Player player, ToolKind kind) {
        Legendary lt = plugin.legendary();
        long now = System.currentTimeMillis();
        ItemStack tool = player.getInventory().getItem(findTool(plugin, player, kind));
        lt.refresh(tool);
        inventory.setItem(TOOL_SLOT, tool.clone());
        actions.put(BACK_SLOT, new Back());
        inventory.setItem(BACK_SLOT, icon(Material.ARROW, text("Back", NamedTextColor.YELLOW),
                List.of(text("Back to your tools", NamedTextColor.GRAY)), false));

        int i = 0;
        for (Upgrade u : Upgrade.values()) {
            if (!u.appliesTo(kind, tool.getType()) || i >= UPGRADE_SLOTS.length) continue;
            Buy buy = new Buy(u);
            actions.put(UPGRADE_SLOTS[i], buy);
            inventory.setItem(UPGRADE_SLOTS[i], upgradeButton(player, tool, kind, u, isAwaitingConfirm(buy, now)));
            i++;
        }

        int soulTier = lt.tier(tool, Upgrade.SOULBOUND);
        if (soulTier > 0) {
            int saves = lt.saves(tool);
            int max = lt.maxSaves(tool);
            boolean canRecharge = plugin.settings().soulboundRechargeAnytime ? saves < max : saves == 0;
            if (canRecharge) {
                Recharge recharge = new Recharge();
                actions.put(RECHARGE_SLOT, recharge);
                inventory.setItem(RECHARGE_SLOT, rechargeButton(player, soulTier, saves, max,
                        isAwaitingConfirm(recharge, now)));
            } else {
                inventory.setItem(RECHARGE_SLOT, icon(Material.TOTEM_OF_UNDYING,
                        text("Soulbound: " + saves + "/" + max + " saves", NamedTextColor.GOLD), List.of(
                                text(plugin.settings().soulboundRechargeAnytime
                                        ? "Fully charged."
                                        : "You can recharge once all saves are used.", NamedTextColor.GRAY)), false));
            }
        }

        if (kind == ToolKind.PICKAXE && lt.tier(tool, Upgrade.DRILL) > 0 && lt.tier(tool, Upgrade.VEIN) > 0) {
            boolean vein = Legendary.MODE_VEIN.equals(lt.mode(tool));
            actions.put(MODE_SLOT, new ToggleMode());
            inventory.setItem(MODE_SLOT, icon(vein ? Material.DIAMOND_ORE : Material.STONE,
                    text("Mode: " + (vein ? "Vein Mining" : "Drill"), NamedTextColor.YELLOW), List.of(
                            text("Click to switch to " + (vein ? "Drill" : "Vein Mining") + ".", NamedTextColor.GRAY),
                            text("Also: /toolshop mode", NamedTextColor.DARK_GRAY)), false));
        }
    }

    private static Material plainIcon(ToolKind kind) {
        return switch (kind) {
            case PICKAXE -> Material.IRON_PICKAXE;
            case AXE -> Material.IRON_AXE;
            case SHOVEL -> Material.IRON_SHOVEL;
            case SWORD -> Material.IRON_SWORD;
        };
    }

    private static boolean appliesToKind(Upgrade u, ToolKind kind) {
        for (Material m : kind.materials()) if (u.appliesTo(kind, m)) return true;
        return false;
    }

    private ItemStack createButton(Player player, ToolKind kind, boolean confirming) {
        Settings s = plugin.settings();
        List<Component> lore = new ArrayList<>();
        lore.add(text("Turn this tool into your " + kind.displayName() + ".", NamedTextColor.GRAY));
        lore.add(text("Upgrade it over time with " + kind.skillName() + " levels and items.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("• Upgrades are permanent", NamedTextColor.WHITE));
        lore.add(text("• Only you can use it", NamedTextColor.WHITE));
        lore.add(text("• It can be lost like any item!", NamedTextColor.RED));
        lore.add(text("• Cost: " + Settings.describeCost(s.createCost), NamedTextColor.WHITE));
        if (plugin.registry().activeId(player.getUniqueId(), kind) != null) {
            lore.add(Component.empty());
            lore.add(text("You already have a " + kind.displayName() + ".", NamedTextColor.RED));
            lore.add(text("Making this one RETIRES the old one forever.", NamedTextColor.RED));
        }
        lore.add(Component.empty());
        lore.add(confirming
                ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                : text("Click to make it legendary", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        return icon(Material.NETHER_STAR, text("Make it Legendary", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true), lore, confirming);
    }

    private ItemStack upgradeButton(Player player, ItemStack tool, ToolKind kind, Upgrade u, boolean confirming) {
        Settings s = plugin.settings();
        Legendary lt = plugin.legendary();
        int current = lt.tier(tool, u);
        List<Component> lore = new ArrayList<>();
        lore.add(text(u.summary(), NamedTextColor.GRAY));
        for (int t = 1; t <= u.maxTier(); t++) {
            String detail = tierDetail(u, t);
            if (detail == null) continue;
            NamedTextColor c = t <= current ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY;
            lore.add(text((t <= current ? "✔ " : "  ") + (u.maxTier() > 1 ? Upgrade.roman(t) + ": " : "") + detail, c));
        }
        lore.add(Component.empty());

        Material icon = iconFor(u);
        if (current >= u.maxTier()) {
            lore.add(text("MAXED", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            return icon(icon, text(u.withTier(current), NamedTextColor.GOLD), lore, true);
        }

        int next = current + 1;
        Settings.Tier req = s.tier(u, next);
        lore.add(text("Next: " + u.withTier(next), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true));
        int needLevel = plugin.requiredLevel(u, next, kind);
        if (plugin.mcmmo().available() && s.requireLevels && needLevel > 0) {
            int lvl = plugin.mcmmo().level(player, kind.mcmmoSkill());
            boolean ok = lvl >= needLevel;
            lore.add(text((ok ? "✔ " : "✘ ") + kind.skillName() + " level " + needLevel
                    + " (you: " + (lvl < 0 ? "?" : lvl) + ")", ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        for (Map.Entry<Material, Integer> e : req.cost().entrySet()) {
            boolean ok = player.getInventory().containsAtLeast(ItemStack.of(e.getKey()), e.getValue());
            lore.add(text((ok ? "✔ " : "✘ ") + e.getValue() + " " + Settings.itemName(e.getKey(), e.getValue()),
                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        if (req.cost().isEmpty()) lore.add(text("✔ No items needed", NamedTextColor.GREEN));
        lore.add(Component.empty());
        lore.add(confirming
                ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                : text("Click to upgrade", NamedTextColor.GREEN));
        Component name = text(current == 0 ? u.displayName() : u.withTier(current),
                current == 0 ? NamedTextColor.YELLOW : NamedTextColor.AQUA);
        return icon(icon, name, lore, confirming);
    }

    private ItemStack rechargeButton(Player player, int tier, int saves, int max, boolean confirming) {
        Settings.Tier req = plugin.settings().tier(Upgrade.SOULBOUND, tier);
        List<Component> lore = new ArrayList<>();
        lore.add(text("Saves left: " + saves + "/" + max, saves == 0 ? NamedTextColor.RED : NamedTextColor.YELLOW));
        lore.add(text("Refill to " + max + " saves. No level needed.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        for (Map.Entry<Material, Integer> e : req.cost().entrySet()) {
            boolean ok = player.getInventory().containsAtLeast(ItemStack.of(e.getKey()), e.getValue());
            lore.add(text((ok ? "✔ " : "✘ ") + e.getValue() + " " + Settings.itemName(e.getKey(), e.getValue()),
                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        if (req.cost().isEmpty()) lore.add(text("✔ Free", NamedTextColor.GREEN));
        lore.add(Component.empty());
        lore.add(confirming
                ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                : text("Click to recharge", NamedTextColor.GREEN));
        return icon(Material.TOTEM_OF_UNDYING, text("Recharge Soulbound", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true), lore, confirming);
    }

    /** What a tier does, for the button description. */
    private String tierDetail(Upgrade u, int t) {
        Settings s = plugin.settings();
        String area = switch (t) {
            case 1 -> "1x2";
            case 2 -> "3x3";
            default -> "5x5";
        };
        return switch (u) {
            case DRILL, EXCAVATOR -> "breaks " + area;
            case VEIN -> "up to " + Settings.forTier(s.veinMaxBlocks, t) + " ores";
            case TIMBER -> "up to " + Settings.forTier(s.timberMaxLogs, t) + " logs"
                    + (t >= s.timberSilkLeavesFromTier ? " + Silk Touch collects leaves" : "");
            case DURABILITY -> Settings.forTier(s.durabilityChance, t) + "% chance to save durability";
            case FIREPROOF -> "survives fire and lava for " + shortTime(Settings.forTier(s.fireproofSeconds, t));
            case RECOVERY -> "glows for you when dropped, +" + shortTime(Settings.forTier(s.recoverySeconds, t)) + " before despawning"
                    + (t >= 3 ? ", survives explosions and cactus, returns from the void"
                    : t == 2 ? ", survives explosions and cactus" : ", death location message");
            case SOULBOUND -> Settings.forTier(s.soulboundSaves, t) + " saves (upgrading refills them)";
            case LIFESTEAL -> "heal " + Settings.forTier(s.lifestealPercent, t) + "% of damage dealt" + pvpNote(s);
            case BEHEADING -> Settings.forTier(s.beheadingChance, t) + "% head chance per kill" + pvpNote(s);
            case BOUNTY -> "+" + Settings.forTier(s.bountyPercent, t) + "% XP from kills" + pvpNote(s);
            default -> null;
        };
    }

    private static String pvpNote(Settings s) {
        if (s.swordPvpMultiplier >= 1) return "";
        if (s.swordPvpMultiplier <= 0) return " (not on players)";
        return " (" + Math.round(s.swordPvpMultiplier * 100) + "% on players)";
    }

    private static String shortTime(int seconds) {
        return seconds % 60 == 0 ? (seconds / 60) + " min" : seconds + "s";
    }

    static Material iconFor(Upgrade u) {
        return switch (u) {
            case DRILL -> Material.STONE;
            case VEIN -> Material.DIAMOND_ORE;
            case TIMBER -> Material.OAK_LOG;
            case EXCAVATOR -> Material.DIRT;
            case MAGNET -> Material.LODESTONE;
            case DURABILITY -> Material.ANVIL;
            case LAST_STAND -> Material.SHIELD;
            case FIREPROOF -> Material.MAGMA_CREAM;
            case RECOVERY -> Material.RECOVERY_COMPASS;
            case SOULBOUND -> Material.TOTEM_OF_UNDYING;
            case LIFESTEAL -> Material.GHAST_TEAR;
            case BEHEADING -> Material.SKELETON_SKULL;
            case BOUNTY -> Material.EXPERIENCE_BOTTLE;
        };
    }

    private ItemStack infoButton() {
        List<Component> lore = List.of(
                text("1. Hold an iron, diamond or netherite pickaxe,", NamedTextColor.GRAY),
                text("   axe, shovel or sword and click the star.", NamedTextColor.GRAY),
                text("2. That makes it legendary (one of each per player).", NamedTextColor.GRAY),
                text("   Click a legendary tool here to see its upgrades.", NamedTextColor.GRAY),
                text("3. Level up the matching mcMMO skill and", NamedTextColor.GRAY),
                text("   bring the items to buy upgrades.", NamedTextColor.GRAY),
                text("4. Sneak while breaking to use abilities.", NamedTextColor.GRAY),
                Component.empty(),
                text("Upgrades are permanent, but if the tool breaks,", NamedTextColor.RED),
                text("burns or despawns, it's gone. Last Stand stops it", NamedTextColor.RED),
                text("breaking, Fireproof buys time in lava (Recovery", NamedTextColor.RED),
                text("protects netherite tools instead), and", NamedTextColor.RED),
                text("Soulbound saves it on death a few times.", NamedTextColor.RED));
        return icon(Material.BOOK, text("How it works", NamedTextColor.YELLOW), lore, false);
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = ItemStack.of(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (glint) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private static Component text(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }
}
