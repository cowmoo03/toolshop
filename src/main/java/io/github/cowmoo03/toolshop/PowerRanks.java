package io.github.cowmoo03.toolshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Power ranks: titles by mcMMO power level (Novice, Apprentice, Adept, ...), shown before the player's name in chat,
 * the tab list and death messages. Reaching a rank is announced to everyone and gives its reward once.
 * Highest rank rewarded per player is kept in plugins/ToolShop/ranks.yml.
 */
public final class PowerRanks implements Listener {
    private static final String FILE = "ranks.yml";

    public record Rank(String name, int powerLevel, TextColor color, Map<Material, Integer> items, int flightCharms) {}

    private final ToolShopPlugin plugin;
    private final List<Rank> ranks = new ArrayList<>();
    /** Player -> index of the highest rank whose reward they've been given. */
    private final Map<UUID, Integer> rewarded = new HashMap<>();
    private boolean enabled;
    private boolean showInChat;

    public PowerRanks(ToolShopPlugin plugin) {
        this.plugin = plugin;
        load();
        reload();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkAll, 200L, 600L);
    }

    public void reload() {
        ranks.clear();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("power-ranks");
        enabled = c == null || c.getBoolean("enabled", true);
        showInChat = c == null || c.getBoolean("show-in-chat-and-tab", true);
        List<Map<?, ?>> raw = c == null ? List.of() : c.getMapList("ranks");
        if (raw.isEmpty() && plugin.getConfig().getDefaults() != null) {
            raw = plugin.getConfig().getDefaults().getMapList("power-ranks.ranks");
        }
        for (Map<?, ?> e : raw) {
            String name = String.valueOf(e.get("name"));
            int power = e.get("power-level") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            TextColor color = NamedTextColor.NAMES.valueOr(String.valueOf(e.get("color")).toLowerCase(), NamedTextColor.GRAY);
            Map<Material, Integer> items = new LinkedHashMap<>();
            int charms = 0;
            if (e.get("reward") instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> re : m.entrySet()) {
                    String key = String.valueOf(re.getKey());
                    int amount = re.getValue() instanceof Number n ? n.intValue() : 0;
                    if (amount <= 0) continue;
                    if (key.equalsIgnoreCase("FLIGHT_CHARM")) {
                        charms = amount;
                        continue;
                    }
                    Material mat = Material.matchMaterial(key);
                    if (mat != null && mat.isItem()) items.put(mat, amount);
                    else plugin.getLogger().warning("power-ranks: unknown reward item '" + key + "'");
                }
            }
            ranks.add(new Rank(name, power, color, items, charms));
        }
        ranks.sort((a, b) -> Integer.compare(a.powerLevel(), b.powerLevel()));
        for (Player p : Bukkit.getOnlinePlayers()) applyTag(p);
    }

    public boolean enabled() { return enabled && !ranks.isEmpty() && plugin.mcmmo() != null && plugin.mcmmo().available(); }
    public List<Rank> ranks() { return ranks; }

    /** Power level this rank needs, on mcMMO's current scale. */
    public int needed(Rank r) { return r.powerLevel() * plugin.levelScale(); }

    /** Index of the rank for this power level, or -1. */
    public int rankIndex(int power) {
        int idx = -1;
        for (int i = 0; i < ranks.size(); i++) if (power >= needed(ranks.get(i))) idx = i;
        return idx;
    }

    public String rewardText(Rank r) {
        List<String> parts = new ArrayList<>();
        if (r.flightCharms() > 0) parts.add(r.flightCharms() + " Flight Charm" + (r.flightCharms() == 1 ? "" : "s"));
        if (!r.items().isEmpty()) parts.add(Settings.describeCost(r.items()));
        return parts.isEmpty() ? "no reward" : String.join(", ", parts);
    }

    // ---------------------------------------------------------------- checking

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // mcMMO loads player data a moment after joining.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) check(p); }, 80L);
    }

    private void checkAll() {
        for (Player p : Bukkit.getOnlinePlayers()) check(p);
    }

    private void check(Player player) {
        if (!enabled()) return;
        int power = plugin.mcmmo().powerLevel(player);
        if (power < 0) return; // still loading
        int idx = rankIndex(power);
        applyTag(player, idx);
        int done = rewarded.getOrDefault(player.getUniqueId(), 0); // Novice (index 0) has nothing to give
        if (idx <= done) return;
        for (int i = done + 1; i <= idx; i++) reward(player, ranks.get(i), i == idx);
        rewarded.put(player.getUniqueId(), idx);
        save();
    }

    private void reward(Player player, Rank r, boolean announce) {
        List<ItemStack> give = new ArrayList<>();
        r.items().forEach((m, n) -> {
            int left = n;
            while (left > 0) {
                int stack = Math.min(left, m.getMaxStackSize());
                give.add(ItemStack.of(m, stack));
                left -= stack;
            }
        });
        if (r.flightCharms() > 0 && plugin.flight() != null) give.add(plugin.flight().create(r.flightCharms()));
        for (ItemStack is : give) {
            player.getInventory().addItem(is).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        plugin.log(player, "RANK " + r.name() + " reward: " + rewardText(r), "-");
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Rank reward for " + r.name() + ": " + rewardText(r), NamedTextColor.GREEN)));
        if (!announce) return;
        Component tag = Component.text(r.name(), r.color()).decoration(TextDecoration.BOLD, true);
        Bukkit.broadcast(Component.text("✦ ", NamedTextColor.GOLD).append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(" reached ", NamedTextColor.GRAY)).append(tag)
                .append(Component.text(" (power level " + needed(r) + ")!", NamedTextColor.GRAY)));
        player.showTitle(Title.title(tag, Component.text("New power rank", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    // ---------------------------------------------------------------- name tag

    private void applyTag(Player player) {
        if (!enabled()) return;
        int power = plugin.mcmmo().powerLevel(player);
        if (power >= 0) applyTag(player, rankIndex(power));
    }

    private void applyTag(Player player, int idx) {
        if (!enabled || !showInChat || idx < 0) {
            player.displayName(null);
            player.playerListName(null);
            return;
        }
        Rank r = ranks.get(idx);
        Component name = Component.text("[" + r.name() + "] ", r.color()).append(Component.text(player.getName(), NamedTextColor.WHITE));
        player.displayName(name);
        player.playerListName(name);
    }

    // ---------------------------------------------------------------- saving

    private void load() {
        File f = new File(plugin.getDataFolder(), FILE);
        if (!f.exists()) return;
        ConfigurationSection s = YamlConfiguration.loadConfiguration(f).getConfigurationSection("rewarded");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                rewarded.put(UUID.fromString(k), s.getInt(k));
            } catch (IllegalArgumentException ignored) {
                // skip bad lines
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        rewarded.forEach((id, idx) -> yml.set("rewarded." + id, idx));
        try {
            yml.save(new File(plugin.getDataFolder(), FILE));
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save " + FILE + ": " + e.getMessage());
        }
    }
}
