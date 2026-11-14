package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The /toolshop window. The first page shows the player's three legendary tools, the "Make it Legendary" star
 * (which needs a plain iron/diamond/netherite tool in hand) and extras like the Flight Charm. Clicking a legendary
 * tool that's in their inventory opens its upgrades. Every purchase needs a second click to confirm.
 */
public final class ShopMenu implements InventoryHolder {
    public static final int TOOL_SLOT = 4;
    /** Home page: tools on row 2, weapons and the elytra on row 3, armor on row 4, buttons on the bottom row. */
    private static final Map<ToolKind, Integer> HOME_SLOT = Map.ofEntries(
            Map.entry(ToolKind.PICKAXE, 10), Map.entry(ToolKind.AXE, 12), Map.entry(ToolKind.SHOVEL, 14), Map.entry(ToolKind.HOE, 16),
            Map.entry(ToolKind.SWORD, 20), Map.entry(ToolKind.BOW, 21), Map.entry(ToolKind.CROSSBOW, 22),
            Map.entry(ToolKind.TRIDENT, 23), Map.entry(ToolKind.ELYTRA, 24),
            Map.entry(ToolKind.HELMET, 28), Map.entry(ToolKind.CHESTPLATE, 30), Map.entry(ToolKind.LEGGINGS, 32),
            Map.entry(ToolKind.BOOTS, 34));
    public static final int CREATE_SLOT = 38;
    public static final int FLIGHT_SLOT = 39;
    public static final int SPAWNER_SLOT = 41;
    public static final int RETIRE_SLOT = 44;
    public static final int BACKPACK_SLOT = 40;
    public static final int RANKS_SLOT = 42;
    public static final int VILLAGER_SLOT = 43;
    public static final int BACK_SLOT = 36;
    public static final int MODE_SLOT = 40;
    public static final int RECHARGE_SLOT = 42;
    public static final int INFO_SLOT = 36;
    private static final int[] UPGRADE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33};
    public static final long CONFIRM_WINDOW_MS = 5000;

    /** What a slot does. */
    public sealed interface Action permits Create, Buy, ToggleMode, Recharge, Open, Back, Retire, Preview,
            Spawners, OpenSpawner, SpawnerUpgrade, SpawnerToggle, SpawnerUnclaim, SpawnerClaim, OpenBackpack, Ranks,
            Villagers, OpenVillager, VillagerClaim, VillagerUpgrade, VillagerUnclaim, PickEnchant, EnchantFilter, EnchantPage,
            EnchantSearch, ClearSearch, AssignEnchant, BowMending, VillagerListPage, RenameVillager {}
    public record VillagerListPage(int page) implements Action {}
    public record RenameVillager(UUID id) implements Action {}
    /** "Your Villagers" list, one villager's page, and its buttons. */
    public record Villagers() implements Action {}
    public record OpenVillager(UUID id) implements Action {}
    public record VillagerClaim(UUID id) implements Action {}
    public record VillagerUpgrade(UUID id) implements Action {}
    public record VillagerUnclaim(UUID id) implements Action {}
    /** The enchanted-book picker for a claimed librarian: open it, filter by rarity (null = all), page, search, assign. */
    public record PickEnchant() implements Action {}
    public record EnchantFilter(VillagerClaims.Rarity rarity) implements Action {}
    public record EnchantPage(int page) implements Action {}
    public record EnchantSearch() implements Action {}
    public record ClearSearch() implements Action {}
    public record AssignEnchant(UUID id, String key) implements Action {}
    /** Legendary bow: use a Mending book to get Mending alongside Infinity for a few days. */
    public record BowMending() implements Action {}
    public record OpenBackpack() implements Action {}
    public record Ranks() implements Action {}
    /** Claim an unclaimed spawner, or take over someone else's (takeover = true). */
    public record SpawnerClaim(String key, boolean takeover) implements Action {}
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
    /** True on the Power Ranks page. */
    private boolean ranksPage;
    /** The spawner a player right-clicked in the world (may not be claimed yet), and whether the menu came from there. */
    private SpawnerUpgrades.Record worldSpawner;
    private boolean fromWorld;
    private static final int[] SPAWNER_LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    /** Villager pages: the villager shown (from the world or the list), the list, and the enchant picker's state. */
    private UUID villagerId;
    private VillagerClaims.Record worldVillager;
    private boolean villagerList;
    private int villagerListPage;
    private static final int[] VILLAGER_LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private boolean picker;
    private VillagerClaims.Rarity pickerRarity;
    private int pickerPage;
    private String pickerSearch;
    private static final int[] PICKER_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    public static final int BOW_MENDING_SLOT = 38;

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
        worldSpawner = null;
        fromWorld = false;
        ranksPage = false;
        villagerId = null;
        worldVillager = null;
        villagerList = false;
        closePicker();
        pending = null;
    }

    // ---------------------------------------------------------------- villager page state

    /** Opened by sneak + right-clicking a villager: its own page, whoever owns it. */
    public void showVillager(Villager v, VillagerClaims.Record r) {
        setPage(null);
        villagerId = v.getUniqueId();
        worldVillager = r;
        fromWorld = true;
    }

    public void showVillagers() {
        int keepPage = villagerListPage;
        setPage(null);
        villagerList = true;
        villagerListPage = keepPage;
    }

    public void setVillagerListPage(int page) {
        villagerListPage = Math.max(0, page);
    }

    public void showVillager(UUID id) {
        setPage(null);
        villagerId = id;
    }

    /** The villager this page is about, or null. */
    public VillagerClaims.Record currentVillager() {
        if (villagerId == null || plugin.villagers() == null) return null;
        VillagerClaims.Record r = plugin.villagers().record(villagerId);
        if (r == null && worldVillager != null && worldVillager.villager.equals(villagerId)) r = worldVillager;
        return r;
    }

    public void showPicker() {
        closePicker();
        picker = true;
        pending = null;
    }

    private void closePicker() {
        picker = false;
        pickerRarity = null;
        pickerPage = 0;
        pickerSearch = null;
    }

    public void setPickerFilter(VillagerClaims.Rarity rarity) {
        pickerRarity = rarity;
        pickerPage = 0;
    }

    public void setPickerPage(int page) {
        pickerPage = Math.max(0, page);
    }

    public void setPickerSearch(String search) {
        pickerSearch = search == null || search.isBlank() ? null : search.trim();
        pickerPage = 0;
        if (pickerSearch != null) pickerRarity = null;
    }

    public void showRanks() {
        setPage(null);
        ranksPage = true;
    }

    /** Opened by right-clicking a spawner: its own page, whoever owns it. */
    public void showWorldSpawner(SpawnerUpgrades.Record r) {
        setPage(null);
        spawnerKey = r.key();
        worldSpawner = r;
        fromWorld = true;
    }

    /** The spawner this page is about, or null. */
    public SpawnerUpgrades.Record currentSpawner() {
        if (spawnerKey == null) return null;
        SpawnerUpgrades.Record r = plugin.spawners().record(spawnerKey);
        if (r == null && worldSpawner != null && worldSpawner.key().equals(spawnerKey)) r = worldSpawner;
        return r;
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
        if (picker) {
            closePicker();
            pending = null;
        } else if (villagerId != null && !fromWorld) showVillagers();
        else if (spawnerKey != null) showSpawners();
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
        SpawnerUpgrades.Record shown = currentSpawner();
        if (spawnerKey != null && (shown == null || (!fromWorld && !player.getUniqueId().equals(shown.owner)))) {
            spawnerKey = null;
            spawnerList = true;
        }
        VillagerClaims.Record villager = currentVillager();
        if (villagerId != null && (villager == null || (!fromWorld && !player.getUniqueId().equals(villager.owner)))) {
            villagerId = null;
            closePicker();
            villagerList = true;
        }
        if (picker && (villager == null || !player.getUniqueId().equals(villager.owner) || !VillagerClaims.isLibrarian(villager))) {
            closePicker();
        }
        if (ranksPage) renderRanks(player);
        else if (villagerId != null && picker) renderPicker(player, villager);
        else if (villagerId != null) renderVillager(player, villager);
        else if (villagerList) renderVillagerList(player);
        else if (spawnerKey != null) renderSpawner(player, shown);
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
        for (ToolKind kind : kinds) {
            Integer homeSlot = HOME_SLOT.get(kind);
            if (homeSlot == null) continue;
            int slot = homeSlot;
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
        if (plugin.backpacks() != null && plugin.backpacks().enabled()) {
            int bp = plugin.backpacks().tierOf(player.getUniqueId());
            actions.put(BACKPACK_SLOT, new OpenBackpack());
            inventory.setItem(BACKPACK_SLOT, icon(Material.BUNDLE, text("Backpack", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                    List.of(text(bp == 0 ? "Extra storage, separate from your inventory." : "Tier " + Upgrade.roman(bp), NamedTextColor.GRAY),
                            text("Upgrades with power level + items.", NamedTextColor.GRAY),
                            text("Also: /backpack (/bp), /trash", NamedTextColor.DARK_GRAY),
                            Component.empty(),
                            text(bp == 0 ? "Click to get one" : "Click to open", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)), bp > 0));
        }
        if (plugin.ranks() != null && plugin.ranks().enabled()) {
            PowerRanks pr = plugin.ranks();
            int power = plugin.mcmmo().powerLevel(player);
            int idx = power < 0 ? -1 : pr.rankIndex(power);
            List<Component> lore = new ArrayList<>();
            lore.add(text("Power level: " + (power < 0 ? "?" : power), NamedTextColor.WHITE));
            if (idx >= 0) lore.add(text("Rank: " + pr.ranks().get(idx).name(), pr.ranks().get(idx).color()));
            if (idx + 1 < pr.ranks().size()) {
                PowerRanks.Rank next = pr.ranks().get(idx + 1);
                lore.add(text("Next: " + next.name() + " at " + pr.needed(next), NamedTextColor.GRAY));
            }
            lore.add(Component.empty());
            lore.add(text("Click to see all ranks and rewards", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            actions.put(RANKS_SLOT, new Ranks());
            inventory.setItem(RANKS_SLOT, icon(Material.GOLDEN_HELMET, text("Power Ranks", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.BOLD, true), lore, false));
        }
        if (plugin.spawners() != null && plugin.spawners().enabled()) {
            actions.put(SPAWNER_SLOT, new Spawners());
            inventory.setItem(SPAWNER_SLOT, spawnerButton(player));
        }
        if (plugin.villagers() != null && plugin.villagers().enabled()) {
            actions.put(VILLAGER_SLOT, new Villagers());
            inventory.setItem(VILLAGER_SLOT, villagerButton(player));
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
        String perTier = SkillSynergy.perTierText(plugin, kind);
        if (perTier != null) headLore.add(text("⚡ " + perTier, NamedTextColor.LIGHT_PURPLE));
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
        for (int t = 1; t <= u.maxTier(); t++) {
            lore.add(Component.empty());
            String detail = u == Upgrade.EMPOWERED ? Legendary.empoweredText(kind, t) : tierDetail(u, t);
            String head = u.maxTier() > 1 ? Upgrade.roman(t) + (detail != null ? ": " + detail : "") : (detail != null ? detail : "");
            if (!head.isEmpty()) lore.add(text(head, NamedTextColor.WHITE));
            if (levels) {
                int need = plugin.requiredLevel(u, t, kind);
                boolean ok = have >= need;
                lore.add(text("  " + (ok ? "✔ " : "✘ ") + kind.skillName() + " " + need + (have >= 0 ? " (you: " + have + ")" : ""),
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            if (u.isMythic(t) && plugin.mythicPowerNeeded() > 0) {
                lore.add(text("  ✦ Mythic: power level " + plugin.mythicPowerNeeded(), NamedTextColor.LIGHT_PURPLE));
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
        lore.add(text("Right-click a mob spawner to open its menu:", NamedTextColor.GRAY));
        lore.add(text("claim it, upgrade yours, or take over", NamedTextColor.GRAY));
        lore.add(text("someone else's.", NamedTextColor.GRAY));
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

    /** The rank ladder: each rank's power level and reward, with the player's progress. */
    private void renderRanks(Player player) {
        PowerRanks pr = plugin.ranks();
        backButton("Back to your tools");
        int power = plugin.mcmmo().powerLevel(player);
        int idx = power < 0 ? -1 : pr.rankIndex(power);
        inventory.setItem(TOOL_SLOT, icon(Material.GOLDEN_HELMET, text("Power Ranks", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true),
                List.of(text("Power level = all your mcMMO skill levels added up.", NamedTextColor.GRAY),
                        text("Yours: " + (power < 0 ? "?" : power), NamedTextColor.WHITE),
                        text("Your rank shows in chat and the tab list.", NamedTextColor.GRAY),
                        text("Each rank's reward is given once.", NamedTextColor.GRAY),
                        text("Leaderboard: /mctop", NamedTextColor.DARK_GRAY)), false));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < pr.ranks().size() && i < slots.length; i++) {
            PowerRanks.Rank r = pr.ranks().get(i);
            boolean reached = i <= idx;
            List<Component> lore = new ArrayList<>();
            lore.add(text("Power level " + pr.needed(r), NamedTextColor.WHITE));
            lore.add(text("Reward: " + pr.rewardText(r), NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(i == idx ? text("◀ Your rank", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
                    : reached ? text("✔ Reached", NamedTextColor.GREEN)
                    : text("✘ " + (pr.needed(r) - Math.max(0, power)) + " power levels to go", NamedTextColor.RED));
            Material mat = reached ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS; // full blocks read clearly
            if (i == idx) mat = Material.NETHER_STAR;
            inventory.setItem(slots[i], icon(mat, text(r.name(), r.color()).decoration(TextDecoration.BOLD, true), lore, i == idx));
        }
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
                text("right-clicking it.", NamedTextColor.GRAY)), false));
        if (mine.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, text("No spawners yet", NamedTextColor.GRAY), List.of(
                    text("Find a mob spawner and right-click it", NamedTextColor.GRAY),
                    text("to claim it for " + su.describe(su.claimCost()) + ".", NamedTextColor.GRAY)), false));
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

    /**
     * One spawner. The owner gets upgrade (from anywhere), on/off and unclaim; anyone else who right-clicked it gets
     * Claim (unclaimed) or Take over (someone else's).
     */
    private void renderSpawner(Player player, SpawnerUpgrades.Record r) {
        SpawnerUpgrades su = plugin.spawners();
        long now = System.currentTimeMillis();
        boolean mine = player.getUniqueId().equals(r.owner);
        if (!fromWorld) backButton("Back to your spawners");
        List<Component> head = new ArrayList<>();
        head.add(text("At " + r.where(), NamedTextColor.GRAY));
        head.add(text(!r.claimed() ? "Unclaimed" : mine ? "Yours" : "Claimed by " + r.ownerName,
                !r.claimed() ? NamedTextColor.WHITE : mine ? NamedTextColor.GREEN : NamedTextColor.RED));
        head.add(text(speedText(r), NamedTextColor.AQUA));
        head.add(text(r.enabled ? "● On" : "● Off", r.enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (mine) head.add(text(r.spawned + " mobs spawned since you claimed it", NamedTextColor.DARK_GRAY));
        head.add(text("Works like a normal spawner either way.", NamedTextColor.DARK_GRAY));
        inventory.setItem(TOOL_SLOT, icon(Material.SPAWNER, text(r.mob + " spawner", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true), head, r.enabled));

        if (!mine) {
            boolean takeover = r.claimed();
            SpawnerUpgrades.Cost cost = takeover ? su.takeoverCost() : su.claimCost();
            SpawnerClaim claim = new SpawnerClaim(r.key(), takeover);
            boolean confirming = isAwaitingConfirm(claim, now);
            String problem = su.claimProblem(player, cost);
            List<Component> lore = new ArrayList<>();
            lore.add(text(takeover ? "Take this spawner from " + r.ownerName + "." : "Make this spawner yours.", NamedTextColor.WHITE));
            lore.add(text("Then upgrade it, turn it on/off or manage it", NamedTextColor.GRAY));
            lore.add(text("from anywhere in /toolshop (Your Spawners).", NamedTextColor.GRAY));
            if (takeover) lore.add(text("They get nothing back.", NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            lore.add(text("Cost: " + su.describe(cost), NamedTextColor.YELLOW));
            lore.add(Component.empty());
            if (problem != null) {
                lore.add(text("✘ " + problem, NamedTextColor.RED));
            } else {
                actions.put(22, claim);
                lore.add(confirming
                        ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                        : text("Click to " + (takeover ? "take it over" : "claim it"), NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            }
            inventory.setItem(22, icon(takeover ? Material.IRON_SWORD : Material.GOLDEN_HELMET,
                    text(takeover ? "Take over" : "Claim", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), lore, confirming));
            if (r.tier < su.tiers().size()) {
                SpawnerUpgrades.Tier next = su.tiers().get(r.tier);
                inventory.setItem(24, icon(Material.EXPERIENCE_BOTTLE, text("Upgrades", NamedTextColor.GRAY), List.of(
                        text("Claim it first to upgrade it.", NamedTextColor.GRAY),
                        text("Next: Tier " + Upgrade.roman(r.tier + 1) + ", spawns " + SpawnerUpgrades.faster(next.speed()), NamedTextColor.WHITE),
                        text("for " + su.describe(next.cost()), NamedTextColor.DARK_GRAY)), false));
            }
            return;
        }

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

    // ---------------------------------------------------------------- villagers

    /** The villager's job block, for icons. */
    private static Material jobIcon(String profession) {
        return switch (profession.toLowerCase(Locale.ROOT)) {
            case "librarian" -> Material.LECTERN;
            case "armorer" -> Material.BLAST_FURNACE;
            case "butcher" -> Material.SMOKER;
            case "cartographer" -> Material.CARTOGRAPHY_TABLE;
            case "cleric" -> Material.BREWING_STAND;
            case "farmer" -> Material.COMPOSTER;
            case "fisherman" -> Material.BARREL;
            case "fletcher" -> Material.FLETCHING_TABLE;
            case "leatherworker" -> Material.CAULDRON;
            case "mason" -> Material.STONECUTTER;
            case "shepherd" -> Material.LOOM;
            case "toolsmith" -> Material.SMITHING_TABLE;
            case "weaponsmith" -> Material.GRINDSTONE;
            default -> Material.VILLAGER_SPAWN_EGG;
        };
    }

    private String discountText(VillagerClaims.Record r) {
        int pct = plugin.villagers().discount(r.tier);
        return r.tier <= 0 ? "No discount yet" : "Tier " + Upgrade.roman(r.tier) + ": trades " + pct + "% cheaper";
    }

    private static String tradeText(VillagerClaims.Record r) {
        Enchantment e = VillagerClaims.byKey(r.enchant);
        return e == null ? null : "Sells " + VillagerClaims.enchantName(e) + " " + Upgrade.roman(e.getMaxLevel())
                + " (" + VillagerClaims.tradePrice(e) + " emeralds + book)";
    }

    /** Home page button: what villager claims do and how many the player has. */
    private ItemStack villagerButton(Player player) {
        VillagerClaims vc = plugin.villagers();
        int have = vc.claimsOf(player.getUniqueId()).size();
        List<Component> lore = new ArrayList<>();
        lore.add(text("Sneak + right-click a villager to open its menu:", NamedTextColor.GRAY));
        lore.add(text("claim it, upgrade it so its trades get cheaper,", NamedTextColor.GRAY));
        lore.add(text("or give a librarian an enchanted book trade.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("Claim: " + vc.describe(vc.claimCost()), NamedTextColor.WHITE));
        if (!vc.tiers().isEmpty()) {
            lore.add(text("Discounts up to " + vc.tiers().get(vc.tiers().size() - 1).discount() + "% off every trade", NamedTextColor.WHITE));
        }
        lore.add(text("Villagers trade like normal either way.", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(text("You have " + have + (vc.maxClaims() > 0 ? "/" + vc.maxClaims() : "") + " claimed", NamedTextColor.YELLOW));
        lore.add(text("Click to manage them", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        return icon(Material.EMERALD, text("Your Villagers", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true), lore, have > 0);
    }

    private void renderVillagerList(Player player) {
        VillagerClaims vc = plugin.villagers();
        backButton("Back to your tools");
        List<VillagerClaims.Record> mine = vc.claimsOf(player.getUniqueId());
        inventory.setItem(TOOL_SLOT, icon(Material.EMERALD, text("Your Villagers", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true), List.of(
                text(mine.size() + (vc.maxClaims() > 0 ? "/" + vc.maxClaims() : "") + " claimed", NamedTextColor.YELLOW),
                text("Sorted by name. Claim more by", NamedTextColor.GRAY),
                text("sneak + right-clicking a villager.", NamedTextColor.GRAY)), false));
        if (mine.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, text("No villagers yet", NamedTextColor.GRAY), List.of(
                    text("Sneak + right-click a villager", NamedTextColor.GRAY),
                    text("to claim it for " + vc.describe(vc.claimCost()) + ".", NamedTextColor.GRAY)), false));
            return;
        }
        int pages = Math.max(1, (mine.size() + VILLAGER_LIST_SLOTS.length - 1) / VILLAGER_LIST_SLOTS.length);
        if (villagerListPage >= pages) villagerListPage = pages - 1;
        if (pages > 1) {
            inventory.setItem(40, icon(Material.PAPER, text("Page " + (villagerListPage + 1) + "/" + pages, NamedTextColor.WHITE), List.of(), false));
            if (villagerListPage > 0) {
                actions.put(39, new VillagerListPage(villagerListPage - 1));
                inventory.setItem(39, icon(Material.ARROW, text("Previous page", NamedTextColor.YELLOW), List.of(), false));
            }
            if (villagerListPage + 1 < pages) {
                actions.put(41, new VillagerListPage(villagerListPage + 1));
                inventory.setItem(41, icon(Material.ARROW, text("Next page", NamedTextColor.YELLOW), List.of(), false));
            }
        }
        for (int i = 0; i < VILLAGER_LIST_SLOTS.length; i++) {
            int idx = villagerListPage * VILLAGER_LIST_SLOTS.length + i;
            if (idx >= mine.size()) break;
            VillagerClaims.Record r = mine.get(idx);
            int slot = VILLAGER_LIST_SLOTS[i];
            actions.put(slot, new OpenVillager(r.villager));
            List<Component> lore = new ArrayList<>();
            if (r.name != null) lore.add(text(r.profession, NamedTextColor.YELLOW));
            lore.add(text("At " + r.where() + (r.world.equals("world") ? "" : " (" + r.world + ")"), NamedTextColor.GRAY));
            lore.add(text(discountText(r), NamedTextColor.AQUA));
            String trade = tradeText(r);
            if (trade != null) lore.add(text(trade, NamedTextColor.LIGHT_PURPLE));
            lore.add(Component.empty());
            lore.add(text("Click to manage", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            inventory.setItem(slot, icon(jobIcon(r.profession), text(r.label(), NamedTextColor.GOLD), lore, r.tier > 0));
        }
    }

    /**
     * One villager. The owner gets upgrade (from anywhere), the enchant trade (librarians) and unclaim; anyone else who
     * sneak + right-clicked it gets Claim if nobody owns it.
     */
    private void renderVillager(Player player, VillagerClaims.Record r) {
        VillagerClaims vc = plugin.villagers();
        long now = System.currentTimeMillis();
        boolean mine = player.getUniqueId().equals(r.owner);
        boolean loaded = VillagerClaims.find(r.villager) != null;
        if (!fromWorld) backButton("Back to your villagers");
        List<Component> head = new ArrayList<>();
        if (r.name != null) head.add(text(r.profession, NamedTextColor.YELLOW));
        head.add(text("At " + r.where(), NamedTextColor.GRAY));
        head.add(text(!r.claimed() ? "Unclaimed" : mine ? "Yours" : "Claimed by " + r.ownerName,
                !r.claimed() ? NamedTextColor.WHITE : mine ? NamedTextColor.GREEN : NamedTextColor.RED));
        head.add(text(discountText(r), NamedTextColor.AQUA));
        String trade = tradeText(r);
        if (trade != null) head.add(text(trade, NamedTextColor.LIGHT_PURPLE));
        if (r.claimed()) head.add(text("Other players can't hurt it.", NamedTextColor.DARK_GRAY));
        head.add(text("Right-click (not sneaking) to trade like normal.", NamedTextColor.DARK_GRAY));
        inventory.setItem(TOOL_SLOT, icon(jobIcon(r.profession), text(r.label(), NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true), head, r.tier > 0));

        if (!mine) {
            if (r.claimed()) {
                inventory.setItem(22, icon(Material.BARRIER, text("Claimed by " + r.ownerName, NamedTextColor.RED), List.of(
                        text("Only its owner can manage it.", NamedTextColor.GRAY),
                        text("You can still trade with it, and its", NamedTextColor.GRAY),
                        text("discount works for you too.", NamedTextColor.GRAY)), false));
            } else {
                VillagerClaim claim = new VillagerClaim(r.villager);
                boolean confirming = isAwaitingConfirm(claim, now);
                String problem = loaded ? vc.claimProblem(player) : "This villager isn't loaded anymore.";
                List<Component> lore = new ArrayList<>();
                lore.add(text("Make this villager yours. You'll name it", NamedTextColor.WHITE));
                lore.add(text("next; the name shows above it.", NamedTextColor.WHITE));
                lore.add(text("Then upgrade it to make its trades cheaper", NamedTextColor.GRAY));
                lore.add(text("(for everyone who trades with it), and give", NamedTextColor.GRAY));
                lore.add(text("a librarian the enchanted book trade you want.", NamedTextColor.GRAY));
                lore.add(text("Manage it from /toolshop (Your Villagers).", NamedTextColor.GRAY));
                lore.add(Component.empty());
                lore.add(text("Cost: " + vc.describe(vc.claimCost()), NamedTextColor.YELLOW));
                lore.add(Component.empty());
                if (problem != null) {
                    lore.add(text("✘ " + problem, NamedTextColor.RED));
                } else {
                    actions.put(22, claim);
                    lore.add(confirming
                            ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                            : text("Click to claim and name it", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
                }
                inventory.setItem(22, icon(Material.GOLDEN_HELMET, text("Claim", NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true), lore, confirming));
            }
            List<Component> tiers = new ArrayList<>();
            tiers.add(text("Owners can buy discount tiers:", NamedTextColor.GRAY));
            for (int t = 0; t < vc.tiers().size(); t++) {
                VillagerClaims.Tier tier = vc.tiers().get(t);
                tiers.add(text(Upgrade.roman(t + 1) + ": " + tier.discount() + "% off, " + vc.describe(tier.cost()),
                        t < r.tier ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
            }
            inventory.setItem(24, icon(Material.EXPERIENCE_BOTTLE, text("Discount tiers", NamedTextColor.GRAY), tiers, false));
            return;
        }

        // Upgrade
        List<VillagerClaims.Tier> tiers = vc.tiers();
        if (r.tier < tiers.size()) {
            VillagerClaims.Tier next = tiers.get(r.tier);
            VillagerUpgrade up = new VillagerUpgrade(r.villager);
            boolean confirming = isAwaitingConfirm(up, now);
            String problem = vc.problem(player, next.cost());
            List<Component> lore = new ArrayList<>();
            lore.add(text("Tier " + Upgrade.roman(r.tier + 1) + ": every trade " + next.discount() + "% cheaper", NamedTextColor.WHITE));
            lore.add(text("(at least 1 emerald off; works for anyone", NamedTextColor.GRAY));
            lore.add(text("trading with it, on top of normal discounts)", NamedTextColor.GRAY));
            lore.add(text("Cost: " + vc.describe(next.cost()), NamedTextColor.GRAY));
            lore.add(text("Works from anywhere.", NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            if (problem != null) {
                lore.add(text("✘ " + problem, NamedTextColor.RED));
            } else {
                actions.put(20, up);
                lore.add(confirming
                        ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                        : text("Click to upgrade", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            }
            inventory.setItem(20, icon(Material.EXPERIENCE_BOTTLE, text("Upgrade discount", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true), lore, confirming));
        } else {
            inventory.setItem(20, icon(Material.NETHER_STAR, text("Fully upgraded", NamedTextColor.GOLD),
                    List.of(text(discountText(r), NamedTextColor.AQUA)), true));
        }

        // Enchanted book trade (librarians)
        if (VillagerClaims.isLibrarian(r)) {
            List<Component> lore = new ArrayList<>();
            lore.add(text("Pick an enchantment for this librarian to sell", NamedTextColor.GRAY));
            lore.add(text("as a max-level book (emeralds + a book).", NamedTextColor.GRAY));
            lore.add(text("Rarer enchantments cost more to set:", NamedTextColor.GRAY));
            for (VillagerClaims.Rarity rr : VillagerClaims.Rarity.values()) {
                lore.add(text("  " + rr.label + ": " + Settings.describeCost(vc.assignCost(rr)), rr.color));
            }
            lore.add(text("A new pick replaces the old one.", NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            if (trade != null) lore.add(text("Now: " + trade, NamedTextColor.LIGHT_PURPLE));
            if (!loaded) lore.add(text("Go near it to change the trade.", NamedTextColor.RED));
            lore.add(text("Click to choose", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            actions.put(22, new PickEnchant());
            inventory.setItem(22, icon(Material.ENCHANTED_BOOK, text("Enchanted book trade", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.BOLD, true), lore, false));
        } else {
            inventory.setItem(22, icon(Material.BOOK, text("Enchanted book trade", NamedTextColor.GRAY), List.of(
                    text("Only librarians can be given one.", NamedTextColor.GRAY),
                    text("Claim a librarian to pick the book it sells.", NamedTextColor.DARK_GRAY)), false));
        }

        actions.put(24, new RenameVillager(r.villager));
        inventory.setItem(24, icon(Material.NAME_TAG, text("Rename", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true), List.of(
                text("Name: " + (r.name != null ? r.name : "(none)"), NamedTextColor.WHITE),
                text("Shown above it and in Your Villagers.", NamedTextColor.GRAY),
                Component.empty(),
                text("Click to rename", NamedTextColor.GREEN)), false));

        VillagerUnclaim unclaim = new VillagerUnclaim(r.villager);
        boolean confirmingUnclaim = isAwaitingConfirm(unclaim, now);
        actions.put(RETIRE_SLOT, unclaim);
        inventory.setItem(RETIRE_SLOT, icon(Material.BARRIER, text("Unclaim", NamedTextColor.RED), List.of(
                text("Give up this villager. Its discount and", NamedTextColor.GRAY),
                text("book trade stay, its name is removed,", NamedTextColor.GRAY),
                text("and anyone can claim it.", NamedTextColor.GRAY),
                Component.empty(),
                confirmingUnclaim
                        ? text("CLICK AGAIN TO UNCLAIM", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                        : text("Click to unclaim", NamedTextColor.YELLOW)), confirmingUnclaim));
    }

    /** Picking the enchanted book a claimed librarian sells: rarity tabs, search, pages, and two-click assign. */
    private void renderPicker(Player player, VillagerClaims.Record r) {
        VillagerClaims vc = plugin.villagers();
        long now = System.currentTimeMillis();
        backButton("Back to the villager");
        boolean loaded = VillagerClaims.find(r.villager) != null;
        List<Component> head = new ArrayList<>();
        head.add(text("Pick the enchanted book this librarian sells.", NamedTextColor.GRAY));
        String trade = tradeText(r);
        head.add(text(trade != null ? "Now: " + trade : "No book trade set yet", NamedTextColor.LIGHT_PURPLE));
        if (pickerSearch != null) head.add(text("Searching: \"" + pickerSearch + "\"", NamedTextColor.YELLOW));
        if (!loaded) head.add(text("Go near the villager to change its trade.", NamedTextColor.RED));
        inventory.setItem(TOOL_SLOT, icon(Material.LECTERN, text("Enchanted book trade", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true), head, false));

        // Rarity tabs
        Material[] tabIcons = {Material.WHITE_DYE, Material.LIME_DYE, Material.LIGHT_BLUE_DYE, Material.MAGENTA_DYE, Material.ORANGE_DYE};
        boolean allSelected = pickerRarity == null && pickerSearch == null;
        actions.put(10, new EnchantFilter(null));
        inventory.setItem(10, icon(Material.BOOK, text("All", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, allSelected),
                List.of(text(allSelected ? "Showing everything" : "Click to show everything", NamedTextColor.GRAY)), allSelected));
        VillagerClaims.Rarity[] rarities = VillagerClaims.Rarity.values();
        for (int i = 0; i < rarities.length; i++) {
            VillagerClaims.Rarity rr = rarities[i];
            boolean sel = rr == pickerRarity;
            actions.put(11 + i, new EnchantFilter(rr));
            inventory.setItem(11 + i, icon(tabIcons[i], text(rr.label, rr.color).decoration(TextDecoration.BOLD, sel), List.of(
                    text("Costs " + Settings.describeCost(vc.assignCost(rr)) + " to set", NamedTextColor.GRAY),
                    Component.empty(),
                    text(sel ? "Showing these" : "Click to show only these", NamedTextColor.YELLOW)), sel));
        }
        actions.put(16, new EnchantSearch());
        inventory.setItem(16, icon(Material.NAME_TAG, text("Search", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true), List.of(
                text("Type part of an enchantment's name", NamedTextColor.GRAY),
                text("(like \"prot\" or \"loot\").", NamedTextColor.GRAY),
                Component.empty(),
                text("Click to search", NamedTextColor.GREEN)), pickerSearch != null));
        if (pickerSearch != null) {
            actions.put(17, new ClearSearch());
            inventory.setItem(17, icon(Material.BARRIER, text("Clear search", NamedTextColor.RED),
                    List.of(text("Searching: \"" + pickerSearch + "\"", NamedTextColor.GRAY)), false));
        }

        // The list
        List<Enchantment> list = new ArrayList<>();
        String q = pickerSearch == null ? null : pickerSearch.toLowerCase(Locale.ROOT);
        for (Enchantment e : VillagerClaims.assignable()) {
            if (pickerRarity != null && VillagerClaims.rarity(e) != pickerRarity) continue;
            if (q != null && !VillagerClaims.enchantName(e).toLowerCase(Locale.ROOT).contains(q)
                    && !e.getKey().getKey().contains(q.replace(' ', '_'))) continue;
            list.add(e);
        }
        int pages = Math.max(1, (list.size() + PICKER_SLOTS.length - 1) / PICKER_SLOTS.length);
        if (pickerPage >= pages) pickerPage = pages - 1;
        if (list.isEmpty()) {
            inventory.setItem(22, icon(Material.BARRIER, text("Nothing found", NamedTextColor.GRAY),
                    List.of(text("Try another search or tab.", NamedTextColor.GRAY)), false));
        }
        for (int i = 0; i < PICKER_SLOTS.length; i++) {
            int idx = pickerPage * PICKER_SLOTS.length + i;
            if (idx >= list.size()) break;
            Enchantment e = list.get(idx);
            int slot = PICKER_SLOTS[i];
            VillagerClaims.Rarity rr = VillagerClaims.rarity(e);
            boolean current = e.getKey().getKey().equals(r.enchant);
            List<Component> lore = new ArrayList<>();
            lore.add(text(rr.label, rr.color));
            lore.add(text("It will sell: " + VillagerClaims.enchantName(e) + " " + Upgrade.roman(e.getMaxLevel()) + " book", NamedTextColor.WHITE));
            lore.add(text("for " + VillagerClaims.tradePrice(e) + " emeralds + 1 book (restocks like normal)", NamedTextColor.GRAY));
            lore.add(Component.empty());
            if (current) {
                lore.add(text("✔ It sells this now", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
                inventory.setItem(slot, icon(Material.ENCHANTED_BOOK, text(VillagerClaims.enchantName(e), rr.color)
                        .decoration(TextDecoration.BOLD, true), lore, true));
                continue;
            }
            Map<Material, Integer> cost = vc.assignCost(rr);
            lore.add(text("Cost to set: " + Settings.describeCost(cost), NamedTextColor.YELLOW));
            Enchantment old = VillagerClaims.byKey(r.enchant);
            if (old != null) lore.add(text("Replaces " + VillagerClaims.enchantName(old), NamedTextColor.DARK_GRAY));
            String problem = !loaded ? "Go near the villager first." : vc.problem(player, new VillagerClaims.Cost(0, cost));
            lore.add(Component.empty());
            AssignEnchant assign = new AssignEnchant(r.villager, e.getKey().getKey());
            boolean confirming = isAwaitingConfirm(assign, now);
            if (problem != null) {
                lore.add(text("✘ " + problem, NamedTextColor.RED));
            } else {
                actions.put(slot, assign);
                lore.add(confirming
                        ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                        : text("Click to set this trade", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            }
            inventory.setItem(slot, icon(Material.BOOK, text(VillagerClaims.enchantName(e), rr.color)
                    .decoration(TextDecoration.BOLD, true), lore, confirming));
        }

        // Pages
        if (pages > 1) {
            inventory.setItem(40, icon(Material.PAPER, text("Page " + (pickerPage + 1) + "/" + pages, NamedTextColor.WHITE), List.of(), false));
            if (pickerPage > 0) {
                actions.put(39, new EnchantPage(pickerPage - 1));
                inventory.setItem(39, icon(Material.ARROW, text("Previous page", NamedTextColor.YELLOW), List.of(), false));
            }
            if (pickerPage + 1 < pages) {
                actions.put(41, new EnchantPage(pickerPage + 1));
                inventory.setItem(41, icon(Material.ARROW, text("Next page", NamedTextColor.YELLOW), List.of(), false));
            }
        }
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

        if (kind == ToolKind.BOW && plugin.settings().bowMendingEnabled) {
            BowMending bm = new BowMending();
            boolean confirming = isAwaitingConfirm(bm, now);
            String problem = bowMendingProblem(plugin, player, tool);
            long until = lt.mendingUntil(tool);
            long left = until - System.currentTimeMillis();
            String days = Settings.trimNumber(plugin.settings().bowMendingDays);
            List<Component> lore = new ArrayList<>();
            lore.add(text("Mending and Infinity on one bow:", NamedTextColor.GRAY));
            lore.add(text("only possible through ToolShop.", NamedTextColor.GRAY));
            lore.add(text("A Mending enchanted book (used up) gives", NamedTextColor.GRAY));
            lore.add(text("it Mending for " + days + " real days.", NamedTextColor.GRAY));
            lore.add(text("Renew with another book once it has", NamedTextColor.GRAY));
            lore.add(text("less than a day left (resets to " + days + " days).", NamedTextColor.GRAY));
            lore.add(Component.empty());
            if (left > 0) lore.add(text("✦ Active: " + Legendary.timeLeft(left) + " left", NamedTextColor.LIGHT_PURPLE));
            else if (until == 0) lore.add(text("Ran out", NamedTextColor.RED));
            boolean inf = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.INFINITY) > 0;
            lore.add(text((inf ? "✔ " : "✘ ") + "Bow has Infinity", inf ? NamedTextColor.GREEN : NamedTextColor.RED));
            boolean book = mendingBookSlot(player) >= 0;
            lore.add(text((book ? "✔ " : "✘ ") + "Mending book in your inventory", book ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(Component.empty());
            if (problem != null) {
                lore.add(text("✘ " + problem, NamedTextColor.RED));
            } else {
                actions.put(BOW_MENDING_SLOT, bm);
                lore.add(confirming
                        ? text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                        : text(left > 0 ? "Click to renew" : "Click to add Mending", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            }
            inventory.setItem(BOW_MENDING_SLOT, icon(Material.ENCHANTED_BOOK, text("Mending + Infinity", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.BOLD, true), lore, left > 0 || confirming));
        }

        if (kind == ToolKind.PICKAXE && lt.tier(tool, Upgrade.DRILL) > 0 && lt.tier(tool, Upgrade.VEIN) > 0) {
            String mode = lt.mode(tool);
            String nextMode = switch (mode) {
                case Legendary.MODE_AUTO -> Legendary.MODE_DRILL;
                case Legendary.MODE_DRILL -> Legendary.MODE_VEIN;
                default -> Legendary.MODE_AUTO;
            };
            actions.put(MODE_SLOT, new ToggleMode());
            Material modeIcon = switch (mode) {
                case Legendary.MODE_DRILL -> Material.STONE;
                case Legendary.MODE_VEIN -> Material.DIAMOND_ORE;
                default -> Material.REDSTONE_ORE;
            };
            inventory.setItem(MODE_SLOT, icon(modeIcon, text("Mode: " + Legendary.modeName(mode), NamedTextColor.YELLOW), List.of(
                    text(Legendary.MODE_AUTO.equals(mode) ? "Sneak-breaking ore vein-mines it;"
                            : Legendary.MODE_DRILL.equals(mode) ? "Always drills, even on ores."
                            : "Only vein-mines ores; no drilling.", NamedTextColor.GRAY),
                    text(Legendary.MODE_AUTO.equals(mode) ? "anything else drills." : "", NamedTextColor.GRAY),
                    Component.empty(),
                    text("Click to switch to " + Legendary.modeName(nextMode) + ".", NamedTextColor.YELLOW),
                    text("Also: /toolshop mode", NamedTextColor.DARK_GRAY)), false));
        }
    }

    /** Inventory slot of a Mending enchanted book, or -1. */
    public static int mendingBookSlot(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack is = contents[i];
            if (is != null && is.getType() == Material.ENCHANTED_BOOK
                    && is.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta m
                    && m.hasStoredEnchant(org.bukkit.enchantments.Enchantment.MENDING)) return i;
        }
        return -1;
    }

    /** Why this bow can't get (or renew) Mending + Infinity right now, or null. */
    public static String bowMendingProblem(ToolShopPlugin plugin, Player player, ItemStack bow) {
        if (bow.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.INFINITY) <= 0) return "Enchant the bow with Infinity first.";
        long left = plugin.legendary().mendingUntil(bow) - System.currentTimeMillis();
        if (left >= 24 * 3_600_000L) return "Still running: renew when less than a day is left.";
        if (mendingBookSlot(player) < 0) return "You need a Mending enchanted book.";
        return null;
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
            case BOW -> Material.BOW;
            case HELMET -> Material.IRON_HELMET;
            case CHESTPLATE -> Material.IRON_CHESTPLATE;
            case LEGGINGS -> Material.IRON_LEGGINGS;
            case BOOTS -> Material.IRON_BOOTS;
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
            String detail = u == Upgrade.EMPOWERED ? Legendary.empoweredText(kind, t) : tierDetail(u, t);
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
        if (u.isMythic(next) && plugin.mythicPowerNeeded() > 0) {
            int power = plugin.mcmmo().powerLevel(player);
            boolean ok = power >= plugin.mythicPowerNeeded();
            lore.add(text((ok ? "✔ " : "✘ ") + "Mythic: power level " + plugin.mythicPowerNeeded()
                    + " (you: " + (power < 0 ? "?" : power) + ")", ok ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE));
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
            case 3 -> "5x5";
            default -> "7x7";
        };
        return switch (u) {
            case DRILL, EXCAVATOR -> "breaks " + area;
            case VEIN -> "up to " + Settings.forTier(s.veinMaxBlocks, t) + " ores";
            case TIMBER -> "up to " + Settings.forTier(s.timberMaxLogs, t) + " logs"
                    + (t >= s.timberSilkLeavesFromTier ? " + clears its leaves (Silk Touch: leaf blocks)" : "");
            case DURABILITY -> Settings.forTier(s.durabilityChance, t) + "% chance to save durability";
            case EMPOWERED -> "main enchant " + t + " level" + (t == 1 ? "" : "s") + " past its max";
            case SOULBOUND -> Settings.forTier(s.soulboundSaves, t) + " saves (upgrading refills them)";
            case LIFESTEAL -> "heal " + Settings.forTier(s.lifestealPercent, t) + "% of damage dealt" + pvpNote(s);
            case BEHEADING -> Settings.forTier(s.beheadingChance, t) + "% head chance per kill" + pvpNote(s);
            case BOUNTY -> "+" + Settings.forTier(s.bountyPercent, t) + "% XP from kills" + pvpNote(s);
            case HARVEST -> area(t) + " of grown crops";
            case GROWTH_AURA -> Settings.forTier(s.growthAuraRadius, t) + "-block radius";
            case TILLER -> "tills " + area(t);
            case TIDAL_PULL -> new String[]{"gentle", "strong", "very strong"}[Math.min(2, t - 1)] + " pull" + pvpNote(s);
            case STORM_CALLER -> "lightning every " + Settings.forTier(s.stormCallerCooldown, t) + "s at most" + pvpNote(s);
            case ARROW_SAVER -> Settings.forTier(s.arrowSaverChance, t) + "% chance";
            case VOLLEY -> "+" + Settings.forTier(s.volleyArrows, t) + " arrow" + (Settings.forTier(s.volleyArrows, t) == 1 ? "" : "s") + pvpNote(s);
            case BLAST_BOLTS -> Settings.forTier(s.blastBoltsDamage, t) + " damage around the hit" + pvpNote(s);
            case BOOST -> Settings.forTier(s.boostCharges, t) + " boost" + (Settings.forTier(s.boostCharges, t) == 1 ? "" : "s") + " per flight (refill on landing)";
            case ROCKET_SAVER -> Settings.forTier(s.rocketSaverChance, t) + "% chance";
            case SEEKER -> "locks on within " + Settings.forTier(s.seekerRadius, t) + " blocks";
            case QUICK_DRAW -> "any shot flies at " + Settings.forTier(s.quickDrawPower, t) + "% power or more";
            case FROST_ARROWS -> "Slowness II for " + Settings.forTier(s.frostSeconds, t) + "s" + pvpNote(s);
            case REPLANTER -> "a sapling of the same wood plants where the trunk stood";
            case FORAGER -> "+" + Settings.forTier(s.foragerSaplingChance, t) + "% saplings, +" + Settings.forTier(s.foragerAppleChance, t)
                    + "% apples per leaf" + (t >= 3 ? ", golden apple chance" : "");
            case LUMBERJACKS_HASTE -> "Haste " + Upgrade.roman(t) + " while chopping";
            case CLEAR_MIND -> "no Blindness, Darkness, Nausea or Mining Fatigue";
            case WATER_BREATHING -> "never run out of air";
            case VITALITY -> "+" + hearts(Settings.forTier(s.vitalityHealth, t)) + " heart" + (Settings.forTier(s.vitalityHealth, t) == 2 ? "" : "s");
            case SWIFTNESS -> "+" + Settings.forTier(s.swiftnessPercent, t) + "% speed";
            case SPRING -> "+" + Settings.forTier(s.springPercent, t) + "% jump, " + t + " more block" + (t == 1 ? "" : "s") + " before fall damage";
            case LAVA_WALKER -> "walk over lava; it turns back after 5s";
            default -> null;
        };
    }

    private static String hearts(int health) {
        return health % 2 == 0 ? String.valueOf(health / 2) : (health / 2) + ".5";
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
            case EMPOWERED -> Material.ENCHANTED_BOOK;
            case SOULBOUND -> Material.TOTEM_OF_UNDYING;
            case LIFESTEAL -> Material.GHAST_TEAR;
            case BEHEADING -> Material.SKELETON_SKULL;
            case BOUNTY -> Material.EXPERIENCE_BOTTLE;
            case HARVEST -> Material.WHEAT;
            case GROWTH_AURA -> Material.BONE_MEAL;
            case TILLER -> Material.FARMLAND;
            case TIDAL_PULL -> Material.NAUTILUS_SHELL;
            case STORM_CALLER -> Material.LIGHTNING_ROD;
            case ARROW_SAVER -> Material.ARROW;
            case VOLLEY -> Material.SPECTRAL_ARROW;
            case BLAST_BOLTS -> Material.TNT;
            case BOOST -> Material.PHANTOM_MEMBRANE;
            case ROCKET_SAVER -> Material.FIREWORK_ROCKET;
            case CRASH_GUARD -> Material.HAY_BLOCK;
            case SEEKER -> Material.ENDER_EYE;
            case QUICK_DRAW -> Material.STRING;
            case FROST_ARROWS -> Material.BLUE_ICE;
            case REPLANTER -> Material.OAK_SAPLING;
            case FORAGER -> Material.APPLE;
            case LUMBERJACKS_HASTE -> Material.GOLDEN_AXE;
            case CLEAR_MIND -> Material.MILK_BUCKET;
            case WATER_BREATHING -> Material.PUFFERFISH;
            case VITALITY -> Material.GOLDEN_APPLE;
            case SWIFTNESS -> Material.SUGAR;
            case SPRING -> Material.SLIME_BALL;
            case LAVA_WALKER -> Material.MAGMA_CREAM;
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
                text("breaking, and Soulbound keeps it on death", NamedTextColor.RED),
                text("a few times.", NamedTextColor.RED));
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
