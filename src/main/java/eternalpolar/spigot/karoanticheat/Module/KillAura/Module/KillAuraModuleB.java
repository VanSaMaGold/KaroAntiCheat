package eternalpolar.spigot.karoanticheat.Module.KillAura.Module;

import eternalpolar.spigot.karoanticheat.Module.KillAura.KillAuraCheck;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KillAuraModuleB {
    private final KillAuraCheck parent;
    private boolean enabled;
    private int maxVisibleTargets;
    private float autoAimThreshold;
    private double trackingConsistency;
    private boolean useGetEntityMethod;
    private Method getEntityMethod;
    private int minimumAttacks;
    private double visibilityCheckDistance;
    private long entityUpdateThreshold;
    private final Map<Integer, EntityVisibilityData> entityVisibilityCache = new ConcurrentHashMap<>();

    public KillAuraModuleB(KillAuraCheck parent) {
        this.parent = parent;
        this.enabled = true;
        this.minimumAttacks = 5;
        this.visibilityCheckDistance = 12.0;
        this.entityUpdateThreshold = 500;

        try {
            getEntityMethod = World.class.getMethod("getEntity", int.class);
            useGetEntityMethod = true;
        } catch (NoSuchMethodException e) {
            useGetEntityMethod = false;
            getEntityMethod = null;
        }

        loadConfig();
    }

    public void loadConfig() {
        this.maxVisibleTargets = parent.config.getInt("modules.B.max-visible-targets", 3);
        this.autoAimThreshold = (float) parent.config.getDouble("modules.B.auto-aim-threshold", 0.15);
        this.trackingConsistency = parent.config.getDouble("modules.B.tracking-consistency", 0.85);
        this.minimumAttacks = parent.config.getInt("modules.B.minimum-attacks", 5);
        this.visibilityCheckDistance = parent.config.getDouble("modules.B.visibility-check-distance", 12.0);
        this.entityUpdateThreshold = parent.config.getLong("modules.B.entity-update-threshold", 500);
    }

    public boolean check(Player player) {
        if (!enabled) return false;

        UUID uuid = player.getUniqueId();
        List<KillAuraCheck.AttackData> attacks = parent.getAttackHistory().getOrDefault(uuid, null);

        if (attacks == null || attacks.size() < minimumAttacks) return false;

        double targetCountConfidence = checkTargetCountConfidence(player, uuid, attacks);
        double autoAimConfidence = checkAutoAimPatternConfidence(uuid, attacks);
        double trackingConfidence = checkTrackingConsistencyConfidence(uuid);
        double lineOfSightConfidence = checkLineOfSightPattern(uuid, attacks);
        double aimSmoothingConfidence = checkAimSmoothingPattern(uuid, attacks);

        int highConfidenceCount = 0;
        if (targetCountConfidence > 0.7) highConfidenceCount++;
        if (autoAimConfidence > 0.7) highConfidenceCount++;
        if (trackingConfidence > 0.7) highConfidenceCount++;
        if (lineOfSightConfidence > 0.75) highConfidenceCount++;
        if (aimSmoothingConfidence > 0.8) highConfidenceCount++;

        return highConfidenceCount >= 2 ||
                (targetCountConfidence > 0.9) ||
                (autoAimConfidence > 0.9) ||
                (trackingConfidence > 0.9) ||
                (lineOfSightConfidence > 0.9) ||
                (aimSmoothingConfidence > 0.9);
    }

    private double checkTargetCountConfidence(Player player, UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        long recentTime = System.currentTimeMillis() - 5000;
        List<Integer> recentTargets = attacks.stream()
                .filter(a -> a.getTimestamp() >= recentTime)
                .map(KillAuraCheck.AttackData::getTargetEntityId)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        if (recentTargets.size() <= maxVisibleTargets) return 0.0;

        int visibleCount = 0;
        int partiallyVisibleCount = 0;
        World world = player.getWorld();

        for (int entityId : recentTargets) {
            LivingEntity entity = getLivingEntity(world, entityId);
            if (entity != null) {
                if (parent.isEntityVisible(player, entity)) {
                    visibleCount++;
                } else {
                    double distance = player.getLocation().distance(entity.getLocation());
                    if (distance < visibilityCheckDistance * 0.7 && hasLineOfSightToEntity(player, entity)) {
                        partiallyVisibleCount++;
                    }
                }
            }
        }

        double invisibleRatio = 1.0 - (double) (visibleCount + partiallyVisibleCount * 0.5) / recentTargets.size();
        double targetDensity = (double) recentTargets.size() / maxVisibleTargets;

        return Math.min(invisibleRatio * Math.min(targetDensity, 2.0), 1.0);
    }

    private boolean hasLineOfSightToEntity(Player player, LivingEntity entity) {
        if (!player.getWorld().equals(entity.getWorld())) return false;

        int entityId = entity.getEntityId();
        EntityVisibilityData cachedData = entityVisibilityCache.get(entityId);

        if (cachedData != null && System.currentTimeMillis() - cachedData.timestamp < entityUpdateThreshold) {
            return cachedData.hasLineOfSight;
        }

        boolean hasLOS = player.hasLineOfSight(entity);
        entityVisibilityCache.put(entityId, new EntityVisibilityData(hasLOS, System.currentTimeMillis()));

        return hasLOS;
    }

    private LivingEntity getLivingEntity(World world, int entityId) {
        try {
            if (useGetEntityMethod && getEntityMethod != null) {
                Entity entity = (Entity) getEntityMethod.invoke(world, entityId);
                return (entity instanceof LivingEntity) ? (LivingEntity) entity : null;
            } else {
                for (Entity entity : world.getEntities()) {
                    if (entity.getEntityId() == entityId && entity instanceof LivingEntity) {
                        return (LivingEntity) entity;
                    }
                }
                return null;
            }
        } catch (Exception e) {
            for (Entity entity : world.getEntities()) {
                if (entity.getEntityId() == entityId && entity instanceof LivingEntity) {
                    return (LivingEntity) entity;
                }
            }
            return null;
        }
    }

    private double checkAutoAimPatternConfidence(UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        int perfectAims = 0;
        int veryGoodAims = 0;
        int validAttacks = 0;
        double distanceWeightedAim = 0.0;

        for (KillAuraCheck.AttackData attack : attacks) {
            if (attack.getDistance() > 1.5 && attack.getDistance() < 6.0) {
                validAttacks++;
                float totalDiff = attack.getYawDifference() + attack.getPitchDifference();

                if (totalDiff < autoAimThreshold) {
                    perfectAims++;
                    distanceWeightedAim += (6.0 - attack.getDistance()) / 5.0;
                } else if (totalDiff < autoAimThreshold * 2) {
                    veryGoodAims++;
                }
            }
        }

        if (validAttacks == 0) return 0.0;

        double perfectRatio = (double) perfectAims / validAttacks;
        double weightedPerfectRatio = perfectRatio * 0.7 + (double) veryGoodAims / validAttacks * 0.3;

        double distanceFactor = validAttacks > 0 ? distanceWeightedAim / validAttacks : 0;

        return Math.min(weightedPerfectRatio + distanceFactor * 0.3, 1.0);
    }

    private double checkTrackingConsistencyConfidence(UUID uuid) {
        Map<Integer, Long> targets = parent.getTargetTracking().getOrDefault(uuid, null);
        if (targets == null || targets.size() < 2) return 0.0;

        List<KillAuraCheck.AttackData> attacks = parent.getAttackHistory().getOrDefault(uuid, null);
        if (attacks == null || attacks.size() < minimumAttacks) return 0.0;

        int inconsistentTracks = 0;
        int delayedTracks = 0;
        int totalChecks = 0;

        for (KillAuraCheck.AttackData attack : attacks) {
            totalChecks++;
            Long lastTracked = targets.get(attack.getTargetEntityId());
            if (lastTracked == null) {
                inconsistentTracks++;
            } else {
                long timeDiff = attack.getTimestamp() - lastTracked;
                if (timeDiff >= 500) {
                    inconsistentTracks++;
                    if (timeDiff >= 1000) {
                        delayedTracks++;
                    }
                }
            }
        }

        if (totalChecks == 0) return 0.0;

        double baseConfidence = (double) inconsistentTracks / totalChecks;
        double weightedConfidence = baseConfidence * 0.6 + (double) delayedTracks / totalChecks * 0.4;

        return Math.min(weightedConfidence, 1.0);
    }

    private double checkLineOfSightPattern(UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        int losBreaks = 0;
        int totalChecks = 0;
        int consecutiveBreaks = 0;
        int maxConsecutiveBreaks = 0;

        for (int i = 0; i < attacks.size(); i++) {
            KillAuraCheck.AttackData attack = attacks.get(i);
            if (!attack.isVisible() && attack.getDistance() > 3.0) {
                LivingEntity target = parent.getTargetEntity(parent.getPlayerFromUUID(uuid), attack.getTargetEntityId());
                if (target != null && !hasLineOfSightToEntity(parent.getPlayerFromUUID(uuid), target)) {
                    losBreaks++;
                    consecutiveBreaks++;
                    if (consecutiveBreaks > maxConsecutiveBreaks) {
                        maxConsecutiveBreaks = consecutiveBreaks;
                    }
                } else {
                    consecutiveBreaks = 0;
                }
                totalChecks++;
            } else {
                consecutiveBreaks = 0;
            }
        }

        if (totalChecks == 0) return 0.0;

        double losBreakRatio = (double) losBreaks / totalChecks;
        double consecutiveFactor = Math.min((double) maxConsecutiveBreaks / 3, 1.0);

        return Math.min(losBreakRatio + consecutiveFactor * 0.3, 1.0);
    }

    private double checkAimSmoothingPattern(UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 8) return 0.0;

        double totalSmoothness = 0.0;
        int smoothSamples = 0;

        for (int i = 2; i < attacks.size(); i++) {
            float yaw1 = attacks.get(i-2).getYawDifference();
            float yaw2 = attacks.get(i-1).getYawDifference();
            float yaw3 = attacks.get(i).getYawDifference();

            float pitch1 = attacks.get(i-2).getPitchDifference();
            float pitch2 = attacks.get(i-1).getPitchDifference();
            float pitch3 = attacks.get(i).getPitchDifference();

            double yawSmoothness = calculateSmoothness(yaw1, yaw2, yaw3);
            double pitchSmoothness = calculateSmoothness(pitch1, pitch2, pitch3);

            totalSmoothness += (yawSmoothness + pitchSmoothness) / 2.0;
            smoothSamples++;
        }

        if (smoothSamples == 0) return 0.0;

        double avgSmoothness = totalSmoothness / smoothSamples;
        return avgSmoothness > 0.9 ? avgSmoothness : 0.0;
    }

    private double calculateSmoothness(float a, float b, float c) {
        double diff1 = Math.abs(b - a);
        double diff2 = Math.abs(c - b);

        if (diff1 < 0.01 || diff2 < 0.01) return 1.0;

        double ratio = Math.min(diff1 / diff2, diff2 / diff1);
        return ratio > 0.8 ? ratio : 0.0;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxVisibleTargets() {
        return maxVisibleTargets;
    }

    public float getAutoAimThreshold() {
        return autoAimThreshold;
    }

    public double getTrackingConsistency() {
        return trackingConsistency;
    }

    private static class EntityVisibilityData {
        final boolean hasLineOfSight;
        final long timestamp;

        EntityVisibilityData(boolean hasLineOfSight, long timestamp) {
            this.hasLineOfSight = hasLineOfSight;
            this.timestamp = timestamp;
        }
    }
}
