package eternalpolar.spigot.karoanticheat.Commands;

import eternalpolar.spigot.karoanticheat.Module.KillAura.KillAuraCheck;
import eternalpolar.spigot.karoanticheat.Module.KillAura.KillAuraModel;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CMD implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final List<String> subCommands = Arrays.asList("reload");
    private static final String PREFIX = ChatColor.GRAY + "[" + ChatColor.YELLOW + "Karo" + ChatColor.GRAY + "] " + ChatColor.RESET;
    private static final String BASE = ChatColor.WHITE + "";
    private static final String SUCCESS = ChatColor.GREEN + "";
    private static final String ERROR = ChatColor.RED + "";
    private static final String CMD = ChatColor.AQUA + "";

    public CMD(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private String formatMessage(String message) {
        return PREFIX + BASE + message;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                return handleReload(sender);
            default:
                sender.sendMessage(formatMessage(ERROR + "Unknown command. Use /karo for help."));
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("karo.reload") && !sender.isOp()) {
            sender.sendMessage(formatMessage(ERROR + "No permission."));
            return true;
        }

        try {
            long start = System.currentTimeMillis();
            plugin.reloadConfig();
            sender.sendMessage(formatMessage(SUCCESS + "Config reloaded. (" +
                    (System.currentTimeMillis() - start) + "ms)"));
        } catch (Exception e) {
            sender.sendMessage(formatMessage(ERROR + "Reload failed: " + e.getMessage()));
            plugin.getLogger().warning("Configuration reload error: " + e.getMessage());
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.GRAY + "——————————————");
        sender.sendMessage("");
        sender.sendMessage(formatMessage(CMD + "/karo reload " + BASE + "- Reload configuration"));
        sender.sendMessage("");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "——————————————");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            for (String cmd : subCommands) {
                if (cmd.startsWith(args[0].toLowerCase())) {
                    completions.add(cmd);
                }
            }
        }

        return completions;
    }

}
