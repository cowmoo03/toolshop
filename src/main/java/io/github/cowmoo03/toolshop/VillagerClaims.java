package io.github.cowmoo03.toolshop;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Villager claims. A villager works like vanilla whether or not it's claimed; normal right-click always trades.
 * Sneak + right-click opens its window: claim it, then (as the owner) buy discount tiers that make every trade cheaper,
 * and on a librarian assign one enchanted-book trade, priced by how rare the enchantment is. Claimed villagers can't be
 * hurt by other players. Data lives in plugins/ToolShop/villagers.yml.
 */
public final class VillagerClaims implements Listener {
    private static final String FILE = "villagers.yml";

    public record Cost(int powerLevel, Map<Material, Integer> items) {}
    public record Tier(int discount, Cost cost) {}

    /** Rarity groups, by how often vanilla offers the enchantment (its weight). */
    public enum Rarity {
        COMMON("Common", NamedTextColor.WHITE), UNCOMMON("Uncommon", NamedTextColor.GREEN), RARE("Rare", NamedTextColor.AQUA),
        VERY_RARE("Very rare", NamedTextColor.LIGHT_PURPLE), TREASURE("Treasure", NamedTextColor.GOLD);

        public final String label;
        public final NamedTextColor color;

        Rarity(String label, NamedTextColor color) {
            this.label = label;
            this.color = color;
        }
    }

    private static final Set<String> COMMON = Set.of("protection", "efficiency", "sharpness", "power");
    private static final Set<String> UNCOMMON = Set.of("fire_protection", "feather_falling", "projectile_protection", "smite",
            "bane_of_arthropods", "knockback", "unbreaking", "loyalty", "quick_charge", "piercing", "density");
    private static final Set<String> VERY_RARE = Set.of("thorns", "silk_touch", "infinity", "channeling");
    private static final Set<String> TREASURE = Set.of("mending", "frost_walker", "soul_speed", "swift_sneak", "wind_burst");

    public static Rarity rarity(Enchantment e) {
        String k = e.getKey().getKey();
        if (TREASURE.contains(k)) return Rarity.TREASURE;
        if (COMMON.contains(k)) return Rarity.COMMON;
        if (UNCOMMON.contains(k)) return Rarity.UNCOMMON;
        if (VERY_RARE.contains(k)) return Rarity.VERY_RARE;
        return Rarity.RARE;
    }

    /** A claimed (or upgraded) villager. */
    public static final class Record {
        public final UUID villager;
        public UUID owner;
        public String ownerName;
        public int tier;
        public String enchant; // key of the assigned enchanted-book trade, or null
        public String profession = "Villager";
        public String name; // the name its owner gave it, shown above it
        public String world = "world";
        public int x, y, z;

        Record(UUID villager) {
            this.villager = villager;
        }

        public boolean claimed() { return owner != null; }
        public String where() { return x + ", " + y + ", " + z; }
        /** Its name, or its job if it has none. */
        public String label() { return name != null ? name : profession; }
    }

    private final ToolShopPlugin plugin;
    private final Map<UUID, Record> records = new HashMap<>();
    private boolean enabled;
    private int maxClaims;
    private Cost claimCost;
    private final List<Tier> tiers = new ArrayList<>();
    private final Map<Rarity, Map<Material, Integer>> assignCost = new LinkedHashMap<>();

    public VillagerClaims(ToolShopPlugin plugin) {
        this.plugin = plugin;
        load();
        reload();
    }

    // ---------------------------------------------------------------- config

    public void reload() {
        tiers.clear();
        assignCost.clear();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("villager-claims");
        ConfigurationSection d = plugin.getConfig().getDefaults() == null ? null
                : plugin.getConfig().getDefaults().getConfigurationSection("villager-claims");
        enabled = c == null || c.getBoolean("enabled", true);
        maxClaims = Math.max(0, c == null ? 0 : c.getInt("max-claims", 0));
        ConfigurationSection claim = section(c, d, "claim");
        claimCost = new Cost(claim == null ? 0 : claim.getInt("power-level", 0),
                claim == null ? Map.of() : items(claim.getConfigurationSection("cost") == null ? null : claim.getConfigurationSection("cost").getValues(false)));
        List<Map<?, ?>> raw = c == null ? List.of() : c.getMapList("tiers");
        if (raw.isEmpty() && d != null) raw = d.getMapList("tiers");
        for (Map<?, ?> e : raw) {
            int discount = e.get("discount") instanceof Number n ? Math.max(1, Math.min(90, n.intValue())) : 10;
            int power = e.get("power-level") instanceof Number n ? n.intValue() : 0;
            tiers.add(new Tier(discount, new Cost(Math.max(0, power), items(e.get("cost")))));
        }
        ConfigurationSection ec = section(c, d, "enchant-trade-cost");
        for (Rarity r : Rarity.values()) {
            String key = r.name().toLowerCase(Locale.ROOT).replace('_', '-');
            ConfigurationSection rs = ec == null ? null : ec.getConfigurationSection(key);
            assignCost.put(r, rs == null ? Map.of(Material.EMERALD, 64) : items(rs.getValues(false)));
        }
    }

    private static ConfigurationSection section(ConfigurationSection c, ConfigurationSection d, String name) {
        ConfigurationSection s = c == null ? null : c.getConfigurationSection(name);
        return s != null ? s : d == null ? null : d.getConfigurationSection(name);
    }

    private Map<Material, Integer> items(Object raw) {
        Map<Material, Integer> cost = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Material mat = Material.matchMaterial(String.valueOf(e.getKey()));
                int amount = e.getValue() instanceof Number n ? n.intValue() : 0;
                if (mat != null && mat.isItem() && amount > 0) cost.put(mat, amount);
                else plugin.getLogger().warning("villager-claims: unknown item '" + e.getKey() + "'");
            }
        }
        return cost;
    }

    public boolean enabled() { return enabled; }
    public int maxClaims() { return maxClaims; }
    public Cost claimCost() { return claimCost; }
    public List<Tier> tiers() { return tiers; }
    public Map<Material, Integer> assignCost(Rarity r) { return assignCost.getOrDefault(r, Map.of()); }

    public int powerNeeded(Cost c) {
        if (c.powerLevel() <= 0 || plugin.mcmmo() == null || !plugin.mcmmo().available()) return 0;
        return c.powerLevel() * plugin.levelScale();
    }

    public String describe(Cost c) {
        String items = c.items().isEmpty() ? "free" : Settings.describeCost(c.items());
        int need = powerNeeded(c);
        return need > 0 ? items + " + power level " + need : items;
    }

    /** Why the player can't pay this, or null. */
    public String problem(Player player, Cost c) {
        int need = powerNeeded(c);
        if (need > 0) {
            int have = plugin.mcmmo().powerLevel(player);
            if (have < 0) return "Your skills are still loading.";
            if (have < need) return "Needs power level " + need + " (you: " + have + ")";
        }
        if (player.getGameMode() == GameMode.CREATIVE) return null;
        List<String> missing = ShopListener.missingItems(player, c.items());
        return missing.isEmpty() ? null : "You still need: " + String.join(", ", missing);
    }

    public String claimProblem(Player player) {
        if (maxClaims > 0 && claimsOf(player.getUniqueId()).size() >= maxClaims) {
            return "You already have " + maxClaims + " villagers claimed (the most you can have).";
        }
        return problem(player, claimCost);
    }

    private void pay(Player player, Map<Material, Integer> items) {
        if (player.getGameMode() != GameMode.CREATIVE) ShopListener.take(player, items);
    }

    // ---------------------------------------------------------------- records

    public Record record(UUID villager) { return records.get(villager); }

    public List<Record> claimsOf(UUID player) {
        List<Record> list = new ArrayList<>();
        for (Record r : records.values()) if (player.equals(r.owner)) list.add(r);
        list.sort(Comparator.comparing((Record r) -> r.label().toLowerCase(Locale.ROOT)).thenComparingInt(r -> r.x));
        return list;
    }

    /** The villager's record, or a new unsaved one describing it. */
    public Record view(Villager v) {
        Record r = records.get(v.getUniqueId());
        if (r == null) r = new Record(v.getUniqueId());
        touch(r, v);
        return r;
    }

    private static void touch(Record r, Villager v) {
        Location l = v.getLocation();
        r.world = l.getWorld().getName();
        r.x = l.getBlockX();
        r.y = l.getBlockY();
        r.z = l.getBlockZ();
        r.profession = professionName(v);
    }

    public static String professionName(Villager v) {
        String key = v.getProfession().getKey().getKey();
        if (key.equals("none")) return "Unemployed villager";
        if (key.equals("nitwit")) return "Nitwit";
        return Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('_', ' ');
    }

    public static boolean isLibrarian(Villager v) {
        return v.getProfession().getKey().getKey().equals("librarian");
    }

    /** Whether this claimed villager is a librarian (checked live when it's loaded). */
    public static boolean isLibrarian(Record r) {
        Villager v = find(r.villager);
        return v != null ? isLibrarian(v) : "Librarian".equals(r.profession);
    }

    // ---------------------------------------------------------------- opening the window

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!enabled || event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager v)) return;
        Player player = event.getPlayer();
        Record claimed = records.get(v.getUniqueId());
        ItemStack held = player.getInventory().getItemInMainHand();
        if (claimed != null && claimed.claimed() && held.getType() == Material.NAME_TAG && !player.isSneaking()) {
            // Its name is set in its menu, so a name tag can't change it.
            event.setCancelled(true);
            player.sendActionBar(Component.text(player.getUniqueId().equals(claimed.owner)
                    ? "Rename it in its menu (sneak + right-click)." : "This villager belongs to " + claimed.ownerName + ".", NamedTextColor.RED));
            return;
        }
        if (claimed != null && claimed.claimed()) showName(v, claimed); // renamed while it was unloaded
        if (player.isSneaking() && player.hasPermission("toolshop.use")) {
            // Sneak + right-click: the villager's window instead of trading.
            event.setCancelled(true);
            ShopMenu menu = new ShopMenu(plugin);
            menu.showVillager(v, view(v));
            menu.render(player);
            player.openInventory(menu.getInventory());
            return;
        }
        // Normal right-click trades as usual; an upgraded villager's prices drop first (vanilla's reputation discount
        // is added on top when the trade window opens, and both reset when it closes).
        Record r = records.get(v.getUniqueId());
        if (r != null && r.tier > 0 && !v.isTrading()) applyDiscount(v, discount(r.tier));
    }

    public int discount(int tier) {
        if (tier <= 0 || tiers.isEmpty()) return 0;
        return tiers.get(Math.min(tier, tiers.size()) - 1).discount();
    }

    private static void applyDiscount(Villager v, int pct) {
        for (int i = 0; i < v.getRecipeCount(); i++) {
            MerchantRecipe recipe = v.getRecipe(i);
            List<ItemStack> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) continue;
            int base = ingredients.get(0).getAmount();
            int off = (int) Math.floor(base * pct / 100.0);
            if (off < 1 && base > 1) off = 1; // a discount always takes at least one off
            recipe.setSpecialPrice(-Math.min(off, base - 1));
            v.setRecipe(i, recipe);
        }
    }

    // ---------------------------------------------------------------- actions from the window

    /** Cleans up a name typed by a player: no colour codes, at most 24 characters; null if nothing is left. */
    public static String cleanName(String typed) {
        if (typed == null) return null;
        String n = typed.replace("§", "").replaceAll("\\s+", " ").trim();
        if (n.length() > 24) n = n.substring(0, 24).trim();
        return n.isEmpty() ? null : n;
    }

    private static void showName(Villager v, Record r) {
        if (r.name == null) {
            v.customName(null);
            v.setCustomNameVisible(false);
        } else {
            v.customName(Component.text(r.name, NamedTextColor.GOLD));
            v.setCustomNameVisible(true);
        }
    }

    public void claim(Player player, Villager v, String name) {
        Record r = view(v);
        if (r.claimed()) {
            fail(player, "This villager is already claimed by " + r.ownerName + ".");
            return;
        }
        String problem = claimProblem(player);
        if (problem != null) {
            fail(player, problem);
            return;
        }
        pay(player, claimCost.items());
        r.owner = player.getUniqueId();
        r.ownerName = player.getName();
        r.name = cleanName(name);
        records.put(v.getUniqueId(), r);
        showName(v, r);
        save();
        plugin.log(player, "VILLAGER_CLAIM " + r.profession + " at " + r.world + " " + r.where() + ", paid " + describe(claimCost), "-");
        ok(player, "You claimed " + (r.name != null ? r.name : "this " + r.profession.toLowerCase(Locale.ROOT))
                + ". Sneak + right-click it, or use /toolshop (Your Villagers), to manage it.");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
    }

    /** Buys the next discount tier. Works from anywhere: the discount is applied whenever someone trades with it. */
    public void upgrade(Player player, UUID id) {
        Villager v = find(id);
        Record r = records.get(id);
        if (r == null || !player.getUniqueId().equals(r.owner)) {
            fail(player, "Only the villager's owner can upgrade it.");
            return;
        }
        if (r.tier >= tiers.size()) {
            fail(player, "This villager is fully upgraded.");
            return;
        }
        Tier next = tiers.get(r.tier);
        String problem = problem(player, next.cost());
        if (problem != null) {
            fail(player, problem);
            return;
        }
        pay(player, next.cost().items());
        r.tier++;
        if (v != null) touch(r, v);
        save();
        plugin.log(player, "VILLAGER_UPGRADE " + r.profession + " to tier " + r.tier + ", paid " + describe(next.cost()), "-");
        ok(player, "Villager upgraded to Tier " + Upgrade.roman(r.tier) + ": its trades are " + next.discount() + "% cheaper.");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 1f, 1f);
        if (v != null && plugin.settings().effects) v.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, v.getLocation().add(0, 1.8, 0), 12, 0.4, 0.4, 0.4, 0);
    }

    public void unclaim(Player player, UUID id) {
        Record r = records.get(id);
        if (r == null || !player.getUniqueId().equals(r.owner)) return;
        r.owner = null;
        r.ownerName = null;
        r.name = null;
        Villager v = find(id);
        if (v != null) showName(v, r);
        if (r.tier == 0 && r.enchant == null) records.remove(id);
        save();
        plugin.log(player, "VILLAGER_UNCLAIM " + r.profession + " at " + r.world + " " + r.where(), "-");
        ok(player, "You unclaimed this villager. Its discount and assigned trade stay.");
    }

    /** Renames the owner's villager (its name shows above it once it's loaded). */
    public void rename(Player player, UUID id, String typed) {
        Record r = records.get(id);
        if (r == null || !player.getUniqueId().equals(r.owner)) return;
        String name = cleanName(typed);
        if (name == null) {
            fail(player, "Type a name first.");
            return;
        }
        r.name = name;
        Villager v = find(id);
        if (v != null) showName(v, r);
        save();
        ok(player, "Renamed to " + name + "." + (v == null ? " The name shows above it next time it's loaded." : ""));
    }

    /** Emeralds a player pays the villager for this book (vanilla-like: more for higher levels, double for treasure). */
    public static int tradePrice(Enchantment e) {
        int price = 5 + 10 * e.getMaxLevel();
        if (rarity(e) == Rarity.TREASURE) price *= 2;
        return Math.min(64, price);
    }

    /** The enchantments a librarian can be given (no curses). */
    public static List<Enchantment> assignable() {
        List<Enchantment> list = new ArrayList<>();
        for (Enchantment e : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) if (!e.isCursed()) list.add(e);
        list.sort(Comparator.comparing(e -> e.getKey().getKey()));
        return list;
    }

    public static String enchantName(Enchantment e) {
        String n = e.getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** Gives a claimed librarian a trade for this enchanted book (max level), replacing the one assigned before. */
    public void assign(Player player, UUID id, Enchantment e) {
        Record r = records.get(id);
        if (r == null || !player.getUniqueId().equals(r.owner)) {
            fail(player, "Only the villager's owner can assign its trade.");
            return;
        }
        Villager v = find(id);
        if (v == null) {
            fail(player, "Go near the villager first: it has to be loaded to change its trades.");
            return;
        }
        if (!isLibrarian(v)) {
            fail(player, "Only librarians can be given an enchanted book trade.");
            return;
        }
        if (e.getKey().getKey().equals(r.enchant)) {
            fail(player, "This villager already sells " + enchantName(e) + ".");
            return;
        }
        Map<Material, Integer> cost = assignCost(rarity(e));
        String problem = problem(player, new Cost(0, cost));
        if (problem != null) {
            fail(player, problem);
            return;
        }
        pay(player, cost);
        List<MerchantRecipe> recipes = new ArrayList<>(v.getRecipes());
        if (r.enchant != null) recipes.removeIf(rec -> isAssignedTrade(rec, r.enchant));
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(e, e.getMaxLevel(), true);
        book.setItemMeta(meta);
        MerchantRecipe rec = new MerchantRecipe(book, 0, 12, true, 10, 0.2f);
        rec.addIngredient(ItemStack.of(Material.EMERALD, tradePrice(e)));
        rec.addIngredient(ItemStack.of(Material.BOOK));
        recipes.add(rec);
        v.setRecipes(recipes);
        r.enchant = e.getKey().getKey();
        touch(r, v);
        save();
        plugin.log(player, "VILLAGER_TRADE " + enchantName(e) + " on " + r.where() + ", paid " + Settings.describeCost(cost), "-");
        ok(player, "This librarian now sells " + enchantName(e) + " " + Upgrade.roman(e.getMaxLevel())
                + " for " + tradePrice(e) + " emeralds and a book.");
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.1f);
    }

    private static boolean isAssignedTrade(MerchantRecipe rec, String enchantKey) {
        ItemStack result = rec.getResult();
        if (result.getType() != Material.ENCHANTED_BOOK || !(result.getItemMeta() instanceof EnchantmentStorageMeta m)) return false;
        if (rec.getIngredients().size() < 2 || rec.getIngredients().get(1).getType() != Material.BOOK) return false;
        for (Enchantment e : m.getStoredEnchants().keySet()) {
            if (e.getKey().getKey().equals(enchantKey) && m.getStoredEnchantLevel(e) == e.getMaxLevel()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- protection, death, zombie conversion

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager v)) return;
        Record r = records.get(v.getUniqueId());
        if (r == null || !r.claimed()) return;
        Entity d = event.getDamager();
        Player attacker = d instanceof Player p ? p : d instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (attacker != null && !attacker.getUniqueId().equals(r.owner) && !attacker.isOp()) {
            event.setCancelled(true);
            attacker.sendActionBar(Component.text("This villager belongs to " + r.ownerName + ".", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (records.remove(event.getEntity().getUniqueId()) != null) save();
    }

    /** A claimed villager turned into a zombie villager (or cured back) keeps its claim. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        Record old = records.remove(event.getEntity().getUniqueId());
        if (old == null) return;
        Record moved = new Record(event.getTransformedEntity().getUniqueId());
        moved.owner = old.owner;
        moved.ownerName = old.ownerName;
        moved.tier = old.tier;
        moved.enchant = old.enchant;
        moved.name = old.name;
        moved.profession = old.profession;
        moved.world = old.world;
        moved.x = old.x;
        moved.y = old.y;
        moved.z = old.z;
        records.put(moved.villager, moved);
        save();
    }

    // ---------------------------------------------------------------- saving

    private void load() {
        File f = new File(plugin.getDataFolder(), FILE);
        if (!f.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(f).getConfigurationSection("villagers");
        if (all == null) return;
        for (String k : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Record r = new Record(UUID.fromString(k));
                String owner = s.getString("owner", "");
                r.owner = owner.isEmpty() ? null : UUID.fromString(owner);
                r.ownerName = s.getString("owner-name");
                r.tier = s.getInt("tier");
                r.enchant = s.getString("enchant");
                r.name = s.getString("name");
                r.profession = s.getString("profession", "Villager");
                r.world = s.getString("world", "world");
                r.x = s.getInt("x");
                r.y = s.getInt("y");
                r.z = s.getInt("z");
                records.put(r.villager, r);
            } catch (IllegalArgumentException ignored) {
                // skip bad entries
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Record r : records.values()) {
            String p = "villagers." + r.villager + ".";
            yml.set(p + "owner", r.owner == null ? "" : r.owner.toString());
            yml.set(p + "owner-name", r.ownerName);
            yml.set(p + "tier", r.tier);
            yml.set(p + "enchant", r.enchant);
            yml.set(p + "name", r.name);
            yml.set(p + "profession", r.profession);
            yml.set(p + "world", r.world);
            yml.set(p + "x", r.x);
            yml.set(p + "y", r.y);
            yml.set(p + "z", r.z);
        }
        try {
            yml.save(new File(plugin.getDataFolder(), FILE));
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save " + FILE + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The loaded villager with this id, or null. */
    public static Villager find(UUID id) {
        return Bukkit.getEntity(id) instanceof Villager v ? v : null;
    }

    private static void ok(Player player, String message) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, NamedTextColor.GREEN)));
    }

    private static void fail(Player player, String message) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, NamedTextColor.RED)));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    /** The enchantment with this key (e.g. "sharpness"), or null. */
    public static Enchantment byKey(String key) {
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(key));
    }
}
