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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The /toolshop window. Players hold a tool in their main hand: a plain iron/diamond/netherite tool can be
 * made legendary, and a legendary tool shows its upgrades. Every purchase needs a second click to confirm.
 */
public final class ShopMenu implements InventoryHolder {
    public static final int TOOL_SLOT = 4;
    public static final int CREATE_SLOT = 22;
    public static final int MODE_SLOT = 40;
    public static final int RECHARGE_SLOT = 42;
    public static final int INFO_SLOT = 36;
    private static final int[] UPGRADE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33};
    public static final long CONFIRM_WINDOW_MS = 5000;

    /** What a slot does. */
    public sealed interface Action permits Create, Buy, ToggleMode, Recharge {}
    public record Create(ToolKind kind) implements Action {}
    public record Buy(Upgrade upgrade) implements Action {}
    public record ToggleMode() implements Action {}
    public record Recharge() implements Action {}

    private final ToolShopPlugin plugin;
    private final Inventory inventory;
    private final Map<Integer, Action> actions = new HashMap<>();
    private Action pending;
    private long pendingUntil;

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

    public void render(Player player) {
        actions.clear();
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        inventory.setItem(INFO_SLOT, infoButton());

        ItemStack hand = player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        ToolKind plainKind = ToolKind.of(hand.getType());
        long now = System.currentTimeMillis();

        if (plainKind == null) {
            inventory.setItem(TOOL_SLOT, icon(Material.BARRIER, text("Hold a tool", NamedTextColor.YELLOW), List.of(
                    text("Hold an iron, diamond or netherite", NamedTextColor.GRAY),
                    text("pickaxe, axe or shovel, then open", NamedTextColor.GRAY),
                    text("/toolshop again.", NamedTextColor.GRAY)), false));
            return;
        }

        if (!lt.isLegendary(hand)) {
            inventory.setItem(TOOL_SLOT, hand.clone());
            Create create = new Create(plainKind);
            actions.put(CREATE_SLOT, create);
            inventory.setItem(CREATE_SLOT, createButton(player, plainKind, isAwaitingConfirm(create, now)));
            return;
        }

        lt.refresh(hand);
        inventory.setItem(TOOL_SLOT, hand.clone());
        if (!lt.usableBy(hand, player)) {
            boolean yours = player.getUniqueId().equals(lt.owner(hand));
            inventory.setItem(CREATE_SLOT, icon(Material.BARRIER, text(yours ? "Retired tool" : "Not your tool",
                    NamedTextColor.RED), List.of(text(yours
                            ? "You've made a newer legendary tool of this type,"
                            : "Only its owner can use or upgrade it.", NamedTextColor.GRAY),
                    text(yours ? "so this one no longer works." : "", NamedTextColor.GRAY)), false));
            return;
        }

        ToolKind kind = lt.kind(hand);
        int i = 0;
        for (Upgrade u : Upgrade.values()) {
            if (!u.appliesTo(kind, hand.getType()) || i >= UPGRADE_SLOTS.length) continue;
            Buy buy = new Buy(u);
            actions.put(UPGRADE_SLOTS[i], buy);
            inventory.setItem(UPGRADE_SLOTS[i], upgradeButton(player, hand, kind, u, isAwaitingConfirm(buy, now)));
            i++;
        }

        int soulTier = lt.tier(hand, Upgrade.SOULBOUND);
        if (soulTier > 0) {
            int saves = lt.saves(hand);
            int max = lt.maxSaves(hand);
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

        if (kind == ToolKind.PICKAXE && lt.tier(hand, Upgrade.DRILL) > 0 && lt.tier(hand, Upgrade.VEIN) > 0) {
            boolean vein = Legendary.MODE_VEIN.equals(lt.mode(hand));
            actions.put(MODE_SLOT, new ToggleMode());
            inventory.setItem(MODE_SLOT, icon(vein ? Material.DIAMOND_ORE : Material.STONE,
                    text("Mode: " + (vein ? "Vein Mining" : "Drill"), NamedTextColor.YELLOW), List.of(
                            text("Click to switch to " + (vein ? "Drill" : "Vein Mining") + ".", NamedTextColor.GRAY),
                            text("Also: /toolshop mode", NamedTextColor.DARK_GRAY)), false));
        }
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
        if (plugin.mcmmo().available() && s.requireLevels && req.level() > 0) {
            int lvl = plugin.mcmmo().level(player, kind.mcmmoSkill());
            boolean ok = lvl >= req.level();
            lore.add(text((ok ? "✔ " : "✘ ") + kind.skillName() + " level " + req.level()
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
            case FIREPROOF -> "survives fire and lava for " + Settings.forTier(s.fireproofSeconds, t) + "s";
            case SOULBOUND -> Settings.forTier(s.soulboundSaves, t) + " saves (upgrading refills them)";
            default -> null;
        };
    }

    private static Material iconFor(Upgrade u) {
        return switch (u) {
            case DRILL -> Material.STONE;
            case VEIN -> Material.DIAMOND_ORE;
            case TIMBER -> Material.OAK_LOG;
            case EXCAVATOR -> Material.DIRT;
            case MAGNET -> Material.LODESTONE;
            case DURABILITY -> Material.ANVIL;
            case LAST_STAND -> Material.SHIELD;
            case FIREPROOF -> Material.MAGMA_CREAM;
            case SOULBOUND -> Material.TOTEM_OF_UNDYING;
        };
    }

    private ItemStack infoButton() {
        List<Component> lore = List.of(
                text("1. Hold an iron, diamond or netherite", NamedTextColor.GRAY),
                text("   pickaxe, axe or shovel.", NamedTextColor.GRAY),
                text("2. Make it legendary (one of each per player).", NamedTextColor.GRAY),
                text("3. Level up the matching mcMMO skill and", NamedTextColor.GRAY),
                text("   bring the items to buy upgrades.", NamedTextColor.GRAY),
                text("4. Sneak while breaking to use abilities.", NamedTextColor.GRAY),
                Component.empty(),
                text("Upgrades are permanent, but if the tool breaks,", NamedTextColor.RED),
                text("burns or despawns, it's gone. Last Stand stops it", NamedTextColor.RED),
                text("breaking, Fireproof buys time in lava, and", NamedTextColor.RED),
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
