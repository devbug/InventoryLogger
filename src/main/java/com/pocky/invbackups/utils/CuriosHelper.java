package com.pocky.invbackups.utils;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import com.pocky.invbackups.InventoryBackupsMod;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class for Curios API integration
 * Handles optional dependency safely - works with or without Curios installed
 */
public class CuriosHelper {

    private static final String CURIOS_MOD_ID = "curios";
    private static final int CURIOS_SLOT_START = 1000; // Start index for Curios slots to avoid conflicts

    private static Boolean curiosLoaded = null;

    /**
     * Check if Curios mod is loaded
     */
    public static boolean isCuriosLoaded() {
        if (curiosLoaded == null) {
            curiosLoaded = ModList.get().isLoaded(CURIOS_MOD_ID);
            if (curiosLoaded) {
                InventoryBackupsMod.LOGGER.info("Curios API detected - Curios inventory slots will be saved");
            } else {
                InventoryBackupsMod.LOGGER.debug("Curios API not found - skipping Curios slots");
            }
        }
        return curiosLoaded;
    }

    /**
     * Collect all items from Curios slots
     * Returns empty map if Curios is not loaded
     */
    public static Map<Integer, ItemStack> collectCuriosItems(Player player) {
        Map<Integer, ItemStack> curiosItems = new HashMap<>();

        if (!isCuriosLoaded()) {
            return curiosItems;
        }

        try {
            // Use reflection to safely access Curios API
            curiosItems = collectCuriosItemsInternal(player);
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to collect Curios items", e);
        }

        return curiosItems;
    }

    /**
     * Restore items to Curios slots
     * Does nothing if Curios is not loaded
     */
    public static void restoreCuriosItems(Player player, Map<Integer, ItemStack> curiosItems) {
        if (!isCuriosLoaded() || curiosItems.isEmpty()) {
            return;
        }

        try {
            restoreCuriosItemsInternal(player, curiosItems);
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to restore Curios items", e);
        }
    }

    /**
     * Check if Curios inventory is empty
     */
    public static boolean isCuriosEmpty(Player player) {
        if (!isCuriosLoaded()) {
            return true;
        }

        try {
            return collectCuriosItemsInternal(player).isEmpty();
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to check if Curios is empty", e);
            return true;
        }
    }

    /**
     * Internal method that uses reflection to call Curios API
     * This allows the mod to work without Curios on classpath
     */
    private static Map<Integer, ItemStack> collectCuriosItemsInternal(Player player) {
        Map<Integer, ItemStack> items = new HashMap<>();
        AtomicInteger slotIndex = new AtomicInteger(CURIOS_SLOT_START);

        try {
            // Use reflection to access Curios API: CuriosApi.getCuriosInventory(player)
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            var getCuriosInventoryMethod = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            Object optionalInventory = getCuriosInventoryMethod.invoke(null, player);

            // Check if Optional is present
            java.util.Optional<?> opt = (java.util.Optional<?>) optionalInventory;
            if (opt.isPresent()) {
                Object curiosInventory = opt.get();

                // Get curios map: curiosInventory.getCurios()
                var getCuriosMethod = curiosInventory.getClass().getMethod("getCurios");
                java.util.Map<?, ?> curiosMap = (java.util.Map<?, ?>) getCuriosMethod.invoke(curiosInventory);

                // Iterate through all curio slots
                for (Object stacksHandler : curiosMap.values()) {
                    // Get the IItemHandlerModifiable: stacksHandler.getStacks()
                    var getStacksMethod = stacksHandler.getClass().getMethod("getStacks");
                    Object itemHandler = getStacksMethod.invoke(stacksHandler);

                    // Get slot count: itemHandler.getSlots()
                    var getSlotsMethod = itemHandler.getClass().getMethod("getSlots");
                    int slots = (int) getSlotsMethod.invoke(itemHandler);

                    // Get items from each slot: itemHandler.getStackInSlot(i)
                    var getStackInSlotMethod = itemHandler.getClass().getMethod("getStackInSlot", int.class);
                    for (int i = 0; i < slots; i++) {
                        ItemStack stack = (ItemStack) getStackInSlotMethod.invoke(itemHandler, i);
                        if (!stack.isEmpty()) {
                            items.put(slotIndex.getAndIncrement(), stack.copy());
                        } else {
                            slotIndex.getAndIncrement();
                        }
                    }
                }
            }

            InventoryBackupsMod.LOGGER.debug("Collected {} Curios items from player {}",
                items.size(), player.getScoreboardName());

        } catch (ClassNotFoundException e) {
            InventoryBackupsMod.LOGGER.debug("Curios API not found - skipping Curios items");
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to collect Curios items via reflection", e);
        }

        return items;
    }

    /**
     * Internal method that uses reflection to call Curios API to restore items
     */
    private static void restoreCuriosItemsInternal(Player player, Map<Integer, ItemStack> curiosItems) {
        AtomicInteger slotIndex = new AtomicInteger(CURIOS_SLOT_START);

        try {
            // Use reflection to access Curios API
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            var getCuriosInventoryMethod = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            Object optionalInventory = getCuriosInventoryMethod.invoke(null, player);

            java.util.Optional<?> opt = (java.util.Optional<?>) optionalInventory;
            if (opt.isPresent()) {
                Object curiosInventory = opt.get();

                var getCuriosMethod = curiosInventory.getClass().getMethod("getCurios");
                java.util.Map<?, ?> curiosMap = (java.util.Map<?, ?>) getCuriosMethod.invoke(curiosInventory);

                for (Object stacksHandler : curiosMap.values()) {
                    var getStacksMethod = stacksHandler.getClass().getMethod("getStacks");
                    Object itemHandler = getStacksMethod.invoke(stacksHandler);

                    var getSlotsMethod = itemHandler.getClass().getMethod("getSlots");
                    int slots = (int) getSlotsMethod.invoke(itemHandler);

                    var setStackInSlotMethod = itemHandler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);
                    for (int i = 0; i < slots; i++) {
                        int currentIndex = slotIndex.getAndIncrement();
                        ItemStack itemToRestore = curiosItems.get(currentIndex);

                        if (itemToRestore != null) {
                            setStackInSlotMethod.invoke(itemHandler, i, itemToRestore.copy());
                        } else {
                            setStackInSlotMethod.invoke(itemHandler, i, ItemStack.EMPTY);
                        }
                    }
                }
            }

            InventoryBackupsMod.LOGGER.debug("Restored {} Curios items to player {}",
                curiosItems.size(), player.getScoreboardName());

        } catch (ClassNotFoundException e) {
            InventoryBackupsMod.LOGGER.debug("Curios API not found - skipping restore");
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to restore Curios items via reflection", e);
        }
    }

    /**
     * Check if the slot index belongs to Curios
     */
    public static boolean isCuriosSlot(int slotIndex) {
        return slotIndex >= CURIOS_SLOT_START;
    }
    
    /**
     * Collect mapping of slot index to slot type (e.g., "ring", "necklace")
     * Used for slot validation in GUI
     */
    public static Map<Integer, String> collectCuriosSlotTypes(Player player) {
        Map<Integer, String> slotTypes = new HashMap<>();
        
        if (!isCuriosLoaded()) {
            return slotTypes;
        }
        
        try {
            AtomicInteger slotIndex = new AtomicInteger(CURIOS_SLOT_START);
            
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            var getCuriosInventoryMethod = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            Object optionalInventory = getCuriosInventoryMethod.invoke(null, player);
            
            java.util.Optional<?> opt = (java.util.Optional<?>) optionalInventory;
            if (opt.isPresent()) {
                Object curiosInventory = opt.get();
                
                var getCuriosMethod = curiosInventory.getClass().getMethod("getCurios");
                java.util.Map<?, ?> curiosMap = (java.util.Map<?, ?>) getCuriosMethod.invoke(curiosInventory);
                
                // Iterate with entries to get both slot type and handler
                for (java.util.Map.Entry<?, ?> entry : curiosMap.entrySet()) {
                    String slotType = (String) entry.getKey(); // "ring", "necklace", etc.
                    Object stacksHandler = entry.getValue();
                    
                    var getStacksMethod = stacksHandler.getClass().getMethod("getStacks");
                    Object itemHandler = getStacksMethod.invoke(stacksHandler);
                    
                    var getSlotsMethod = itemHandler.getClass().getMethod("getSlots");
                    int slots = (int) getSlotsMethod.invoke(itemHandler);
                    
                    // Map each slot index to its type
                    for (int i = 0; i < slots; i++) {
                        slotTypes.put(slotIndex.getAndIncrement(), slotType);
                    }
                }
            }
            
            InventoryBackupsMod.LOGGER.debug("Collected {} Curios slot types from player {}",
                slotTypes.size(), player.getScoreboardName());
            
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to collect Curios slot types", e);
        }
        
        return slotTypes;
    }
    
    /**
     * Check if an item can be equipped in a specific Curios slot type
     * Uses Curios API to validate item compatibility
     */
    public static boolean canEquipInCuriosSlot(ItemStack stack, String slotType, Player player) {
        if (!isCuriosLoaded() || stack.isEmpty()) {
            return true; // Allow empty stacks
        }
        
        try {
            // Use CuriosApi.getCurio(stack) to get ICurio capability
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            var getCurioMethod = curiosApiClass.getMethod("getCurio", ItemStack.class);
            Object optionalCurio = getCurioMethod.invoke(null, stack);
            
            java.util.Optional<?> opt = (java.util.Optional<?>) optionalCurio;
            if (opt.isPresent()) {
                Object curio = opt.get();
                
                // Try multiple method signatures (API versions may differ)
                Boolean result = tryCanEquipMethods(curio, slotType, player);
                
                if (result != null) {
                    return result;
                }
                
                // If no canEquip method found, allow by default (safer for compatibility)
                InventoryBackupsMod.LOGGER.warn("Could not find canEquip method for Curio item: {}, allowing by default", stack.getItem());
                return true;
            }
            
            // If no ICurio capability, item cannot be equipped as Curio
            return false;
            
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to check Curios equip validity for slot type: {}", slotType, e);
            // Allow on error for compatibility (some modded items may not follow standard API)
            return true;
        }
    }
    
    /**
     * Try different canEquip method signatures for compatibility with different Curios API versions
     */
    private static Boolean tryCanEquipMethods(Object curio, String slotType, Player player) {
        // Try signature 1: canEquip(String slotType, LivingEntity entity)
        try {
            var method = curio.getClass().getMethod("canEquip", String.class, net.minecraft.world.entity.LivingEntity.class);
            return (Boolean) method.invoke(curio, slotType, player);
        } catch (NoSuchMethodException e) {
            // Try next signature
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.debug("Method signature 1 failed", e);
        }
        
        // Try signature 2: canEquip(SlotContext context)
        try {
            Class<?> slotContextClass = Class.forName("top.theillusivec4.curios.api.SlotContext");
            var method = curio.getClass().getMethod("canEquip", slotContextClass);
            
            // Create SlotContext (identifier, entity, index, onlyVisible)
            var constructor = slotContextClass.getConstructor(
                String.class,
                net.minecraft.world.entity.LivingEntity.class,
                int.class,
                boolean.class
            );
            Object context = constructor.newInstance(slotType, player, 0, false);
            
            return (Boolean) method.invoke(curio, context);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // Method not found
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.debug("Method signature 2 failed", e);
        }
        
        // Try signature 3: canEquip(String identifier, LivingEntity livingEntity, int index)
        try {
            var method = curio.getClass().getMethod("canEquip", String.class, net.minecraft.world.entity.LivingEntity.class, int.class);
            return (Boolean) method.invoke(curio, slotType, player, 0);
        } catch (NoSuchMethodException e) {
            // Method not found
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.debug("Method signature 3 failed", e);
        }
        
        return null; // No compatible method found
    }
    
    /**
     * Get icon resource location for a Curios slot type
     * Returns the texture path for the slot background icon
     */
    public static com.mojang.datafixers.util.Pair<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> getCuriosSlotIcon(String slotType) {
        if (!isCuriosLoaded() || slotType == null) {
            return null;
        }
        
        try {
            // Curios slot icons are typically at: curios:gui/slots/{slotType}
            net.minecraft.resources.ResourceLocation atlasLocation = 
                net.minecraft.resources.ResourceLocation.tryParse("minecraft:textures/atlas/blocks.png");
            net.minecraft.resources.ResourceLocation iconLocation = 
                net.minecraft.resources.ResourceLocation.tryParse("curios:gui/slot/empty_" + slotType + "_slot");
            
            if (atlasLocation != null && iconLocation != null) {
                return com.mojang.datafixers.util.Pair.of(atlasLocation, iconLocation);
            }
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.debug("Could not load Curios icon for slot type: {}", slotType);
        }
        
        return null;
    }
    
    /**
     * Get user-friendly display name for a Curios slot type
     * Converts technical names like "ring" to "Ring Slot"
     */
    public static String getCuriosSlotDisplayName(String slotType) {
        if (slotType == null) {
            return "Unknown Slot";
        }
        
        // Common Curios slot types with friendly names
        return switch (slotType.toLowerCase()) {
            case "ring" -> "Ring";
            case "necklace" -> "Necklace";
            case "bracelet" -> "Bracelet";
            case "belt" -> "Belt";
            case "body" -> "Body";
            case "charm" -> "Charm";
            case "head" -> "Head Accessory";
            case "hands" -> "Hands";
            case "back" -> "Back";
            case "curio" -> "Curio";
            default -> {
                // Capitalize first letter
                String name = slotType.substring(0, 1).toUpperCase() + slotType.substring(1);
                yield name;
            }
        };
    }
}
