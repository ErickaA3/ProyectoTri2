package com.project.model.gamification;

import java.util.UUID;

/**
 * Representa una entrada del ranking en la leaderboard de duelos.
 * Combina datos de: duels (victorias), user_stats (xp, streak), users (username).
 */
public class LeaderboardEntry {

    private UUID   userId;
    private String username;
    private int    wins;
    private int    xp;
    private int    streak;
    private int    rankPosition;   // posición calculada (1-based)

    // ── Constructores ─────────────────────────────────────────────

    public LeaderboardEntry() {}

    public LeaderboardEntry(UUID userId, String username, int wins, int xp, int streak) {
        this.userId   = userId;
        this.username = username;
        this.wins     = wins;
        this.xp       = xp;
        this.streak   = streak;
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public UUID   getUserId()                    { return userId; }
    public void   setUserId(UUID userId)         { this.userId = userId; }

    public String getUsername()                  { return username; }
    public void   setUsername(String username)   { this.username = username; }

    public int    getWins()                      { return wins; }
    public void   setWins(int wins)              { this.wins = wins; }

    public int    getXp()                        { return xp; }
    public void   setXp(int xp)                 { this.xp = xp; }

    public int    getStreak()                    { return streak; }
    public void   setStreak(int streak)          { this.streak = streak; }

    public int    getRankPosition()              { return rankPosition; }
    public void   setRankPosition(int rank)      { this.rankPosition = rank; }

    @Override
    public String toString() {
        return "LeaderboardEntry{rank=" + rankPosition + ", username='" + username
               + "', wins=" + wins + ", xp=" + xp + ", streak=" + streak + "}";
    }
}