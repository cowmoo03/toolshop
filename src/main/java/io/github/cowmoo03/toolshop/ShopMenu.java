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
import java.util.List;

/** The /toolshop window. Players hold the tool in their main hand and click its button. */
public final class ShopMenu implements InventoryHolder {
    public static final int TIMBER_SLOT = 11;
    public static final int VEIN_SLOT = 13;
    public static final int EXCAVATOR_SLOT = 15;
    public static final int INFO_SLOT = 22;
    /** How long a purchase waits for the confirming second click. */
    public static final long CONFIRM_WINDOW_MS = 5000;

    private final ToolShopPlugin plugin;
    private final Inventory inventory;
    private ToolType pending;
    private long pendingUntil;

    public ShopMenu(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, 27, Component.text("Tool Shop"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Which upgrade a slot sells, or null for decoration. */
    public static ToolType typeAt(int slot) {
        return switch (slot) {
            case TIMBER_SLOT -> ToolType.TIMBER;
            case VEIN_SLOT -> ToolType.VEIN;
            case EXCAVATOR_SLOT -> ToolType.EXCAVATOR;
            default -> null;
        };
    }

    /** True if this button was clicked once already and is waiting for the confirming click. */
    public boolean isAwaitingConfirm(ToolType type, long now) {
        return pending == type && now < pendingUntil;
    }

    public void askConfirm(ToolType type, long now) {
        pending = type;
        pendingUntil = now + CONFIRM_WINDOW_MS;
    }

    public void clearConfirm() {
        pending = null;
    }

    /** Fills the window, with prices that match whatever the player is holding. */
    public void render(Player player) {
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);

        inventory.setItem(TIMBER_SLOT, toolButton(player, ToolType.TIMBER));
        inventory.setItem(VEIN_SLOT, toolButton(player, ToolType.VEIN));
        inventory.setItem(EXCAVATOR_SLOT, toolButton(player, ToolType.EXCAVATOR));
        inventory.setItem(INFO_SLOT, infoButton());
    }

    private ItemStack toolButton(Player player, ToolType type) {
        Settings s = plugin.settings();
        List<Component> lore = new ArrayList<>();
        lore.add(text(type.howTo(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("Upgrade: your " + type.baseToolName() + " + " + s.currencyAmount(s.upgradePrice(type)),
                NamedTextColor.WHITE));
        lore.add(text("Renew: " + s.currencyAmount(s.renewPrice(type)), NamedTextColor.WHITE));
        lore.add(text("Lasts " + ToolData.formatDuration(s.durationMs), NamedTextColor.WHITE));
        lore.add(Component.empty());

        Offer offer = Offer.forHand(plugin, player, type);
        if (offer == null) {
            lore.add(text("Hold your " + type.baseToolName() + " in your main hand,", NamedTextColor.YELLOW));
            lore.add(text("then click here.", NamedTextColor.YELLOW));
        } else if (isAwaitingConfirm(type, System.currentTimeMillis())) {
            lore.add(text("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            lore.add(text("You will pay " + s.currencyAmount(offer.price()) + " to " + offer.verb(), NamedTextColor.YELLOW));
            if (offer.note() != null) lore.add(text(offer.note(), NamedTextColor.GRAY));
        } else {
            lore.add(text("Click to " + offer.verb() + " for " + s.currencyAmount(offer.price()), NamedTextColor.GREEN)
                    .decoration(TextDecoration.BOLD, true));
            if (offer.note() != null) lore.add(text(offer.note(), NamedTextColor.GRAY));
        }
        ItemStack button = icon(type.material(),
                text(type.displayName(), type.color()).decoration(TextDecoration.BOLD, true), lore);
        if (isAwaitingConfirm(type, System.currentTimeMillis())) {
            ItemMeta meta = button.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            button.setItemMeta(meta);
        }
        return button;
    }

    private ItemStack infoButton() {
        Settings s = plugin.settings();
        List<Component> lore = List.of(
                text("1. Hold a netherite axe, pickaxe or shovel.", NamedTextColor.GRAY),
                text("2. Click its upgrade twice and pay in " + s.currency.name().toLowerCase().replace('_', ' ') + "s.",
                        NamedTextColor.GRAY),
                text("3. Sneak while breaking to use the ability.", NamedTextColor.GRAY),
                Component.empty(),
                text("Upgrades last " + ToolData.formatDuration(s.durationMs) + ".", NamedTextColor.WHITE),
                text("After that you have " + ToolData.formatDuration(s.graceMs) + " to renew", NamedTextColor.WHITE),
                text("cheaply, then it costs the full price again.", NamedTextColor.WHITE),
                text("The tool itself is never taken away.", NamedTextColor.WHITE));
        return icon(Material.BOOK, text("How it works", NamedTextColor.YELLOW), lore);
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static Component text(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }
}
