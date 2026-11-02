package io.github.cowmoo03.toolshop;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * /toolshop                                  open the shop
 * /toolshop give <player> <timber|vein|excavator> [days]   admin: give a new upgraded tool
 * /toolshop reload                           admin: reload config.yml
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

        if (!sender.hasPermission("toolshop.admin")) {
            send(sender, "Usage: /toolshop", NamedTextColor.RED);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadSettings();
                send(sender, "Config reloaded.", NamedTextColor.GREEN);
            }
            case "give" -> give(sender, args);
            default -> send(sender, "Usage: /toolshop [give <player> <timber|vein|excavator> [days] | reload]", NamedTextColor.RED);
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "Usage: /toolshop give <player> <timber|vein|excavator> [days]", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "Player " + args[1] + " is not online.", NamedTextColor.RED);
            return;
        }
        ToolType type = ToolType.fromId(args[2]);
        if (type == null) {
            send(sender, "Tool must be timber, vein or excavator.", NamedTextColor.RED);
            return;
        }
        long durationMs = plugin.settings().durationMs;
        if (args.length >= 4) {
            try {
                double days = Double.parseDouble(args[3]);
                if (days <= 0 || days > 3650) throw new NumberFormatException();
                durationMs = Math.round(days * ToolData.DAY);
            } catch (NumberFormatException e) {
                send(sender, "Days must be a number above 0.", NamedTextColor.RED);
                return;
            }
        }

        ItemStack tool = ItemStack.of(type.material());
        plugin.toolData().activate(tool, type, System.currentTimeMillis() + durationMs);
        target.getInventory().addItem(tool).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));

        plugin.logPurchase(target, "ADMIN_GIVE by " + sender.getName(), type, 0, plugin.toolData().getId(tool));
        send(sender, "Gave " + target.getName() + " a " + type.displayName() + " for "
                + ToolData.formatDuration(durationMs) + ".", NamedTextColor.GREEN);
        if (target != sender) {
            send(target, "You received a " + type.displayName() + "!", NamedTextColor.GREEN);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission("toolshop.admin")) return List.of();
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("give", "reload"));
        } else if (args[0].equalsIgnoreCase("give") && args.length == 2) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (args[0].equalsIgnoreCase("give") && args.length == 3) {
            options.addAll(List.of("timber", "vein", "excavator"));
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
