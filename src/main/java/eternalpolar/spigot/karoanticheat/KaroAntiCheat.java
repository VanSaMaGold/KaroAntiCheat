package eternalpolar.spigot.karoanticheat;

import eternalpolar.spigot.karoanticheat.Check.Check;
import eternalpolar.spigot.karoanticheat.Commands.CMD;
import eternalpolar.spigot.karoanticheat.Listener.PlayerOnGroundRecord;
import eternalpolar.spigot.karoanticheat.Module.KillAura.KillAuraCheck;
import eternalpolar.spigot.karoanticheat.Utils.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class KaroAntiCheat extends JavaPlugin {
    private static KaroAntiCheat instance;
    private PlayerOnGroundRecord groundRecord;

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();
        saveDefaultConfig();

        groundRecord = new PlayerOnGroundRecord(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(groundRecord, this);
        Objects.requireNonNull(getCommand("karo")).setExecutor(new CMD(this));
        Objects.requireNonNull(getCommand("karo")).setTabCompleter(new CMD(this));
        new KillAuraCheck("1.0", this);
        initializeMetrics();
        logStartupInfo(startTime);
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    private void logStartupInfo(long startTime) {
        long startupTime = System.currentTimeMillis() - startTime;
        sendColoredMessage("");
        sendColoredMessage("&eKaro &7enabled &f| &aVersion: " + getDescription().getVersion());
        sendColoredMessage("&fAuthor: &bEternal Polar");
        sendColoredMessage("&fServer: " + getServer().getName() + " " + getServer().getVersion());
        sendColoredMessage("&fLoad time: " + startupTime + "ms");
        sendColoredMessage("");
        sendColoredMessage("&fQQ: &b2047752264 &7| &aWeChat: &bdll764");
        sendColoredMessage("");
    }

    private void sendColoredMessage(String message) {
        Bukkit.getConsoleSender().sendMessage(
                ChatColor.translateAlternateColorCodes('&',
                        "&7[&eKaro&7] &r" + message)
        );
    }

    private void initializeMetrics() {
        try {
            int pluginId = 27503;
            new Metrics(this, pluginId);
        } catch (Exception e) {}
    }

    public static KaroAntiCheat getInstance() {
        return instance;
    }

    public PlayerOnGroundRecord getGroundRecord() {
        return groundRecord;
    }
}
