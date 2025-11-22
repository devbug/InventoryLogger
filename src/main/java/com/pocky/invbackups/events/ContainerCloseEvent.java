package com.pocky.invbackups.events;

import com.pocky.invbackups.config.InventoryConfig;
import com.pocky.invbackups.data.InventoryData;
import com.pocky.invbackups.utils.InventoryUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * Saves inventory when player closes a container (chest, barrel, etc.)
 * Does not trigger for player's own inventory (E key)
 */
public class ContainerCloseEvent {

    public static boolean containerCloseSaveEnabled = false;

    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close event) {
        if (!containerCloseSaveEnabled) return;
        
        // Exclude player's own inventory (E key)
        if (event.getContainer() instanceof InventoryMenu) {
            return;
        }
        
        // Only process server players
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        
        // Don't save empty inventories
        if (InventoryUtil.isEmpty(player)) {
            return;
        }
        
        // Save backup asynchronously
        InventoryData.encode(
            player.level().registryAccess(), 
            InventoryUtil.collectInventory(player),
            player
        ).saveAsync(player.getUUID(), "container-close");
    }
}
