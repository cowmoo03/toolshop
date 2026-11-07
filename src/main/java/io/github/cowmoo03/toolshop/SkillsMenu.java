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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The /skills window: every mcMMO skill with level and XP (main page), and a page per skill listing its abilities in
 * plain words, which are unlocked, and what level the locked ones need. Mining, Woodcutting and Excavation also show
 * the ToolShop upgrades they unlock.
 */
public final class SkillsMenu implements InventoryHolder {
    public static final int BACK_SLOT = 45;
    public static final int CLOSE_SLOT = 49;
    public static final int TOP_SLOT = 47;
    public static final int SHOP_SLOT = 51;
    private static final int[] ABILITY_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int[] UPGRADE_SLOTS = {37, 38, 39, 40, 41, 42, 43, 44};
    private static final int WRAP = 34;

    private final ToolShopPlugin plugin;
    private final Inventory inventory;
    /** Skill id shown on this page, or null for the main page. */
    private final String skillId;
    /** Main page: slot -> skill id. */
    private final Map<Integer, String> skillSlots = new HashMap<>();

    public SkillsMenu(ToolShopPlugin plugin, String skillId) {
        this.plugin = plugin;
        this.skillId = skillId;
        SkillCatalog.Skill skill = skillId == null ? null : SkillCatalog.SKILLS.get(skillId);
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text(skill == null ? "Skills" : "Skills › " + skill.name()));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public String skillId() {
        return skillId;
    }

    public String skillAt(int slot) {
        return skillSlots.get(slot);
    }

    public void render(Player player) {
        inventory.clear();
        skillSlots.clear();
        if (skillId == null) renderMain(player);
        else renderSkill(player, SkillCatalog.SKILLS.get(skillId));
    }

    // ------------------------------------------------------------------ main page

    private void renderMain(Player player) {
        McMMOHook mc = plugin.mcmmo();
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(player);
            head.setItemMeta(sm);
        }
        int power = mc.powerLevel(player);
        inventory.setItem(4, named(head, text(player.getName(), NamedTextColor.GOLD), List.of(
                text("Power level: " + (power < 0 ? "?" : power), NamedTextColor.YELLOW),
                text("(all your skill levels added up)", NamedTextColor.DARK_GRAY),
                Component.empty(),
                text("Click a skill to see its abilities.", NamedTextColor.GRAY))));

        Material[] panes = {Material.LIME_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE,
                Material.LIGHT_BLUE_STAINED_GLASS_PANE};
        int row = 1;
        for (Map.Entry<String, List<String>> group : SkillCatalog.GROUPS) {
            int base = row * 9; // a label in the first column, then the group's skills
            inventory.setItem(base, named(ItemStack.of(panes[row - 1]), text(group.getKey(), NamedTextColor.WHITE), List.of()));
            int slot = base + 1;
            for (String id : group.getValue()) {
                SkillCatalog.Skill skill = SkillCatalog.SKILLS.get(id);
                if (skill == null) continue;
                inventory.setItem(slot, skillIcon(player, skill, true));
                skillSlots.put(slot, id);
                slot++;
            }
            row++;
        }

        inventory.setItem(TOP_SLOT, named(ItemStack.of(Material.GOLDEN_HELMET), text("Leaderboards", NamedTextColor.YELLOW),
                List.of(text("See the top players (/mctop)", NamedTextColor.GRAY))));
        inventory.setItem(CLOSE_SLOT, named(ItemStack.of(Material.BARRIER), text("Close", NamedTextColor.RED), List.of()));
        inventory.setItem(SHOP_SLOT, named(ItemStack.of(Material.NETHERITE_PICKAXE), text("Tool Shop", NamedTextColor.GOLD),
                List.of(text("Make your tools legendary (/toolshop)", NamedTextColor.GRAY))));
        inventory.setItem(53, named(ItemStack.of(Material.BOOK), text("How skills work", NamedTextColor.YELLOW), List.of(
                text("Everything you do gives XP in a skill:", NamedTextColor.GRAY),
                text("mining, chopping, fighting, fishing...", NamedTextColor.GRAY),
                text("Levels unlock abilities that work on", NamedTextColor.GRAY),
                text("their own (passives).", NamedTextColor.GRAY),
                Component.empty(),
                text("Super abilities: right-click with the", NamedTextColor.AQUA),
                text("skill's tool to ready one, then use it.", NamedTextColor.AQUA),
                text("They have a cooldown (/mccooldown).", NamedTextColor.AQUA))));
    }

    /** A skill's icon with level, XP bar and next unlock. */
    private ItemStack skillIcon(Player player, SkillCatalog.Skill skill, boolean clickHint) {
        McMMOHook mc = plugin.mcmmo();
        int level = mc.level(player, skill.id());
        List<Component> lore = new ArrayList<>();
        lore.add(text("Level " + (level < 0 ? "?" : level), NamedTextColor.GOLD));
        if (skill.isChild()) {
            lore.add(text("Levels up with " + String.join(" and ", skill.parents()) + ".", NamedTextColor.GRAY));
        } else {
            int xp = mc.xp(player, skill.id());
            int need = mc.xpToNext(player, skill.id());
            if (xp >= 0 && need > 0) {
                lore.add(bar(xp, need));
                lore.add(text(String.format("%,d / %,d XP", xp, need), NamedTextColor.GRAY));
            }
            lore.add(text("XP from: " + skill.xpFrom(), NamedTextColor.DARK_GRAY));
        }
        SkillCatalog.Ability next = null;
        int nextLevel = Integer.MAX_VALUE;
        for (SkillCatalog.Ability a : skill.abilities()) {
            int unlock = unlockLevel(skill, a);
            if (unlock > level && unlock < nextLevel) {
                next = a;
                nextLevel = unlock;
            }
        }
        lore.add(Component.empty());
        if (next != null && level >= 0) {
            lore.add(text("Next: " + next.name() + " at level " + nextLevel, NamedTextColor.AQUA));
        } else if (level >= 0) {
            lore.add(text("All abilities unlocked", NamedTextColor.GREEN));
        }
        if (skill.tool() != null) {
            lore.add(text("Unlocks " + skill.tool().displayName() + " upgrades", NamedTextColor.GOLD));
        }
        if (clickHint) lore.add(text("▶ Click to see abilities", NamedTextColor.YELLOW));
        return named(ItemStack.of(skill.icon()), text(skill.name(), NamedTextColor.GREEN), lore);
    }

    // ------------------------------------------------------------------ skill page

    private void renderSkill(Player player, SkillCatalog.Skill skill) {
        inventory.setItem(4, skillIcon(player, skill, false));
        int level = plugin.mcmmo().level(player, skill.id());

        int i = 0;
        for (SkillCatalog.Ability a : skill.abilities()) {
            if (i >= ABILITY_SLOTS.length) break;
            inventory.setItem(ABILITY_SLOTS[i++], abilityIcon(skill, a, level));
        }

        if (skill.tool() != null) {
            inventory.setItem(36, named(ItemStack.of(Material.ORANGE_STAINED_GLASS_PANE),
                    text(skill.tool().displayName() + " upgrades", NamedTextColor.GOLD),
                    List.of(text("Unlocked by your " + skill.name() + " level,", NamedTextColor.GRAY),
                            text("bought with items in /toolshop.", NamedTextColor.GRAY))));
            int u = 0;
            for (Upgrade up : Upgrade.values()) {
                if (u >= UPGRADE_SLOTS.length) break;
                if (!appliesToKind(up, skill.tool())) continue;
                inventory.setItem(UPGRADE_SLOTS[u++], upgradeIcon(up, skill, level));
            }
        }

        inventory.setItem(BACK_SLOT, named(ItemStack.of(Material.ARROW), text("◀ All skills", NamedTextColor.YELLOW), List.of()));
        inventory.setItem(CLOSE_SLOT, named(ItemStack.of(Material.BARRIER), text("Close", NamedTextColor.RED), List.of()));
        if (skill.tool() != null) {
            inventory.setItem(SHOP_SLOT, named(ItemStack.of(Material.NETHERITE_PICKAXE), text("Tool Shop", NamedTextColor.GOLD),
                    List.of(text("Make your tools legendary (/toolshop)", NamedTextColor.GRAY))));
        }
    }

    private ItemStack abilityIcon(SkillCatalog.Skill skill, SkillCatalog.Ability a, int level) {
        List<Integer> ranks = a.rankKey() == null ? null : plugin.mcmmo().unlockLevels(skill.name(), a.rankKey());
        int unlock = unlockLevel(skill, a);
        boolean unlocked = level >= 0 && level >= unlock;
        boolean sup = a.kind() == SkillCatalog.Kind.SUPER;

        Material icon = !unlocked ? Material.GRAY_DYE : sup ? Material.NETHER_STAR : Material.LIME_DYE;
        List<Component> lore = new ArrayList<>();
        lore.add(text(sup ? "Super ability" : "Passive: works on its own", sup ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.DARK_GREEN));
        lore.add(Component.empty());
        for (String line : wrap(a.description())) lore.add(text(line, NamedTextColor.WHITE));
        if (a.howTo() != null) {
            lore.add(Component.empty());
            for (String line : wrap("How: " + a.howTo())) lore.add(text(line, NamedTextColor.AQUA));
        }
        lore.add(Component.empty());
        if (unlocked) {
            lore.add(text(unlock <= 0 ? "✔ Everyone starts with this" : "✔ Unlocked", NamedTextColor.GREEN));
            if (ranks != null && ranks.size() > 1) {
                int rank = 0;
                for (int l : ranks) if (level >= l) rank++;
                lore.add(text("Rank " + rank + " of " + ranks.size()
                        + (rank < ranks.size() ? " · next rank at level " + ranks.get(rank) : " · max rank"), NamedTextColor.GRAY));
            } else if (a.kind() == SkillCatalog.Kind.PASSIVE) {
                lore.add(text("Gets stronger as you level up", NamedTextColor.GRAY));
            }
        } else {
            lore.add(text("🔒 Unlocks at level " + unlock + (level >= 0 ? " (you're " + level + ")" : ""), NamedTextColor.RED));
        }
        ItemStack item = named(ItemStack.of(icon), text(a.name(), unlocked ? NamedTextColor.GREEN : NamedTextColor.GRAY), lore);
        if (unlocked && sup) glint(item);
        return item;
    }

    private ItemStack upgradeIcon(Upgrade up, SkillCatalog.Skill skill, int level) {
        Settings s = plugin.settings();
        List<Component> lore = new ArrayList<>();
        for (String line : wrap(up.summary())) lore.add(text(line, NamedTextColor.WHITE));
        if (up == Upgrade.FIREPROOF) lore.add(text("Iron and diamond tools only", NamedTextColor.DARK_GRAY));
        if (up == Upgrade.RECOVERY) lore.add(text("Netherite tools only", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        for (int t = 1; t <= up.maxTier(); t++) {
            int need = plugin.requiredLevel(up, t, skill.tool());
            boolean ok = !s.requireLevels || level >= need;
            String label = up.maxTier() == 1 ? "Needs " : Upgrade.roman(t) + ": ";
            lore.add(text((ok ? "✔ " : "🔒 ") + label + skill.name() + " " + need, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        lore.add(text("+ items, in /toolshop", NamedTextColor.DARK_GRAY));
        return named(ItemStack.of(ShopMenu.iconFor(up)), text(up.displayName(), NamedTextColor.GOLD), lore);
    }

    private static boolean appliesToKind(Upgrade up, ToolKind kind) {
        for (Material m : kind.materials()) if (up.appliesTo(kind, m)) return true;
        return false;
    }

    /** Level the ability unlocks at, from mcMMO's skillranks.yml when available. */
    private int unlockLevel(SkillCatalog.Skill skill, SkillCatalog.Ability a) {
        if (a.rankKey() == null) return 0;
        List<Integer> ranks = plugin.mcmmo().unlockLevels(skill.name(), a.rankKey());
        if (ranks != null && !ranks.isEmpty()) return ranks.get(0);
        return plugin.mcmmo().retroMode() ? a.fallbackUnlock() * 10 : a.fallbackUnlock();
    }

    // ------------------------------------------------------------------ helpers

    private static Component bar(int xp, int need) {
        int filled = (int) Math.round(20.0 * Math.min(xp, need) / need);
        int percent = (int) Math.floor(100.0 * xp / need);
        return Component.text("|".repeat(filled), NamedTextColor.GREEN)
                .append(Component.text("|".repeat(20 - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + percent + "%", NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static List<String> wrap(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > WRAP) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static Component text(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(ItemStack item, Component name, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static void glint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }
}
