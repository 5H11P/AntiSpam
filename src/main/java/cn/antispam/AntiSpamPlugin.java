package cn.antispam;

import cn.antispam.listener.ChatListener;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiSpamPlugin extends JavaPlugin {
    private ChatListener chatListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chatListener = new ChatListener(this);
        getServer().getPluginManager().registerEvents(chatListener, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("antispam")) {
            return false;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            String permission = getConfig().getString("commands.reload-permission", "antispam.reload");
            if (!sender.hasPermission(permission)) {
                sender.sendMessage(color(getMessage("no-permission")));
                return true;
            }

            reloadConfig();
            chatListener.reload();
            chatListener.clearStates();
            sender.sendMessage(color(getMessage("reload-success")));
            return true;
        }

        sender.sendMessage(color(getMessage("unknown-command")));
        return true;
    }

    public String getMessage(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String message = getConfig().getString("messages." + key, "");
        return message.replace("%prefix%", prefix);
    }

    public String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
