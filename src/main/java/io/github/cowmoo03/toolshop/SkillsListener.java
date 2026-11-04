package io.github.cowmoo03.toolshop;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.inventory.Inventory;

import java.util.Locale;
import java.util.Set;

/**
 * Clicks in the /skills window; sends mcMMO's /mcstats and per-skill commands (/mining, /axes...) to the window
 * instead of their wall of chat; hides mcMMO's clutter from players' command suggestions (ops still see it all,
 * and the commands still work if typed).
 */
public final class SkillsListener implements Listener {
    /** mcMMO commands the /skills window replaces, hidden from players' suggestions. */
    private static final Set<String> HIDDEN = Set.of("mcstats", "mmoinfo", "mmoxpbar", "mmopower", "mcmmo",
            "mmodebug", "mmoshowdb");

    private final ToolShopPlugin plugin;

    public SkillsListener(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String skillId) {
        SkillsMenu menu = new SkillsMenu(plugin, skillId);
        menu.render(player);
        player.openInventory(menu.getInventory());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof SkillsMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;
        int slot = event.getRawSlot();

        Runnable action = null;
        if (menu.skillId() == null && menu.skillAt(slot) != null) {
            String id = menu.skillAt(slot);
            action = () -> open(player, id);
        } else if (menu.skillId() != null && slot == SkillsMenu.BACK_SLOT) {
            action = () -> open(player, null);
        } else if (slot == SkillsMenu.CLOSE_SLOT) {
            action = player::closeInventory;
        } else if (menu.skillId() == null && slot == SkillsMenu.TOP_SLOT) {
            action = () -> {
                player.closeInventory();
                player.performCommand("mctop");
            };
        } else if (slot == SkillsMenu.SHOP_SLOT && top.getItem(slot) != null) {
            action = () -> player.performCommand("toolshop");
        }
        if (action == null) return;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1f);
        // Opening another window from inside a click event is unsafe; do it on the next tick.
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SkillsMenu) event.setCancelled(true);
    }

    /** /mcstats -> the main window; /mining, /axes, /mcmmo:mining... (no arguments) -> that skill's page. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Settings s = plugin.settings();
        if (!s.skillsMenuEnabled || !s.skillsReplaceCommands || !plugin.mcmmo().available()) return;
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 1) return;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.startsWith("mcmmo:")) cmd = cmd.substring(6);
        if (cmd.equals("mcstats")) {
            event.setCancelled(true);
            open(event.getPlayer(), null);
        } else if (SkillCatalog.SKILLS.containsKey(cmd)) {
            event.setCancelled(true);
            open(event.getPlayer(), cmd);
        }
    }

    /** Trims mcMMO's long list of commands out of non-ops' tab completion. */
    @EventHandler
    public void onCommandList(PlayerCommandSendEvent event) {
        Settings s = plugin.settings();
        if (!s.skillsMenuEnabled || !s.skillsTidyCommands || event.getPlayer().isOp()) return;
        event.getCommands().removeIf(c -> {
            String name = c.toLowerCase(Locale.ROOT);
            if (name.startsWith("mcmmo:") || name.startsWith("toolshop:")) return true;
            return HIDDEN.contains(name) || SkillCatalog.SKILLS.containsKey(name);
        });
    }
}
