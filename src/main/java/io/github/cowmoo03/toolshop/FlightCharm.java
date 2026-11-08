package io.github.cowmoo03.toolshop;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.Keyed;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Flight Charm: a crafted item anyone can use. Right-click for a few minutes of creative-style flight, for building.
 * One flight at a time: a charm can't be used (and isn't used up) while a flight is running.
 * The flight ends when time runs out, when the player goes too far (sideways) from where they took off, or on PvP
 * damage (given or taken). After it ends the player takes no fall damage on the way down.
 */
public final class FlightCharm implements Listener {
    private static final long TICK_INTERVAL = 10L;
    /** How long after a flight ends a fall is still forgiven, in case the player never touches the ground. */
    private static final long FALL_SAFE_MS = 60_000L;
    private static final String LANDING_FILE = "flight-landing.yml";

    private final ToolShopPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey recipeKey;
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> fallSafeUntil = new HashMap<>();
    /** Players whose flight ended because they left (or the server stopped); their next landing is forgiven. */
    private final Set<UUID> fallSafeOnJoin = new HashSet<>();

    private boolean enabled;
    private int flightSeconds;
    /** mcMMO power level needed to craft a charm, on the scale mcMMO uses (0 = no lock). */
    private int unlockConfigLevel;
    private final Map<UUID, Long> lastLockMessage = new HashMap<>();
    private int unlockCheckCountdown;
    private double maxDistance;
    private long pvpCooldownMs;
    private boolean resetStrayFlight;
    private boolean showEffect;
    private boolean showBar;

    private static final class Flight {
        final Location start;
        long endsAt;
        final BossBar bar;
        boolean warnedDistance;
        long lastCountdownSecond = -1;
        /** Out of range: flight is taken away and the clock is stopped until the player is back in range. */
        boolean paused;
        long pausedLeftMs;

        long leftMs(long now) {
            return Math.max(0, paused ? pausedLeftMs : endsAt - now);
        }

        Flight(Location start, long endsAt, BossBar bar) {
            this.start = start;
            this.endsAt = endsAt;
            this.bar = bar;
        }
    }

    public FlightCharm(ToolShopPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "flight_charm");
        this.recipeKey = new NamespacedKey(plugin, "flight_charm");
        loadLandingFile();
        reload();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    // ---------------------------------------------------------------- config and recipe

    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("flight-charm");
        enabled = c == null || c.getBoolean("enabled", true);
        double minutes = c == null ? 5 : c.getDouble("minutes", 5);
        flightSeconds = (int) Math.max(10, Math.round(minutes * 60));
        unlockConfigLevel = Math.max(0, c == null ? 100 : c.getInt("unlock-power-level", 100));
        maxDistance = Math.max(8, c == null ? 64 : c.getDouble("max-distance", 64));
        pvpCooldownMs = Math.max(0, c == null ? 10 : c.getInt("pvp-cooldown-seconds", 10)) * 1000L;
        resetStrayFlight = c == null || c.getBoolean("reset-stray-flight-on-join", true);
        showEffect = c == null || c.getBoolean("show-effect", true);
        showBar = c == null || c.getBoolean("boss-bar", true);

        Bukkit.removeRecipe(recipeKey);
        if (!enabled) return;
        ConfigurationSection r = c == null ? null : c.getConfigurationSection("recipe");
        if (!addRecipe(r)) {
            plugin.getLogger().warning("flight-charm.recipe is invalid; using the default recipe.");
            addRecipe(null);
        }
        for (Player p : Bukkit.getOnlinePlayers()) updateRecipeBook(p, false);
    }

    private boolean addRecipe(ConfigurationSection r) {
        List<String> shape = r == null ? List.of("FGF", "GDG", "FGF") : r.getStringList("shape");
        Map<Character, Material> ingredients = new LinkedHashMap<>();
        if (r == null) {
            ingredients.put('F', Material.FEATHER);
            ingredients.put('G', Material.GOLD_INGOT);
            ingredients.put('D', Material.DIAMOND);
        } else {
            ConfigurationSection ing = r.getConfigurationSection("ingredients");
            if (ing == null) return false;
            for (String key : ing.getKeys(false)) {
                Material m = Material.matchMaterial(String.valueOf(ing.getString(key)));
                if (key.length() != 1 || m == null || !m.isItem()) return false;
                ingredients.put(key.charAt(0), m);
            }
        }
        int amount = r == null ? 1 : Math.max(1, Math.min(64, r.getInt("amount", 1)));
        try {
            ShapedRecipe recipe = new ShapedRecipe(recipeKey, create(amount));
            recipe.shape(shape.toArray(String[]::new));
            for (String row : shape) {
                for (char ch : row.toCharArray()) {
                    if (ch != ' ' && !ingredients.containsKey(ch)) return false;
                }
            }
            for (Map.Entry<Character, Material> e : ingredients.entrySet()) {
                if (shape.stream().anyMatch(row -> row.indexOf(e.getKey()) >= 0)) recipe.setIngredient(e.getKey(), e.getValue());
            }
            return Bukkit.addRecipe(recipe);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- the item

    /** A stack of Flight Charms. */
    public ItemStack create(int amount) {
        ItemStack item = ItemStack.of(Material.FEATHER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Flight Charm", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Right-click to fly for " + duration(flightSeconds) + ".", NamedTextColor.GRAY));
        lore.add(line("Stay within " + (int) maxDistance + " blocks of where you take off.", NamedTextColor.GRAY));
        lore.add(line("Fighting a player ends the flight.", NamedTextColor.GRAY));
        lore.add(line("No fall damage when it ends.", NamedTextColor.GRAY));
        lore.add(line("Used up when you use it.", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCharm(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** Charms are feathers; they can't be used as feathers in a crafting grid. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isCharm(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
        if (!(event.getRecipe() instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) return;
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        int need = requiredPowerLevel();
        int have = need > 0 ? plugin.mcmmo().powerLevel(player) : 0;
        if (need <= 0 || have >= need) return;
        event.getInventory().setResult(null);
        long now = System.currentTimeMillis();
        if (now - lastLockMessage.getOrDefault(player.getUniqueId(), 0L) > 3000) {
            lastLockMessage.put(player.getUniqueId(), now);
            tell(player, have < 0
                    ? "Your skills are still loading. Try again in a moment."
                    : "Flight Charms unlock at mcMMO power level " + need + ". You're at " + have
                        + ". See /skills.", NamedTextColor.RED);
        }
    }

    /** A crafter block has no player to check, so it can't make charms while the recipe is locked. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent event) {
        if (event.getRecipe().getKey().equals(recipeKey) && requiredPowerLevel() > 0) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- unlock by power level

    /** Power level needed to craft, on mcMMO's current scale; 0 when there's no lock or no mcMMO. */
    public int requiredPowerLevel() {
        if (unlockConfigLevel <= 0 || plugin.mcmmo() == null || !plugin.mcmmo().available()) return 0;
        return unlockConfigLevel * plugin.levelScale();
    }

    /** True if the player may craft charms (false while their mcMMO data is still loading). */
    public boolean canCraft(Player player) {
        int need = requiredPowerLevel();
        return need <= 0 || plugin.mcmmo().powerLevel(player) >= need;
    }

    /**
     * Shows the recipe in the recipe book only once the player has unlocked it, and tells them when that happens.
     * Leaves it alone while their mcMMO data is loading.
     */
    private void updateRecipeBook(Player player, boolean announce) {
        if (!enabled) return;
        int need = requiredPowerLevel();
        int have = need > 0 ? plugin.mcmmo().powerLevel(player) : 0;
        if (need > 0 && have < 0) return;
        boolean unlocked = need <= 0 || have >= need;
        boolean known = player.hasDiscoveredRecipe(recipeKey);
        if (unlocked && !known) {
            player.discoverRecipe(recipeKey);
            if (announce && need > 0) {
                tell(player, "You reached power level " + need + ": Flight Charms unlocked! Craft one with 4 feathers, "
                        + "4 gold ingots and a diamond (it's in your recipe book).", NamedTextColor.AQUA);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
            }
        } else if (!unlocked && known) {
            player.undiscoverRecipe(recipeKey);
        }
    }

    // ---------------------------------------------------------------- using it

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(hand);
        if (!isCharm(item)) return;
        if (hand == EquipmentSlot.OFF_HAND && isCharm(player.getInventory().getItemInMainHand())) return;
        // Let players open chests, doors etc. while holding a charm; sneak to use the charm on them.
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()) return;
        event.setCancelled(true);
        use(player, hand, item);
    }

    private void use(Player player, EquipmentSlot hand, ItemStack item) {
        if (!enabled) {
            tell(player, "Flight Charms are turned off right now.", NamedTextColor.RED);
            return;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            tell(player, "You can already fly.", NamedTextColor.YELLOW);
            return;
        }
        long now = System.currentTimeMillis();
        long combat = combatUntil.getOrDefault(player.getUniqueId(), 0L);
        if (combat > now) {
            tell(player, "You were just in a fight. Wait " + ((combat - now + 999) / 1000) + "s.", NamedTextColor.RED);
            return;
        }
        Flight active = flights.get(player.getUniqueId());
        if (active != null) {
            String left = duration((active.leftMs(now) + 999) / 1000);
            tell(player, active.paused
                    ? "Your flight is paused (" + left + " left). Go back within " + (int) maxDistance
                        + " blocks of where you took off to get it back."
                    : "You're already flying (" + left + " left). Use another charm once this flight ends.",
                    NamedTextColor.YELLOW);
            return;
        }
        if (player.getAllowFlight()) {
            tell(player, "You can already fly.", NamedTextColor.YELLOW);
            return;
        }
        item.setAmount(item.getAmount() - 1);
        player.getInventory().setItem(hand, item.getAmount() > 0 ? item : null);

        BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        Flight flight = new Flight(player.getLocation().clone(), now + flightSeconds * 1000L, bar);
        flights.put(player.getUniqueId(), flight);
        fallSafeUntil.remove(player.getUniqueId());
        player.setAllowFlight(true);
        player.setFlying(true);
        if (showBar) player.showBossBar(bar);
        showTimer(player, flight, now);
        tell(player, "You can fly for " + duration(flightSeconds) + ". Stay within " + (int) maxDistance
                + " blocks of here.", NamedTextColor.AQUA);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.6f);
        updateBar(flight, now);
    }

    // ---------------------------------------------------------------- every half second

    private void tick() {
        long now = System.currentTimeMillis();
        if (--unlockCheckCountdown <= 0) {
            unlockCheckCountdown = 20; // every 10 seconds
            for (Player p : Bukkit.getOnlinePlayers()) updateRecipeBook(p, true);
        }
        Iterator<Map.Entry<UUID, Flight>> it = flights.entrySet().iterator();
        List<Player> toEnd = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, Flight> e = it.next();
            Player player = Bukkit.getPlayer(e.getKey());
            Flight f = e.getValue();
            if (player == null) {
                it.remove();
                continue;
            }
            double dist = distance(player, f);
            boolean inRange = dist <= maxDistance;

            if (f.paused) {
                if (inRange) {
                    resume(player, f, now);
                } else {
                    String where = dist == Double.MAX_VALUE ? "the world you took off in"
                            : (int) Math.ceil(dist - maxDistance) + " blocks closer";
                    player.sendActionBar(Component.text("Flight paused: go back to " + where + " to fly again.",
                            NamedTextColor.GOLD));
                    updateBar(f, now);
                }
                continue;
            }

            if (!player.getAllowFlight()) {
                toEnd.add(player); // something else took flight away
                reasons.add("");
                continue;
            }
            if (!inRange) {
                pause(player, f, now);
                continue;
            }
            if (now >= f.endsAt) {
                toEnd.add(player);
                reasons.add("Your flight ran out. No fall damage on the way down.");
                continue;
            }
            if (dist > maxDistance - 8) {
                if (!f.warnedDistance) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
                f.warnedDistance = true;
                player.sendActionBar(Component.text("Turn back! Flight pauses " + (int) Math.max(0, maxDistance - dist)
                        + " blocks from here.", NamedTextColor.RED));
            } else {
                f.warnedDistance = false;
            }
            long left = (f.endsAt - now + 999) / 1000;
            if (left <= 10 && left != f.lastCountdownSecond) {
                f.lastCountdownSecond = left;
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);
                if (!f.warnedDistance) player.sendActionBar(Component.text("Flight ends in " + left + "s", NamedTextColor.YELLOW));
            }
            updateBar(f, now);
            showTimer(player, f, now);
        }
        for (int i = 0; i < toEnd.size(); i++) end(toEnd.get(i), reasons.get(i), true);

        // Forget fall protection once the player is back on the ground (or it's been a minute).
        fallSafeUntil.entrySet().removeIf(e -> {
            if (e.getValue() < now) return true;
            Player p = Bukkit.getPlayer(e.getKey());
            return p == null || (p.isOnGround() && e.getValue() - FALL_SAFE_MS + 1000 < now);
        });
        combatUntil.values().removeIf(t -> t < now);
    }

    /** Sideways distance from takeoff, or Double.MAX_VALUE in another world. */
    private static double distance(Player player, Flight f) {
        Location loc = player.getLocation();
        if (!loc.getWorld().equals(f.start.getWorld())) return Double.MAX_VALUE;
        double dx = loc.getX() - f.start.getX();
        double dz = loc.getZ() - f.start.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Out of range: take flight away, stop the clock, and forgive the fall. */
    private void pause(Player player, Flight f, long now) {
        f.paused = true;
        f.pausedLeftMs = Math.max(0, f.endsAt - now);
        f.warnedDistance = false;
        player.setFlying(false);
        player.setAllowFlight(false);
        fallSafeUntil.put(player.getUniqueId(), now + FALL_SAFE_MS);
        removeTimer(player);
        updateBar(f, now);
        tell(player, "Flight paused: you went more than " + (int) maxDistance + " blocks from where you took off. "
                + "Go back and it comes back with " + duration((f.pausedLeftMs + 999) / 1000)
                + " left. No fall damage on the way down.", NamedTextColor.GOLD);
        player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.6f, 0.8f);
    }

    /** Back in range: flight comes back and the clock starts again from where it stopped. */
    private void resume(Player player, Flight f, long now) {
        f.paused = false;
        f.endsAt = now + f.pausedLeftMs;
        f.lastCountdownSecond = -1;
        player.setAllowFlight(true);
        if (!player.isOnGround()) player.setFlying(true); // still falling: catch them
        updateBar(f, now);
        showTimer(player, f, now);
        tell(player, "Back in range: you can fly again (" + duration((f.pausedLeftMs + 999) / 1000) + " left).",
                NamedTextColor.AQUA);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.6f);
    }

    private void updateBar(Flight f, long now) {
        long leftMs = f.leftMs(now);
        float progress = (float) Math.min(1.0, leftMs / (flightSeconds * 1000.0));
        f.bar.progress(progress);
        String time = duration((leftMs + 999) / 1000);
        if (f.paused) {
            f.bar.color(BossBar.Color.YELLOW);
            f.bar.name(Component.text("✈ Flight paused: " + time + " left (out of range)", NamedTextColor.GOLD));
        } else {
            f.bar.color(leftMs <= 10_000 ? BossBar.Color.RED : BossBar.Color.BLUE);
            f.bar.name(Component.text("✈ Flight: " + time, NamedTextColor.AQUA));
        }
    }

    /**
     * Ends a player's flight. In survival or adventure their flight is switched off and their next fall is forgiven.
     * An empty message ends it quietly.
     */
    private void end(Player player, String message, boolean forgiveFall) {
        Flight f = flights.remove(player.getUniqueId());
        if (f == null) return;
        player.hideBossBar(f.bar);
        removeTimer(player);
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
            player.setFlying(false);
            player.setAllowFlight(false);
            if (forgiveFall) fallSafeUntil.put(player.getUniqueId(), System.currentTimeMillis() + FALL_SAFE_MS);
        }
        if (message != null && !message.isEmpty()) {
            tell(player, message, NamedTextColor.YELLOW);
            player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.6f, 0.8f);
        }
    }

    // ---------------------------------------------------------------- events that end flight or forgive falls

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) return;
        Long until = fallSafeUntil.get(player.getUniqueId());
        if (until != null && until >= System.currentTimeMillis()) {
            event.setCancelled(true);
            fallSafeUntil.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        long until = System.currentTimeMillis() + pvpCooldownMs;
        for (Player p : List.of(attacker, victim)) {
            combatUntil.put(p.getUniqueId(), until);
            end(p, "Your flight ended: PvP. No fall damage on the way down.", true);
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        end(event.getPlayer(), "", false);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        GameMode to = event.getNewGameMode();
        if (to == GameMode.CREATIVE || to == GameMode.SPECTATOR) {
            Flight f = flights.remove(event.getPlayer().getUniqueId());
            if (f != null) {
                event.getPlayer().hideBossBar(f.bar);
                removeTimer(event.getPlayer());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (flights.containsKey(player.getUniqueId())) {
            end(player, "", false);
            fallSafeOnJoin.add(player.getUniqueId());
        }
        combatUntil.remove(player.getUniqueId());
        lastLockMessage.remove(player.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // mcMMO loads player data a moment after joining.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) updateRecipeBook(player, false);
        }, 60L);
        GameMode mode = player.getGameMode();
        boolean survival = mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
        boolean forgive = fallSafeOnJoin.remove(player.getUniqueId());
        // Flight left switched on by a crash would otherwise last forever.
        if (survival && resetStrayFlight && player.getAllowFlight()) {
            player.setFlying(false);
            player.setAllowFlight(false);
            forgive = true;
        }
        if (forgive && survival) fallSafeUntil.put(player.getUniqueId(), System.currentTimeMillis() + FALL_SAFE_MS);
    }

    /** Server stopping: switch flight off before player data is saved, and remember who needs a safe landing. */
    public void shutdown() {
        for (UUID id : new ArrayList<>(flights.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) end(p, "", false);
            fallSafeOnJoin.add(id);
        }
        File file = new File(plugin.getDataFolder(), LANDING_FILE);
        if (fallSafeOnJoin.isEmpty()) {
            file.delete();
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("safe-landing", fallSafeOnJoin.stream().map(UUID::toString).toList());
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save " + LANDING_FILE + ": " + e.getMessage());
        }
    }

    private void loadLandingFile() {
        File file = new File(plugin.getDataFolder(), LANDING_FILE);
        if (!file.exists()) return;
        for (String s : YamlConfiguration.loadConfiguration(file).getStringList("safe-landing")) {
            try {
                fallSafeOnJoin.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // skip bad lines
            }
        }
        file.delete();
    }

    // ---------------------------------------------------------------- potion-style timer

    /**
     * Shows the time left as a Slow Falling effect (icon top-right, countdown in the inventory), the way a potion
     * does. It lasts exactly as long as the flight. Put back if milk clears it. A real Slow Falling potion the
     * player drank is left alone.
     */
    private void showTimer(Player player, Flight f, long now) {
        if (!showEffect) return;
        PotionEffect current = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current != null && current.hasParticles()) return; // their own potion
        int ticks = (int) Math.max(1, (f.endsAt - now) / 50);
        if (current != null && Math.abs(current.getDuration() - ticks) <= 20) return; // still in step
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, false, false, true));
    }

    /** Removes the timer effect, but never a Slow Falling potion the player drank (those have particles). */
    private void removeTimer(Player player) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current != null && !current.hasParticles()) player.removePotionEffect(PotionEffectType.SLOW_FALLING);
    }

    // ---------------------------------------------------------------- helpers

    private static String duration(long seconds) {
        long m = seconds / 60, s = seconds % 60;
        if (m == 0) return s + "s";
        if (s == 0) return m + (m == 1 ? " minute" : " minutes");
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private static void tell(Player player, String message, NamedTextColor color) {
        player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, color)));
    }
}
