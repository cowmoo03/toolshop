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
 * /toolshop give <player> <pickaxe|axe|shovel> [iron|diamond|netherite]   admin: give a new legendary tool
 * /toolshop upgrade <player> <upgrade> <tier>      admin: set an upgrade on the player's held legendary tool
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
            default -> send(sender, "Usage: /toolshop [mode | give <player> <pickaxe|axe|shovel> [material]"
                    + " | upgrade <player> <upgrade> <tier> | reload]", NamedTextColor.RED);
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "Usage: /toolshop give <player> <pickaxe|axe|shovel> [iron|diamond|netherite]", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "Player " + args[1] + " is not online.", NamedTextColor.RED);
            return;
        }
        ToolKind kind = ToolKind.fromId(args[2]);
        if (kind == null) {
            send(sender, "Tool must be pickaxe, axe or shovel.", NamedTextColor.RED);
            return;
        }
        String material = args.length >= 4 ? args[3].toUpperCase(Locale.ROOT) : "NETHERITE";
        Material mat = Material.matchMaterial(material + "_" + kind.name());
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
            if (admin) options.addAll(List.of("give", "upgrade", "reload"));
        } else if (admin && args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("upgrade"))) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (admin && args.length == 3 && args[0].equalsIgnoreCase("give")) {
            options.addAll(List.of("pickaxe", "axe", "shovel"));
        } else if (admin && args.length == 4 && args[0].equalsIgnoreCase("give")) {
            options.addAll(List.of("iron", "diamond", "netherite"));
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
