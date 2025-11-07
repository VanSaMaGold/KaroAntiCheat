package eternalpolar.spigot.karoanticheat.Check;

import org.bukkit.configuration.file.FileConfiguration;

public class ProgressBar {
    private final boolean enabled;
    private final String filledChar;
    private final String emptyChar;
    private final String filledColor;
    private final String emptyColor;
    private final int length;

    public ProgressBar(FileConfiguration config) {
        this.enabled = config.getBoolean("progress-bar.enabled", true);
        this.filledChar = config.getString("progress-bar.filled-character", "|");
        this.emptyChar = config.getString("progress-bar.empty-character", "-");
        this.filledColor = config.getString("progress-bar.filled-color", "&c");
        this.emptyColor = config.getString("progress-bar.empty-color", "&7");
        this.length = config.getInt("progress-bar.length", 10);
    }

    public String generate(int current, int max) {
        if (!enabled || max <= 0) return "";

        double ratio = (double) current / max;
        int filled = (int) Math.ceil(ratio * length);
        filled = Math.min(filled, length);

        StringBuilder sb = new StringBuilder();
        sb.append(filledColor);
        sb.append(Check.repeat(filledChar, filled));
        sb.append(emptyColor);
        sb.append(Check.repeat(emptyChar, length - filled));

        return sb.toString().replace("&", "§");
    }
}
