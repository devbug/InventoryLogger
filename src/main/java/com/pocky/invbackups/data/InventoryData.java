package com.pocky.invbackups.data;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.JsonUtils;
import com.pocky.invbackups.io.JsonFileHandler;
import com.pocky.invbackups.io.AsyncBackupExecutor;
import com.pocky.invbackups.utils.CuriosHelper;
import com.pocky.invbackups.utils.SophisticatedBackpacksHelper;
import com.pocky.invbackups.InventoryBackupsMod;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class InventoryData implements Serializable {

    /**
     * Хранит id слота и предмет в формате строки
     */
    List<ItemData> data = new ArrayList<>();
    
    /**
     * Stores backpack content snapshots (UUID -> NBT data)
     * This ensures backpack contents are preserved even if the player modifies them later
     */
    Map<String, String> backpackSnapshots = new HashMap<>();
    
    /**
     * Stores player experience data (level, progress, total)
     * Null for legacy backups that didn't include experience
     */
    private ExperienceData experienceData;

    public void save(UUID playerUUID, boolean isPlayerDead) {
        save(playerUUID, isPlayerDead ? "death" : null);
    }

    public void save(UUID playerUUID, String suffix) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        String formattedDateTime = now.format(formatter);
        String fileName;
        if (suffix != null && !suffix.isEmpty()) {
            fileName = formattedDateTime + "-" + suffix;
        } else {
            fileName = formattedDateTime;
        }

        String path = "inventory/" + playerUUID.toString() + "/";

        try {
            new JsonFileHandler<>(this).save(path, fileName);
        } catch (Exception e) {
        }
    }

    /**
     * Save asynchronously (non-blocking)
     */
    public CompletableFuture<Void> saveAsync(UUID playerUUID, String suffix) {
        String description = playerUUID.toString() + "/inventory/" + 
            (suffix != null && !suffix.isEmpty() ? suffix : "auto");
        
        return AsyncBackupExecutor.saveAsync(() -> {
            save(playerUUID, suffix);
        }, description);
    }

    /**
     * Save asynchronously with death flag
     */
    public CompletableFuture<Void> saveAsync(UUID playerUUID, boolean isPlayerDead) {
        return saveAsync(playerUUID, isPlayerDead ? "death" : null);
    }

    public Map<Integer, ItemStack> decode(HolderLookup.Provider registryAccess) {

        Map<Integer, ItemStack> map = new HashMap<>();
        
        data.forEach(e -> {
            ItemStack stack = ItemStack.parseOptional(registryAccess, getTag(e.getNbt()));
            
            // If this is a backpack with a snapshot, restore it with a new UUID
            if (SophisticatedBackpacksHelper.isSophisticatedBackpack(stack)) {
                stack = restoreBackpackFromSnapshot(stack, registryAccess);
            }
            
            map.put(e.getIndex(), stack);
        });

        return map;
    }

    public Inventory getInventory(Player player) {
        Inventory inv = new Inventory(player);
        HolderLookup.Provider registryAccess = player.level().registryAccess();
        Map<Integer, ItemStack> curiosItems = new HashMap<>();

        data.forEach(e -> {
            int index = e.getIndex();
            ItemStack stack = ItemStack.parseOptional(registryAccess, getTag(e.getNbt()));

            if (index == 100) {
                inv.armor.set(0, stack);
            } else if (index == 101) {
                inv.armor.set(1, stack);
            } else if (index == 102) {
                inv.armor.set(2, stack);
            } else if (index == 103) {
                inv.armor.set(3, stack);
            } else if (index == -106) {
                inv.offhand.set(0, stack);
            } else if (CuriosHelper.isCuriosSlot(index)) {
                // Collect Curios items separately
                curiosItems.put(index, stack);
            } else if (index >= 0 && index < inv.items.size()) {
                // Regular inventory slots: set to exact position
                inv.items.set(index, stack);
            }
        });

        // Restore Curios items if this is a ServerPlayer and Curios is loaded
        if (player instanceof ServerPlayer serverPlayer && !curiosItems.isEmpty()) {
            CuriosHelper.restoreCuriosItems(serverPlayer, curiosItems);
        }

        return inv;
    }

    /**
     * Legacy encode without experience data (for backwards compatibility)
     */
    public static InventoryData encode(HolderLookup.Provider registryAccess, Map<Integer, ItemStack> map) {
        return encode(registryAccess, map, null);
    }
    
    /**
     * Encode inventory data with optional experience data
     * @param registryAccess Registry access for item serialization
     * @param map Item map (slot -> ItemStack)
     * @param player Player to extract experience from (null to skip experience backup)
     * @return InventoryData instance
     */
    public static InventoryData encode(HolderLookup.Provider registryAccess, Map<Integer, ItemStack> map, ServerPlayer player) {
        List<ItemData> result = new ArrayList<>();
        Map<String, String> backpackSnapshots = new HashMap<>();

        map.forEach((i, s) -> {
            // Skip empty ItemStacks to avoid IllegalStateException
            if (!s.isEmpty()) {
                CompoundTag tag = (CompoundTag) s.save(registryAccess);
                result.add(new ItemData(i, tag.toString()));
                
                // If this is a backpack, save its contents snapshot
                if (SophisticatedBackpacksHelper.isSophisticatedBackpack(s)) {
                    UUID backpackUuid = SophisticatedBackpacksHelper.getBackpackUuid(s);
                    if (backpackUuid != null) {
                        CompoundTag snapshot = SophisticatedBackpacksHelper.getBackpackSnapshot(backpackUuid);
                        if (snapshot != null && !snapshot.isEmpty()) {
                            backpackSnapshots.put(backpackUuid.toString(), snapshot.toString());
                        }
                    }
                }
            }
        });

        InventoryData data = new InventoryData();
        data.setData(result);
        data.setBackpackSnapshots(backpackSnapshots);
        
        // Add experience data if player is provided
        if (player != null) {
            data.setExperienceData(ExperienceData.fromPlayer(player));
        }

        return data;
    }

    private CompoundTag getTag(String nbt) {
        var jsonObject = new JsonObject();
        jsonObject.addProperty("nbt", nbt);

        return JsonUtils.readNBT(jsonObject, "nbt");
    }

    public List<ItemData> getData() {
        return data;
    }

    public void setData(List<ItemData> data) {
        this.data = data;
    }
    
    public Map<String, String> getBackpackSnapshots() {
        return backpackSnapshots;
    }
    
    public void setBackpackSnapshots(Map<String, String> backpackSnapshots) {
        this.backpackSnapshots = backpackSnapshots != null ? backpackSnapshots : new HashMap<>();
    }
    
    public ExperienceData getExperienceData() {
        return experienceData;
    }
    
    public void setExperienceData(ExperienceData experienceData) {
        this.experienceData = experienceData;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InventoryData that = (InventoryData) o;
        return Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data, backpackSnapshots);
    }
    
    /**
     * Restore a backpack from snapshot with a new UUID
     * This ensures the restored backpack doesn't conflict with existing ones
     */
    private ItemStack restoreBackpackFromSnapshot(ItemStack originalBackpack, HolderLookup.Provider registryAccess) {
        UUID originalUuid = SophisticatedBackpacksHelper.getBackpackUuid(originalBackpack);
        
        // If no UUID, just return as-is (empty backpack)
        if (originalUuid == null) {
            return originalBackpack;
        }
        
        // Check if we have a snapshot for this UUID
        String snapshotStr = backpackSnapshots.get(originalUuid.toString());
        if (snapshotStr == null || snapshotStr.isEmpty()) {
            // No snapshot - remove UUID to make it a fresh backpack
            ItemStack freshBackpack = originalBackpack.copy();
            try {
                var jsonObject = new JsonObject();
                jsonObject.addProperty("nbt", originalBackpack.save(registryAccess).toString());
                CompoundTag tag = JsonUtils.readNBT(jsonObject, "nbt");
                
                // Remove UUID component
                net.minecraft.core.component.DataComponentType<?> uuidComponent = 
                    net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sophisticatedcore", "storage_uuid")
                    );
                if (uuidComponent != null) {
                    freshBackpack.remove(uuidComponent);
                }
            } catch (Exception e) {
                InventoryBackupsMod.LOGGER.error("Failed to remove UUID from backpack", e);
            }
            return freshBackpack;
        }
        
        // Parse the snapshot
        CompoundTag snapshot;
        try {
            var jsonObject = new JsonObject();
            jsonObject.addProperty("nbt", snapshotStr);
            snapshot = JsonUtils.readNBT(jsonObject, "nbt");
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to parse backpack snapshot", e);
            return originalBackpack;
        }
        
        // Create new backpack with new UUID
        ItemStack newBackpack = originalBackpack.copy();
        UUID newUuid = UUID.randomUUID();
        SophisticatedBackpacksHelper.setBackpackUuid(newBackpack, newUuid);
        
        // Restore the snapshot to the new UUID
        SophisticatedBackpacksHelper.restoreBackpackSnapshot(newUuid, snapshot);
        
        InventoryBackupsMod.LOGGER.debug("Restored backpack: {} -> {}", originalUuid, newUuid);
        
        return newBackpack;
    }
}
