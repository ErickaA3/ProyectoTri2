package com.project.model.users;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Representa la tabla `user_stats`.
 */
public class Statistics {

    private UUID      userId;
    private int       xp;
    private int       level;
    private int       coins;
    private int       streakCurrent;
    private int       streakRecord;
    private LocalDate streakLastActivity;
    private boolean   hasStreakShield;

    // ── Constructores ──────────────────────────────────────────────────────────

    public Statistics() {}

    public Statistics(UUID userId) {
        this.userId = userId;
        this.xp            = 0;
        this.level         = 1;
        this.coins         = 0;
        this.streakCurrent = 0;
        this.streakRecord  = 0;
        this.hasStreakShield = false;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public UUID getUserId()                            { return userId; }
    public void setUserId(UUID userId)                 { this.userId = userId; }

    public int getXp()                                 { return xp; }
    public void setXp(int xp)                         { this.xp = xp; }

    public int getLevel()                              { return level; }
    public void setLevel(int level)                   { this.level = level; }

    public int getCoins()                              { return coins; }
    public void setCoins(int coins)                   { this.coins = coins; }

    public int getStreakCurrent()                      { return streakCurrent; }
    public void setStreakCurrent(int streakCurrent)   { this.streakCurrent = streakCurrent; }

    public int getStreakRecord()                       { return streakRecord; }
    public void setStreakRecord(int streakRecord)     { this.streakRecord = streakRecord; }

    public LocalDate getStreakLastActivity()           { return streakLastActivity; }
    public void setStreakLastActivity(LocalDate d)    { this.streakLastActivity = d; }

    public boolean isHasStreakShield()                 { return hasStreakShield; }
    public void setHasStreakShield(boolean b)         { this.hasStreakShield = b; }
}