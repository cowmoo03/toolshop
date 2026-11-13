package io.github.cowmoo03.toolshop;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

/**
 * Makes legendary items and mcMMO work together. While a player uses their legendary item:
 * <ul>
 *   <li>XP they earn in that item's skill is raised by xp-percent-per-tier for every upgrade tier on it.</li>
 *   <li>The skill's super ability (Super Breaker, Giga Drill Breaker, Green Terra, Serrated Strikes, Skull Splitter...)
 *   gets a shorter cooldown: cooldown-percent-per-tier for every upgrade tier.</li>
 * </ul>
 * Hooked into mcMMO's events by reflection, so ToolShop still runs without mcMMO.
 */
public final class SkillSynergy implements Listener {
    private static final String XP_EVENT = "com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent";
    private static final String ABILITY_EVENT = "com.gmail.nossr50.events.skills.abilities.McMMOPlayerAbilityActivateEvent";

    /** mcMMO skill -> the legendary item that boosts it. */
    private static final Map<String, ToolKind> SKILL_ITEM = Map.ofEntries(
            Map.entry("MINING", ToolKind.PICKAXE), Map.entry("WOODCUTTING", ToolKind.AXE), Map.entry("AXES", ToolKind.AXE),
            Map.entry("EXCAVATION", ToolKind.SHOVEL), Map.entry("HERBALISM", ToolKind.HOE), Map.entry("SWORDS", ToolKind.SWORD),
            Map.entry("TRIDENTS", ToolKind.TRIDENT), Map.entry("CROSSBOWS", ToolKind.CROSSBOW), Map.entry("ARCHERY", ToolKind.BOW),
            Map.entry("ACROBATICS", ToolKind.ELYTRA));

    /** The super ability each legendary item speeds up, for display. */
    private static final Map<ToolKind, String> SUPER_NAME = Map.of(
            ToolKind.PICKAXE, "Super Breaker", ToolKind.SHOVEL, "Giga Drill Breaker", ToolKind.HOE, "Green Terra",
            ToolKind.SWORD, "Serrated Strikes", ToolKind.AXE, "Skull Splitter");

    private final ToolShopPlugin plugin;
    private Method xpGetSkill, xpGetRaw, xpSetRaw, xpGetPlayer;
    private Method abGetSkill, abGetAbility, abGetPlayer, abGetMmoPlayer;

    private SkillSynergy(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("unchecked")
    public static boolean register(ToolShopPlugin plugin) {
        Plugin mcmmo = plugin.getServer().getPluginManager().getPlugin("mcMMO");
        if (mcmmo == null || !mcmmo.isEnabled()) return false;
        SkillSynergy s = new SkillSynergy(plugin);
        ClassLoader loader = mcmmo.getClass().getClassLoader();
        try {
            Class<?> xp = Class.forName(XP_EVENT, true, loader);
            s.xpGetSkill = xp.getMethod("getSkill");
            s.xpGetRaw = xp.getMethod("getRawXpGained");
            s.xpSetRaw = xp.getMethod("setRawXpGained", float.class);
            s.xpGetPlayer = xp.getMethod("getPlayer");
            Class<? extends Event> xpType = (Class<? extends Event>) xp;
            plugin.getServer().getPluginManager().registerEvent(xpType, s, EventPriority.HIGH,
                    (l, e) -> s.onXp(xpType, e), plugin, true);

            Class<?> ab = Class.forName(ABILITY_EVENT, true, loader);
            s.abGetSkill = ab.getMethod("getSkill");
            s.abGetAbility = ab.getMethod("getAbility");
            s.abGetPlayer = ab.getMethod("getPlayer");
            s.abGetMmoPlayer = ab.getMethod("getMcMMOPlayer");
            Class<? extends Event> abType = (Class<? extends Event>) ab;
            plugin.getServer().getPluginManager().registerEvent(abType, s, EventPriority.MONITOR,
                    (l, e) -> s.onAbility(abType, e), plugin, true);
            plugin.setSynergy(s);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Couldn't link legendary items to mcMMO skills: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- bonuses

    /** The player's legendary item for this kind, if they're using it right now (held, or worn for the elytra). */
    private ItemStack inUse(Player player, ToolKind kind) {
        ItemStack item = kind == ToolKind.ELYTRA ? player.getInventory().getChestplate() : player.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        return item != null && lt.usableBy(item, player) && lt.kind(item) == kind ? item : null;
    }

    public int xpPercent(ItemStack item) {
        Settings s = plugin.settings();
        return Math.min(s.synergyMaxXpPercent, s.synergyXpPercentPerTier * plugin.legendary().totalTiers(item));
    }

    public int cooldownPercent(ItemStack item) {
        Settings s = plugin.settings();
        return Math.min(s.synergyMaxCooldownPercent, s.synergyCooldownPercentPerTier * plugin.legendary().totalTiers(item));
    }

    /** "+12% Mining XP · Super Breaker cooldown -24%", or null if this item gives nothing yet. */
    public static String describe(ToolShopPlugin plugin, ItemStack item, ToolKind kind) {
        if (plugin.synergy() == null || !plugin.settings().synergyEnabled) return null;
        int xp = plugin.synergy().xpPercent(item);
        int cd = plugin.synergy().cooldownPercent(item);
        String ability = SUPER_NAME.get(kind);
        StringBuilder sb = new StringBuilder();
        if (xp > 0) sb.append("+").append(xp).append("% ").append(kind.skillName()).append(" XP");
        if (cd > 0 && ability != null) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append(ability).append(" cooldown -").append(cd).append("%");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** What each upgrade tier adds, for menus: "Each upgrade tier: +2% Mining XP, -4% Super Breaker cooldown". */
    public static String perTierText(ToolShopPlugin plugin, ToolKind kind) {
        Settings s = plugin.settings();
        if (plugin.synergy() == null || !s.synergyEnabled) return null;
        String ability = SUPER_NAME.get(kind);
        return "Each upgrade tier: +" + s.synergyXpPercentPerTier + "% " + kind.skillName() + " XP"
                + (ability != null ? ", -" + s.synergyCooldownPercentPerTier + "% " + ability + " cooldown" : "");
    }

    // ---------------------------------------------------------------- mcMMO events

    private void onXp(Class<? extends Event> type, Event event) {
        if (!type.isInstance(event) || !plugin.settings().synergyEnabled) return;
        try {
            ToolKind kind = SKILL_ITEM.get(String.valueOf(xpGetSkill.invoke(event)).toUpperCase(Locale.ROOT));
            if (kind == null || !(xpGetPlayer.invoke(event) instanceof Player player)) return;
            ItemStack item = inUse(player, kind);
            if (item == null) return;
            int pct = xpPercent(item);
            if (pct <= 0) return;
            float raw = (Float) xpGetRaw.invoke(event);
            xpSetRaw.invoke(event, raw * (1f + pct / 100f));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // mcMMO changed: leave its XP alone.
        }
    }

    private void onAbility(Class<? extends Event> type, Event event) {
        if (!type.isInstance(event) || !plugin.settings().synergyEnabled) return;
        if (event instanceof org.bukkit.event.Cancellable c && c.isCancelled()) return;
        try {
            ToolKind kind = SKILL_ITEM.get(String.valueOf(abGetSkill.invoke(event)).toUpperCase(Locale.ROOT));
            if (kind == null || !(abGetPlayer.invoke(event) instanceof Player player)) return;
            ItemStack item = inUse(player, kind);
            if (item == null) return;
            int pct = cooldownPercent(item);
            if (pct <= 0) return;
            Object ability = abGetAbility.invoke(event);
            Object mmoPlayer = abGetMmoPlayer.invoke(event);
            if (ability == null || mmoPlayer == null) return;
            // mcMMO sets the ability's end time right after this event; the cooldown runs from there. Move that time
            // earlier (next tick) by the share of the cooldown we're cutting.
            plugin.getServer().getScheduler().runTask(plugin, () -> shortenCooldown(mmoPlayer, ability, pct));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // mcMMO changed: leave its cooldowns alone.
        }
    }

    private void shortenCooldown(Object mmoPlayer, Object ability, int pct) {
        try {
            // Enum constants can be anonymous subclasses: use the enum's own class for method lookups.
            Class<?> abilityType = ability instanceof Enum<?> en ? en.getDeclaringClass() : ability.getClass();
            Object profile = mmoPlayer.getClass().getMethod("getProfile").invoke(mmoPlayer);
            Method get = profile.getClass().getMethod("getAbilityDATS", abilityType);
            Method set = findMethod(profile.getClass(), "setAbilityDATS", abilityType, long.class);
            if (set == null) return;
            set.setAccessible(true);
            int cooldown = (Integer) abilityType.getMethod("getCooldown").invoke(ability);
            long endsAtSeconds = ((Number) get.invoke(profile, ability)).longValue();
            long cut = Math.round(cooldown * pct / 100.0);
            set.invoke(profile, ability, (endsAtSeconds - cut) * 1000L);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // mcMMO changed: leave its cooldowns alone.
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // try the superclass
            }
        }
        return null;
    }
}
