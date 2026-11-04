package io.github.cowmoo03.toolshop;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** /skills [skill]: opens the skills window, or one skill's page. */
public final class SkillsCommand implements BasicCommand {
    private final ToolShopPlugin plugin;

    public SkillsCommand(ToolShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players can open the skills window.");
            return;
        }
        if (!plugin.settings().skillsMenuEnabled || !plugin.mcmmo().available()) {
            player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Skills aren't available right now.", NamedTextColor.RED)));
            return;
        }
        String skill = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : null;
        if (skill != null && !SkillCatalog.SKILLS.containsKey(skill)) {
            player.sendMessage(ToolShopPlugin.PREFIX.append(Component.text("Unknown skill: " + args[0], NamedTextColor.RED)));
            return;
        }
        plugin.skills().open(player, skill);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) return List.of();
        String last = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return SkillCatalog.SKILLS.keySet().stream().filter(k -> k.startsWith(last)).toList();
    }
}
