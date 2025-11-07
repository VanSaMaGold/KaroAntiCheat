package eternalpolar.spigot.karoanticheat.Module.KillAura.Module;

import eternalpolar.spigot.karoanticheat.Module.KillAura.KillAuraCheck;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KillAuraModuleD {
    private final KillAuraCheck parent;
    private boolean enabled;
    private double predictionAccuracyThreshold;
    private double reactionTimeThreshold;
    private double strafeAttackConsistency;
    private int minimumAttacks;
    private double predictionWindowMultiplier;
    private double strafeAccuracyThreshold;
    private int reactionTimeSampleSize;

    private final Map<UUID, PlayerCombatBaseline> playerBaselines = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, List<Location>>> targetLocationHistory = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> reactionTimeHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombatUpdate = new ConcurrentHashMap<>();

    public KillAuraModuleD(KillAuraCheck parent) {
        this.parent = parent;
        this.enabled = true;
        this.minimumAttacks = 6;
        this.predictionWindowMultiplier = 1.2;
        this.strafeAccuracyThreshold = 0.85;
        this.reactionTimeSampleSize = 10;
        loadConfig();
    }

    public void loadConfig() {
        this.predictionAccuracyThreshold = parent.config.getDouble("modules.D.prediction-accuracy-threshold", 0.85);
        this.reactionTimeThreshold = parent.config.getDouble("modules.D.reaction-time-threshold", 80);
        this.strafeAttackConsistency = parent.config.getDouble("modules.D.strafe-attack-consistency", 0.8);
        this.minimumAttacks = parent.config.getInt("modules.D.minimum-attacks", 6);
        this.predictionWindowMultiplier = parent.config.getDouble("modules.D.prediction-window-multiplier", 1.2);
        this.strafeAccuracyThreshold = parent.config.getDouble("modules.D.strafe-accuracy-threshold", 0.85);
        this.reactionTimeSampleSize = parent.config.getInt("modules.D.reaction-time-sample-size", 10);
    }

    public boolean check(Player player) {
        if (!enabled) return false;

        UUID uuid = player.getUniqueId();
        List<KillAuraCheck.AttackData> attacks = parent.getAttackHistory().getOrDefault(uuid, null);

        if (attacks == null || attacks.size() < minimumAttacks) {
            updateBaseline(player, attacks);
            return false;
        }

        PlayerCombatBaseline baseline = getOrCreateBaseline(uuid);
        updateBaseline(player, attacks);

        if (!baseline.isEstablished()) {
            return false;
        }

        double targetPredictionScore = checkTargetPrediction(player, attacks, baseline);
        double reactionTimeScore = checkReactionTime(player, attacks, baseline);
        double strafeAttackScore = checkStrafeAttackPattern(player, attacks, baseline);
        double aimPredictionScore = checkAimPrediction(player, attacks, baseline);
        double targetTrackingScore = checkTargetTrackingPattern(player, attacks, baseline);

        int highConfidenceCount = 0;
        if (targetPredictionScore > 0.8) highConfidenceCount++;
        if (reactionTimeScore > 0.8) highConfidenceCount++;
        if (strafeAttackScore > 0.8) highConfidenceCount++;
        if (aimPredictionScore > 0.8) highConfidenceCount++;
        if (targetTrackingScore > 0.8) highConfidenceCount++;

        return highConfidenceCount >= 2;
    }

    private PlayerCombatBaseline getOrCreateBaseline(UUID uuid) {
        return playerBaselines.computeIfAbsent(uuid, k -> new PlayerCombatBaseline());
    }

    private void updateBaseline(Player player, List<KillAuraCheck.AttackData> attacks) {
        if (attacks == null || attacks.isEmpty()) return;

        PlayerCombatBaseline baseline = getOrCreateBaseline(player.getUniqueId());
        baseline.updateWithNewAttacks(attacks);

        updateTargetLocationHistory(player, attacks);
        updateReactionTimeHistory(player, attacks);

        lastCombatUpdate.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void updateTargetLocationHistory(Player player, List<KillAuraCheck.AttackData> attacks) {
        UUID uuid = player.getUniqueId();
        Map<Integer, List<Location>> targetLocations = targetLocationHistory.computeIfAbsent(uuid, k -> new HashMap<>());

        for (KillAuraCheck.AttackData attack : attacks) {
            int targetId = attack.getTargetEntityId();
            List<Location> locations = targetLocations.computeIfAbsent(targetId, k -> new ArrayList<>());

            org.bukkit.entity.LivingEntity target = parent.getTargetEntity(player, targetId);
            if (target != null) {
                locations.add(target.getLocation().clone());
                if (locations.size() > 15) {
                    locations.remove(0);
                }
            }
        }
    }

    private void updateReactionTimeHistory(Player player, List<KillAuraCheck.AttackData> attacks) {
        UUID uuid = player.getUniqueId();
        List<Long> reactionTimes = reactionTimeHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        Map<Integer, Long> lastSeenTimes = new HashMap<>();

        for (KillAuraCheck.AttackData attack : attacks) {
            int targetId = attack.getTargetEntityId();
            Long lastSeen = lastSeenTimes.get(targetId);

            if (lastSeen == null) {
                lastSeenTimes.put(targetId, attack.getTimestamp());
                continue;
            }

            long reactionTime = attack.getTimestamp() - lastSeen;
            reactionTimes.add(reactionTime);
            lastSeenTimes.put(targetId, attack.getTimestamp());
        }

        if (reactionTimes.size() > reactionTimeSampleSize * 2) {
            reactionTimes.subList(0, reactionTimes.size() - reactionTimeSampleSize * 2).clear();
        }
    }

    private double checkTargetPrediction(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        if (attacks.size() < 10) return 0.0;

        int correctPredictions = 0;
        int veryAccuratePredictions = 0;
        int totalPredictions = 0;

        Map<Integer, List<Location>> targetLocations = targetLocationHistory.getOrDefault(player.getUniqueId(), Collections.emptyMap());

        for (int i = 5; i < attacks.size(); i++) {
            KillAuraCheck.AttackData currentAttack = attacks.get(i);
            int targetId = currentAttack.getTargetEntityId();

            List<Location> locations = targetLocations.getOrDefault(targetId, Collections.emptyList());
            if (locations.size() < 3) continue;

            List<Location> recentLocations = locations.subList(
                    Math.max(0, locations.size() - 3),
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
                if (distance < 0.15) {
                    veryAccuratePredictions++;
                }
            }

            totalPredictions++;
        }

        if (totalPredictions == 0) return 0.0;

        double accuracy = (double) correctPredictions / totalPredictions;
        double weightedAccuracy = accuracy * 0.7 + (double) veryAccuratePredictions / totalPredictions * 0.3;

        return accuracy > baseline.getAveragePredictionAccuracy() + 0.4 ? weightedAccuracy : 0.0;
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
        dx *= speedMultiplier * predictionWindowMultiplier;
        dy *= speedMultiplier * predictionWindowMultiplier;
        dz *= speedMultiplier * predictionWindowMultiplier;

        return new Location(
                loc3.getWorld(),
                loc3.getX() + dx,
                loc3.getY() + dy,
                loc3.getZ() + dz
        );
    }

    private double getTargetSpeedMultiplier(Player player, int targetId) {
        org.bukkit.entity.LivingEntity target = parent.getTargetEntity(player, targetId);
        if (target == null) return 1.0;

        double multiplier = 1.0;
        if (target.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = target.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            multiplier *= (1.0 + level * 0.2);
        }

        return multiplier;
    }

    private double checkReactionTime(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        if (attacks.size() < 5) return 0.0;

        List<Long> reactionTimes = reactionTimeHistory.getOrDefault(player.getUniqueId(), Collections.emptyList());
        if (reactionTimes.size() < 3) return 0.0;

        List<Long> recentReactionTimes = reactionTimes.subList(
                Math.max(0, reactionTimes.size() - reactionTimeSampleSize),
                reactionTimes.size()
        );

        double avgReactionTime = recentReactionTimes.stream().mapToLong(l -> l).average().orElse(0);
        double baselineReactionTime = baseline.getAverageReactionTime();

        long fastReactions = recentReactionTimes.stream().filter(t -> t < reactionTimeThreshold).count();
        double fastRatio = (double) fastReactions / recentReactionTimes.size();

        long veryFastReactions = recentReactionTimes.stream().filter(t -> t < reactionTimeThreshold * 0.7).count();
        double veryFastRatio = (double) veryFastReactions / recentReactionTimes.size();

        double reactionTimeRatio = avgReactionTime / baselineReactionTime;

        if (avgReactionTime < baselineReactionTime * 0.6) {
            return Math.min(fastRatio + veryFastRatio + (1 - reactionTimeRatio) * 0.3, 1.0);
        }

        return 0.0;
    }

    private double checkStrafeAttackPattern(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        if (attacks.size() < 8) return 0.0;

        List<KillAuraCheck.AttackData> leftStrafeAttacks = new ArrayList<>();
        List<KillAuraCheck.AttackData> rightStrafeAttacks = new ArrayList<>();
        List<KillAuraCheck.AttackData> forwardAttacks = new ArrayList<>();
        List<KillAuraCheck.AttackData> backwardAttacks = new ArrayList<>();

        List<Location> playerLocations = new ArrayList<>();
        for (KillAuraCheck.AttackData attack : attacks) {
            playerLocations.add(player.getLocation().clone());
        }

        for (int i = 1; i < attacks.size(); i++) {
            Location prevLoc = playerLocations.get(i-1);
            Location currLoc = playerLocations.get(i);

            double dx = currLoc.getX() - prevLoc.getX();
            double dz = currLoc.getZ() - prevLoc.getZ();
            float yaw = prevLoc.getYaw() % 360;
            if (yaw < 0) yaw += 360;

            double movementAngle = Math.toDegrees(Math.atan2(dz, dx)) % 360;
            if (movementAngle < 0) movementAngle += 360;

            double relativeAngle = (movementAngle - yaw + 360) % 360;

            if (relativeAngle > 30 && relativeAngle < 150) {
                rightStrafeAttacks.add(attacks.get(i));
            } else if (relativeAngle > 210 && relativeAngle < 330) {
                leftStrafeAttacks.add(attacks.get(i));
            } else if (relativeAngle >= 150 && relativeAngle <= 210) {
                backwardAttacks.add(attacks.get(i));
            } else {
                forwardAttacks.add(attacks.get(i));
            }
        }

        double leftPrecision = calculateAveragePrecision(leftStrafeAttacks);
        double rightPrecision = calculateAveragePrecision(rightStrafeAttacks);
        double forwardPrecision = calculateAveragePrecision(forwardAttacks);
        double backwardPrecision = calculateAveragePrecision(backwardAttacks);

        double strafeConsistency = 0.0;
        int count = 0;

        if (!leftStrafeAttacks.isEmpty() && !forwardAttacks.isEmpty() && forwardPrecision > 0) {
            strafeConsistency += leftPrecision / forwardPrecision;
            count++;
        }

        if (!rightStrafeAttacks.isEmpty() && !forwardAttacks.isEmpty() && forwardPrecision > 0) {
            strafeConsistency += rightPrecision / forwardPrecision;
            count++;
        }

        if (!leftStrafeAttacks.isEmpty() && !backwardAttacks.isEmpty() && backwardPrecision > 0) {
            strafeConsistency += leftPrecision / backwardPrecision;
            count++;
        }

        if (!rightStrafeAttacks.isEmpty() && !backwardAttacks.isEmpty() && backwardPrecision > 0) {
            strafeConsistency += rightPrecision / backwardPrecision;
            count++;
        }

        if (count == 0) return 0.0;

        strafeConsistency /= count;

        double baselineConsistency = baseline.getAverageStrafeConsistency();

        return strafeConsistency > baselineConsistency + 0.3 && strafeConsistency > strafeAccuracyThreshold ?
                Math.min(strafeConsistency, 1.0) : 0.0;
    }

    private double checkAimPrediction(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        if (attacks.size() < 6) return 0.0;

        List<Double> aimChanges = new ArrayList<>();
        List<Double> timeDifferences = new ArrayList<>();

        for (int i = 1; i < attacks.size(); i++) {
            KillAuraCheck.AttackData prev = attacks.get(i-1);
            KillAuraCheck.AttackData curr = attacks.get(i);

            double yawChange = Math.abs(curr.getYawDifference() - prev.getYawDifference());
            double pitchChange = Math.abs(curr.getPitchDifference() - prev.getPitchDifference());
            double totalChange = Math.sqrt(yawChange * yawChange + pitchChange * pitchChange);

            aimChanges.add(totalChange);
            timeDifferences.add((double) (curr.getTimestamp() - prev.getTimestamp()));
        }

        int smallChanges = 0;
        int largeChanges = 0;
        int alternations = 0;

        for (int i = 0; i < aimChanges.size(); i++) {
            double change = aimChanges.get(i);
            if (change < 2.0) {
                smallChanges++;
            } else if (change > 5.0) {
                largeChanges++;
            }

            if (i > 0) {
                double prevChange = aimChanges.get(i-1);
                if ((prevChange < 2.0 && change > 5.0) ||
                        (prevChange > 5.0 && change < 2.0)) {
                    alternations++;
                }
            }
        }

        double alternationRatio = aimChanges.size() > 0 ? (double) alternations / aimChanges.size() : 0;
        double uniformity = calculateVariance(aimChanges);

        double changeTimeCorrelation = calculateCorrelation(aimChanges, timeDifferences);

        double baselineAlternation = baseline.getAverageAlternationRatio();

        double score = 0.0;
        if (alternationRatio < 0.2) score += 0.5;
        if (uniformity < 3.0) score += 0.3;
        if (changeTimeCorrelation < 0.1) score += 0.2;

        return score > 0.7 && alternationRatio < baselineAlternation - 0.2 ? score : 0.0;
    }

    private double checkTargetTrackingPattern(Player player, List<KillAuraCheck.AttackData> attacks, PlayerCombatBaseline baseline) {
        if (attacks.size() < 7) return 0.0;

        Map<Integer, List<Double>> targetAimData = new HashMap<>();

        for (KillAuraCheck.AttackData attack : attacks) {
            int targetId = attack.getTargetEntityId();
            double aimPrecision = 10.0 / (1.0 + (attack.getYawDifference() + attack.getPitchDifference()) / 2.0);

            targetAimData.computeIfAbsent(targetId, k -> new ArrayList<>()).add(aimPrecision);
        }

        if (targetAimData.size() < 2) return 0.0;

        double precisionVariance = 0.0;
        int targetCount = 0;

        for (List<Double> precisions : targetAimData.values()) {
            if (precisions.size() >= 3) {
                precisionVariance += calculateVariance(precisions);
                targetCount++;
            }
        }

        if (targetCount == 0) return 0.0;

        precisionVariance /= targetCount;

        double baselineVariance = baseline.getAveragePrecisionVariance();

        return precisionVariance < baselineVariance * 0.4 ? Math.max(0, 1 - precisionVariance / baselineVariance) : 0.0;
    }

    private double calculateAveragePrecision(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.isEmpty()) return 0.0;

        double totalPrecision = 0.0;
        for (KillAuraCheck.AttackData attack : attacks) {
            totalPrecision += (attack.getYawDifference() + attack.getPitchDifference()) / 2.0;
        }

        return totalPrecision / attacks.size();
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

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public static class PlayerCombatBaseline {
        private final List<Double> predictionAccuracies = new ArrayList<>();
        private final List<Long> reactionTimes = new ArrayList<>();
        private final List<Double> strafeConsistencies = new ArrayList<>();
        private final List<Double> alternationRatios = new ArrayList<>();
        private final List<Double> precisionVariances = new ArrayList<>();
        private long lastUpdateTime;
        private int sampleCount;
        private static final int MAX_SAMPLES = 50;

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

            calculateAndAddReactionTimes(attacks);
            calculateAndAddStrafeConsistency(attacks);
            calculateAndAddPrecisionVariance(attacks);

            trimLists();

            sampleCount++;
            lastUpdateTime = currentTime;
        }

        private void calculateAndAddReactionTimes(List<KillAuraCheck.AttackData> attacks) {
            Map<Integer, Long> lastSeenTimes = new HashMap<>();

            for (KillAuraCheck.AttackData attack : attacks) {
                int targetId = attack.getTargetEntityId();
                Long lastSeen = lastSeenTimes.get(targetId);

                if (lastSeen == null) {
                    lastSeenTimes.put(targetId, attack.getTimestamp());
                    continue;
                }

                long reactionTime = attack.getTimestamp() - lastSeen;
                reactionTimes.add(reactionTime);
                lastSeenTimes.put(targetId, attack.getTimestamp());
            }
        }

        private void calculateAndAddStrafeConsistency(List<KillAuraCheck.AttackData> attacks) {
            if (attacks.size() < 8) return;

            List<KillAuraCheck.AttackData> movingAttacks = new ArrayList<>();
            List<KillAuraCheck.AttackData> stationaryAttacks = new ArrayList<>();

            for (KillAuraCheck.AttackData attack : attacks) {
                if (attack.getPlayerVelocity() > 0.1) {
                    movingAttacks.add(attack);
                } else {
                    stationaryAttacks.add(attack);
                }
            }

            if (movingAttacks.isEmpty() || stationaryAttacks.isEmpty()) return;

            double movingPrecision = calculateAveragePrecision(movingAttacks);
            double stationaryPrecision = calculateAveragePrecision(stationaryAttacks);

            if (stationaryPrecision > 0) {
                double consistency = movingPrecision / stationaryPrecision;
                strafeConsistencies.add(consistency);
            }
        }

        private void calculateAndAddPrecisionVariance(List<KillAuraCheck.AttackData> attacks) {
            Map<Integer, List<Double>> targetPrecisions = new HashMap<>();

            for (KillAuraCheck.AttackData attack : attacks) {
                int targetId = attack.getTargetEntityId();
                double precision = 1.0 / (1.0 + (attack.getYawDifference() + attack.getPitchDifference()) / 2.0);
                targetPrecisions.computeIfAbsent(targetId, k -> new ArrayList<>()).add(precision);
            }

            if (targetPrecisions.size() < 2) return;

            double varianceSum = 0.0;
            int count = 0;

            for (List<Double> precisions : targetPrecisions.values()) {
                if (precisions.size() >= 3) {
                    varianceSum += calculateVariance(precisions);
                    count++;
                }
            }

            if (count > 0) {
                precisionVariances.add(varianceSum / count);
            }
        }

        private double calculateAveragePrecision(List<KillAuraCheck.AttackData> attacks) {
            if (attacks.isEmpty()) return 0.0;

            double totalPrecision = 0.0;
            for (KillAuraCheck.AttackData attack : attacks) {
                totalPrecision += (attack.getYawDifference() + attack.getPitchDifference()) / 2.0;
            }

            return totalPrecision / attacks.size();
        }

        private double calculateVariance(List<Double> values) {
            if (values.size() < 2) return 0.0;

            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double sumSq = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();

            return sumSq / values.size();
        }

        private void trimLists() {
            if (predictionAccuracies.size() > MAX_SAMPLES) {
                predictionAccuracies.subList(0, predictionAccuracies.size() - MAX_SAMPLES).clear();
            }
            if (reactionTimes.size() > MAX_SAMPLES) {
                reactionTimes.subList(0, reactionTimes.size() - MAX_SAMPLES).clear();
            }
            if (strafeConsistencies.size() > MAX_SAMPLES) {
                strafeConsistencies.subList(0, strafeConsistencies.size() - MAX_SAMPLES).clear();
            }
            if (alternationRatios.size() > MAX_SAMPLES) {
                alternationRatios.subList(0, alternationRatios.size() - MAX_SAMPLES).clear();
            }
            if (precisionVariances.size() > MAX_SAMPLES) {
                precisionVariances.subList(0, precisionVariances.size() - MAX_SAMPLES).clear();
            }
        }

        public double getAveragePredictionAccuracy() {
            if (predictionAccuracies.isEmpty()) return 0.3;
            return predictionAccuracies.stream().mapToDouble(Double::doubleValue).average().orElse(0.3);
        }

        public double getAverageReactionTime() {
            if (reactionTimes.isEmpty()) return 200.0;
            return reactionTimes.stream().mapToLong(Long::longValue).average().orElse(200.0);
        }

        public double getAverageStrafeConsistency() {
            if (strafeConsistencies.isEmpty()) return 0.5;
            return strafeConsistencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
        }

        public double getAverageAlternationRatio() {
            if (alternationRatios.isEmpty()) return 0.3;
            return alternationRatios.stream().mapToDouble(Double::doubleValue).average().orElse(0.3);
        }

        public double getAveragePrecisionVariance() {
            if (precisionVariances.isEmpty()) return 0.05;
            return precisionVariances.stream().mapToDouble(Double::doubleValue).average().orElse(0.05);
        }

        public boolean isEstablished() {
            return sampleCount >= 8;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }

        public int getSampleCount() {
            return sampleCount;
        }
    }
}
