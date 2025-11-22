package com.pocky.invbackups.utils;

import com.pocky.invbackups.InventoryBackupsMod;
import com.pocky.invbackups.config.InventoryConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class BackupCleanupService {

    private static final Path INVENTORY_BACKUP_DIR = Path.of("InventoryLog/inventory/");
    private static final Path ENDERCHEST_BACKUP_DIR = Path.of("InventoryLog/enderchest/");
    
    private static boolean cleanupEnabled = false;
    private static boolean initializationFailed = false;

    /**
     * Initialize and validate cleanup service
     * Called once during server startup
     * @throws RuntimeException if initialization fails
     */
    public static void initialize() {
        try {
            // Validate configuration access
            int retentionDays = InventoryConfig.general.retentionDays.get();
            
            if (retentionDays < 1) {
                throw new IllegalStateException("retentionDays must be at least 1, got: " + retentionDays);
            }
            
            // Validate directories can be created
            if (!INVENTORY_BACKUP_DIR.toFile().exists()) {
                INVENTORY_BACKUP_DIR.toFile().mkdirs();
            }
            if (!ENDERCHEST_BACKUP_DIR.toFile().exists()) {
                ENDERCHEST_BACKUP_DIR.toFile().mkdirs();
            }
            
            cleanupEnabled = true;
            InventoryBackupsMod.LOGGER.info("Backup cleanup service initialized successfully (retention: {} days)", retentionDays);
            
        } catch (Exception e) {
            initializationFailed = true;
            InventoryBackupsMod.LOGGER.error("CRITICAL: Failed to initialize backup cleanup service!", e);
            throw new RuntimeException("Backup cleanup initialization failed - server cannot start safely", e);
        }
    }

    /**
     * Deletes backup files older than the configured retention period
     * Safe to call periodically - will not crash server if cleanup fails
     */
    public static void cleanupOldBackups() {
        // Skip if initialization failed
        if (initializationFailed) {
            InventoryBackupsMod.LOGGER.warn("Skipping backup cleanup - service initialization failed");
            return;
        }
        
        // Skip if not enabled
        if (!cleanupEnabled) {
            InventoryBackupsMod.LOGGER.warn("Skipping backup cleanup - service not initialized. Call initialize() first.");
            return;
        }
        
        try {
            int retentionDays = InventoryConfig.general.retentionDays.get();
            Instant cutoffTime = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

            int inventoryDeleted = cleanupDirectory(INVENTORY_BACKUP_DIR, cutoffTime);
            int enderChestDeleted = cleanupDirectory(ENDERCHEST_BACKUP_DIR, cutoffTime);

            int totalDeleted = inventoryDeleted + enderChestDeleted;
            if (totalDeleted > 0) {
                InventoryBackupsMod.LOGGER.info("Backup cleanup completed: deleted " + inventoryDeleted +
                    " inventory backup(s) and " + enderChestDeleted + " ender chest backup(s)");
            }
        } catch (Exception e) {
            // Runtime cleanup failure should not crash server
            // Players' game experience is more important than backup cleanup
            InventoryBackupsMod.LOGGER.error("Backup cleanup failed (non-critical)", e);
        }
    }

    private static int cleanupDirectory(Path backupDir, Instant cutoffTime) {
        File backupDirFile = backupDir.toFile();
        if (!backupDirFile.exists() || !backupDirFile.isDirectory()) {
            return 0;
        }

        int deletedCount = 0;
        int errorCount = 0;

        // Iterate through player UUID directories
        File[] playerDirs = backupDirFile.listFiles(File::isDirectory);
        if (playerDirs == null) return 0;

        for (File playerDir : playerDirs) {
            File[] backupFiles = playerDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (backupFiles == null) continue;

            for (File backupFile : backupFiles) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(backupFile.toPath(), BasicFileAttributes.class);
                    Instant fileTime = attrs.creationTime().toInstant();

                    if (fileTime.isBefore(cutoffTime)) {
                        if (backupFile.delete()) {
                            deletedCount++;
                            InventoryBackupsMod.LOGGER.debug("Deleted old backup: " + backupFile.getName());
                        } else {
                            errorCount++;
                            InventoryBackupsMod.LOGGER.warn("Failed to delete backup: " + backupFile.getName());
                        }
                    }
                } catch (IOException e) {
                    errorCount++;
                    InventoryBackupsMod.LOGGER.error("Error checking backup file: " + backupFile.getName(), e);
                }
            }

            // Delete empty player directories
            if (playerDir.listFiles() != null && playerDir.listFiles().length == 0) {
                if (playerDir.delete()) {
                    InventoryBackupsMod.LOGGER.debug("Deleted empty player directory: " + playerDir.getName());
                }
            }
        }

        if (errorCount > 0) {
            InventoryBackupsMod.LOGGER.warn("Backup cleanup had " + errorCount + " error(s) in " + backupDir);
        }

        return deletedCount;
    }
}
