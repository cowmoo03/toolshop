package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles clicks in the /toolshop window: opening a tool's upgrades, making tools legendary (tool in hand), buying
 * upgrades and switching pickaxe mode (tool anywhere in the inventory).
 */
public final class ShopListener implements Listener {
    private final ToolShopPlugin plugin;

    public ShopListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof ShopMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        ShopMenu.Action action = menu.actionAt(event.getRawSlot());
        if (action == null) return;
        long now = System.currentTimeMillis();

        ToolKind page = menu.page();
        if (action instanceof ShopMenu.Open o) {
            menu.setPage(o.kind());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
        } else if (action instanceof ShopMenu.Back) {
            menu.setPage(null);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.9f);
        } else if (action instanceof ShopMenu.ToggleMode) {
            if (page != null) toggleMode(player, ShopMenu.findTool(plugin, player, page));
        } else if (menu.isAwaitingConfirm(action, now)) {
            menu.clearConfirm();
            if (action instanceof ShopMenu.Create c) create(player, c.kind());
            else if (action instanceof ShopMenu.Retire r) retire(player, r.kind());
            else if (action instanceof ShopMenu.Buy b && page != null) buy(player, b.upgrade(), page);
            else if (action instanceof ShopMenu.Recharge && page != null) recharge(player, page);
        } else {
            menu.askConfirm(action, now);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        }
        menu.render(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ShopMenu) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ actions

    private void create(Player player, ToolKind kind) {
        PlayerInventory inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        Legendary lt = plugin.legendary();
        if (ToolKind.of(hand.getType()) != kind || lt.isLegendary(hand)) {
            fail(player, "Hold the tool you want to make legendary.");
            return;
        }
        Map<Material, Integer> cost = plugin.settings().createCost;
        List<String> missing = missingItems(player, cost);
        if (!missing.isEmpty()) {
            fail(player, "You still need: " + String.join(", ", missing));
            return;
        }
        take(player, cost);

        String oldId = plugin.registry().activeId(player.getUniqueId(), kind);
        String id = lt.create(hand, player, kind);
        plugin.registry().setActive(player.getUniqueId(), kind, id);
        lt.refresh(hand);
        inv.setItemInMainHand(hand);

        plugin.log(player, "CREATE " + kind.id() + " (" + hand.getType().name().toLowerCase() + ")"
                + (oldId != null ? ", retired " + oldId : "") + ", paid " + Settings.describeCost(cost), id);
        ok(player, "This is now your " + kind.displayName() + "! Click it in /toolshop to upgrade it.");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
    }

    private void buy(Player player, Upgrade upgrade, ToolKind kind) {
        PlayerInventory inv = player.getInventory();
        int slot = ShopMenu.findTool(plugin, player, kind);
        Legendary lt = plugin.legendary();
        if (slot < 0) {
            fail(player, "Your " + kind.displayName() + " has to be in your inventory to upgrade it.");
            return;
        }
        ItemStack hand = inv.getItem(slot);
        if (!upgrade.appliesTo(kind, hand.getType())) {
            fail(player, upgrade.displayName() + " can't go on this tool.");
            return;
        }
        int current = lt.tier(hand, upgrade);
        if (current >= upgrade.maxTier()) {
            fail(player, upgrade.displayName() + " is already maxed.");
            return;
        }
        int next = current + 1;
        Settings s = plugin.settings();
        Settings.Tier req = s.tier(upgrade, next);

        int needLevel = plugin.requiredLevel(upgrade, next, kind);
        if (plugin.mcmmo().available() && s.requireLevels && needLevel > 0) {
            int lvl = plugin.mcmmo().level(player, kind.mcmmoSkill());
            if (lvl < 0) {
                fail(player, "Couldn't read your mcMMO level yet. Try again in a moment.");
                return;
            }
            if (lvl < needLevel) {
                fail(player, "You need " + kind.skillName() + " level " + needLevel + " (you're level " + lvl + ").");
                return;
            }
        }
        List<String> missing = missingItems(player, req.cost());
        if (!missing.isEmpty()) {
            fail(player, "You still need: " + String.join(", ", missing));
            return;
        }
        take(player, req.cost());

        ItemStack tool = inv.getItem(slot); // re-read after taking items
        lt.setTier(tool, upgrade, next);
        if (upgrade == Upgrade.VEIN && lt.tier(tool, Upgrade.DRILL) == 0) lt.setMode(tool, Legendary.MODE_VEIN);
        lt.refresh(tool);
        inv.setItem(slot, tool);

        plugin.log(player, "UPGRADE " + upgrade.withTier(next) + ", paid " + Settings.describeCost(req.cost()), lt.id(tool));
        ok(player, "Upgraded to " + upgrade.withTier(next) + "!");
        player.playSound(player.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1f);
    }

    /** Retires a legendary tool the player isn't carrying (lost, or left somewhere), so it stops working. */
    private void retire(Player player, ToolKind kind) {
        if (ShopMenu.findTool(plugin, player, kind) >= 0) {
            fail(player, "Your " + kind.displayName() + " is in your inventory, so it isn't lost.");
            return;
        }
        List<String> removed = plugin.registry().clear(player.getUniqueId(), kind);
        if (removed.isEmpty()) {
            fail(player, "You don't have a " + kind.displayName() + " to retire.");
            return;
        }
        plugin.log(player, "RETIRE " + kind.id() + " (lost)", String.join(",", removed));
        ok(player, "Your old " + kind.displayName() + " is retired. Hold a tool and click the star to make a new one.");
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 0.9f);
    }

    /** Refills Soulbound saves for the items of the tool's current Soulbound tier (no level needed). */
    private void recharge(Player player, ToolKind kind) {
        PlayerInventory inv = player.getInventory();
        int slot = ShopMenu.findTool(plugin, player, kind);
        Legendary lt = plugin.legendary();
        ItemStack hand = slot < 0 ? null : inv.getItem(slot);
        int tier = hand == null ? 0 : lt.tier(hand, Upgrade.SOULBOUND);
        if (tier <= 0) {
            fail(player, "Your " + kind.displayName() + " with Soulbound has to be in your inventory to recharge it.");
            return;
        }
        int saves = lt.saves(hand);
        int max = lt.maxSaves(hand);
        boolean allowed = plugin.settings().soulboundRechargeAnytime ? saves < max : saves == 0;
        if (!allowed) {
            fail(player, saves >= max ? "Soulbound is already full." : "You can recharge once all saves are used.");
            return;
        }
        Map<Material, Integer> cost = plugin.settings().tier(Upgrade.SOULBOUND, tier).cost();
        List<String> missing = missingItems(player, cost);
        if (!missing.isEmpty()) {
            fail(player, "You still need: " + String.join(", ", missing));
            return;
        }
        take(player, cost);
        ItemStack tool = inv.getItem(slot);
        lt.setSaves(tool, max);
        lt.refresh(tool);
        inv.setItem(slot, tool);
        plugin.log(player, "RECHARGE soulbound to " + max + ", paid " + Settings.describeCost(cost), lt.id(tool));
        ok(player, "Soulbound recharged: " + max + "/" + max + " saves.");
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.2f);
    }

    /** Switches a pickaxe that has both Drill and Vein Mining between the two. Also used by /toolshop mode. */
    public void toggleMode(Player player) {
        toggleMode(player, player.getInventory().getHeldItemSlot());
    }

    /** Switches the pickaxe in this inventory slot between Drill and Vein Mining. */
    public void toggleMode(Player player, int slot) {
        ItemStack hand = slot < 0 ? null : player.getInventory().getItem(slot);
        Legendary lt = plugin.legendary();
        if (hand == null || !lt.usableBy(hand, player) || lt.kind(hand) != ToolKind.PICKAXE
                || lt.tier(hand, Upgrade.DRILL) == 0 || lt.tier(hand, Upgrade.VEIN) == 0) {
            fail(player, "Hold your legendary pickaxe with both Drill and Vein Mining to switch modes.");
            return;
        }
        boolean toVein = !Legendary.MODE_VEIN.equals(lt.mode(hand));
        lt.setMode(hand, toVein ? Legendary.MODE_VEIN : Legendary.MODE_DRILL);
        lt.refresh(hand);
        player.getInventory().setItem(slot, hand);
        player.sendActionBar(Component.text("Mode: " + (toVein ? "Vein Mining" : "Drill"), NamedTextColor.YELLOW));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, toVein ? 1.4f : 0.9f);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> missingItems(Player player, Map<Material, Integer> cost) {
        List<String> missing = new ArrayList<>();
        cost.forEach((m, n) -> {
            if (!player.getInventory().containsAtLeast(ItemStack.of(m), n)) missing.add(n + " " + Settings.itemName(m, n));
        });
        return missing;
    }

    private static void take(Player player, Map<Material, Integer> cost) {
        cost.forEach((m, n) -> {
            int left = n;
            while (left > 0) {
                int chunk = Math.min(left, m.getMaxStackSize());
                player.getInventory().removeItem(ItemStack.of(m, chunk));
                left -= chunk;
            }
        });
    }

    private static void ok(Player player, String message) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, NamedTextColor.GREEN)));
    }

    private static void fail(Player player, String message) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, NamedTextColor.RED)));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }
}
