package io.github.cowmoo03.orewatch;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /orewatch <player>          stats and ratios for a player
 * /orewatch top [24h|7d|all]  most suspicious players
 * /orewatch finds <player>    a player's recent valuable finds, with teleport links
 * /orewatch tp <player> <n>   teleport to one of those finds
 * /orewatch notify            turn rare-find messages on/off for yourself
 * /orewatch reset <player>    clear a player's stats (admin)
 * /orewatch reload            reload config.yml (admin)
 */
public final class OreWatchCommand implements BasicCommand {
    private final OreWatchPlugin plugin;

    public OreWatchCommand(OreWatchPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            help(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "top" -> top(sender, args.length > 1 ? args[1] : "24h");
            case "finds" -> {
                if (args.length < 2) {
                    send(sender, "Usage: /orewatch finds <player>", NamedTextColor.RED);
                    return;
                }
                finds(sender, args[1]);
            }
            case "tp" -> {
                if (args.length < 3) {
                    send(sender, "Usage: /orewatch tp <player> <find number>  (see /orewatch finds <player>)", NamedTextColor.RED);
                    return;
                }
                teleport(sender, args[1], args[2]);
            }
            case "notify" -> {
                if (!(sender instanceof Player p)) {
                    send(sender, "Only players can toggle notifications.", NamedTextColor.RED);
                    return;
                }
                boolean on = plugin.stats().toggleNotify(p.getUniqueId());
                send(sender, "Rare-find messages are now " + (on ? "ON" : "OFF") + ".", NamedTextColor.GREEN);
            }
            case "reset" -> {
                if (!sender.hasPermission("orewatch.admin")) {
                    send(sender, "You don't have permission for that.", NamedTextColor.RED);
                    return;
                }
                if (args.length < 2) {
                    send(sender, "Usage: /orewatch reset <player>", NamedTextColor.RED);
                    return;
                }
                UUID id = resolve(args[1]);
                if (id != null && plugin.stats().reset(id)) {
                    plugin.stats().save();
                    send(sender, "Cleared OreWatch stats for " + args[1] + ".", NamedTextColor.GREEN);
                } else {
                    send(sender, "No stats for " + args[1] + ".", NamedTextColor.YELLOW);
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("orewatch.admin")) {
                    send(sender, "You don't have permission for that.", NamedTextColor.RED);
                    return;
                }
                plugin.reloadRules();
                send(sender, "Config reloaded.", NamedTextColor.GREEN);
            }
            case "help" -> help(sender);
            default -> showPlayer(sender, args[0]);
        }
    }

    private void help(CommandSender sender) {
        send(sender, "/orewatch <player> | top [24h|7d|all] | finds <player> | tp <player> <n> | notify"
                + (sender.hasPermission("orewatch.admin") ? " | reset <player> | reload" : ""), NamedTextColor.YELLOW);
    }

    private UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        UUID id = plugin.stats().findByName(name);
        if (id != null) return id;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private void showPlayer(CommandSender sender, String name) {
        UUID id = resolve(name);
        Stats.PlayerData d = id == null ? null : plugin.stats().get(id);
        if (d == null) {
            send(sender, "No mining stats for " + name + " yet.", NamedTextColor.YELLOW);
            return;
        }
        Counts day = d.recent(24);
        Counts week = d.recent(24 * plugin.rules().historyDays);
        sender.sendMessage(OreWatchPlugin.PREFIX.append(Component.text("Mining stats for " + d.name, NamedTextColor.GOLD)));
        for (Rules.Resource r : plugin.rules().resources.values()) {
            sender.sendMessage(Component.text(" " + r.displayName() + " ", NamedTextColor.WHITE)
                    .append(Component.text("(limit " + OreWatchPlugin.formatRate(r.maxPer1000()) + "/1000, min "
                            + r.minVeins() + " veins)", NamedTextColor.DARK_GRAY)));
            sender.sendMessage(line("   24h: ", day, r));
            sender.sendMessage(line("   " + plugin.rules().historyDays + "d:  ", week, r));
            sender.sendMessage(line("   all: ", d.lifetime, r));
        }
        Rules rules = plugin.rules();
        if (rules.breakthroughsEnabled) {
            sender.sendMessage(Component.text(" Cave breakthroughs ", NamedTextColor.WHITE)
                    .append(Component.text("(led to a find; limit " + Math.round(rules.maxProductivePercent)
                            + "%, min " + rules.minBreakthroughs + ")", NamedTextColor.DARK_GRAY)));
            sender.sendMessage(breakthroughLine("   24h: ", day));
            sender.sendMessage(breakthroughLine("   " + rules.historyDays + "d:  ", week));
            sender.sendMessage(breakthroughLine("   all: ", d.lifetime));
        }
        sender.sendMessage(Component.text(" [recent finds]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/orewatch finds " + d.name)));
    }

    private Component breakthroughLine(String label, Counts c) {
        Rules rules = plugin.rules();
        long total = c.breakthroughs();
        double pct = c.productivePercent();
        boolean flagged = total >= rules.minBreakthroughs && pct > rules.maxProductivePercent;
        TextColor color = flagged ? NamedTextColor.RED
                : total > 0 && pct > rules.maxProductivePercent * 0.7 ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
        return Component.text(label + c.productive() + " of " + total + " led to a find (" + Math.round(pct) + "%)"
                + (flagged ? "  ⚠" : ""), color);
    }

    /** "3 buried + 5 cave veins (31 ores) / 2,400 overworld = 1.3/1000"; the rate uses buried veins only. */
    private Component line(String label, Counts c, Rules.Resource r) {
        long buried = c.buried(r.id());
        long cave = c.cave(r.id());
        long ores = c.ores(r.id());
        long filler = c.filler(r.filler());
        double rate = Counts.rate(buried, filler);
        boolean flagged = buried >= r.minVeins() && rate > r.maxPer1000();
        TextColor color = flagged ? NamedTextColor.RED : buried > 0 && rate > r.maxPer1000() * 0.7 ? NamedTextColor.YELLOW
                : NamedTextColor.GRAY;
        return Component.text(label + buried + " buried + " + cave + " cave veins (" + ores + " ores) / "
                + String.format("%,d", filler) + " " + r.filler() + " = " + OreWatchPlugin.formatRate(rate) + "/1000"
                + (flagged ? "  ⚠" : ""), color);
    }

    private void finds(CommandSender sender, String name) {
        UUID id = resolve(name);
        Stats.PlayerData d = id == null ? null : plugin.stats().get(id);
        if (d == null || d.finds.isEmpty()) {
            send(sender, "No recent finds for " + name + ".", NamedTextColor.YELLOW);
            return;
        }
        sender.sendMessage(OreWatchPlugin.PREFIX.append(Component.text("Recent finds for " + d.name + " (newest first)",
                NamedTextColor.GOLD)));
        long now = System.currentTimeMillis();
        int n = 1;
        for (Stats.Find f : d.finds) {
            String text = " " + n + ". " + ago(now - f.time()) + " ago  " + Rules.pretty(f.resource()) + " (" + f.blocks()
                    + ", " + (f.buried() ? "buried" : "cave") + ")  " + f.world() + " " + f.x() + ", " + f.y() + ", " + f.z() + " ";
            sender.sendMessage(Component.text(text, f.buried() ? NamedTextColor.WHITE : NamedTextColor.GRAY)
                    .append(Component.text("[tp]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/orewatch tp " + d.name + " id:" + f.time()))));
            n++;
        }
        sender.sendMessage(Component.text(" Tip: go into spectator mode first to look at their tunnels.", NamedTextColor.DARK_GRAY));
    }

    private void teleport(CommandSender sender, String name, String which) {
        if (!(sender instanceof Player p)) {
            send(sender, "Only players can teleport.", NamedTextColor.RED);
            return;
        }
        UUID id = resolve(name);
        Stats.PlayerData d = id == null ? null : plugin.stats().get(id);
        if (d == null || d.finds.isEmpty()) {
            send(sender, "No recent finds for " + name + ".", NamedTextColor.YELLOW);
            return;
        }
        Stats.Find find = null;
        try {
            if (which.startsWith("id:")) {
                long time = Long.parseLong(which.substring(3));
                for (Stats.Find f : d.finds) if (f.time() == time) find = f;
            } else {
                int n = Integer.parseInt(which);
                if (n >= 1 && n <= d.finds.size()) find = d.finds.get(n - 1);
            }
        } catch (NumberFormatException ignored) {
            // handled below
        }
        if (find == null) {
            send(sender, "No such find. See /orewatch finds " + d.name, NamedTextColor.RED);
            return;
        }
        World world = Bukkit.getWorld(find.world());
        if (world == null) {
            send(sender, "World '" + find.world() + "' isn't loaded.", NamedTextColor.RED);
            return;
        }
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            sender.sendMessage(OreWatchPlugin.PREFIX
                    .append(Component.text("Switch to spectator first so you don't get stuck in the rock. ", NamedTextColor.YELLOW))
                    .append(Component.text("[go spectator]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/gamemode spectator"))));
            return;
        }
        Stats.Find target = find;
        Location to = new Location(world, find.x() + 0.5, find.y(), find.z() + 0.5, p.getLocation().getYaw(),
                p.getLocation().getPitch());
        p.teleportAsync(to).thenAccept(ok -> {
            if (ok) send(p, "Teleported to " + d.name + "'s " + Rules.pretty(target.resource()).toLowerCase() + " find ("
                    + ago(System.currentTimeMillis() - target.time()) + " ago).", NamedTextColor.GREEN);
        });
    }

    /** 90_000 -> "1m", 7_200_000 -> "2h", 3 days -> "3d" */
    private static String ago(long ms) {
        long m = Math.max(0, ms / 60_000);
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 48) return h + "h";
        return (h / 24) + "d";
    }

    /** Players ranked by how far over their limit they are, for the worst resource. */
    private void top(CommandSender sender, String period) {
        int hours = switch (period.toLowerCase(Locale.ROOT)) {
            case "7d", "week" -> 24 * plugin.rules().historyDays;
            case "all", "lifetime" -> -1;
            default -> 24;
        };
        record Row(String name, String resource, long veins, long filler, double rate, double score, boolean flagged) {}
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, Stats.PlayerData> e : plugin.stats().all().entrySet()) {
            Counts c = hours < 0 ? e.getValue().lifetime : e.getValue().recent(hours);
            Row worst = null;
            for (Rules.Resource r : plugin.rules().resources.values()) {
                long veins = c.buried(r.id());
                if (veins == 0) continue;
                long filler = c.filler(r.filler());
                double rate = Counts.rate(veins, filler);
                double score = Double.isInfinite(rate) ? 999 : rate / r.maxPer1000();
                Row row = new Row(e.getValue().name, r.displayName(), veins, filler, rate, score,
                        veins >= r.minVeins() && rate > r.maxPer1000());
                if (worst == null || row.score() > worst.score()) worst = row;
            }
            Rules rules = plugin.rules();
            long total = c.breakthroughs();
            if (rules.breakthroughsEnabled && total >= rules.minBreakthroughs) {
                double pct = c.productivePercent();
                double score = rules.maxProductivePercent <= 0 ? 999 : pct / rules.maxProductivePercent;
                Row row = new Row(e.getValue().name, "Breakthroughs", c.productive(), total, pct, score,
                        pct > rules.maxProductivePercent);
                if (worst == null || row.score() > worst.score()) worst = row;
            }
            if (worst != null) rows.add(worst);
        }
        rows.sort(Comparator.comparingDouble(Row::score).reversed());
        String label = hours < 0 ? "all time" : hours == 24 ? "last 24h" : "last " + plugin.rules().historyDays + " days";
        sender.sendMessage(OreWatchPlugin.PREFIX.append(Component.text("Most suspicious (" + label + ")", NamedTextColor.GOLD)));
        if (rows.isEmpty()) {
            sender.sendMessage(Component.text(" No finds recorded yet.", NamedTextColor.GRAY));
            return;
        }
        int i = 1;
        for (Row r : rows.subList(0, Math.min(10, rows.size()))) {
            TextColor color = r.flagged() ? NamedTextColor.RED : r.score() > 0.7 ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
            String detail = r.resource().equals("Breakthroughs")
                    ? r.veins() + " of " + r.filler() + " led to a find (" + Math.round(r.rate()) + "%)"
                    : r.veins() + " buried veins / " + String.format("%,d", r.filler()) + " = "
                            + OreWatchPlugin.formatRate(r.rate()) + "/1000";
            sender.sendMessage(Component.text(" " + i++ + ". " + r.name() + ": " + r.resource() + " " + detail
                    + " (" + Math.round(Math.min(r.score(), 99) * 100) + "% of limit)" + (r.flagged() ? " ⚠" : ""), color)
                    .clickEvent(ClickEvent.runCommand("/orewatch " + r.name())));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("top", "finds", "tp", "notify", "help"));
            if (source.getSender().hasPermission("orewatch.admin")) options.addAll(List.of("reset", "reload"));
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            options.addAll(List.of("24h", "7d", "all"));
        } else if (args.length == 2 && List.of("reset", "finds", "tp").contains(args[0].toLowerCase(Locale.ROOT))) {
            plugin.stats().all().values().forEach(d -> options.add(d.name));
        }
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).distinct().toList();
    }

    @Override
    public String permission() {
        return "orewatch.command";
    }

    private static void send(CommandSender to, String message, NamedTextColor color) {
        to.sendMessage(OreWatchPlugin.PREFIX.append(Component.text(message, color)));
    }
}
