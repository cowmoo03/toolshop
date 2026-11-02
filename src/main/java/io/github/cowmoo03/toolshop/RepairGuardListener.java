package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stops repairs that would throw away a paid upgrade:
 * - crafting-grid merges (they create a brand new tool without the upgrade)
 * - grindstones (they rebuild the tool and may drop the upgrade)
 * - anvils with the upgraded tool in the right slot (that tool gets used up)
 * Anvils with the upgraded tool on the left, and Mending, still work normally.
 */
public final class RepairGuardListener implements Listener {
    private static final long MESSAGE_COOLDOWN_MS = 3000;

    private final ToolShopPlugin plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    public RepairGuardListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isUpgraded(item)) {
                event.getInventory().setResult(null);
                warn(event.getView().getPlayer(),
                        "Upgraded tools can't be merged in a crafting grid. Repair them with an anvil or Mending.");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrindstone(PrepareGrindstoneEvent event) {
        Inventory inv = event.getInventory();
        if (isUpgraded(inv.getItem(0)) || isUpgraded(inv.getItem(1))) {
            event.setResult(null);
            warn(event.getView().getPlayer(),
                    "Upgraded tools can't go in a grindstone. Repair them with an anvil or Mending.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        if (isUpgraded(event.getInventory().getItem(1))) {
            event.setResult(null);
            warn(event.getView().getPlayer(),
                    "Put the upgraded tool in the LEFT anvil slot, or it gets used up.");
        }
    }

    private boolean isUpgraded(ItemStack item) {
        return plugin.toolData().getType(item) != null;
    }

    /** Prepare events fire many times while a window is open, so only message every few seconds. */
    private void warn(HumanEntity who, String message) {
        if (!(who instanceof Player player)) return;
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }
}
