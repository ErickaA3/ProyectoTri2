package com.project.model.users;

import java.time.LocalDate;
import java.util.UUID;

public class DailyMission {

    private UUID userId;
    private int missionId;
    private LocalDate date;
    private int progress;
    private boolean completed;

    // Datos de la misión (JOIN con missions)
    private String description;
    private String type;
    private int requiredCount;
    private int xpReward;
    private int coinReward;

    public DailyMission() {}

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public int getMissionId() { return missionId; }
    public void setMissionId(int missionId) { this.missionId = missionId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getRequiredCount() { return requiredCount; }
    public void setRequiredCount(int requiredCount) { this.requiredCount = requiredCount; }

    public int getXpReward() { return xpReward; }
    public void setXpReward(int xpReward) { this.xpReward = xpReward; }

    public int getCoinReward() { return coinReward; }
    public void setCoinReward(int coinReward) { this.coinReward = coinReward; }
}