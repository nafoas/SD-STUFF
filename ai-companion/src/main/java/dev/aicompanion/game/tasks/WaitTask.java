package dev.aicompanion.game.tasks;

import dev.aicompanion.entity.CompanionEntity;

public class WaitTask extends Task {
    private final int totalTicks;

    public WaitTask(int seconds) {
        this.totalTicks = Math.max(1, Math.min(300, seconds)) * 20;
    }

    @Override
    public boolean isMajor() {
        return false;
    }

    @Override
    public String describe() {
        return "waiting";
    }

    @Override
    protected Result step(CompanionEntity c) {
        return ticks >= totalTicks ? ok("Waited " + totalTicks / 20 + " seconds.") : null;
    }
}
