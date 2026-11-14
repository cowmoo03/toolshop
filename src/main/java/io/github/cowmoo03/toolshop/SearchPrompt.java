package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A text box for menus: opens an anvil window where the player types into the rename field and clicks the paper on
 * the right to submit. Closing it (Esc) cancels. Nothing goes into or comes out of the player's inventory.
 */
public final class SearchPrompt implements Listener {
    private record Pending(InventoryView view, String label, Consumer<String> onDone, Runnable onCancel) {}

    private final ToolShopPlugin plugin;
    private final Map<UUID, Pending> open = new HashMap<>();

    public SearchPrompt(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("UnstableApiUsage")
    /** label: what the submit paper says, e.g. "Search" shows "Search: text" / "Click to search". */
    public void open(Player player, String title, String hint, String label, Consumer<String> onDone, Runnable onCancel) {
        AnvilView view = MenuType.ANVIL.builder().checkReachable(false).title(Component.text(title)).build(player);
        ItemStack paper = ItemStack.of(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        meta.displayName(Component.text(hint).decoration(TextDecoration.ITALIC, false));
        paper.setItemMeta(meta);
        open.put(player.getUniqueId(), new Pending(view, label, onDone, onCancel));
        view.getTopInventory().setItem(0, paper);
        player.openInventory(view);
    }

    private Pending pending(InventoryView view, Object who) {
        if (!(who instanceof Player p)) return null;
        Pending pd = open.get(p.getUniqueId());
        return pd != null && pd.view() == view ? pd : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareAnvilEvent event) {
        Pending pd = pending(event.getView(), event.getView().getPlayer());
        if (pd == null) return;
        String typed = event.getView().getRenameText();
        ItemStack result = ItemStack.of(Material.PAPER);
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text(pd.label() + ": " + (typed == null ? "" : typed), NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Click to " + pd.label().toLowerCase(java.util.Locale.ROOT), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        result.setItemMeta(meta);
        event.setResult(result);
        event.getView().setRepairCost(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Pending pd = pending(event.getView(), event.getWhoClicked());
        if (pd == null) return;
        event.setCancelled(true);
        if (event.getRawSlot() != 2 || !(event.getWhoClicked() instanceof Player player)) return;
        String typed = event.getView() instanceof AnvilView av ? av.getRenameText() : null;
        open.remove(player.getUniqueId());
        event.getView().getTopInventory().clear();
        String text = typed == null ? "" : typed.trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            pd.onDone().accept(text);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (pending(event.getView(), event.getWhoClicked()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // Empty the anvil first so the paper isn't handed to the player.
        Pending pd = pending(event.getView(), event.getPlayer());
        if (pd == null) return;
        event.getView().getTopInventory().clear();
        open.remove(event.getPlayer().getUniqueId());
        if (event.getPlayer() instanceof Player player && event.getReason() != InventoryCloseEvent.Reason.DISCONNECT) {
            plugin.getServer().getScheduler().runTask(plugin, () -> { if (player.isOnline()) pd.onCancel().run(); });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        open.remove(event.getPlayer().getUniqueId());
    }
}
