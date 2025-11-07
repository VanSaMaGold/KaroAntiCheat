package eternalpolar.spigot.karoanticheat.Listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

public class PlayerOnGroundRecord implements Listener {
    private final Map<UUID, LinkedList<Location>> groundLocations = new HashMap<>();
    private final Map<UUID, Long> lastRecordTime = new HashMap<>();
    private final long recordInterval = 500;
    private final int maxRecords = 5;
    private final JavaPlugin plugin;
    private boolean isRegistered = false;

    public PlayerOnGroundRecord(JavaPlugin plugin) {
        this.plugin = plugin;
        registerListener();
    }

    public void registerListener() {
        if (!isRegistered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            isRegistered = true;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastRecordTime.getOrDefault(uuid, 0L) < recordInterval) {
            return;
        }

        if (player.isOnGround() && hasSignificantMovement(event.getFrom(), event.getTo())) {
            Location groundLoc = player.getLocation().clone();
            groundLoc.setPitch(player.getLocation().getPitch());
            groundLoc.setYaw(player.getLocation().getYaw());

            groundLocations.computeIfAbsent(uuid, k -> new LinkedList<>()).addFirst(groundLoc);

            LinkedList<Location> locations = groundLocations.get(uuid);
            while (locations.size() > maxRecords) {
                locations.removeLast();
            }

            lastRecordTime.put(uuid, currentTime);
        }
    }

    private boolean hasSignificantMovement(Location from, Location to) {
        if (from.getWorld() != to.getWorld()) {
            return true;
        }
        return Math.abs(from.getX() - to.getX()) > 0.1 ||
                Math.abs(from.getZ() - to.getZ()) > 0.1 ||
                Math.abs(from.getY() - to.getY()) > 0.3;
    }

    public Location getLastGroundLocation(Player player) {
        if (player == null) return null;

        LinkedList<Location> locations = groundLocations.get(player.getUniqueId());
        if (locations == null || locations.isEmpty()) return null;

        for (Location loc : locations) {
            if (isValidLocation(loc)) {
                return loc;
            }
        }

        groundLocations.remove(player.getUniqueId());
        return null;
    }
    public void teleportToLastGround(Player player, Runnable callback) {
        if (player == null) {
            if (callback != null) callback.run();
            return;
        }

        Location targetLoc = getLastGroundLocation(player);
        Location finalLoc;

        if (targetLoc == null) {
            finalLoc = player.getWorld().getHighestBlockAt(player.getLocation()).getLocation().add(0, 1, 0);
        } else {
            finalLoc = targetLoc;
        }
        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            boolean success = false;
            try {
                success = player.teleport(finalLoc);
            } catch (Exception e) {
                plugin.getLogger().warning("传送玩家时出错: " + e.getMessage());
            } finally {
                if (callback != null) callback.run();
            }
        }, 0L);
    }

    private boolean isValidLocation(Location loc) {
        return loc != null &&
                loc.getWorld() != null &&
                loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4) &&
                loc.getBlock().getType().isSolid();
    }

    public void clearRecord(Player player) {
        if (player != null) {
            groundLocations.remove(player.getUniqueId());
            lastRecordTime.remove(player.getUniqueId());
        }
    }

    public void clearAllRecords() {
        groundLocations.clear();
        lastRecordTime.clear();
    }
}
