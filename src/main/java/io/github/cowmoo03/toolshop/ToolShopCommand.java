package io.github.cowmoo03.toolshop;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * /toolshop                                        open the shop
 * /toolshop mode                                   switch your legendary pickaxe between Drill and Vein Mining
 * /toolshop give <player> <pickaxe|axe|shovel|hoe|sword|bow|trident|crossbow|elytra|helmet|chestplate|leggings|boots> [iron|diamond|netherite]   admin: give a new legendary tool
 * /toolshop give <player> flight [amount]          admin: give Flight Charms
 * /toolshop upgrade <player> <upgrade> <tier>      admin: set an upgrade on the player's held legendary tool
 * /toolshop reset <player> [pickaxe|axe|shovel|hoe|sword|bow|trident|crossbow|elytra|helmet|chestplate|leggings|boots|all]  admin: remove a player's legendary tools
 * /toolshop reload                                 admin: reload config.yml
 */
public final class ToolShopCommand implements BasicCommand {
    private final ToolShopPlugin plugin;

    public ToolShopCommand(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                send(sender, "Only players can open the shop.", NamedTextColor.RED);
                return;
            }
            ShopMenu menu = new ShopMenu(plugin);
            menu.render(player);
            player.openInventory(menu.getInventory());
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("mode")) {
            if (sender instanceof Player player) plugin.shopListener().toggleMode(player);
            return;
        }

        if (!sender.hasPermission("toolshop.admin")) {
            send(sender, "Usage: /toolshop [mode]", NamedTextColor.RED);
            return;
        }
        switch (sub) {
            case "reload" -> {
                plugin.reloadSettings();
                send(sender, "Config reloaded.", NamedTextColor.GREEN);
            }
            case "give" -> give(sender, args);
            case "upgrade" -> upgrade(sender, args);
            case "reset" -> reset(sender, args);
            default -> send(sender, "Usage: /toolshop [mode | give <player> <pickaxe|axe|shovel|hoe|sword|bow|trident|crossbow|elytra|helmet|chestplate|leggings|boots|flight> [material|amount]"
                    + " | upgrade <player> <upgrade> <tier> | reset <player> [kind|all] | reload]", NamedTextColor.RED);
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "Usage: /toolshop give <player> <pickaxe|axe|shovel|hoe|sword|bow|trident|crossbow|elytra|helmet|chestplate|leggings|boots|flight> [iron|diamond|netherite|amount]",
                    NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "Player " + args[1] + " is not online.", NamedTextColor.RED);
            return;
        }
        if (args[2].equalsIgnoreCase("flight")) {
            int amount = 1;
            try {
                if (args.length >= 4) amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                send(sender, "Amount must be a number from 1 to 64.", NamedTextColor.RED);
                return;
            }
            target.getInventory().addItem(plugin.flight().create(amount)).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
            plugin.getLogger().info("ADMIN_GIVE " + amount + " Flight Charm(s) to " + target.getName() + " by " + sender.getName());
            send(sender, "Gave " + target.getName() + " " + amount + " Flight Charm" + (amount == 1 ? "" : "s") + ".",
                    NamedTextColor.GREEN);
            return;
        }
        ToolKind kind = ToolKind.fromId(args[2]);
        if (kind == null) {
            send(sender, "Must be pickaxe, axe, shovel, hoe, sword, bow, trident, crossbow, elytra, helmet, chestplate, leggings, boots or flight.", NamedTextColor.RED);
            return;
        }
        String material = args.length >= 4 ? args[3].toUpperCase(Locale.ROOT) : "NETHERITE";
        Material mat = kind.materials().size() == 1 ? kind.materials().iterator().next()
                : Material.matchMaterial(material + "_" + kind.name());
        if (mat == null || !kind.materials().contains(mat)) {
            send(sender, "Material must be iron, diamond or netherite.", NamedTextColor.RED);
            return;
        }
        ItemStack tool = ItemStack.of(mat);
        String id = plugin.legendary().create(tool, target, kind);
        plugin.registry().setActive(target.getUniqueId(), kind, id);
        plugin.legendary().refresh(tool);
        target.getInventory().addItem(tool).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        plugin.log(target, "ADMIN_GIVE " + kind.id() + " (" + mat.name().toLowerCase() + ") by " + sender.getName(), id);
        send(sender, "Gave " + target.getName() + " a new " + kind.displayName() + ". Any older one is now retired.",
                NamedTextColor.GREEN);
    }

    private void upgrade(CommandSender sender, String[] args) {
        if (args.length < 4) {
            send(sender, "Usage: /toolshop upgrade <player> <upgrade> <tier>  (they must hold their legendary tool)",
                    NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "Player " + args[1] + " is not online.", NamedTextColor.RED);
            return;
        }
        Upgrade upgrade = Upgrade.fromId(args[2]);
        if (upgrade == null) {
            send(sender, "Unknown upgrade. Options: " + upgradeIds(), NamedTextColor.RED);
            return;
        }
        int tier;
        try {
            tier = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            tier = -1;
        }
        if (tier < 0 || tier > upgrade.maxTier()) {
            send(sender, "Tier must be 0 to " + upgrade.maxTier() + ".", NamedTextColor.RED);
            return;
        }
        ItemStack hand = target.getInventory().getItemInMainHand();
        Legendary lt = plugin.legendary();
        if (!lt.usableBy(hand, target)) {
            send(sender, target.getName() + " must be holding their own legendary tool.", NamedTextColor.RED);
            return;
        }
        if (!upgrade.appliesTo(lt.kind(hand), hand.getType())) {
            send(sender, upgrade.displayName() + " can't go on that tool.", NamedTextColor.RED);
            return;
        }
        lt.setTier(hand, upgrade, tier);
        if (upgrade == Upgrade.VEIN && tier > 0 && lt.tier(hand, Upgrade.DRILL) == 0) lt.setMode(hand, Legendary.MODE_VEIN);
        lt.refresh(hand);
        target.getInventory().setItemInMainHand(hand);
        plugin.log(target, "ADMIN_SET " + upgrade.id() + "=" + tier + " by " + sender.getName(), lt.id(hand));
        send(sender, "Set " + target.getName() + "'s " + upgrade.displayName() + " to tier " + tier + ".", NamedTextColor.GREEN);
    }

    /**
     * Retires a player's legendary tools (all, or one kind) so they can start over. Tools in their inventory, if
     * they're online, turn back into plain tools; copies anywhere else (chests, dropped) stop working.
     */
    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "Usage: /toolshop reset <player> [pickaxe|axe|shovel|hoe|sword|bow|trident|crossbow|elytra|helmet|chestplate|leggings|boots|all]", NamedTextColor.RED);
            return;
        }
        org.bukkit.OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            send(sender, "No player called " + args[1] + " has played here.", NamedTextColor.RED);
            return;
        }
        ToolKind kind = null;
        if (args.length >= 3 && !args[2].equalsIgnoreCase("all")) {
            kind = ToolKind.fromId(args[2]);
            if (kind == null) {
                send(sender, "Must be pickaxe, axe, shovel, hoe, sword, bow, trident, crossbow, elytra, helmet, chestplate, leggings, boots or all.", NamedTextColor.RED);
                return;
            }
        }
        java.util.List<String> removed = plugin.registry().clear(target.getUniqueId(), kind);

        int stripped = 0;
        Player online = target.getPlayer();
        if (online != null) {
            Legendary lt = plugin.legendary();
            ItemStack[] contents = online.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (!lt.isLegendary(item) || !online.getUniqueId().equals(lt.owner(item))) continue;
                if (kind != null && lt.kind(item) != kind) continue;
                lt.strip(item);
                online.getInventory().setItem(slot, item);
                stripped++;
            }
            if (stripped > 0 || !removed.isEmpty()) {
                send(online, "An admin reset your legendary " + (kind == null ? "tools" : kind.id())
                        + ". You can make a new one in /toolshop.", NamedTextColor.YELLOW);
            }
        }
        String who = target.getName() == null ? args[1] : target.getName();
        if (removed.isEmpty() && stripped == 0) {
            send(sender, who + " had no legendary " + (kind == null ? "tools" : kind.id()) + ".", NamedTextColor.YELLOW);
            return;
        }
        if (online != null) {
            plugin.log(online, "ADMIN_RESET " + (kind == null ? "all" : kind.id()) + " by " + sender.getName(),
                    String.join(",", removed));
        } else {
            plugin.getLogger().info("ADMIN_RESET " + who + " " + (kind == null ? "all" : kind.id())
                    + " by " + sender.getName() + " (" + String.join(",", removed) + ")");
        }
        send(sender, "Reset " + who + "'s legendary " + (kind == null ? "tools" : kind.id()) + ": "
                + removed.size() + " retired" + (online != null ? ", " + stripped + " turned back into plain tools" : "")
                + ". Copies anywhere else no longer work.", NamedTextColor.GREEN);
    }

    private static String upgradeIds() {
        return String.join(", ", Arrays.stream(Upgrade.values()).map(Upgrade::id).toList());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        boolean admin = source.getSender().hasPermission("toolshop.admin");
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.add("mode");
            if (admin) options.addAll(List.of("give", "upgrade", "reset", "reload"));
        } else if (admin && args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("upgrade")
                || args[0].equalsIgnoreCase("reset"))) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (admin && args.length == 3 && args[0].equalsIgnoreCase("give")) {
            options.addAll(List.of("pickaxe", "axe", "shovel", "hoe", "sword", "bow", "trident", "crossbow", "elytra", "helmet", "chestplate", "leggings", "boots", "flight"));
        } else if (admin && args.length == 4 && args[0].equalsIgnoreCase("give")) {
            options.addAll(args[2].equalsIgnoreCase("flight") ? List.of("1", "16", "64") : List.of("iron", "diamond", "netherite"));
        } else if (admin && args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            options.addAll(List.of("all", "pickaxe", "axe", "shovel", "hoe", "sword", "bow", "trident", "crossbow", "elytra", "helmet", "chestplate", "leggings", "boots"));
        } else if (admin && args.length == 3 && args[0].equalsIgnoreCase("upgrade")) {
            Arrays.stream(Upgrade.values()).forEach(u -> options.add(u.id()));
        } else if (admin && args.length == 4 && args[0].equalsIgnoreCase("upgrade")) {
            options.addAll(List.of("0", "1", "2", "3"));
        }
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    @Override
    public String permission() {
        return "toolshop.use";
    }

    private static void send(CommandSender to, String message, NamedTextColor color) {
        to.sendMessage(ToolShopPlugin.PREFIX.append(Component.text(message, color)));
    }
}
