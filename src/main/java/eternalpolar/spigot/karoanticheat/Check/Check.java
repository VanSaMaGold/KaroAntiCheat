package eternalpolar.spigot.karoanticheat.Check;

import eternalpolar.spigot.karoanticheat.api.FlagEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import eternalpolar.spigot.karoanticheat.Utils.ActionbarUtils;

public abstract class Check {
    protected final String name;
    public final String version;
    protected final Map<UUID, Integer> vl = new ConcurrentHashMap<>();
    protected final int maxVl;
    protected final long autoReduceInterval;
    protected final int autoReduceAmount;
    protected final String punishmentCommand;
    protected final Map<UUID, Boolean> playerAlerts = new ConcurrentHashMap<>();
    protected final ProgressBar progressBar;
    public FileConfiguration config;
    protected final JavaPlugin plugin;
    protected final boolean lightningEnabled;
    protected final boolean lightningDamage;
    protected final boolean broadcastToAll;
    protected final boolean sendActionbarToPlayer;
    protected final String actionbarMessage;
    private static boolean supportsLightningEffect;
    private boolean isAutoReduceTaskRegistered = false;

    static {
        try {
            World.class.getMethod("strikeLightningEffect", Location.class);
            supportsLightningEffect = true;
        } catch (NoSuchMethodException e) {
            supportsLightningEffect = false;
        }
    }

    public Check(String name, String version, JavaPlugin plugin) {
        this.name = name;
        this.version = version;
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.maxVl = config.getInt("checks." + name + ".max-vl", 15);
        this.autoReduceInterval = config.getLong("checks." + name + ".auto-reduce.interval", 20000);
        this.autoReduceAmount = config.getInt("checks." + name + ".auto-reduce.amount", 1);
        this.punishmentCommand = config.getString("checks." + name + ".punishment-command", "msg %player% Potential cheat detected");
        this.progressBar = new ProgressBar(config);
        this.lightningEnabled = config.getBoolean("punishment-effects.lightning.enabled", true);
        this.lightningDamage = config.getBoolean("punishment-effects.lightning.damage", false);
        this.broadcastToAll = config.getBoolean("checks." + name + ".broadcast-to-all", false);
        this.sendActionbarToPlayer = config.getBoolean("checks." + name + ".send-actionbar-to-player", false);
        this.actionbarMessage = config.getString("checks." + name + ".actionbar-message", "&cCheat detected! VL: %current_vl%/%max_vl%");
        initializeDefaultAlertStates();
        startAutoReduceTask();
    }

    private void initializeDefaultAlertStates() {
        boolean defaultEnabled = plugin.getConfig().getBoolean("alerts.enabled", true);
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (player == null || !player.isOnline()) continue;
            if (hasAlertPermission(player)) {
                playerAlerts.putIfAbsent(player.getUniqueId(), defaultEnabled);
            } else {
                playerAlerts.putIfAbsent(player.getUniqueId(), false);
            }
        }
    }

    protected boolean hasAlertPermission(Player player) {
        if (player == null || !player.isOnline()) return false;
        return player.hasPermission("anticheat.alert") || player.isOp();
    }

    public abstract void check(Player player);

    public void addVl(Player player, int amount, String details) {
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        int current = vl.getOrDefault(uuid, 0) + amount;
        vl.put(uuid, current);

        FlagEvent flagEvent = new FlagEvent(player, this, current, details);
        Bukkit.getPluginManager().callEvent(flagEvent);
        if (flagEvent.isCancelled()) return;

        String progress = progressBar.generate(current, maxVl);
        sendAlert(player, current, progress, details);

        if (sendActionbarToPlayer) {
            sendPlayerActionbar(player, current, details);
        }

        if (current >= maxVl) {
            punish(player, details);
            resetVl(player);
        }
    }

    private void sendPlayerActionbar(Player player, int currentVl, String details) {
        String msg = actionbarMessage.replace("%player%", player.getName())
                .replace("%check%", name)
                .replace("%details%", details)
                .replace("%current_vl%", String.valueOf(currentVl))
                .replace("%max_vl%", String.valueOf(maxVl));
        ActionbarUtils.sendActionbar(player, translateColor(msg));
    }

    protected void removeVl(Player player, int amount) {
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        int current = Math.max(0, vl.getOrDefault(uuid, 0) - amount);
        vl.put(uuid, current);
    }

    protected void resetVl(Player player) {
        if (player == null || !player.isOnline()) return;
        vl.put(player.getUniqueId(), 0);
    }

    protected int getVl(Player player) {
        if (player == null || !player.isOnline()) return 0;
        return vl.getOrDefault(player.getUniqueId(), 0);
    }

    public void punish(Player player, String details) {
        if (player == null || !player.isOnline() || punishmentCommand == null || punishmentCommand.isEmpty()) return;

        String command = punishmentCommand
                .replace("%player%", player.getName())
                .replace("%check%", name)
                .replace("%details%", details);

        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            try {
                if (player.isOnline()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error executing punishment command: " + e.getMessage(), e);
            }
        }, 0L);

        String broadcastMsg = plugin.getConfig().getString("messages.punishment-broadcast",
                "%player% has been punished for %check% - %details%");
        broadcastMsg = broadcastMsg.replace("%player%", player.getName())
                .replace("%check%", name)
                .replace("%details%", details);

        String coloredBroadcast = translateColor(broadcastMsg);
        sendPunishmentBroadcast(coloredBroadcast);

        if (lightningEnabled) {
            spawnLightning(player.getLocation());
        }
    }

    private void spawnLightning(Location location) {
        if (location == null || location.getWorld() == null) return;

        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            World world = location.getWorld();
            try {
                if (lightningDamage) {
                    world.strikeLightning(location);
                } else {
                    if (supportsLightningEffect) {
                        world.strikeLightningEffect(location);
                    } else {
                        LightningStrike strike = world.strikeLightning(location);
                        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, strike::remove, 1L);
                    }
                }
            } catch (Exception e) {}
        }, 0L);
    }

    protected void sendAlert(Player player, int currentVl, String progress, String details) {
        if (player == null || !player.isOnline()) return;

        String message = plugin.getConfig().getString("messages.alert-message",
                "[Alert] %player% triggered check: %check% (%details%) - %current_vl%/%max_vl% %progress_bar%");
        message = message.replace("%player%", player.getName())
                .replace("%check%", name)
                .replace("%details%", details)
                .replace("%current_vl%", String.valueOf(currentVl))
                .replace("%max_vl%", String.valueOf(maxVl))
                .replace("%progress_bar%", progress)
                .replace("%separator%", plugin.getConfig().getString("progress-bar.separator", "/"));

        String coloredMessage = translateColor(message);
        sendToAlertRecipients(coloredMessage);
    }

    private void sendToAlertRecipients(String message) {
        for (Player recipient : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (recipient == null || !recipient.isOnline()) continue;
            if (hasAlertPermission(recipient) && hasAlertEnabled(recipient)) {
                recipient.sendMessage(message);
            }
        }
    }

    private void sendPunishmentBroadcast(String message) {
        if (broadcastToAll) {
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (player != null && player.isOnline()) {
                    player.sendMessage(message);
                }
            }
        } else {
            sendToAlertRecipients(message);
        }
    }

    private void startAutoReduceTask() {
        if (autoReduceInterval > 0 && !isAutoReduceTaskRegistered) {
            plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                    removeVl(player, autoReduceAmount);
                }
            }, 0L, autoReduceInterval / 50);
            isAutoReduceTaskRegistered = true;
        }
    }

    public void setAlertEnabled(Player player, boolean enabled) {
        if (player == null || !player.isOnline()) return;
        if (!hasAlertPermission(player)) return;
        playerAlerts.put(player.getUniqueId(), enabled);
    }

    public boolean hasAlertEnabled(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (!hasAlertPermission(player)) return false;
        return playerAlerts.getOrDefault(player.getUniqueId(), plugin.getConfig().getBoolean("alerts.enabled", true));
    }

    protected String translateColor(String message) {
        return message.replace("&", "§");
    }

    public String getName() {
        return name;
    }

    protected static String repeat(String str, int times) {
        if (times <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event.getPlayer() == null || !event.getPlayer().isOnline()) return;

        boolean defaultEnabled = plugin.getConfig().getBoolean("alerts.enabled", true);
        if (hasAlertPermission(event.getPlayer())) {
            playerAlerts.putIfAbsent(event.getPlayer().getUniqueId(), defaultEnabled);
        } else {
            playerAlerts.putIfAbsent(event.getPlayer().getUniqueId(), false);
        }
    }

    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer() == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        vl.remove(uuid);
        playerAlerts.remove(uuid);
    }

    public abstract boolean isExempt(Player player);
}