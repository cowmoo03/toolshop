package io.github.cowmoo03.toolshop;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backpacks and the trash window.
 * <ul>
 *   <li>/backpack (/bp): extra storage separate from the inventory, 9 slots per tier, plus a control row with the
 *   backpack's info, the next upgrade (power level + items, two clicks) and a trash button.</li>
 *   <li>From backpack.keep-on-death-from-tier the contents survive death; below it they drop with the inventory.</li>
 *   <li>/trash: a window whose contents are deleted when it closes.</li>
 * </ul>
 * Contents are saved in plugins/ToolShop/backpacks/&lt;uuid&gt;.yml when the window closes.
 */
public final class Backpacks implements Listener {
    private static final long CONFIRM_MS = 5000;

    public record Tier(int rows, int powerLevel, Map<Material, Integer> cost) {}

    /** Holder for an open backpack window; the last row is controls, the rest is storage. */
    public static final class BackpackHolder implements InventoryHolder {
        final UUID owner;
        final int tier;
        final int storageSlots;
        Inventory inventory;
        long upgradePendingSince;
        /** Set once this window's storage has been saved, so it's never saved twice (or over a newer save). */
        boolean storageSlotsSaved;

        BackpackHolder(UUID owner, int tier, int storageSlots) {
            this.owner = owner;
            this.tier = tier;
            this.storageSlots = storageSlots;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    /** Holder for the trash window. */
    public static final class TrashHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private static final int INFO = 0, UPGRADE = 4, TRASH = 8;

    private final ToolShopPlugin plugin;
    private final File folder;
    private final List<Tier> tiers = new ArrayList<>();
    private boolean enabled;
    private int keepFromTier;

    public Backpacks(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "backpacks");
        reload();
    }

    public void reload() {
        tiers.clear();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("backpack");
        enabled = c == null || c.getBoolean("enabled", true);
        keepFromTier = c == null ? 4 : c.getInt("keep-on-death-from-tier", 4);
        List<Map<?, ?>> raw = c == null ? List.of() : c.getMapList("tiers");
        if (raw.isEmpty() && plugin.getConfig().getDefaults() != null) {
            raw = plugin.getConfig().getDefaults().getMapList("backpack.tiers");
        }
        for (Map<?, ?> e : raw) {
            int rows = e.get("rows") instanceof Number n ? Math.max(1, Math.min(5, n.intValue())) : 1;
            int power = e.get("power-level") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            Map<Material, Integer> cost = new LinkedHashMap<>();
            if (e.get("cost") instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> ce : m.entrySet()) {
                    Material mat = Material.matchMaterial(String.valueOf(ce.getKey()));
                    int amount = ce.getValue() instanceof Number n ? n.intValue() : 0;
                    if (mat != null && mat.isItem() && amount > 0) cost.put(mat, amount);
                    else plugin.getLogger().warning("backpack.tiers: unknown item '" + ce.getKey() + "'");
                }
            }
            tiers.add(new Tier(rows, power, cost));
        }
    }

    public boolean enabled() { return enabled && !tiers.isEmpty(); }
    public List<Tier> tiers() { return tiers; }
    public int keepFromTier() { return keepFromTier; }

    public int powerNeeded(Tier t) {
        if (t.powerLevel() <= 0 || plugin.mcmmo() == null || !plugin.mcmmo().available()) return 0;
        return t.powerLevel() * plugin.levelScale();
    }

    public String describe(Tier t) {
        String items = t.cost().isEmpty() ? "free" : Settings.describeCost(t.cost());
        int need = powerNeeded(t);
        return need > 0 ? items + " + power level " + need : items;
    }

    // ---------------------------------------------------------------- storage

    private File file(UUID id) { return new File(folder, id + ".yml"); }

    public int tierOf(UUID id) {
        File f = file(id);
        return f.exists() ? YamlConfiguration.loadConfiguration(f).getInt("tier", 0) : 0;
    }

    private ItemStack[] load(UUID id, int slots) {
        ItemStack[] items = new ItemStack[slots];
        File f = file(id);
        if (!f.exists()) return items;
        List<?> list = YamlConfiguration.loadConfiguration(f).getList("items", List.of());
        for (int i = 0; i < list.size() && i < slots; i++) {
            if (list.get(i) instanceof ItemStack is) items[i] = is;
        }
        return items;
    }

    private void save(UUID id, int tier, ItemStack[] items) {
        if (!folder.exists() && !folder.mkdirs()) return;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("tier", tier);
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack is : items) list.add(is == null || is.isEmpty() ? null : is);
        yml.set("items", list);
        try {
            yml.save(file(id));
        } catch (IOException e) {
            plugin.getLogger().severe("Couldn't save backpack " + id + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the window

    public void open(Player player) {
        if (!enabled()) {
            player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Backpacks are turned off.", NamedTextColor.RED)));
            return;
        }
        // Save a backpack window that's already open first, so the new one loads its latest contents.
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof BackpackHolder old) saveOpen(old);
        int tier = Math.min(tierOf(player.getUniqueId()), tiers.size());
        int rows = tier == 0 ? 0 : tiers.get(tier - 1).rows();
        BackpackHolder holder = new BackpackHolder(player.getUniqueId(), tier, rows * 9);
        Inventory inv = Bukkit.createInventory(holder, (rows + 1) * 9,
                Component.text(tier == 0 ? "Backpack (not unlocked)" : "Backpack · Tier " + Upgrade.roman(tier)));
        holder.inventory = inv;
        ItemStack[] stored = load(player.getUniqueId(), holder.storageSlots);
        for (int i = 0; i < stored.length; i++) inv.setItem(i, stored[i]);
        drawControls(player, holder);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.1f);
    }

    private void drawControls(Player player, BackpackHolder h) {
        int base = h.storageSlots;
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 9; i++) h.inventory.setItem(base + i, pane);

        List<Component> info = new ArrayList<>();
        info.add(line(h.tier == 0 ? "You don't have a backpack yet." : h.storageSlots + " slots", NamedTextColor.WHITE));
        info.add(line("Open it any time with /backpack (or /bp).", NamedTextColor.GRAY));
        info.add(line(h.tier >= keepFromTier && keepFromTier > 0 ? "✔ Keeps its items when you die"
                : "Drops its items when you die (kept from Tier " + Upgrade.roman(Math.max(1, keepFromTier)) + ")",
                h.tier >= keepFromTier ? NamedTextColor.GREEN : NamedTextColor.RED));
        h.inventory.setItem(base + INFO, button(Material.BUNDLE, Component.text("Backpack" + (h.tier > 0 ? " · Tier "
                + Upgrade.roman(h.tier) : ""), NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), info));

        if (h.tier < tiers.size()) {
            Tier next = tiers.get(h.tier);
            boolean confirming = System.currentTimeMillis() - h.upgradePendingSince <= CONFIRM_MS;
            String problem = problem(player, next);
            List<Component> lore = new ArrayList<>();
            lore.add(line((h.tier == 0 ? "Get a backpack: " : "Tier " + Upgrade.roman(h.tier + 1) + ": ") + next.rows() * 9
                    + " slots", NamedTextColor.WHITE));
            if (h.tier + 1 >= keepFromTier && h.tier < keepFromTier) lore.add(line("Keeps its items when you die", NamedTextColor.GREEN));
            lore.add(line("Cost: " + describe(next), NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(problem != null ? line("✘ " + problem, NamedTextColor.RED)
                    : confirming ? line("CLICK AGAIN TO CONFIRM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                    : line("Click to " + (h.tier == 0 ? "get it" : "upgrade"), NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            h.inventory.setItem(base + UPGRADE, button(Material.EXPERIENCE_BOTTLE,
                    Component.text(h.tier == 0 ? "Get a Backpack" : "Upgrade", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), lore));
        } else {
            h.inventory.setItem(base + UPGRADE, button(Material.NETHER_STAR,
                    Component.text("Fully upgraded", NamedTextColor.GOLD), List.of(line(h.storageSlots + " slots", NamedTextColor.GRAY))));
        }

        h.inventory.setItem(base + TRASH, button(Material.LAVA_BUCKET, Component.text("Trash", NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true), List.of(
                line("Opens a trash window. Whatever is left", NamedTextColor.GRAY),
                line("in it is deleted when you close it.", NamedTextColor.GRAY),
                line("Also: /trash", NamedTextColor.DARK_GRAY))));
    }

    private String problem(Player player, Tier t) {
        int need = powerNeeded(t);
        if (need > 0) {
            int have = plugin.mcmmo().powerLevel(player);
            if (have < 0) return "Your skills are still loading.";
            if (have < need) return "Needs power level " + need + " (you: " + have + ")";
        }
        if (player.getGameMode() == GameMode.CREATIVE) return null;
        List<String> missing = ShopListener.missingItems(player, t.cost());
        return missing.isEmpty() ? null : "You still need: " + String.join(", ", missing);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof BackpackHolder h)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        boolean inControls = raw >= h.storageSlots && raw < h.inventory.getSize();
        // Shift-clicking into a backpack with no storage, or number-key swaps into the control row, are blocked too.
        if (inControls || (h.storageSlots == 0 && event.isShiftClick())) {
            event.setCancelled(true);
            if (!inControls || event.getClickedInventory() != h.inventory) return;
            int slot = raw - h.storageSlots;
            if (slot == TRASH) {
                saveOpen(h);
                plugin.getServer().getScheduler().runTask(plugin, () -> openTrash(player));
            } else if (slot == UPGRADE) {
                upgradeClick(player, h);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof BackpackHolder h)) return;
        for (int raw : event.getRawSlots()) {
            if (raw >= h.storageSlots && raw < h.inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void upgradeClick(Player player, BackpackHolder h) {
        if (h.tier >= tiers.size()) return;
        Tier next = tiers.get(h.tier);
        String problem = problem(player, next);
        if (problem != null) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - h.upgradePendingSince > CONFIRM_MS) {
            h.upgradePendingSince = now;
            drawControls(player, h);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) ShopListener.take(player, next.cost());
        ItemStack[] items = new ItemStack[h.storageSlots];
        for (int i = 0; i < h.storageSlots; i++) items[i] = h.inventory.getItem(i);
        save(player.getUniqueId(), h.tier + 1, items);
        h.storageSlotsSaved = true; // don't save the old window again on close
        plugin.log(player, "BACKPACK tier " + (h.tier + 1) + ", paid " + describe(next), "-");
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(h.tier == 0 ? "You got a backpack! Open it with /backpack."
                : "Backpack upgraded to Tier " + Upgrade.roman(h.tier + 1) + " (" + next.rows() * 9 + " slots).", NamedTextColor.GREEN)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        plugin.getServer().getScheduler().runTask(plugin, () -> open(player));
    }

    /** Saves an open backpack window's storage (once). */
    private void saveOpen(BackpackHolder h) {
        if (h.storageSlotsSaved) return;
        h.storageSlotsSaved = true;
        ItemStack[] items = new ItemStack[h.storageSlots];
        for (int i = 0; i < h.storageSlots; i++) items[i] = h.inventory.getItem(i);
        save(h.owner, h.tier, items);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getInventory();
        if (top.getHolder(false) instanceof BackpackHolder h) {
            saveOpen(h);
        } else if (top.getHolder(false) instanceof TrashHolder) {
            int deleted = 0;
            for (ItemStack is : top.getContents()) if (is != null && !is.isEmpty()) deleted += is.getAmount();
            top.clear();
            if (deleted > 0 && event.getPlayer() instanceof Player p) {
                p.sendActionBar(Component.text("Deleted " + deleted + " item" + (deleted == 1 ? "" : "s") + ".", NamedTextColor.GRAY));
                p.playSound(p.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.6f, 1.2f);
            }
        }
    }

    // ---------------------------------------------------------------- trash

    public void openTrash(Player player) {
        TrashHolder holder = new TrashHolder();
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Trash · deleted when you close", NamedTextColor.DARK_RED));
        holder.inventory = inv;
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------- death, quit, shutdown

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof BackpackHolder h) {
            saveOpen(h);
            player.closeInventory();
        }
        int tier = tierOf(player.getUniqueId());
        if (tier <= 0 || event.getKeepInventory() || (keepFromTier > 0 && tier >= keepFromTier)) return;
        int slots = tiers.get(Math.min(tier, tiers.size()) - 1).rows() * 9;
        ItemStack[] items = load(player.getUniqueId(), slots);
        boolean any = false;
        for (ItemStack is : items) {
            if (is != null && !is.isEmpty()) {
                event.getDrops().add(is);
                any = true;
            }
        }
        if (any) {
            save(player.getUniqueId(), tier, new ItemStack[slots]);
            player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Your backpack dropped its items. From Tier "
                    + Upgrade.roman(keepFromTier) + " it keeps them.", NamedTextColor.RED)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder(false) instanceof BackpackHolder h) saveOpen(h);
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder(false) instanceof BackpackHolder h) {
                saveOpen(h);
                p.closeInventory();
            }
        }
    }

    // ---------------------------------------------------------------- commands

    public BasicCommand backpackCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (source.getSender() instanceof Player p) open(p);
                else source.getSender().sendMessage("Only players have backpacks.");
            }
        };
    }

    public BasicCommand trashCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (source.getSender() instanceof Player p) openTrash(p);
                else source.getSender().sendMessage("Only players can use the trash.");
            }
        };
    }

    // ---------------------------------------------------------------- helpers

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
