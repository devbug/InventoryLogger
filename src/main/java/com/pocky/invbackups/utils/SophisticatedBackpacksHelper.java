package com.pocky.invbackups.utils;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import com.pocky.invbackups.InventoryBackupsMod;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Helper class for Sophisticated Backpacks integration
 * Handles optional dependency safely using reflection - works with or without the mod installed
 */
public class SophisticatedBackpacksHelper {

    private static final String BACKPACKS_MOD_ID = "sophisticatedbackpacks";
    private static final String CORE_MOD_ID = "sophisticatedcore";
    
    private static Boolean modLoaded = null;
    
    // Reflection cache
    private static Class<?> backpackStorageClass;
    private static Method getStorageMethod;
    private static Method getContentsMethod;
    private static Method setContentsMethod;
    private static Object storageInstance;
    private static DataComponentType<?> storageUuidComponent;

    /**
     * Check if Sophisticated Backpacks mod is loaded
     */
    public static boolean isBackpacksLoaded() {
        if (modLoaded == null) {
            modLoaded = ModList.get().isLoaded(BACKPACKS_MOD_ID) && 
                        ModList.get().isLoaded(CORE_MOD_ID);
            if (modLoaded) {
                InventoryBackupsMod.LOGGER.info("Sophisticated Backpacks detected - backpack contents will be fully preserved in backups");
            } else {
                InventoryBackupsMod.LOGGER.debug("Sophisticated Backpacks not found - backpack snapshot feature disabled");
            }
        }
        return modLoaded;
    }

    /**
     * Check if an ItemStack is a Sophisticated Backpack
     */
    public static boolean isSophisticatedBackpack(ItemStack stack) {
        if (stack.isEmpty() || !isBackpacksLoaded()) {
            return false;
        }
        
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return itemId != null && itemId.getNamespace().equals(BACKPACKS_MOD_ID);
    }

    /**
     * Extract UUID from a backpack ItemStack
     * Returns null if no UUID is present or if mod is not loaded
     */
    public static UUID getBackpackUuid(ItemStack backpack) {
        if (!isBackpacksLoaded() || !isSophisticatedBackpack(backpack)) {
            return null;
        }
        
        try {
            DataComponentType<?> uuidComponent = getStorageUuidComponent();
            if (uuidComponent == null) {
                return null;
            }
            
            Object uuidObj = backpack.get(uuidComponent);
            return uuidObj instanceof UUID ? (UUID) uuidObj : null;
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to extract backpack UUID", e);
            return null;
        }
    }

    /**
     * Set UUID for a backpack ItemStack
     */
    public static void setBackpackUuid(ItemStack backpack, UUID uuid) {
        if (!isBackpacksLoaded() || !isSophisticatedBackpack(backpack) || uuid == null) {
            return;
        }
        
        try {
            DataComponentType<?> uuidComponent = getStorageUuidComponent();
            if (uuidComponent == null) {
                return;
            }
            
            // Cast to raw type to avoid generic issues
            @SuppressWarnings("unchecked")
            DataComponentType<UUID> typedComponent = (DataComponentType<UUID>) uuidComponent;
            backpack.set(typedComponent, uuid);
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to set backpack UUID", e);
        }
    }

    /**
     * Get a snapshot of backpack contents from BackpackStorage
     * Returns null if backpack has no UUID or mod is not loaded
     */
    public static CompoundTag getBackpackSnapshot(UUID backpackUuid) {
        if (!isBackpacksLoaded() || backpackUuid == null) {
            return null;
        }
        
        try {
            Object storage = getBackpackStorage();
            if (storage == null) {
                return null;
            }
            
            if (getContentsMethod == null) {
                getContentsMethod = backpackStorageClass.getDeclaredMethod(
                    "getOrCreateBackpackContents", UUID.class
                );
            }
            
            CompoundTag contents = (CompoundTag) getContentsMethod.invoke(storage, backpackUuid);
            
            // Create a deep copy (snapshot) to preserve the state
            return contents != null ? contents.copy() : null;
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to get backpack snapshot for UUID: " + backpackUuid, e);
            return null;
        }
    }

    /**
     * Restore backpack contents to BackpackStorage
     * Creates a new entry with the given UUID and data
     */
    public static void restoreBackpackSnapshot(UUID backpackUuid, CompoundTag snapshot) {
        if (!isBackpacksLoaded() || backpackUuid == null || snapshot == null) {
            return;
        }
        
        try {
            Object storage = getBackpackStorage();
            if (storage == null) {
                return;
            }
            
            if (setContentsMethod == null) {
                setContentsMethod = backpackStorageClass.getDeclaredMethod(
                    "setBackpackContents", UUID.class, CompoundTag.class
                );
            }
            
            // Create a copy to avoid modifying the original snapshot
            setContentsMethod.invoke(storage, backpackUuid, snapshot.copy());
            InventoryBackupsMod.LOGGER.debug("Restored backpack snapshot for UUID: " + backpackUuid);
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to restore backpack snapshot for UUID: " + backpackUuid, e);
        }
    }

    /**
     * Create a new backpack with copied data but a new UUID
     * This ensures that copied backpacks don't share the same storage
     */
    public static ItemStack copyBackpackWithNewUuid(ItemStack originalBackpack) {
        if (!isSophisticatedBackpack(originalBackpack)) {
            return originalBackpack.copy();
        }
        
        UUID originalUuid = getBackpackUuid(originalBackpack);
        
        // If no UUID, just copy normally (empty backpack)
        if (originalUuid == null) {
            return originalBackpack.copy();
        }
        
        // Get snapshot of original contents
        CompoundTag snapshot = getBackpackSnapshot(originalUuid);
        
        // Create new backpack with basic properties copied
        ItemStack newBackpack = originalBackpack.copy();
        
        // Remove the UUID tag (will be regenerated on first use)
        // This makes it a "fresh" backpack that will get a new UUID when opened
        try {
            DataComponentType<?> uuidComponent = getStorageUuidComponent();
            if (uuidComponent != null) {
                newBackpack.remove(uuidComponent);
            }
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to remove UUID from copied backpack", e);
        }
        
        // If we have a snapshot, create a new UUID and restore the data immediately
        if (snapshot != null) {
            UUID newUuid = UUID.randomUUID();
            setBackpackUuid(newBackpack, newUuid);
            restoreBackpackSnapshot(newUuid, snapshot);
        }
        
        return newBackpack;
    }

    // ========== Private Helper Methods ==========

    /**
     * Get the BackpackStorage singleton instance via reflection
     */
    private static Object getBackpackStorage() {
        if (storageInstance != null) {
            return storageInstance;
        }
        
        try {
            if (backpackStorageClass == null) {
                backpackStorageClass = Class.forName(
                    "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage"
                );
            }
            
            if (getStorageMethod == null) {
                getStorageMethod = backpackStorageClass.getDeclaredMethod("get");
            }
            
            storageInstance = getStorageMethod.invoke(null);
            return storageInstance;
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to access BackpackStorage", e);
            return null;
        }
    }

    /**
     * Get the DataComponentType for storage UUID
     */
    private static DataComponentType<?> getStorageUuidComponent() {
        if (storageUuidComponent != null) {
            return storageUuidComponent;
        }
        
        try {
            ResourceLocation componentId = ResourceLocation.fromNamespaceAndPath(
                CORE_MOD_ID, "storage_uuid"
            );
            
            Registry<DataComponentType<?>> registry = BuiltInRegistries.DATA_COMPONENT_TYPE;
            storageUuidComponent = registry.get(componentId);
            
            if (storageUuidComponent == null) {
                InventoryBackupsMod.LOGGER.warn("Could not find sophisticatedcore:storage_uuid component");
            }
            
            return storageUuidComponent;
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to get storage UUID component", e);
            return null;
        }
    }

    /**
     * Reset cached reflection objects (useful for testing or hot reload)
     */
    public static void resetCache() {
        modLoaded = null;
        backpackStorageClass = null;
        getStorageMethod = null;
        getContentsMethod = null;
        setContentsMethod = null;
        storageInstance = null;
        storageUuidComponent = null;
    }
}
