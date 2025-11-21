package com.pocky.invbackups.io;

import com.pocky.invbackups.InventoryBackupsMod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous backup executor to prevent TPS drops from disk I/O
 */
public class AsyncBackupExecutor {
    
    // Dedicated thread pool for backups (max 2 threads - disk I/O doesn't benefit from many threads)
    private static final ExecutorService BACKUP_EXECUTOR = Executors.newFixedThreadPool(
        2,
        r -> {
            Thread t = new Thread(r, "InventoryBackup-IO-Worker");
            t.setDaemon(true);  // Shutdown with server
            t.setPriority(Thread.NORM_PRIORITY - 1);  // Lower priority
            return t;
        }
    );
    
    // Pending tasks counter
    private static final AtomicInteger pendingTasks = new AtomicInteger(0);
    private static final int MAX_PENDING_TASKS = 50;  // Maximum queued tasks
    
    /**
     * Save backup asynchronously
     */
    public static CompletableFuture<Void> saveAsync(Runnable saveTask, String description) {
        // Reject if too many pending tasks
        if (pendingTasks.get() > MAX_PENDING_TASKS) {
            InventoryBackupsMod.LOGGER.warn("Too many pending backups ({}), skipping: {}", 
                pendingTasks.get(), description);
            return CompletableFuture.completedFuture(null);
        }
        
        pendingTasks.incrementAndGet();
        
        return CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                saveTask.run();
                long duration = System.currentTimeMillis() - startTime;
                
                if (duration > 50) {
                    InventoryBackupsMod.LOGGER.warn(
                        "Slow backup save: {} took {}ms", description, duration);
                } else {
                    InventoryBackupsMod.LOGGER.debug(
                        "Backup saved: {} ({}ms)", description, duration);
                }
            } catch (Exception e) {
                InventoryBackupsMod.LOGGER.error("Failed to save backup: " + description, e);
            } finally {
                pendingTasks.decrementAndGet();
            }
        }, BACKUP_EXECUTOR);
    }
    
    /**
     * Shutdown executor and wait for all tasks to complete
     */
    public static void shutdown() {
        InventoryBackupsMod.LOGGER.info("Shutting down backup executor, waiting for {} pending tasks...", 
            pendingTasks.get());
        
        BACKUP_EXECUTOR.shutdown();
        try {
            if (!BACKUP_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                InventoryBackupsMod.LOGGER.warn("Forcing backup executor shutdown");
                BACKUP_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            BACKUP_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get number of pending tasks
     */
    public static int getPendingTaskCount() {
        return pendingTasks.get();
    }
}
