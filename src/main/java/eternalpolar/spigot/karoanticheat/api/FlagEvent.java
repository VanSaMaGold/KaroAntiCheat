package eternalpolar.spigot.karoanticheat.api;

import eternalpolar.spigot.karoanticheat.Check.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class FlagEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final Check check;
    private final int currentVl;
    private final String details;
    private boolean cancelled;

    public FlagEvent(Player player, Check check, int currentVl, String details) {
        this.player = player;
        this.check = check;
        this.currentVl = currentVl;
        this.details = details;
        this.cancelled = false;
    }

    public Player getPlayer() {
        return player;
    }

    public Check getCheck() {
        return check;
    }

    public int getCurrentVl() {
        return currentVl;
    }

    public String getDetails() {
        return details;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}