package com.pocky.invbackups.data;

import net.minecraft.server.level.ServerPlayer;

import java.io.Serializable;
import java.util.Objects;

/**
 * Stores player experience data for backup/restore
 * 
 * Minecraft player experience consists of 3 values:
 * - experienceLevel: The green number shown above hotbar
 * - experienceProgress: Progress towards next level (0.0 to 1.0)
 * - totalExperience: Total XP points accumulated
 */
public class ExperienceData implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Experience level (green number above hotbar)
     */
    private int experienceLevel;
    
    /**
     * Progress towards next level (0.0 to 1.0)
     */
    private float experienceProgress;
    
    /**
     * Total experience points accumulated
     */
    private int totalExperience;
    
    /**
     * Default constructor (no experience)
     */
    public ExperienceData() {
        this(0, 0.0f, 0);
    }
    
    /**
     * Constructor with all experience values
     */
    public ExperienceData(int level, float progress, int total) {
        this.experienceLevel = level;
        // Clamp progress to valid range [0.0, 1.0]
        this.experienceProgress = Math.max(0.0f, Math.min(1.0f, progress));
        this.totalExperience = total;
    }
    
    /**
     * Create ExperienceData from a ServerPlayer
     * @param player The player to extract experience from
     * @return ExperienceData instance
     */
    public static ExperienceData fromPlayer(ServerPlayer player) {
        if (player == null) {
            return new ExperienceData();
        }
        
        return new ExperienceData(
            player.experienceLevel,
            player.experienceProgress,
            player.totalExperience
        );
    }
    
    /**
     * Apply this experience data to a ServerPlayer
     * Also syncs to client
     * @param player The player to apply experience to
     */
    public void applyToPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        
        player.experienceLevel = this.experienceLevel;
        player.experienceProgress = this.experienceProgress;
        player.totalExperience = this.totalExperience;
        
        // Sync to client - CRITICAL for display update
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetExperiencePacket(
            player.experienceProgress,
            player.totalExperience,
            player.experienceLevel
        ));
    }
    
    /**
     * Check if player has any experience
     * @return true if any experience value is non-zero
     */
    public boolean hasExperience() {
        return experienceLevel > 0 || experienceProgress > 0 || totalExperience > 0;
    }
    
    /**
     * Get formatted display string for GUI
     * @return Formatted string like "Lv.30 (50%)"
     */
    public String getDisplayString() {
        if (!hasExperience()) {
            return "Lv.0 (0%)";
        }
        int progressPercent = (int)(experienceProgress * 100);
        return String.format("Lv.%d (%d%%)", experienceLevel, progressPercent);
    }
    
    // Getters and Setters
    
    public int getExperienceLevel() {
        return experienceLevel;
    }
    
    public void setExperienceLevel(int experienceLevel) {
        this.experienceLevel = experienceLevel;
    }
    
    public float getExperienceProgress() {
        return experienceProgress;
    }
    
    public void setExperienceProgress(float experienceProgress) {
        this.experienceProgress = Math.max(0.0f, Math.min(1.0f, experienceProgress));
    }
    
    public int getTotalExperience() {
        return totalExperience;
    }
    
    public void setTotalExperience(int totalExperience) {
        this.totalExperience = totalExperience;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExperienceData that = (ExperienceData) o;
        return experienceLevel == that.experienceLevel &&
               Float.compare(that.experienceProgress, experienceProgress) == 0 &&
               totalExperience == that.totalExperience;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(experienceLevel, experienceProgress, totalExperience);
    }
    
    @Override
    public String toString() {
        return String.format("ExperienceData{level=%d, progress=%.2f%%, total=%d}", 
            experienceLevel, experienceProgress * 100, totalExperience);
    }
}
