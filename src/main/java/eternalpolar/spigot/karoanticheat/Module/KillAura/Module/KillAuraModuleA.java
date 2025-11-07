package eternalpolar.spigot.karoanticheat.Module.KillAura.Module;

import eternalpolar.spigot.karoanticheat.Module.KillAura.KillAuraCheck;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KillAuraModuleA {
    private final KillAuraCheck parent;
    private boolean enabled;
    private long maxAttackRate;
    private float rotationThreshold;
    private long backtrackTolerance;
    private int minimumAttacks;
    private float sprintAttackThreshold;
    private int attackSequenceThreshold;

    public KillAuraModuleA(KillAuraCheck parent) {
        this.parent = parent;
        this.enabled = true;
        loadConfig();
    }

    public void loadConfig() {
        this.maxAttackRate = parent.config.getLong("modules.A.max-attack-rate", 150);
        this.rotationThreshold = (float) parent.config.getDouble("modules.A.rotation-threshold", 30.0);
        this.backtrackTolerance = parent.config.getLong("modules.A.backtrack-tolerance", 150);
        this.minimumAttacks = parent.config.getInt("modules.A.minimum-attacks", 5);
        this.sprintAttackThreshold = (float) parent.config.getDouble("modules.A.sprint-attack-threshold", 2.5);
        this.attackSequenceThreshold = parent.config.getInt("modules.A.attack-sequence-threshold", 3);
    }

    public boolean check(Player player) {
        if (!enabled) return false;

        UUID uuid = player.getUniqueId();
        List<KillAuraCheck.AttackData> attacks = parent.getAttackHistory().getOrDefault(uuid, null);

        if (attacks == null || attacks.size() < minimumAttacks) return false;

        double fastAttackConfidence = checkAttackSpeedConfidence(uuid);
        double rotationConfidence = checkRotationPatternConfidence(uuid, attacks);
        double backtrackConfidence = checkBacktrackingConfidence(uuid, attacks);
        double sprintAttackConfidence = checkSprintAttackPattern(uuid, attacks);
        double attackSequenceConfidence = checkAttackSequencePattern(uuid, attacks);

        int highConfidenceCount = 0;
        if (fastAttackConfidence > 0.7) highConfidenceCount++;
        if (rotationConfidence > 0.7) highConfidenceCount++;
        if (backtrackConfidence > 0.7) highConfidenceCount++;
        if (sprintAttackConfidence > 0.75) highConfidenceCount++;
        if (attackSequenceConfidence > 0.8) highConfidenceCount++;

        return highConfidenceCount >= 2 ||
                (fastAttackConfidence > 0.9) ||
                (rotationConfidence > 0.9) ||
                (backtrackConfidence > 0.9) ||
                (attackSequenceConfidence > 0.9);
    }

    private double checkAttackSpeedConfidence(UUID uuid) {
        List<Long> intervals = parent.getAttackIntervals(uuid);
        if (intervals == null || intervals.isEmpty() || intervals.size() < minimumAttacks - 1) return 0.0;

        int fastCount = 0;
        int veryFastCount = 0;
        long totalInterval = 0;

        for (long interval : intervals) {
            totalInterval += interval;
            if (interval < maxAttackRate) {
                fastCount++;
                if (interval < maxAttackRate * 0.7) {
                    veryFastCount++;
                }
            }
        }

        double avgInterval = (double) totalInterval / intervals.size();
        double normalizedInterval = Math.max(0, 1 - avgInterval / maxAttackRate);

        double baseConfidence = (double) fastCount / intervals.size();
        double weightedConfidence = baseConfidence * 0.7 + (double) veryFastCount / intervals.size() * 0.3;

        return Math.min(weightedConfidence + normalizedInterval * 0.2, 1.0);
    }

    private double checkRotationPatternConfidence(UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        int suspiciousRotations = 0;
        int totalPossibleSwitches = 0;
        float totalYawDiff = 0;
        int yawSamples = 0;

        for (int i = 1; i < attacks.size(); i++) {
            KillAuraCheck.AttackData prev = attacks.get(i-1);
            KillAuraCheck.AttackData curr = attacks.get(i);

            totalYawDiff += Math.abs(curr.getYawDifference());
            yawSamples++;

            if (prev.getTargetEntityId() != curr.getTargetEntityId()) {
                totalPossibleSwitches++;
                float yawDiff = Math.abs(curr.getYawDifference() - prev.getYawDifference());
                long timeDiff = curr.getTimestamp() - prev.getTimestamp();

                if (yawDiff > rotationThreshold && timeDiff < 200) {
                    suspiciousRotations++;
                }
            }
        }

        if (totalPossibleSwitches == 0) return 0.0;

        float avgYawDiff = yawSamples > 0 ? totalYawDiff / yawSamples : 0;
        double yawConsistency = avgYawDiff < 0.5 ? 0.3 : 0;

        return Math.min((double) suspiciousRotations / totalPossibleSwitches + yawConsistency, 1.0);
    }

    private double checkBacktrackingConfidence(UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        int backtrackCount = 0;
        int totalChecks = 0;
        int hiddenBacktrackCount = 0;

        Map<Integer, Long> targetMap = parent.getTargetTracking().getOrDefault(uuid, null);
        if (targetMap == null) return 0.0;

        for (KillAuraCheck.AttackData attack : attacks) {
            totalChecks++;
            Long lastSeen = targetMap.getOrDefault(attack.getTargetEntityId(), 0L);

            if (lastSeen == null) continue;

            long timeDiff = attack.getTimestamp() - lastSeen;
            if (timeDiff > backtrackTolerance) {
                if (!attack.isVisible()) {
                    backtrackCount++;
                    if (timeDiff > backtrackTolerance * 2) {
                        hiddenBacktrackCount++;
                    }
                }
            }
        }

        if (totalChecks == 0) return 0.0;

        double baseConfidence = (double) backtrackCount / totalChecks;
        double weightedConfidence = baseConfidence * 0.7 + (double) hiddenBacktrackCount / totalChecks * 0.3;

        return Math.min(weightedConfidence, 1.0);
    }

    private double checkSprintAttackPattern(UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        int sprintAttacks = 0;
        int preciseSprintAttacks = 0;

        for (KillAuraCheck.AttackData attack : attacks) {
            if (attack.isSprinting()) {
                sprintAttacks++;
                float totalDiff = attack.getYawDifference() + attack.getPitchDifference();
                if (totalDiff < sprintAttackThreshold) {
                    preciseSprintAttacks++;
                }
            }
        }

        if (sprintAttacks < 3) return 0.0;

        double precisionRatio = (double) preciseSprintAttacks / sprintAttacks;
        double sprintAttackFrequency = (double) sprintAttacks / attacks.size();

        return precisionRatio > 0.8 && sprintAttackFrequency > 0.5 ?
                Math.min(precisionRatio + sprintAttackFrequency * 0.2, 1.0) : 0.0;
    }

    private double checkAttackSequencePattern(UUID uuid, List<KillAuraCheck.AttackData> attacks) {
        if (attacks.size() < attackSequenceThreshold + 1) return 0.0;

        int perfectSequences = 0;
        int totalSequences = attacks.size() - attackSequenceThreshold;

        for (int i = 0; i <= attacks.size() - attackSequenceThreshold; i++) {
            boolean isPerfectSequence = true;
            double perfectSequenceVariance = 0;
            long firstTime = attacks.get(i).getTimestamp();

            for (int j = i; j < i + attackSequenceThreshold; j++) {
                long timeDiff = attacks.get(j).getTimestamp() - firstTime;
                float expectedTime = j == i ? 0 : (j - i) * maxAttackRate * 1.2f;

                if (Math.abs(timeDiff - expectedTime) > maxAttackRate * 0.3f) {
                    isPerfectSequence = false;
                    break;
                }

                float aimDiff = attacks.get(j).getYawDifference() + attacks.get(j).getPitchDifference();
                perfectSequenceVariance += aimDiff;
            }

            if (isPerfectSequence) {
                float avgAimDiff = (float) (perfectSequenceVariance / attackSequenceThreshold);
                if (avgAimDiff < 1.5f) {
                    perfectSequences++;
                }
            }
        }

        if (totalSequences == 0) return 0.0;

        return (double) perfectSequences / totalSequences;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getMaxAttackRate() {
        return maxAttackRate;
    }

    public float getRotationThreshold() {
        return rotationThreshold;
    }

    public long getBacktrackTolerance() {
        return backtrackTolerance;
    }
}
