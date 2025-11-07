package eternalpolar.spigot.karoanticheat.Module.KillAura;

import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class KillAuraModel {
    private final Map<UUID, PlayerCombatProfile> playerProfiles = new ConcurrentHashMap<>();
    private final Map<String, Double> featureWeights = new HashMap<>();
    private final double anomalyThreshold;
    private final int trainingWindowSize;
    private final int minTrainingSamples;
    private static final double SMOOTHING_FACTOR = 0.15;
    private static final double MIN_STD_DEV = 0.001;
    private static final double HIT_TIMING_WEIGHT = 1.7;
    private static final double DAMAGE_CONSISTENCY_WEIGHT = 1.5;

    public KillAuraModel(double threshold, int windowSize, int minSamples) {
        this.anomalyThreshold = Math.max(0.5, threshold);
        this.trainingWindowSize = Math.max(10, windowSize);
        this.minTrainingSamples = Math.max(15, minSamples);
        initializeDefaultWeights();
    }

    private void initializeDefaultWeights() {
        featureWeights.put("attack_rate_consistency", 1.6);
        featureWeights.put("rotation_smoothness", 2.0);
        featureWeights.put("target_switch_frequency", 1.5);
        featureWeights.put("visibility_ignorance", 2.2);
        featureWeights.put("distance_consistency", 1.3);
        featureWeights.put("aim_precision", 1.8);
        featureWeights.put("sprint_attack_ratio", 1.4);
        featureWeights.put("movement_aim_correlation", 1.9);
        featureWeights.put("hit_timing_accuracy", HIT_TIMING_WEIGHT);
        featureWeights.put("damage_consistency", DAMAGE_CONSISTENCY_WEIGHT);
    }

    public void updateModel(Player player, List<KillAuraCheck.AttackData> attackHistory) {
        if (attackHistory == null || attackHistory.isEmpty()) return;

        UUID uuid = player.getUniqueId();
        PlayerCombatProfile profile = playerProfiles.computeIfAbsent(uuid, k -> new PlayerCombatProfile());

        CombatSample sample = createCombatSample(attackHistory);
        if (sample == null) return;

        profile.addSample(sample, trainingWindowSize);

        if (profile.getSampleCount() >= trainingWindowSize) {
            profile.trainModel(this);
        }
    }

    private CombatSample createCombatSample(List<KillAuraCheck.AttackData> attackHistory) {
        if (attackHistory.size() < 3) return null;

        int startIdx = Math.max(0, attackHistory.size() - 15);
        List<KillAuraCheck.AttackData> recentAttacks = new ArrayList<>(attackHistory.subList(startIdx, attackHistory.size()));

        if (recentAttacks.size() < 3) return null;

        return new CombatSample(
                System.currentTimeMillis(),
                calculateAttackRateConsistency(recentAttacks),
                calculateRotationSmoothness(recentAttacks),
                calculateTargetSwitchFrequency(recentAttacks),
                calculateVisibilityIgnorance(recentAttacks),
                calculateDistanceConsistency(recentAttacks),
                calculateAimPrecision(recentAttacks),
                calculateSprintAttackRatio(recentAttacks),
                calculateMovementAimCorrelation(recentAttacks),
                calculateHitTimingAccuracy(recentAttacks),
                calculateDamageConsistency(recentAttacks)
        );
    }

    public boolean isAnomaly(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerCombatProfile profile = playerProfiles.get(uuid);

        if (profile == null || profile.getSampleCount() < minTrainingSamples || profile.getFeatureStats().isEmpty()) {
            return false;
        }

        CombatSample latestSample = profile.getLatestSample();
        if (latestSample == null) return false;

        Map<String, Double> features = extractFeatures(latestSample);
        double anomalyScore = calculateAnomalyScore(features, profile);

        List<CombatSample> recentSamples = profile.getRecentSamples(5);
        if (recentSamples.size() >= 3) {
            double avgScore = recentSamples.stream()
                    .mapToDouble(s -> calculateAnomalyScore(extractFeatures(s), profile))
                    .average()
                    .orElse(0.0);
            double trendScore = calculateTrendScore(recentSamples, profile);
            anomalyScore = (anomalyScore * 0.5 + avgScore * 0.3 + trendScore * 0.2);
        }

        return anomalyScore > anomalyThreshold;
    }

    private double calculateTrendScore(List<CombatSample> samples, PlayerCombatProfile profile) {
        if (samples.size() < 3) return 0.0;

        double[] scores = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            scores[i] = calculateAnomalyScore(extractFeatures(samples.get(i)), profile);
        }

        double sum = 0.0;
        for (int i = 1; i < scores.length; i++) {
            sum += scores[i] - scores[i-1];
        }

        double trend = sum / (scores.length - 1);
        double lastScore = scores[scores.length - 1];

        return lastScore + (trend > 0 ? trend * 2 : 0);
    }

    private Map<String, Double> extractFeatures(CombatSample sample) {
        Map<String, Double> features = new HashMap<>();

        features.put("attack_rate_consistency", sample.attackRateConsistency);
        features.put("rotation_smoothness", sample.rotationSmoothness);
        features.put("target_switch_frequency", sample.targetSwitchFrequency);
        features.put("visibility_ignorance", sample.visibilityIgnorance);
        features.put("distance_consistency", sample.distanceConsistency);
        features.put("aim_precision", sample.aimPrecision);
        features.put("sprint_attack_ratio", sample.sprintAttackRatio);
        features.put("movement_aim_correlation", sample.movementAimCorrelation);
        features.put("hit_timing_accuracy", sample.hitTimingAccuracy);
        features.put("damage_consistency", sample.damageConsistency);

        return features;
    }

    private double calculateAnomalyScore(Map<String, Double> features, PlayerCombatProfile profile) {
        double score = 0.0;
        int validFeatureCount = 0;

        for (Map.Entry<String, Double> entry : features.entrySet()) {
            String feature = entry.getKey();
            double value = entry.getValue();
            double weight = featureWeights.getOrDefault(feature, 1.0);

            double deviation = calculateFeatureDeviation(feature, value, profile);
            if (deviation > 0) {
                score += deviation * weight;
                validFeatureCount++;
            }
        }

        return validFeatureCount > 0 ? score / validFeatureCount : 0.0;
    }

    private double calculateFeatureDeviation(String feature, double value, PlayerCombatProfile profile) {
        Map<String, FeatureStats> stats = profile.getFeatureStats();
        if (!stats.containsKey(feature)) return 0.0;

        FeatureStats stat = stats.get(feature);
        double mean = stat.mean;
        double std = Math.max(stat.standardDeviation, MIN_STD_DEV);

        double zScore = Math.abs(value - mean) / std;

        if (feature.equals("rotation_smoothness") || feature.equals("visibility_ignorance")) {
            zScore = Math.pow(zScore, 1.1);
        } else if (feature.equals("hit_timing_accuracy")) {
            zScore = Math.pow(zScore, 1.2);
        } else if (feature.equals("aim_precision")) {
            zScore = Math.pow(zScore, 1.15);
        }

        return Math.min(zScore, 6.0);
    }

    private double calculateAttackRateConsistency(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 4) return 0.0;

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < attacks.size(); i++) {
            long interval = attacks.get(i).getTimestamp() - attacks.get(i-1).getTimestamp();
            intervals.add(interval < 50 ? interval * 2 : interval);
        }

        return calculateVariance(intervals.stream().mapToDouble(l -> l).boxed().collect(Collectors.toList()));
    }

    private double calculateRotationSmoothness(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 4) return 0.0;

        List<Float> yawChanges = new ArrayList<>();
        List<Float> pitchChanges = new ArrayList<>();

        for (int i = 1; i < attacks.size(); i++) {
            float yawDiff = Math.abs(attacks.get(i).getYawDifference() - attacks.get(i-1).getYawDifference());
            float pitchDiff = Math.abs(attacks.get(i).getPitchDifference() - attacks.get(i-1).getPitchDifference());

            yawChanges.add(yawDiff < 1.5 ? yawDiff * 3 : yawDiff);
            pitchChanges.add(pitchDiff < 1.5 ? pitchDiff * 3 : pitchDiff);
        }

        double yawVariance = calculateVariance(yawChanges.stream().mapToDouble(f -> f).boxed().collect(Collectors.toList()));
        double pitchVariance = calculateVariance(pitchChanges.stream().mapToDouble(f -> f).boxed().collect(Collectors.toList()));

        return 1.0 / (1.0 + (yawVariance + pitchVariance) / 2.0);
    }

    private double calculateTargetSwitchFrequency(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 3) return 0.0;

        int switches = 0;
        int total = attacks.size() - 1;

        for (int i = 1; i < attacks.size(); i++) {
            if (attacks.get(i).getTargetEntityId() != attacks.get(i-1).getTargetEntityId()) {
                long timeDiff = attacks.get(i).getTimestamp() - attacks.get(i-1).getTimestamp();
                switches += timeDiff < 300 ? 2 : 1;
            }
        }

        return total > 0 ? (double) switches / total : 0.0;
    }

    private double calculateVisibilityIgnorance(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.isEmpty()) return 0.0;

        long invisibleAttacks = 0;
        for (KillAuraCheck.AttackData attack : attacks) {
            if (!attack.isVisible()) {
                invisibleAttacks += attack.getDistance() > 3.0 ? 2 : 1;
            }
        }

        return (double) invisibleAttacks / attacks.size();
    }

    private double calculateDistanceConsistency(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 4) return 0.0;

        List<Double> distances = attacks.stream()
                .map(ad -> {
                    double dist = ad.getDistance();
                    return dist > 4.5 ? dist * 1.5 : dist;
                })
                .collect(Collectors.toList());

        return calculateVariance(distances);
    }

    private double calculateAimPrecision(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.isEmpty()) return 0.0;

        double totalPrecision = 0.0;
        for (KillAuraCheck.AttackData attack : attacks) {
            double distanceFactor = Math.min(attack.getDistance() / 3.0, 2.0);
            totalPrecision += (1.0 / (1.0 + (attack.getYawDifference() + attack.getPitchDifference()) / 2.0)) * distanceFactor;
        }

        return totalPrecision / attacks.size();
    }

    private double calculateSprintAttackRatio(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 3) return 0.0;

        long sprintAttacks = attacks.stream().filter(KillAuraCheck.AttackData::isSprinting).count();
        if (sprintAttacks == 0) return 0.0;

        long preciseSprintAttacks = attacks.stream()
                .filter(a -> a.isSprinting() && a.getYawDifference() < 4 && a.getPitchDifference() < 4)
                .count();

        return (double) preciseSprintAttacks / sprintAttacks;
    }

    private double calculateMovementAimCorrelation(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 4) return 0.0;

        List<Double> movementValues = new ArrayList<>();
        List<Double> precisionValues = new ArrayList<>();

        for (KillAuraCheck.AttackData attack : attacks) {
            movementValues.add(attack.getPlayerVelocity());
            precisionValues.add(10.0 / (1.0 + (attack.getYawDifference() + attack.getPitchDifference()) / 2.0));
        }

        double correlation = calculateCorrelation(movementValues, precisionValues);
        return correlation < 0 ? -correlation : 0;
    }

    private double calculateHitTimingAccuracy(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 5) return 0.0;

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < attacks.size(); i++) {
            long interval = attacks.get(i).getTimestamp() - attacks.get(i-1).getTimestamp();
            intervals.add(interval);
        }

        if (intervals.size() < 4) return 0.0;

        double variance = calculateVariance(intervals.stream().mapToDouble(l -> l).boxed().collect(Collectors.toList()));
        double normalizedVariance = variance / 10000.0;

        return 1.0 / (1.0 + normalizedVariance);
    }

    private double calculateDamageConsistency(List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < 4) return 0.0;

        List<Double> damageValues = new ArrayList<>();
        for (KillAuraCheck.AttackData attack : attacks) {
            double distance = attack.getDistance();
            double damageFactor = 1.0;

            if (distance < 1.0) {
                damageFactor = 1.0;
            } else if (distance < 3.0) {
                damageFactor = 0.9;
            } else if (distance < 5.0) {
                damageFactor = 0.7;
            } else {
                damageFactor = 0.5;
            }

            double effectiveAccuracy = 1.0 / (1.0 + (attack.getYawDifference() + attack.getPitchDifference()) / 2.0);
            damageValues.add(effectiveAccuracy * damageFactor);
        }

        return calculateVariance(damageValues);
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

    public double calculateVariance(List<Double> values) {
        if (values.size() < 2) return 0.0;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumSq = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();

        return sumSq / values.size();
    }

    public void resetPlayerProfile(Player player) {
        if (player != null) {
            playerProfiles.remove(player.getUniqueId());
        }
    }

    public void resetPlayerProfileByUUID(UUID uuid) {
        playerProfiles.remove(uuid);
    }

    public boolean hasEnoughData(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerCombatProfile profile = playerProfiles.get(uuid);
        return profile != null && profile.getSampleCount() >= minTrainingSamples;
    }

    public static class PlayerCombatProfile {
        private final LinkedList<CombatSample> samples = new LinkedList<>();
        private final Map<String, FeatureStats> featureStats = new HashMap<>();
        private final Map<String, FeatureStats> previousStats = new HashMap<>();

        public synchronized void addSample(CombatSample sample, int maxSize) {
            if (sample == null) return;

            samples.addLast(sample);

            while (samples.size() > maxSize) {
                if (samples.isEmpty()) break;
                samples.removeFirst();
            }
        }

        public void trainModel(KillAuraModel model) {
            List<CombatSample> trainingSamples;
            synchronized (this) {
                if (samples.isEmpty()) return;
                trainingSamples = new ArrayList<>(samples);
            }

            calculateFeatureStatistic("attack_rate_consistency", trainingSamples, model);
            calculateFeatureStatistic("rotation_smoothness", trainingSamples, model);
            calculateFeatureStatistic("target_switch_frequency", trainingSamples, model);
            calculateFeatureStatistic("visibility_ignorance", trainingSamples, model);
            calculateFeatureStatistic("distance_consistency", trainingSamples, model);
            calculateFeatureStatistic("aim_precision", trainingSamples, model);
            calculateFeatureStatistic("sprint_attack_ratio", trainingSamples, model);
            calculateFeatureStatistic("movement_aim_correlation", trainingSamples, model);
            calculateFeatureStatistic("hit_timing_accuracy", trainingSamples, model);
            calculateFeatureStatistic("damage_consistency", trainingSamples, model);
        }

        private void calculateFeatureStatistic(String feature, List<CombatSample> samples, KillAuraModel model) {
            List<Double> featureValues = new ArrayList<>();

            for (CombatSample sample : samples) {
                if (sample == null) continue;

                double value = 0.0;
                switch (feature) {
                    case "attack_rate_consistency": value = sample.attackRateConsistency; break;
                    case "rotation_smoothness": value = sample.rotationSmoothness; break;
                    case "target_switch_frequency": value = sample.targetSwitchFrequency; break;
                    case "visibility_ignorance": value = sample.visibilityIgnorance; break;
                    case "distance_consistency": value = sample.distanceConsistency; break;
                    case "aim_precision": value = sample.aimPrecision; break;
                    case "sprint_attack_ratio": value = sample.sprintAttackRatio; break;
                    case "movement_aim_correlation": value = sample.movementAimCorrelation; break;
                    case "hit_timing_accuracy": value = sample.hitTimingAccuracy; break;
                    case "damage_consistency": value = sample.damageConsistency; break;
                }

                featureValues.add(value);
            }

            if (!featureValues.isEmpty()) {
                double mean = featureValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double std = calculateStandardDeviation(featureValues, mean);

                if (previousStats.containsKey(feature)) {
                    FeatureStats prev = previousStats.get(feature);
                    mean = prev.mean * (1 - SMOOTHING_FACTOR) + mean * SMOOTHING_FACTOR;
                    std = prev.standardDeviation * (1 - SMOOTHING_FACTOR) + std * SMOOTHING_FACTOR;
                }

                FeatureStats newStats = new FeatureStats(mean, std);
                featureStats.put(feature, newStats);
                previousStats.put(feature, newStats);
            }
        }

        private double calculateStandardDeviation(List<Double> values, double mean) {
            if (values.size() < 2) return 0.0;

            double sumSq = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();
            return Math.sqrt(sumSq / (values.size() - 1));
        }

        public synchronized int getSampleCount() {
            return samples.size();
        }

        public synchronized CombatSample getLatestSample() {
            return samples.isEmpty() ? null : samples.getLast();
        }

        public synchronized List<CombatSample> getRecentSamples(int count) {
            if (count <= 0 || samples.isEmpty()) return new ArrayList<>();
            int start = Math.max(0, samples.size() - count);
            return new ArrayList<>(samples.subList(start, samples.size()));
        }

        public Map<String, FeatureStats> getFeatureStats() {
            return new HashMap<>(featureStats);
        }
    }

    public static class CombatSample {
        final long timestamp;
        final double attackRateConsistency;
        final double rotationSmoothness;
        final double targetSwitchFrequency;
        final double visibilityIgnorance;
        final double distanceConsistency;
        final double aimPrecision;
        final double sprintAttackRatio;
        final double movementAimCorrelation;
        final double hitTimingAccuracy;
        final double damageConsistency;

        CombatSample(long timestamp, double attackRateConsistency, double rotationSmoothness,
                     double targetSwitchFrequency, double visibilityIgnorance,
                     double distanceConsistency, double aimPrecision,
                     double sprintAttackRatio, double movementAimCorrelation,
                     double hitTimingAccuracy, double damageConsistency) {
            this.timestamp = timestamp;
            this.attackRateConsistency = attackRateConsistency;
            this.rotationSmoothness = rotationSmoothness;
            this.targetSwitchFrequency = targetSwitchFrequency;
            this.visibilityIgnorance = visibilityIgnorance;
            this.distanceConsistency = distanceConsistency;
            this.aimPrecision = aimPrecision;
            this.sprintAttackRatio = sprintAttackRatio;
            this.movementAimCorrelation = movementAimCorrelation;
            this.hitTimingAccuracy = hitTimingAccuracy;
            this.damageConsistency = damageConsistency;
        }
    }

    public static class FeatureStats {
        final double mean;
        final double standardDeviation;

        public FeatureStats(double mean, double standardDeviation) {
            this.mean = mean;
            this.standardDeviation = standardDeviation;
        }
    }
}
