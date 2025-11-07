package eternalpolar.spigot.karoanticheat.Module.KillAura;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import eternalpolar.spigot.karoanticheat.Check.Check;
import eternalpolar.spigot.karoanticheat.Module.KillAura.Module.KillAuraModuleA;
import eternalpolar.spigot.karoanticheat.Module.KillAura.Module.KillAuraModuleB;
import eternalpolar.spigot.karoanticheat.Module.KillAura.Module.KillAuraModuleC;
import eternalpolar.spigot.karoanticheat.Module.KillAura.Module.KillAuraModuleD;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class KillAuraCheck extends Check {
    private final KillAuraModuleA moduleA = new KillAuraModuleA(this);
    private final KillAuraModuleB moduleB = new KillAuraModuleB(this);
    private final KillAuraModuleC moduleC = new KillAuraModuleC(this);
    private final KillAuraModuleD moduleD = new KillAuraModuleD(this);
    private final KillAuraModel machineLearningModel;
    private PacketAdapter packetListener;
    private final ReentrantLock entityLock = new ReentrantLock();
    private final Map<Integer, EntityData> entityCache = new ConcurrentHashMap<>();
    private long lastCacheCleanup = System.currentTimeMillis();
    private Method worldGetEntityMethod;
    private boolean supportsWorldGetEntity;
    private final long CACHE_EXPIRY = 5000;
    private final int MAX_ATTACK_HISTORY = 100;
    private final double ML_CONFIDENCE_THRESHOLD = 0.75;
    private final Map<UUID, Integer> consecutiveAnomalies = new ConcurrentHashMap<>();
    private final int MIN_CONSECUTIVE_ANOMALIES = 2;
    private BukkitTask cacheCleanupTask;
    private BukkitTask playerCleanupTask;
    private final Map<UUID, Map<Integer, Long>> targetLastVisibleTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombatStart = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> combatComboCount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastViolationTime = new ConcurrentHashMap<>();
    private final int VIOLATION_COOLDOWN = 1500;
    private final int MAX_COMBO_COUNT = 15;
    private final long COMBO_RESET_TIME = 3000;

    protected final Map<UUID, List<AttackData>> attackHistory = new ConcurrentHashMap<>();
    protected final Map<UUID, Map<Integer, Long>> targetTracking = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> lastAttackTime = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> lastTeleportTime = new ConcurrentHashMap<>();
    protected boolean isEnabled;
    private boolean isPluginEnabled;

    public KillAuraCheck(String version, JavaPlugin plugin) {
        super("killaura", version, plugin);
        this.isPluginEnabled = plugin.isEnabled();

        try {
            worldGetEntityMethod = World.class.getMethod("getEntity", int.class);
            supportsWorldGetEntity = true;
        } catch (NoSuchMethodException e) {
            supportsWorldGetEntity = false;
            worldGetEntityMethod = null;
        }

        createConfigFile();
        this.machineLearningModel = new KillAuraModel(
                config.getDouble("ml.anomaly-threshold", 2.2),
                config.getInt("ml.window-size", 50),
                config.getInt("ml.min-samples", 30)
        );

        loadConfig();
        isEnabled = config.getBoolean("enabled", true);
        registerPacketListener();
        startCacheCleanupTask();
        startPlayerCleanupTask();
    }

    private void createConfigFile() {
        File checksFolder = new File(plugin.getDataFolder(), "checks");
        if (!checksFolder.exists()) {
            checksFolder.mkdirs();
        }

        File configFile = new File(checksFolder, "killaura.yml");
        if (!configFile.exists()) {
            try {
                configFile.createNewFile();
                saveDefaultConfig(configFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            this.config = YamlConfiguration.loadConfiguration(configFile);
        } catch (Exception e) {
            e.printStackTrace();
            this.config = new YamlConfiguration();
        }
    }

    private void saveDefaultConfig(File configFile) {
        YamlConfiguration yamlConfig = new YamlConfiguration();
        yamlConfig.set("ml.anomaly-threshold", 2.2);
        yamlConfig.set("ml.window-size", 50);
        yamlConfig.set("ml.min-samples", 30);

        yamlConfig.set("modules.A.enabled", true);
        yamlConfig.set("modules.A.max-attack-rate", 150);
        yamlConfig.set("modules.A.rotation-threshold", 30.0);
        yamlConfig.set("modules.A.backtrack-tolerance", 150);
        yamlConfig.set("modules.A.minimum-attacks", 5);
        yamlConfig.set("modules.A.sprint-attack-threshold", 2.5);
        yamlConfig.set("modules.A.attack-sequence-threshold", 3);

        yamlConfig.set("modules.B.enabled", true);
        yamlConfig.set("modules.B.max-visible-targets", 3);
        yamlConfig.set("modules.B.auto-aim-threshold", 0.15);
        yamlConfig.set("modules.B.tracking-consistency", 0.85);
        yamlConfig.set("modules.B.minimum-attacks", 5);
        yamlConfig.set("modules.B.visibility-check-distance", 12.0);
        yamlConfig.set("modules.B.entity-update-threshold", 500);

        yamlConfig.set("modules.C.enabled", true);
        yamlConfig.set("modules.C.prediction-accuracy-threshold", 0.92);
        yamlConfig.set("modules.C.movement-variance-threshold", 15);
        yamlConfig.set("modules.C.combat-intensity-threshold", 1200);
        yamlConfig.set("modules.C.aim-stability-threshold", 5);
        yamlConfig.set("modules.C.player-speed-multiplier", 1.0);
        yamlConfig.set("modules.C.target-speed-multiplier", 1.0);
        yamlConfig.set("modules.C.prediction-window-size", 5);

        yamlConfig.set("modules.D.enabled", true);
        yamlConfig.set("modules.D.prediction-accuracy-threshold", 0.85);
        yamlConfig.set("modules.D.reaction-time-threshold", 80);
        yamlConfig.set("modules.D.strafe-attack-consistency", 0.8);
        yamlConfig.set("modules.D.minimum-attacks", 6);
        yamlConfig.set("modules.D.prediction-window-multiplier", 1.2);
        yamlConfig.set("modules.D.strafe-accuracy-threshold", 0.85);
        yamlConfig.set("modules.D.reaction-time-sample-size", 10);

        try {
            yamlConfig.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        moduleA.loadConfig();
        moduleB.loadConfig();
        moduleC.loadConfig();
        moduleD.loadConfig();
    }

    private void registerPacketListener() {
        if (!isPluginEnabled || packetListener != null || !isEnabled) return;

        packetListener = new PacketAdapter(plugin,
                PacketType.Play.Client.USE_ENTITY,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK) {

            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!isPluginEnabled || !isEnabled || !plugin.isEnabled()) return;

                Player player = event.getPlayer();
                if (player == null || isExempt(player)) {
                    return;
                }

                if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
                    handleAttackPacket(event, player);
                }
                else if (event.getPacketType() == PacketType.Play.Client.POSITION ||
                        event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
                    updateEntityCacheAsync(player);
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(packetListener);
    }

    private void handleAttackPacket(PacketEvent event, Player player) {
        try {
            if (!isPluginEnabled || !isEnabled || !plugin.isEnabled()) return;

            int entityId = event.getPacket().getIntegers().read(0);
            UUID uuid = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!isPluginEnabled || !plugin.isEnabled() || !player.isOnline()) {
                        return;
                    }

                    LivingEntity target = getTargetEntity(player, entityId);
                    if (target == null) return;

                    updateTargetVisibility(player, target, currentTime);
                    recordAttack(player, target, currentTime);
                    updateCombatCombo(player, currentTime);

                    long lastViolation = lastViolationTime.getOrDefault(uuid, 0L);
                    if (currentTime - lastViolation > VIOLATION_COOLDOWN) {
                        String violationDetails = getViolationDetails(player);
                        boolean isViolation = performCheck(player);
                        if (isViolation) {
                            handleViolation(player, violationDetails);
                            lastViolationTime.put(uuid, currentTime);
                        } else {
                            consecutiveAnomalies.put(uuid, 0);
                        }
                    }
                }
            }.runTask(plugin);

        } catch (Exception e) {
            if (plugin.isEnabled()) {
                e.printStackTrace();
            }
        }
    }

    private String getViolationDetails(Player player) {
        UUID uuid = player.getUniqueId();
        List<AttackData> attacks = attackHistory.getOrDefault(uuid, Collections.emptyList());
        if (attacks.isEmpty()) return "Suspicious attack pattern";

        AttackData lastAttack = attacks.get(attacks.size() - 1);
        return String.format("Attack interval: %dms, Yaw diff: %.1f°, Distance: %.2fm",
                lastAttack.getAttackDelay(), lastAttack.getYawDifference(), lastAttack.getDistance());
    }

    private void updateTargetVisibility(Player player, LivingEntity target, long currentTime) {
        UUID uuid = player.getUniqueId();
        int targetId = target.getEntityId();

        targetLastVisibleTime.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .put(targetId, currentTime);
    }

    private void updateCombatCombo(Player player, long currentTime) {
        UUID uuid = player.getUniqueId();

        Long lastStart = lastCombatStart.get(uuid);
        if (lastStart == null || currentTime - lastStart > COMBO_RESET_TIME) {
            lastCombatStart.put(uuid, currentTime);
            combatComboCount.put(uuid, 1);
        } else {
            int combo = combatComboCount.getOrDefault(uuid, 0) + 1;
            combatComboCount.put(uuid, Math.min(combo, MAX_COMBO_COUNT));
        }
    }

    private boolean performCheck(Player player) {
        if (!isEnabled || isExempt(player)) return false;

        UUID uuid = player.getUniqueId();
        List<AttackData> attacks = attackHistory.getOrDefault(uuid, null);

        if (attacks == null || attacks.size() < 8) return false;

        int violationModules = 0;
        int enabledModules = 0;
        double weightedViolationScore = 0.0;

        if (moduleA.isEnabled()) {
            enabledModules++;
            if (moduleA.check(player)) {
                violationModules++;
                weightedViolationScore += 1.0;
            }
        }

        if (moduleB.isEnabled()) {
            enabledModules++;
            if (moduleB.check(player)) {
                violationModules++;
                weightedViolationScore += 1.2;
            }
        }

        if (moduleC.isEnabled()) {
            enabledModules++;
            if (moduleC.check(player)) {
                violationModules++;
                weightedViolationScore += 1.1;
            }
        }

        if (moduleD.isEnabled()) {
            enabledModules++;
            if (moduleD.check(player)) {
                violationModules++;
                weightedViolationScore += 1.3;
            }
        }

        boolean mlAnomaly = false;
        if (machineLearningModel.hasEnoughData(player)) {
            enabledModules += 2;
            mlAnomaly = machineLearningModel.isAnomaly(player);
            if (mlAnomaly) {
                violationModules += 2;
                weightedViolationScore += 2.0;
            }
        }

        boolean moduleViolation = enabledModules > 0 &&
                (double) violationModules / enabledModules >= 0.5 &&
                weightedViolationScore >= 2.0;

        if (mlAnomaly) {
            int count = consecutiveAnomalies.getOrDefault(uuid, 0) + 1;
            consecutiveAnomalies.put(uuid, count);
            if (count >= MIN_CONSECUTIVE_ANOMALIES) {
                return true;
            }
        }

        return moduleViolation;
    }

    public List<Long> getAttackIntervals(UUID playerId) {
        List<AttackData> attacks = attackHistory.getOrDefault(playerId, Collections.emptyList());
        List<Long> intervals = new ArrayList<>(attacks.size() - 1);

        for (int i = 1; i < attacks.size(); i++) {
            long interval = attacks.get(i).getTimestamp() - attacks.get(i-1).getTimestamp();
            intervals.add(interval);
        }

        return intervals;
    }

    private void updateEntityCacheAsync(Player player) {
        if (!isPluginEnabled || !isEnabled || !plugin.isEnabled()) return;

        UUID uuid = player.getUniqueId();
        Location playerLoc = player.getLocation();
        World world = player.getWorld();
        int worldId = world.hashCode();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isPluginEnabled || !plugin.isEnabled() || !player.isOnline() || !player.getWorld().equals(world)) {
                    return;
                }

                List<Entity> nearbyEntities = player.getNearbyEntities(10, 5, 10);
                entityLock.lock();
                try {
                    for (Entity entity : nearbyEntities) {
                        entityCache.put(entity.getEntityId(), new EntityData(
                                entity.getEntityId(),
                                entity.getType(),
                                entity.getLocation().clone(),
                                worldId,
                                System.currentTimeMillis()
                        ));
                    }
                } finally {
                    entityLock.unlock();
                }
            }
        }.runTask(plugin);
    }

    public LivingEntity getTargetEntity(Player player, int entityId) {
        EntityData cachedEntity = getCachedEntity(entityId, player.getWorld());
        if (cachedEntity != null && cachedEntity.isLivingEntity()) {
            Entity entity = getEntity(player.getWorld(), entityId);
            if (entity instanceof LivingEntity) {
                return (LivingEntity) entity;
            }
        }

        return getFallbackTargetEntity(player, entityId);
    }

    private EntityData getCachedEntity(int entityId, World world) {
        entityLock.lock();
        try {
            EntityData data = entityCache.get(entityId);
            if (data != null && data.worldId == world.hashCode() &&
                    System.currentTimeMillis() - data.timestamp < CACHE_EXPIRY) {
                return data;
            }
            return null;
        } finally {
            entityLock.unlock();
        }
    }

    private LivingEntity getFallbackTargetEntity(Player player, int entityId) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Must get entity on main thread");
        }

        World world = player.getWorld();
        try {
            Entity targetEntity = getEntity(world, entityId);
            if (targetEntity instanceof LivingEntity) {
                return (LivingEntity) targetEntity;
            }

            List<Entity> nearbyEntities = player.getNearbyEntities(10, 5, 10);
            for (Entity nearbyEntity : nearbyEntities) {
                if (nearbyEntity.getEntityId() == entityId && nearbyEntity instanceof LivingEntity) {
                    return (LivingEntity) nearbyEntity;
                }
            }
        } catch (Exception e) {
            if (plugin.isEnabled()) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private Entity getEntity(World world, int entityId) {
        try {
            if (supportsWorldGetEntity && worldGetEntityMethod != null) {
                return (Entity) worldGetEntityMethod.invoke(world, entityId);
            } else {
                for (Entity entity : world.getEntities()) {
                    if (entity.getEntityId() == entityId) {
                        return entity;
                    }
                }
                return null;
            }
        } catch (Exception e) {
            for (Entity entity : world.getEntities()) {
                if (entity.getEntityId() == entityId) {
                    return entity;
                }
            }
            return null;
        }
    }

    private void startCacheCleanupTask() {
        if (!isPluginEnabled || !isEnabled || cacheCleanupTask != null) return;

        cacheCleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!isPluginEnabled || !plugin.isEnabled()) {
                cancelCleanupTasks();
                return;
            }

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCacheCleanup > 10000) {
                entityLock.lock();
                try {
                    Iterator<Map.Entry<Integer, EntityData>> iterator = entityCache.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<Integer, EntityData> entry = iterator.next();
                        if (currentTime - entry.getValue().timestamp > CACHE_EXPIRY) {
                            iterator.remove();
                        }
                    }
                    lastCacheCleanup = currentTime;
                } finally {
                    entityLock.unlock();
                }
            }
        }, 200L, 200L);
    }

    private void startPlayerCleanupTask() {
        if (!isPluginEnabled || !isEnabled || playerCleanupTask != null) return;

        playerCleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!isPluginEnabled || !plugin.isEnabled()) {
                cancelCleanupTasks();
                return;
            }

            long currentTime = System.currentTimeMillis();
            long inactiveThreshold = 5 * 60 * 1000;

            Iterator<Map.Entry<UUID, List<AttackData>>> attackIterator = attackHistory.entrySet().iterator();
            while (attackIterator.hasNext()) {
                Map.Entry<UUID, List<AttackData>> entry = attackIterator.next();
                UUID uuid = entry.getKey();
                Long lastAttack = lastAttackTime.get(uuid);

                if (lastAttack == null || currentTime - lastAttack > inactiveThreshold) {
                    attackIterator.remove();
                    lastAttackTime.remove(uuid);
                    targetTracking.remove(uuid);
                    targetLastVisibleTime.remove(uuid);
                    lastCombatStart.remove(uuid);
                    combatComboCount.remove(uuid);
                    moduleC.cleanupPlayerData(uuid);
                    machineLearningModel.resetPlayerProfileByUUID(uuid);
                    consecutiveAnomalies.remove(uuid);
                }
            }
        }, 6000L, 6000L);
    }

    private void cancelCleanupTasks() {
        if (cacheCleanupTask != null) {
            cacheCleanupTask.cancel();
            cacheCleanupTask = null;
        }
        if (playerCleanupTask != null) {
            playerCleanupTask.cancel();
            playerCleanupTask = null;
        }
    }

    private void recordAttack(Player player, LivingEntity target, long timestamp) {
        UUID uuid = player.getUniqueId();
        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();

        float yawDiff = calculateYawDifference(player, target);
        float pitchDiff = calculatePitchDifference(player, target);
        double distance = playerLoc.distance(targetLoc);
        boolean visible = isEntityVisible(player, target);
        long attackDelay = calculateAttackDelay(player);

        int combo = combatComboCount.getOrDefault(uuid, 1);
        double comboFactor = Math.min(1.0 + (combo - 1) * 0.05, 1.5);

        AttackData attack = new AttackData(
                target.getEntityId(),
                timestamp,
                yawDiff,
                pitchDiff,
                distance,
                visible,
                player.isSprinting(),
                player.getVelocity().length(),
                attackDelay,
                combo,
                comboFactor
        );

        attackHistory.computeIfAbsent(uuid, k -> new ArrayList<>(MAX_ATTACK_HISTORY)).add(attack);
        trimAttackHistory(uuid);

        targetTracking.computeIfAbsent(uuid, k -> new HashMap<>())
                .put(target.getEntityId(), timestamp);

        lastAttackTime.put(uuid, timestamp);

        if (isPluginEnabled && isEnabled && plugin.isEnabled()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!isPluginEnabled || !plugin.isEnabled()) return;
                    machineLearningModel.updateModel(player, attackHistory.getOrDefault(uuid, Collections.emptyList()));
                }
            }.runTaskAsynchronously(plugin);
        }
    }

    private long calculateAttackDelay(Player player) {
        UUID uuid = player.getUniqueId();
        Long lastAttack = lastAttackTime.get(uuid);
        if (lastAttack == null) return 0;
        return System.currentTimeMillis() - lastAttack;
    }

    private void trimAttackHistory(UUID uuid) {
        List<AttackData> attacks = attackHistory.get(uuid);
        if (attacks != null && attacks.size() > MAX_ATTACK_HISTORY) {
            attacks.subList(0, attacks.size() - MAX_ATTACK_HISTORY).clear();
        }
    }

    @Override
    public void check(Player player) {
        if (!isEnabled || isExempt(player) || !isPluginEnabled || !plugin.isEnabled()) return;

        UUID uuid = player.getUniqueId();
        List<AttackData> attacks = attackHistory.getOrDefault(uuid, null);

        if (attacks == null || attacks.size() < 8) return;

        String violationDetails = getViolationDetails(player);
        boolean isViolation = performCheck(player);
        if (isViolation) {
            long currentTime = System.currentTimeMillis();
            long lastViolation = lastViolationTime.getOrDefault(uuid, 0L);

            if (currentTime - lastViolation > VIOLATION_COOLDOWN) {
                handleViolation(player, violationDetails);
                lastViolationTime.put(uuid, currentTime);
            }
        } else {
            consecutiveAnomalies.put(uuid, 0);
        }
    }

    private void handleViolation(Player player, String details) {
        addVl(player, 1, details);
    }

    public boolean isEntityVisible(Player player, LivingEntity entity) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Visibility check must be on main thread");
        }

        Location eyeLoc = player.getEyeLocation();
        Location targetLoc = entity.getEyeLocation();

        if (eyeLoc.distance(targetLoc) < 1.5) {
            return true;
        }

        Vector direction = targetLoc.toVector().subtract(eyeLoc.toVector()).normalize();
        org.bukkit.util.BlockIterator iterator = new org.bukkit.util.BlockIterator(
                player.getWorld(), eyeLoc.toVector(), direction, 0,
                (int) eyeLoc.distance(targetLoc) + 1
        );

        while (iterator.hasNext()) {
            org.bukkit.block.Block block = iterator.next();
            if (block.getType().isSolid() && !isTransparent(block.getType())) {
                return false;
            }
        }
        return true;
    }

    private boolean isTransparent(org.bukkit.Material material) {
        String name = material.name();
        return name.contains("GLASS") || name.contains("AIR") ||
                name.contains("WATER") || name.contains("LAVA") ||
                name.contains("PORTAL") || name.contains("LEAF") ||
                name.contains("TORCH") || name.contains("SIGN") ||
                name.contains("CARPET") || name.contains("PLATE") ||
                name.contains("FLOWER") || name.contains("GRASS") ||
                name.contains("VINE") || name.contains("FENCE");
    }

    private float calculateYawDifference(Player player, LivingEntity target) {
        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();

        double dx = targetLoc.getX() - playerLoc.getX();
        double dz = targetLoc.getZ() - playerLoc.getZ();

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        if (yaw < 0) yaw += 360;

        float playerYaw = playerLoc.getYaw() % 360;
        if (playerYaw < 0) playerYaw += 360;

        float diff = Math.abs(yaw - playerYaw);
        return Math.min(diff, 360 - diff);
    }

    private float calculatePitchDifference(Player player, LivingEntity target) {
        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();

        double dx = targetLoc.getX() - playerLoc.getX();
        double dy = targetLoc.getY() + target.getEyeHeight() - playerLoc.getY() - player.getEyeHeight();
        double dz = targetLoc.getZ() - playerLoc.getZ();

        double distance = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distance));

        float playerPitch = playerLoc.getPitch() % 360;
        if (playerPitch < 0) playerPitch += 360;

        return Math.abs(pitch - playerPitch);
    }

    @Override
    public boolean isExempt(Player player) {
        UUID uuid = player.getUniqueId();
        if (player.hasPermission("karoanticheat.exempt.killaura") ||
                player.isOp() || player.getGameMode().equals(org.bukkit.GameMode.CREATIVE) ||
                player.getGameMode().equals(org.bukkit.GameMode.SPECTATOR)) {
            return true;
        }

        long lastTeleport = lastTeleportTime.getOrDefault(uuid, 0L);
        return System.currentTimeMillis() - lastTeleport < 3000;
    }

    @org.bukkit.event.EventHandler
    public void onPlayerTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!isPluginEnabled || !plugin.isEnabled()) return;

        Player player = event.getPlayer();
        lastTeleportTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @org.bukkit.event.EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (!isPluginEnabled || !plugin.isEnabled()) return;

        UUID uuid = event.getPlayer().getUniqueId();
        cleanupPlayerData(uuid);
    }

    private void cleanupPlayerData(UUID uuid) {
        attackHistory.remove(uuid);
        targetTracking.remove(uuid);
        lastAttackTime.remove(uuid);
        lastTeleportTime.remove(uuid);
        lastViolationTime.remove(uuid);
        targetLastVisibleTime.remove(uuid);
        lastCombatStart.remove(uuid);
        combatComboCount.remove(uuid);
        moduleC.cleanupPlayerData(uuid);
        machineLearningModel.resetPlayerProfileByUUID(uuid);
        consecutiveAnomalies.remove(uuid);
    }

    public void unregister() {
        isPluginEnabled = false;

        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
            packetListener = null;
        }

        entityCache.clear();
        cancelCleanupTasks();
        plugin.getServer().getScheduler().cancelTasks(plugin);
    }

    public void reloadConfig() {
        if (!isPluginEnabled || !plugin.isEnabled()) return;
        createConfigFile();

        isEnabled = config.getBoolean("enabled", true);
        moduleA.loadConfig();
        moduleB.loadConfig();
        moduleC.loadConfig();
        moduleD.loadConfig();

        cancelCleanupTasks();
        if (isEnabled) {
            registerPacketListener();
            startCacheCleanupTask();
            startPlayerCleanupTask();
        } else if (packetListener != null) {
            unregister();
        }
    }

    public Map<UUID, List<AttackData>> getAttackHistory() {
        return attackHistory;
    }

    public Map<UUID, Map<Integer, Long>> getTargetTracking() {
        return targetTracking;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public Player getPlayerFromUUID(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    public static class AttackData {
        private final int targetEntityId;
        private final long timestamp;
        private final float yawDifference;
        private final float pitchDifference;
        private final double distance;
        private final boolean isVisible;
        private final boolean isSprinting;
        private final double playerVelocity;
        private final long attackDelay;
        private final int comboCount;
        private final double comboFactor;

        public AttackData(int targetEntityId, long timestamp, float yawDifference,
                          float pitchDifference, double distance, boolean isVisible,
                          boolean isSprinting, double playerVelocity, long attackDelay,
                          int comboCount, double comboFactor) {
            this.targetEntityId = targetEntityId;
            this.timestamp = timestamp;
            this.yawDifference = yawDifference;
            this.pitchDifference = pitchDifference;
            this.distance = distance;
            this.isVisible = isVisible;
            this.isSprinting = isSprinting;
            this.playerVelocity = playerVelocity;
            this.attackDelay = attackDelay;
            this.comboCount = comboCount;
            this.comboFactor = comboFactor;
        }

        public int getTargetEntityId() {
            return targetEntityId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public float getYawDifference() {
            return yawDifference;
        }

        public float getPitchDifference() {
            return pitchDifference;
        }

        public double getDistance() {
            return distance;
        }

        public boolean isVisible() {
            return isVisible;
        }

        public boolean isSprinting() {
            return isSprinting;
        }

        public double getPlayerVelocity() {
            return playerVelocity;
        }

        public long getAttackDelay() {
            return attackDelay;
        }

        public int getComboCount() {
            return comboCount;
        }

        public double getComboFactor() {
            return comboFactor;
        }
    }

    private static class EntityData {
        final int entityId;
        final org.bukkit.entity.EntityType type;
        final Location location;
        final int worldId;
        final long timestamp;

        EntityData(int entityId, org.bukkit.entity.EntityType type, Location location,
                   int worldId, long timestamp) {
            this.entityId = entityId;
            this.type = type;
            this.location = location;
            this.worldId = worldId;
            this.timestamp = timestamp;
        }

        boolean isLivingEntity() {
            return type.isAlive();
        }
    }
}