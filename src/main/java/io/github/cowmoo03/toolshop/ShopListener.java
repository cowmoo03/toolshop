package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Handles clicks in the /toolshop window and takes payment. */
public final class ShopListener implements Listener {
    private final ToolShopPlugin plugin;

    public ShopListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof ShopMenu menu)) return;
        // Nothing can be moved into, out of, or around while the shop is open.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        ToolType type = switch (event.getRawSlot()) {
            case ShopMenu.TIMBER_SLOT -> ToolType.TIMBER;
            case ShopMenu.VEIN_SLOT -> ToolType.VEIN;
            default -> null;
        };
        if (type == null) return;
        buy(player, type);
        menu.render(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ShopMenu) {
            event.setCancelled(true);
        }
    }

    private void buy(Player player, ToolType type) {
        Settings s = plugin.settings();
        Offer offer = Offer.forHand(plugin, player, type);
        if (offer == null) {
            fail(player, "Hold your " + type.baseToolName() + " in your main hand first.");
            return;
        }

        PlayerInventory inv = player.getInventory();
        ItemStack payment = ItemStack.of(s.currency, Math.max(1, offer.price()));
        if (offer.price() > 0 && !inv.containsAtLeast(ItemStack.of(s.currency), offer.price())) {
            fail(player, "You need " + s.currencyAmount(offer.price()) + " for that.");
            return;
        }
        if (offer.price() > 0) {
            payment.setAmount(offer.price());
            inv.removeItem(payment);
        }

        // Re-read the hand after payment and write the upgraded tool back into it.
        ItemStack tool = inv.getItemInMainHand();
        plugin.toolData().activate(tool, type, offer.newExpiry());
        inv.setItemInMainHand(tool);

        plugin.logPurchase(player, offer.action(), type, offer.price(), plugin.toolData().getId(tool));
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(
                type.displayName() + " active for " + ToolData.formatDuration(offer.newExpiry() - System.currentTimeMillis())
                        + ". Paid " + s.currencyAmount(offer.price()) + ".", NamedTextColor.GREEN)));
        player.playSound(player.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1f);
    }

    private static void fail(Player player, String message) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, NamedTextColor.RED)));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }
}
