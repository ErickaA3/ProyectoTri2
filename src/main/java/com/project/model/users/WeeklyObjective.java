package com.project.model.users;

import java.time.LocalDate;
import java.util.UUID;

public class WeeklyObjective {

    private UUID userId;
    private String type;
    private LocalDate weekStart;
    private String objectiveDescription;
    private int requiredCount;
    private int progress;
    private boolean completed;
    private int xpReward;
    private int coinReward;

    public WeeklyObjective() {}

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public LocalDate getWeekStart() { return weekStart; }
    public void setWeekStart(LocalDate weekStart) { this.weekStart = weekStart; }

    public String getObjectiveDescription() { return objectiveDescription; }
    public void setObjectiveDescription(String objectiveDescription) { this.objectiveDescription = objectiveDescription; }

    public int getRequiredCount() { return requiredCount; }
    public void setRequiredCount(int requiredCount) { this.requiredCount = requiredCount; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public int getXpReward() { return xpReward; }
    public void setXpReward(int xpReward) { this.xpReward = xpReward; }

    public int getCoinReward() { return coinReward; }
    public void setCoinReward(int coinReward) { this.coinReward = coinReward; }
}