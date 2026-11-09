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
    /** Home page: one slot per tool kind (tools on row 2, weapons and elytra on row 3) and the star. */
    private static final int[] HOME_TOOL_SLOTS = {10, 12, 14, 16, 19, 21, 23, 25};
    public static final int CREATE_SLOT = 29;
    public static final int FLIGHT_SLOT = 31;
    public static final int SPAWNER_SLOT = 33;
    public static final int RETIRE_SLOT = 44;
    public static final int BACK_SLOT = 36;
    public static final int MODE_SLOT = 40;
    public static final int RECHARGE_SLOT = 42;
    public static final int INFO_SLOT = 36;
    private static final int[] UPGRADE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33};
    public static final long CONFIRM_WINDOW_MS = 5000;

    /** What a slot does. */
    public sealed interface Action permits Create, Buy, ToggleMode, Recharge, Open, Back, Retire, Preview,
            Spawners, OpenSpawner, SpawnerUpgrade, SpawnerToggle, SpawnerUnclaim {}
    /** "Your Spawners" list, one claimed spawner's dashboard, and its buttons. */
    public record Spawners() implements Action {}
    public record OpenSpawner(String key) implements Action {}
    public record SpawnerUpgrade(String key) implements Action {}
    public record SpawnerToggle(String key) implements Action {}
    public record SpawnerUnclaim(String key) implements Action {}
    /** View-only upgrades page for an item the player doesn't have with them (or doesn't own yet). */
    public record Preview(ToolKind kind) implements Action {}
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
    /** The item whose upgrades are being previewed (view only), or null. */
    private ToolKind preview;
    /** True on the "Your Spawners" list; spawnerKey is set on one spawner's dashboard. */
    private boolean spawnerList;
    private String spawnerKey;
    private static final int[] SPAWNER_LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

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
        preview = null;
        spawnerList = false;
        spawnerKey = null;
        pending = null;
    }

    public void showSpawners() {
        setPage(null);
        spawnerList = true;
    }

    public void showSpawner(String key) {
        setPage(null);
        spawnerKey = key;
    }

    /** Back: from a spawner's dashboard to the list, from anywhere else to the first page. */
    public void back() {
        if (spawnerKey != null) showSpawners();
        else setPage(null);
    }

    /** Shows the view-only upgrades page for this item. */
    public void showPreview(ToolKind kind) {
        setPage(null);
        preview = kind;
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
        if (spawnerKey != null) {
            SpawnerUpgrades.Record r = plugin.spawners().record(spawnerKey);
            if (r == null || !player.getUniqueId().equals(r.owner)) { spawnerKey = null; spawnerList = true; }
        }
        if (spawnerKey != null) renderSpawner(player, plugin.spawners().record(spawnerKey));
        else if (spawnerList) renderSpawnerList(player);
        else if (preview != null) renderPreview(player, preview);
        else if (page == null) renderHome(player);
        else renderTool(player, page);
    }

    /** The tools page: the player's three legendary tools and the star. */
    private void renderHome(Player player) {
        inventory.setItem(INFO_SLOT, infoButton());
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
            } else {
                boolean owned = plugin.registry().activeId(player.getUniqueId(), kind) != null;
                actions.put(slot, new Preview(kind));
                List<Component> lore = new ArrayList<>();
                if (owned) {
                    lore.add(text("Not in your inventory.", NamedTextColor.RED));
                    lore.add(text("Carry it with you to buy its upgrades.", NamedTextColor.GRAY));
                } else {
                    lore.add(text("Hold " + kind.hint(), NamedTextColor.GRAY));
                    lore.add(text("and click the star below to make it legendary.", NamedTextColor.GRAY));
                }
                lore.add(Component.empty());
                lore.add(text("Click to see its upgrades", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
                inventory.setItem(slot, icon(plainIcon(kind), text(owned ? kind.displayName()
                        : "No " + kind.displayName() + " yet", NamedTextColor.GRAY), lore, false));
            }
        }

        if (plugin.flight() != null && plugin.flight().enabled()) inventory.setItem(FLIGHT_SLOT, flightButton(player));
        if (plugin.spawners() != null && plugin.spawners().enabled()) {
            actions.put(SPAWNER_SLOT, new Spawners());
            inventory.setItem(SPAWNER_SLOT, spawnerButton(player));
        }

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
                    text(plainKind != null ? "The item in your hand is already legendary." : "Hold an item to make it legendary:",
                            NamedTextColor.YELLOW),
                    text("an iron, diamond or netherite pickaxe, axe,", NamedTextColor.GRAY),
                    text("shovel, hoe or sword, or a trident, crossbow", NamedTextColor.GRAY),
                    text("or elytra, then open /toolshop again.", NamedTextColor.GRAY)), false));
        }
    }

    /** View-only upgrades for one item: what each does, the levels and items, and retiring a lost one. */
    private void renderPreview(Player player, ToolKind kind) {
        boolean owned = plugin.registry().activeId(player.getUniqueId(), kind) != null;
        List<Component> headLore = new ArrayList<>();
        headLore.add(text("Upgrades for the " + kind.displayName() + ", unlocked with", NamedTextColor.GRAY));
        headLore.add(text(kind.skillName() + " levels and items.", NamedTextColor.GRAY));
        headLore.add(Component.empty());
        if (owned) {
            headLore.add(text("Yours isn't in your inventory.", NamedTextColor.RED));
            headLore.add(text("Carry it with you to buy upgrades.", NamedTextColor.GRAY));
        } else {
            headLore.add(text("You don't have one yet. Hold " + kind.hint(), NamedTextColor.YELLOW));
            headLore.add(text("and click the star on the first page.", NamedTextColor.YELLOW));
        }
        inventory.setItem(TOOL_SLOT, icon(plainIcon(kind), text(kind.displayName(), NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true), headLore, false));
        actions.put(BACK_SLOT, new Back());
        inventory.setItem(BACK_SLOT, icon(Material.ARROW, text("Back", NamedTextColor.YELLOW),
                List.of(text("Back to your tools", NamedTextColor.GRAY)), false));

        int i = 0;
        for (Upgrade u : Upgrade.values()) {
            if (!appliesToKind(u, kind) || i >= UPGRADE_SLOTS.length) continue;
            inventory.setItem(UPGRADE_SLOTS[i++], previewIcon(player, u, kind));
        }

        if (owned) {
            Retire retire = new Retire(kind);
            boolean confirming = isAwaitingConfirm(retire, System.currentTimeMillis());
            actions.put(RETIRE_SLOT, retire);
            List<Component> lore = new ArrayList<>(List.of(
                    text("Lost it for good? Retiring it makes it stop", NamedTextColor.GRAY),
                    text("working wherever it is (even in a chest or", NamedTextColor.GRAY),
                    text("ender chest), and its upgrades are gone.", NamedTextColor.GRAY),
                    text("Making a new one retires it anyway.", NamedTextColor.DARK_GRAY),
                    Component.empty()));
            lore.add(confirming
                    ? text("CLICK AGAIN TO RETIRE IT FOREVER", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                    : text("Click to retire it", NamedTextColor.YELLOW));
            inventory.setItem(RETIRE_SLOT, icon(Material.BARRIER, text("Retire your " + kind.displayName(),
                    NamedTextColor.RED), lore, confirming));
        }
    }

    /** One upgrade on a view-only page: what each tier does, the level it needs for this item, and its items. */
    private ItemStack previewIcon(Player player, Upgrade u, ToolKind kind) {
        Settings s = plugin.settings();
        boolean levels = plugin.mcmmo().available() && s.requireLevels;
        int have = levels ? plugin.mcmmo().level(player, kind.mcmmoSkill()) : 0;
        List<Component> lore = new ArrayList<>();
        lore.add(text(u.summary(kind), NamedTextColor.GRAY));
        if (kind.materials().size() > 1) {
            if (u == Upgrade.FIREPROOF) lore.add(text("Iron and diamond only (netherite can't burn)", NamedTextColor.DARK_AQUA));
            if (u == Upgrade.RECOVERY) lore.add(text("Netherite only", NamedTextColor.DARK_AQUA));
        }
        for (int t = 1; t <= u.maxTier(); t++) {
            lore.add(Component.empty());
            String detail = tierDetail(u, t);
            String head = u.maxTier() > 1 ? Upgrade.roman(t) + (detail != null ? ": " + detail : "") : (detail != null ? detail : "");
            if (!head.isEmpty()) lore.add(text(head, NamedTextColor.WHITE));
            if (levels) {
                int need = plugin.requiredLevel(u, t, kind);
                boolean ok = have >= need;
                lore.add(text("  " + (ok ? "✔ " : "✘ ") + kind.skillName() + " " + need + (have >= 0 ? " (you: " + have + ")" : ""),
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            Map<Material, Integer> cost = s.tier(u, t).cost();
            lore.add(text("  " + (cost.isEmpty() ? "No items" : Settings.describeCost(cost)), NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        lore.add(text("View only", NamedTextColor.DARK_GRAY));
        return icon(iconFor(u), text(u.displayName(), NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), lore, false);
    }

    /** Home page button: how spawner claims work and how many the player has. */
    private ItemStack spawnerButton(Player player) {
        SpawnerUpgrades su = plugin.spawners();
        int have = su.claimsOf(player.getUniqueId()).size();
        List<Component> lore = new ArrayList<>();
        lore.add(text("Right-click a mob spawner to see it.", NamedTextColor.GRAY));
        lore.add(text("Sneak + right-click (twice) to claim it,", NamedTextColor.GRAY));
        lore.add(text("upgrade yours, or take over someone else's.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("Claim: " + su.describe(su.claimCost()), NamedTextColor.WHITE));
        lore.add(text("Take over: " + su.describe(su.takeoverCost()), NamedTextColor.WHITE));
        lore.add(text("Spawners work like normal either way.", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(text("You have " + have + (su.maxClaims() > 0 ? "/" + su.maxClaims() : "") + " claimed", NamedTextColor.YELLOW));
        lore.add(text("Click to manage them", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        return icon(Material.SPAWNER, text("Your Spawners", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true), lore, have > 0);
    }

    private void backButton(String to) {
        actions.put(BACK_SLOT, new Back());
        inventory.setItem(BACK_SLOT, icon(Material.ARROW, text("Back", NamedTextColor.YELLOW),
                List.of(text(to, NamedTextColor.GRAY)), false));
    }

    private String speedText(SpawnerUpgrades.Record r) {
        List<SpawnerUpgrades.Tier> tiers = plugin.spawners().tiers();
        if (r.tier <= 0 || tiers.isEmpty()) return "not upgraded";
        return "Tier " + Upgrade.roman(r.tier) + " (" + SpawnerUpgrades.faster(tiers.get(Math.min(r.tier, tiers.size()) - 1).speed()) + ")";
    }

    private void renderSpawnerList(Player player) {
        SpawnerUpgrades su = plugin.spawners();
        backButton("Back to your tools");
        List<SpawnerUpgrades.Record> mine = su.claimsOf(player.getUniqueId());
        inventory.setItem(TOOL_SLOT, icon(Material.SPAWNER, text("Your Spawners", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true), List.of(
                text(mine.size() + (su.maxClaims() > 0 ? "/" + su.maxClaims() : "") + " claimed", NamedTextColor.YELLOW),
                text("Claim more by finding a spawner and", NamedTextColor.GRAY),
                text("sneak + right-clicking it (twice).", NamedTextColor.GRAY)), false));
        if (mine.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, text("No spawners yet", NamedTextColor.GRAY), List.of(
                    text("Find a mob spawner and sneak + right-click it", NamedTextColor.GRAY),
                    text("(twice) to claim it for " + su.describe(su.claimCost()) + ".", NamedTextColor.GRAY)), false));
            return;
        }
        for (int i = 0; i < mine.size() && i < SPAWNER_LIST_SLOTS.length; i++) {
            SpawnerUpgrades.Record r = mine.get(i);
            int slot = SPAWNER_LIST_SLOTS[i];
            actions.put(slot, new OpenSpawner(r.key()));
            inventory.setItem(slot, icon(Material.SPAWNER, text(r.mob + " spawner", NamedTextColor.GOLD), List.of(
                    text("At " + r.where(), NamedTextColor.GRAY),
                    text(speedText(r), NamedTextColor.AQUA),
                    text(r.enabled ? "● On" : "● Off", r.enabled ? NamedTextColor.GREEN : NamedTextColor.RED),
                    text(r.spawned + " mobs spawned since you claimed it", NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    text("Click to manage", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)), r.enabled));
        }
    }

    /** One claimed spawner: upgrade from anywhere, turn on/off, unclaim. */
    private void renderSpawner(Player player, SpawnerUpgrades.Record r) {
        SpawnerUpgrades su = plugin.spawners();
        long now = System.currentTimeMillis();
        backButton("Back to your spawners");
        inventory.setItem(TOOL_SLOT, icon(Material.SPAWNER, text(r.mob + " spawner", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true), List.of(
                text("At " + r.where(), NamedTextColor.GRAY),
                text(speedText(r), NamedTextColor.AQUA),
                text(r.enabled ? "● On" : "● Off", r.enabled ? NamedTextColor.GREEN : NamedTextColor.RED),
                text(r.spawned + " mobs spawned since you claimed it", NamedTextColor.DARK_GRAY)), r.enabled));

        List<SpawnerUpgrades.Tier> tiers = su.tiers();
        if (r.tier < tiers.size()) {
            SpawnerUpgrades.Tier next = tiers.get(r.tier);
            SpawnerUpgrade up = new SpawnerUpgrade(r.key());
            boolean confirming = isAwaitingConfirm(up, now);
            String problem = su.costProblem(player, next.cost());
            List<Component> lore = new ArrayList<>();
            lore.add(text("Tier " + Upgrade.roman(r.tier + 1) + ": spawns " + SpawnerUpgrades.faster(next.speed()), NamedTextColor.WHITE));
            lore.add(text("Cost: " + su.describe(next.cost()), NamedTextColor.GRAY));
            lore.add(text("Works from anywhere.", NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            if (problem != null) {
                lore.add(text("✘ " + problem, NamedTextColor.RED));
            } else {
                actions.put(22, up);
                lore.add(confirming
                        ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                        : text("Click to upgrade", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            }
            inventory.setItem(22, icon(Material.EXPERIENCE_BOTTLE, text("Upgrade", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true), lore, confirming));
        } else {
            inventory.setItem(22, icon(Material.NETHER_STAR, text("Fully upgraded", NamedTextColor.GOLD),
                    List.of(text(speedText(r), NamedTextColor.AQUA)), true));
        }

        actions.put(20, new SpawnerToggle(r.key()));
        inventory.setItem(20, icon(r.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                text(r.enabled ? "On" : "Off", r.enabled ? NamedTextColor.GREEN : NamedTextColor.RED), List.of(
                        text(r.enabled ? "It spawns like normal." : "It spawns nothing right now.", NamedTextColor.GRAY),
                        Component.empty(),
                        text("Click to turn it " + (r.enabled ? "off" : "on"), NamedTextColor.YELLOW)), false));

        SpawnerUnclaim unclaim = new SpawnerUnclaim(r.key());
        boolean confirmingUnclaim = isAwaitingConfirm(unclaim, now);
        actions.put(RETIRE_SLOT, unclaim);
        inventory.setItem(RETIRE_SLOT, icon(Material.BARRIER, text("Unclaim", NamedTextColor.RED), List.of(
                text("Give up this spawner. Its upgrades stay on it,", NamedTextColor.GRAY),
                text("and anyone can claim it afterwards.", NamedTextColor.GRAY),
                Component.empty(),
                confirmingUnclaim
                        ? text("CLICK AGAIN TO UNCLAIM", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                        : text("Click to unclaim", NamedTextColor.YELLOW)), confirmingUnclaim));
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
            case HOE -> Material.IRON_HOE;
            case TRIDENT -> Material.TRIDENT;
            case CROSSBOW -> Material.CROSSBOW;
            case ELYTRA -> Material.ELYTRA;
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
        lore.add(text(u.summary(kind), NamedTextColor.GRAY));
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
            case HARVEST -> area(t) + " of grown crops";
            case REPLANT -> "uses a seed from the drops (or your inventory)";
            case TILLER -> "tills " + area(t);
            case IMPALER -> "+" + Settings.forTier(s.impalerDamage, t) + " damage" + pvpNote(s);
            case STORM_CALLER -> "lightning every " + Settings.forTier(s.stormCallerCooldown, t) + "s at most" + pvpNote(s);
            case ARROW_SAVER -> Settings.forTier(s.arrowSaverChance, t) + "% chance";
            case VOLLEY -> "+" + Settings.forTier(s.volleyArrows, t) + " arrow" + (Settings.forTier(s.volleyArrows, t) == 1 ? "" : "s") + pvpNote(s);
            case HEAVY_BOLTS -> "+" + Settings.forTier(s.heavyBoltsPercent, t) + "% damage" + pvpNote(s);
            case BOOST -> Settings.forTier(s.boostCharges, t) + " boost" + (Settings.forTier(s.boostCharges, t) == 1 ? "" : "s") + " per flight (refill on landing)";
            case ROCKET_SAVER -> Settings.forTier(s.rocketSaverChance, t) + "% chance";
            default -> null;
        };
    }

    private static String area(int tier) {
        int side = tier * 2 + 1;
        return side + "x" + side;
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
            case HARVEST -> Material.WHEAT;
            case REPLANT -> Material.WHEAT_SEEDS;
            case TILLER -> Material.FARMLAND;
            case IMPALER -> Material.PRISMARINE_SHARD;
            case STORM_CALLER -> Material.LIGHTNING_ROD;
            case ARROW_SAVER -> Material.ARROW;
            case VOLLEY -> Material.SPECTRAL_ARROW;
            case HEAVY_BOLTS -> Material.HEAVY_CORE;
            case BOOST -> Material.PHANTOM_MEMBRANE;
            case ROCKET_SAVER -> Material.FIREWORK_ROCKET;
            case SAFE_LANDING -> Material.HAY_BLOCK;
        };
    }

    private ItemStack infoButton() {
        List<Component> lore = List.of(
                text("1. Hold a tool (pickaxe, axe, shovel, hoe), a weapon", NamedTextColor.GRAY),
                text("   (sword, trident, crossbow) or an elytra and click the star.", NamedTextColor.GRAY),
                text("2. That makes it legendary (one of each per player).", NamedTextColor.GRAY),
                text("   Click a legendary tool here to see its upgrades.", NamedTextColor.GRAY),
                text("3. Level up the matching mcMMO skill and", NamedTextColor.GRAY),
                text("   bring the items to buy upgrades.", NamedTextColor.GRAY),
                text("4. Tool abilities work while sneaking; weapon and", NamedTextColor.GRAY),
                text("   elytra abilities work on their own (see each one).", NamedTextColor.GRAY),
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
