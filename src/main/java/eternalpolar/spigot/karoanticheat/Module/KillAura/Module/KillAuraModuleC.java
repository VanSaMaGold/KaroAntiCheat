package eternalpolar.spigot.karoanticheat.Module.KillAura.Module;

import eternalpolar.spigot.karoanticheat.Module.KillAura.KillAuraCheck;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class KillAuraModuleC {
    private final KillAuraCheck parent;
    private boolean enabled;
    private double predictionAccuracyThreshold;
    private int movementVarianceThreshold;
    private long combatIntensityThreshold;
    private int aimStabilityThreshold;
    private double playerSpeedMultiplier;
    private double targetSpeedMultiplier;
    private int predictionWindowSize;

    private final Map<UUID, PlayerCombatBaseline> playerBaselines = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, List<Location>>> targetMovementHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombatActivity = new ConcurrentHashMap<>();

    public KillAuraModuleC(KillAuraCheck parent) {
        this.parent = parent;
        this.enabled = true;
        this.predictionWindowSize = 5;
        loadConfig();
    }

    public void loadConfig() {
        this.predictionAccuracyThreshold = parent.config.getDouble("modules.C.prediction-accuracy-threshold", 0.92);
        this.movementVarianceThreshold = parent.config.getInt("modules.C.movement-variance-threshold", 15);
        this.combatIntensityThreshold = parent.config.getLong("modules.C.combat-intensity-threshold", 1200);
        this.aimStabilityThreshold = parent.config.getInt("modules.C.aim-stability-threshold", 5);
        this.playerSpeedMultiplier = parent.config.getDouble("modules.C.player-speed-multiplier", 1.0);
        this.targetSpeedMultiplier = parent.config.getDouble("modules.C.target-speed-multiplier", 1.0);
        this.predictionWindowSize = parent.config.getInt("modules.C.prediction-window-size", 5);
    }

    public boolean check(Player player) {
        if (!enabled) return false;

        UUID uuid = player.getUniqueId();
        List<KillAuraCheck.AttackData> attacks = parent.getAttackHistory().getOrDefault(uuid, null);

        if (attacks == null || attacks.size() < 5) {
            updateBaseline(player, attacks);
            return false;
        }

        PlayerCombatBaseline baseline = getOrCreateBaseline(uuid);
        updateBaseline(player, attacks);
        updateTargetMovementHistory(player, attacks);

        if (!baseline.isEstablished()) {
            return false;
        }

        boolean abnormalPrediction = checkTargetPrediction(player, attacks, baseline);
        boolean unnaturalAimStability = checkAimStability(player, attacks, baseline);
        boolean impossibleCombatIntensity = checkCombatIntensity(player, attacks);
        boolean movementAimDiscrepancy = checkMovementAimCorrelation(player, attacks);
        boolean aimAssistPattern = checkAimAssistPattern(player, attacks, baseline);

        return calculateFinalResult(
                new boolean[]{abnormalPrediction, unnaturalAimStability, impossibleCombatIntensity, movementAimDiscrepancy, aimAssistPattern},
                baseline
        );
    }

    private PlayerCombatBaseline getOrCreateBaseline(UUID uuid) {
        return playerBaselines.computeIfAbsent(uuid, k -> new PlayerCombatBaseline());
    }

    private void updateBaseline(Player player, List<KillAuraCheck.AttackData> attacks) {
        if (attacks == null || attacks.isEmpty()) return;

        PlayerCombatBaseline baseline = getOrCreateBaseline(player.getUniqueId());
        baseline.updateWithNewAttacks(attacks);
        lastCombatActivity.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void updateTargetMovementHistory(Player player, List<KillAuraCheck.AttackData> attacks) {
        if (attacks == null || attacks.isEmpty()) return;

        UUID uuid = player.getUniqueId();
        Map<Integer, List<Location>> targetMovements = targetMovementHistory.computeIfAbsent(uuid, k -> new HashMap<>());

        for (KillAuraCheck.AttackData attack : attacks) {
            int targetId = attack.getTargetEntityId();
            List<Location> locations = targetMovements.computeIfAbsent(targetId, k -> new ArrayList<>());

            org.bukkit.entity.LivingEntity target = parent.getTargetEntity(player, targetId);
            if (target != null) {
                locations.add(target.getLocation().clone());

                if (locations.size() > predictionWindowSize * 2) {
                    locations.subList(0, locations.size() - predictionWindowSize * 2).clear();
                }
            }
        }
    }

    private boolean checkTargetPrediction(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        if (attacks.size() < predictionWindowSize * 2) return false;

        int correctPredictions = 0;
        int totalPredictions = 0;

        Map<Integer, List<Location>> targetMovements = targetMovementHistory.getOrDefault(player.getUniqueId(), Collections.emptyMap());

        for (int i = predictionWindowSize; i < attacks.size(); i++) {
            KillAuraCheck.AttackData currentAttack = attacks.get(i);
            int targetId = currentAttack.getTargetEntityId();

            List<Location> locations = targetMovements.getOrDefault(targetId, Collections.emptyList());
            if (locations.size() < predictionWindowSize) continue;

            List<Location> recentLocations = locations.subList(
                    Math.max(0, locations.size() - predictionWindowSize),
                    locations.size()
            );

            Location predictedLoc = predictTargetLocation(recentLocations, player, targetId);
            if (predictedLoc == null) continue;

            org.bukkit.entity.LivingEntity target = parent.getTargetEntity(player, targetId);
            if (target == null) continue;

            Location actualLoc = target.getLocation();

            double distance = predictedLoc.distance(actualLoc);
            if (distance < 0.3) {
                correctPredictions++;
            }

            totalPredictions++;
        }

        if (totalPredictions == 0) return false;

        double accuracy = (double) correctPredictions / totalPredictions;
        return accuracy > predictionAccuracyThreshold &&
                accuracy > baseline.getAveragePredictionAccuracy() + 0.3 &&
                totalPredictions >= 5;
    }

    private Location predictTargetLocation(List<Location> locations, Player player, int targetId) {
        if (locations.size() < 3) return null;

        Location loc1 = locations.get(locations.size() - 3);
        Location loc2 = locations.get(locations.size() - 2);
        Location loc3 = locations.get(locations.size() - 1);

        double dx = loc3.getX() - loc2.getX() + (loc2.getX() - loc1.getX()) * 0.5;
        double dy = loc3.getY() - loc2.getY() + (loc2.getY() - loc1.getY()) * 0.5;
        double dz = loc3.getZ() - loc2.getZ() + (loc2.getZ() - loc1.getZ()) * 0.5;

        double speedMultiplier = getTargetSpeedMultiplier(player, targetId);
        dx *= speedMultiplier;
        dy *= speedMultiplier;
        dz *= speedMultiplier;

        return new Location(
                loc3.getWorld(),
                loc3.getX() + dx,
                loc3.getY() + dy,
                loc3.getZ() + dz
        );
    }

    private double getTargetSpeedMultiplier(Player player, int targetId) {
        org.bukkit.entity.LivingEntity target = parent.getTargetEntity(player, targetId);
        if (target == null) return targetSpeedMultiplier;

        if (target.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = target.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            return targetSpeedMultiplier * (1.0 + level * 0.2);
        }

        return targetSpeedMultiplier;
    }

    private boolean checkAimStability(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        List<Float> yawChanges = new ArrayList<>();
        List<Float> pitchChanges = new ArrayList<>();

        for (int i = 1; i < attacks.size(); i++) {
            yawChanges.add(Math.abs(attacks.get(i).getYawDifference() - attacks.get(i-1).getYawDifference()));
            pitchChanges.add(Math.abs(attacks.get(i).getPitchDifference() - attacks.get(i-1).getPitchDifference()));
        }

        if (yawChanges.size() < 5) return false;

        double avgYawChange = yawChanges.stream().mapToDouble(Float::doubleValue).average().orElse(0);
        double avgPitchChange = pitchChanges.stream().mapToDouble(Float::doubleValue).average().orElse(0);
        double overallStability = (avgYawChange + avgPitchChange) / 2;

        double yawVariance = calculateVariance(yawChanges.stream().mapToDouble(Float::doubleValue).boxed().collect(Collectors.toList()));
        double pitchVariance = calculateVariance(pitchChanges.stream().mapToDouble(Float::doubleValue).boxed().collect(Collectors.toList()));
        double stabilityVariance = (yawVariance + pitchVariance) / 2;

        return overallStability < aimStabilityThreshold &&
                overallStability < baseline.getAverageAimStability() * 0.5 &&
                stabilityVariance < 2.0;
    }

    private boolean checkCombatIntensity(Player player, List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 5) return false;

        long firstAttack = attacks.get(0).getTimestamp();
        long lastAttack = attacks.get(attacks.size() - 1).getTimestamp();
        long duration = lastAttack - firstAttack;

        double attacksPerSecond = duration > 0 ? (double) attacks.size() * 1000 / duration : 0;

        int targetSwitches = 0;
        for (int i = 1; i < attacks.size(); i++) {
            if (attacks.get(i).getTargetEntityId() != attacks.get(i-1).getTargetEntityId()) {
                targetSwitches++;
            }
        }
        double switchesPerSecond = duration > 0 ? (double) targetSwitches * 1000 / duration : 0;

        double playerSpeed = getPlayerSpeed(player);
        double speedFactor = Math.min(playerSpeed / 0.28, 2.0);

        return (attacksPerSecond > 8 * speedFactor && switchesPerSecond > 2) ||
                (attacksPerSecond > 10 && switchesPerSecond > 1);
    }

    private double getPlayerSpeed(Player player) {
        double baseSpeed = 0.28;

        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            baseSpeed *= (1.0 + level * 0.2);
        }

        return baseSpeed * playerSpeedMultiplier;
    }

    private boolean checkMovementAimCorrelation(Player player, List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 5) return false;

        List<Location> playerLocations = new ArrayList<>();
        List<Float> aimPrecisions = new ArrayList<>();
        List<Double> movementSpeeds = new ArrayList<>();

        for (KillAuraCheck.AttackData attack : attacks) {
            playerLocations.add(player.getLocation().clone());
            aimPrecisions.add((attack.getYawDifference() + attack.getPitchDifference()) / 2);
            movementSpeeds.add(attack.getPlayerVelocity());
        }

        double movementVariance = calculateMovementVariance(playerLocations);
        double avgPrecision = aimPrecisions.stream().mapToDouble(Float::doubleValue).average().orElse(0);
        double avgSpeed = movementSpeeds.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double speedPrecisionCorrelation = calculateCorrelation(
                movementSpeeds,
                aimPrecisions.stream().map(p -> 10.0 - p).collect(Collectors.toList())
        );

        return movementVariance > movementVarianceThreshold &&
                avgPrecision < 3.0 &&
                avgSpeed > 0.15 &&
                speedPrecisionCorrelation < 0.2;
    }

    private boolean checkAimAssistPattern(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        if (attacks.size() < 10) return false;

        List<Double> aimAdjustments = new ArrayList<>();
        int targetChanges = 0;

        for (int i = 1; i < attacks.size(); i++) {
            KillAuraCheck.AttackData prev = attacks.get(i-1);
            KillAuraCheck.AttackData curr = attacks.get(i);

            if (prev.getTargetEntityId() != curr.getTargetEntityId()) {
                targetChanges++;
                continue;
            }

            double prevAim = prev.getYawDifference() + prev.getPitchDifference();
            double currAim = curr.getYawDifference() + curr.getPitchDifference();
            double adjustment = Math.abs(currAim - prevAim);

            aimAdjustments.add(adjustment);
        }

        if (aimAdjustments.size() < 5) return false;

        double avgAdjustment = aimAdjustments.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double adjustmentVariance = calculateVariance(aimAdjustments);

        double baselineAdjustment = baseline.getAverageAimAdjustment();
        double baselineVariance = baseline.getAverageAdjustmentVariance();

        return avgAdjustment < baselineAdjustment * 0.4 &&
                adjustmentVariance < baselineVariance * 0.3 &&
                targetChanges > 0;
    }

    private double calculateMovementVariance(List<Location> locations) {
        if (locations.size() < 2) return 0;

        double sumX = 0, sumZ = 0, sumX2 = 0, sumZ2 = 0;
        int n = locations.size();

        for (Location loc : locations) {
            sumX += loc.getX();
            sumZ += loc.getZ();
            sumX2 += loc.getX() * loc.getX();
            sumZ2 += loc.getZ() * loc.getZ();
        }

        double varX = (sumX2 - sumX * sumX / n) / n;
        double varZ = (sumZ2 - sumZ * sumZ / n) / n;

        return Math.sqrt(varX + varZ);
    }

    private double calculateVariance(List<Double> values) {
        if (values.size() < 2) return 0.0;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumSq = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();

        return sumSq / values.size();
    }

    private double calculateCorrelation(List<Double> x, List<Double> y) {
        if (x.size() != y.size() || x.size() < 2) return 0.0;

        int n = x.size();
        double sumX = 0, sumY = 0, sumXY = 0;
        double sumX2 = 0, sumY2 = 0;

        for (int i = 0; i < n; i++) {
            double xi = x.get(i);
            double yi = y.get(i);

            sumX += xi;
            sumY += yi;
            sumXY += xi * yi;
            sumX2 += xi * xi;
            sumY2 += yi * yi;
        }

        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        if (denominator == 0) return 0.0;
        return numerator / denominator;
    }

    private boolean calculateFinalResult(boolean[] detectionResults, PlayerCombatBaseline baseline) {
        int positiveCount = 0;
        for (boolean result : detectionResults) {
            if (result) positiveCount++;
        }

        int requiredPositives = baseline.isHighConfidenceBaseline() ? 2 : 3;
        return positiveCount >= requiredPositives;
    }

    public void cleanupPlayerData(UUID uuid) {
        playerBaselines.remove(uuid);
        targetMovementHistory.remove(uuid);
        lastCombatActivity.remove(uuid);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public static class PlayerCombatBaseline {
        private final List<Double> predictionAccuracies = new ArrayList<>();
        private final List<Double> aimStabilities = new ArrayList<>();
        private final List<Double> combatIntensities = new ArrayList<>();
        private final List<Double> aimAdjustments = new ArrayList<>();
        private final List<Double> adjustmentVariances = new ArrayList<>();
        private long lastUpdateTime;
        private int sampleCount;
        private static final int MAX_SAMPLES = 100;

        public PlayerCombatBaseline() {
            this.lastUpdateTime = System.currentTimeMillis();
            this.sampleCount = 0;
        }

        public void updateWithNewAttacks(List<KillAuraCheck.AttackData> attacks) {
            if (attacks.size() < 5) return;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUpdateTime < 30000) {
                return;
            }

            double stability = calculateAimStability(attacks);
            aimStabilities.add(stability);

            List<Double> adjustments = calculateAimAdjustments(attacks);
            if (!adjustments.isEmpty()) {
                double avgAdjustment = adjustments.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                aimAdjustments.add(avgAdjustment);

                double variance = calculateVariance(adjustments);
                adjustmentVariances.add(variance);
            }

            trimLists();

            sampleCount++;
            lastUpdateTime = currentTime;
        }

        private double calculateAimStability(List<KillAuraCheck.AttackData> attacks) {
            List<Float> yawChanges = new ArrayList<>();
            List<Float> pitchChanges = new ArrayList<>();

            for (int i = 1; i < attacks.size(); i++) {
                yawChanges.add(Math.abs(attacks.get(i).getYawDifference() - attacks.get(i-1).getYawDifference()));
                pitchChanges.add(Math.abs(attacks.get(i).getPitchDifference() - attacks.get(i-1).getPitchDifference()));
            }

            if (yawChanges.isEmpty()) return 10.0;

            double avgYaw = yawChanges.stream().mapToDouble(Float::doubleValue).average().orElse(0);
            double avgPitch = pitchChanges.stream().mapToDouble(Float::doubleValue).average().orElse(0);

            return (avgYaw + avgPitch) / 2;
        }

        private List<Double> calculateAimAdjustments(List<KillAuraCheck.AttackData> attacks) {
            List<Double> adjustments = new ArrayList<>();

            for (int i = 1; i < attacks.size(); i++) {
                KillAuraCheck.AttackData prev = attacks.get(i-1);
                KillAuraCheck.AttackData curr = attacks.get(i);

                if (prev.getTargetEntityId() == curr.getTargetEntityId()) {
                    double prevAim = prev.getYawDifference() + prev.getPitchDifference();
                    double currAim = curr.getYawDifference() + curr.getPitchDifference();
                    adjustments.add(Math.abs(currAim - prevAim));
                }
            }

            return adjustments;
        }

        private double calculateVariance(List<Double> values) {
            if (values.size() < 2) return 0.0;

            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double sumSq = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();

            return sumSq / values.size();
        }

        private void trimLists() {
            if (aimStabilities.size() > MAX_SAMPLES) {
                aimStabilities.subList(0, aimStabilities.size() - MAX_SAMPLES).clear();
            }
            if (predictionAccuracies.size() > MAX_SAMPLES) {
                predictionAccuracies.subList(0, predictionAccuracies.size() - MAX_SAMPLES).clear();
            }
            if (combatIntensities.size() > MAX_SAMPLES) {
                combatIntensities.subList(0, combatIntensities.size() - MAX_SAMPLES).clear();
            }
            if (aimAdjustments.size() > MAX_SAMPLES) {
                aimAdjustments.subList(0, aimAdjustments.size() - MAX_SAMPLES).clear();
            }
            if (adjustmentVariances.size() > MAX_SAMPLES) {
                adjustmentVariances.subList(0, adjustmentVariances.size() - MAX_SAMPLES).clear();
            }
        }

        public double getAverageAimStability() {
            if (aimStabilities.isEmpty()) return 10.0;
            return aimStabilities.stream().mapToDouble(Double::doubleValue).average().orElse(10.0);
        }

        public double getAveragePredictionAccuracy() {
            if (predictionAccuracies.isEmpty()) return 0.5;
            return predictionAccuracies.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
        }

        public double getAverageAimAdjustment() {
            if (aimAdjustments.isEmpty()) return 5.0;
            return aimAdjustments.stream().mapToDouble(Double::doubleValue).average().orElse(5.0);
        }

        public double getAverageAdjustmentVariance() {
            if (adjustmentVariances.isEmpty()) return 10.0;
            return adjustmentVariances.stream().mapToDouble(Double::doubleValue).average().orElse(10.0);
        }

        public boolean isEstablished() {
            return sampleCount >= 10;
        }

        public boolean isHighConfidenceBaseline() {
            return sampleCount >= 20;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }

        public int getSampleCount() {
            return sampleCount;
        }
    }
}
