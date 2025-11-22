package com.pocky.invbackups.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.pocky.invbackups.data.InventoryData;
import com.pocky.invbackups.data.EnderChestData;
import com.pocky.invbackups.io.JsonFileHandler;
import com.pocky.invbackups.ui.ChatUI;
import com.pocky.invbackups.utils.PlayerResolver;
import com.pocky.invbackups.utils.EnderChestUtil;
import com.pocky.invbackups.config.InventoryConfig;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import com.pocky.invbackups.utils.CuriosHelper;
import com.pocky.invbackups.utils.SophisticatedBackpacksHelper;

public class InventoryCommand {

    private static final InventoryCommand command = new InventoryCommand();
    private static final Path BACKUP_DIR = Path.of("InventoryLog");
    
    // Suggestion providers for tab completion
    // Suggestion provider for player names (online + players with backups)
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PLAYERS = (context, builder) -> {
        return suggestPlayers(context, builder);
    };
    
    // Note: Backup file autocomplete removed due to performance concerns with many backups
    // Will be reimplemented after SQL database migration for efficient indexed queries

    public static void register(CommandDispatcher<CommandSourceStack> commandDispatcher) {

        commandDispatcher.register(Commands.literal("inventory")
                .requires(cs -> cs.hasPermission(2))

                // /inventory help
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ChatUI.showHelp(player);
                    return 1;
                })

                // /inventory player <player> - View/edit current inventory
                .then(Commands.literal("player")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .executes(context -> command
                                        .viewCurrentInventory(context.getSource(),
                                                StringArgumentType.getString(context, "target")))))

                // /inventory set <player> <backup>
                .then(Commands.literal("set")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .then(Commands.argument("date", StringArgumentType.string())
                                        .executes(context -> command
                                                .setInventory(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "date"))))))

                // /inventory view <player> <backup>
                .then(Commands.literal("view")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .then(Commands.argument("date", StringArgumentType.string())
                                        .executes(context -> command
                                                .view(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "date"))))))

                // /inventory copy <player> <backup>
                .then(Commands.literal("copy")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .then(Commands.argument("date", StringArgumentType.string())
                                        .executes(context -> command
                                                .copyInventory(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "date"))))))

                // /inventory list <player> [filter] [page]
                .then(Commands.literal("list")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .executes(context -> command.list(context.getSource(),
                                        StringArgumentType.getString(context, "target"),
                                        "", 1))
                                .then(Commands.argument("filter", StringArgumentType.string())
                                        .executes(context -> command.list(context.getSource(),
                                                StringArgumentType.getString(context, "target"),
                                                StringArgumentType.getString(context, "filter"), 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> command.list(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "filter"),
                                                        IntegerArgumentType.getInteger(context, "page")))))
                        )
                )

                // /inventory gui <player> - Open GUI backup browser
                .then(Commands.literal("gui")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .executes(context -> command.openBackupBrowser(context.getSource(),
                                        StringArgumentType.getString(context, "target")))))
        );

        // Ender Chest commands
        commandDispatcher.register(Commands.literal("enderchest")
                .requires(cs -> cs.hasPermission(2))

                // /enderchest help
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ChatUI.showEnderChestHelp(player);
                    return 1;
                })

                // /enderchest player <player> - View/edit current ender chest
                .then(Commands.literal("player")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .executes(context -> command
                                        .viewCurrentEnderChest(context.getSource(),
                                                StringArgumentType.getString(context, "target")))))

                // /enderchest set <player> <backup>
                .then(Commands.literal("set")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .then(Commands.argument("date", StringArgumentType.string())
                                        .executes(context -> command
                                                .setEnderChest(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "date"))))))

                // /enderchest view <player> <backup>
                .then(Commands.literal("view")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .then(Commands.argument("date", StringArgumentType.string())
                                        .executes(context -> command
                                                .viewEnderChest(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "date"))))))

                // /enderchest copy <player> <backup>
                .then(Commands.literal("copy")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .then(Commands.argument("date", StringArgumentType.string())
                                        .executes(context -> command
                                                .copyEnderChest(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "date"))))))

                // /enderchest list <player> [filter] [page]
                .then(Commands.literal("list")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(SUGGEST_PLAYERS)
                                .executes(context -> command.listEnderChest(context.getSource(),
                                        StringArgumentType.getString(context, "target"),
                                        "", 1))
                                .then(Commands.argument("filter", StringArgumentType.string())
                                        .executes(context -> command.listEnderChest(context.getSource(),
                                                StringArgumentType.getString(context, "target"),
                                                StringArgumentType.getString(context, "filter"), 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> command.listEnderChest(context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        StringArgumentType.getString(context, "filter"),
                                                        IntegerArgumentType.getInteger(context, "page")))))
                        )
                )
        );

    }

    public int setInventory(CommandSourceStack source, String targetName, String date) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        // Resolve player (online or offline)
        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.error.player_not_found", targetName));
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        InventoryData invData = JsonFileHandler.load("inventory/" + resolved.getUuid() + "/", date, InventoryData.class);

        if (invData == null) {
            ChatUI.showError(executor, com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.error.backup_not_found", date));
            return 0;
        }

        // Only restore if player is online
        if (!resolved.isOnline()) {
            ChatUI.showError(executor, com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.error.player_offline_cannot_restore", resolved.getName()));
            return 0;
        }

        ServerPlayer target = resolved.getOnlinePlayer();
        target.getInventory().replaceWith(invData.getInventory(target));
        ChatUI.showSuccess(executor, com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.success.restored", date, target.getScoreboardName()));
        ChatUI.showInfo(target, com.pocky.invbackups.utils.TranslationHelper.translate(target, "invbackups.info.inventory_restored", date));
        return 1;
    }

    public int copyInventory(CommandSourceStack source, String targetName, String date) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        // Resolve player (online or offline)
        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        InventoryData invData = JsonFileHandler.load("inventory/" + resolved.getUuid() + "/", date, InventoryData.class);

        if (invData == null) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.backup_not_found", date).getString());
            return 0;
        }

        // Load the backup items into executor's inventory
        Inventory executorInv = executor.getInventory();
        invData.decode(executor.level().registryAccess()).forEach((index, itemStack) -> {
            if (!itemStack.isEmpty()) {
                // Try to add item to inventory, drop if full
                if (!executorInv.add(itemStack.copy())) {
                    executor.drop(itemStack.copy(), false);
                }
            }
        });

        ChatUI.showSuccess(executor, Component.translatable("invbackups.success.copied", date, resolved.getName()).getString());
        return 1;
    }

    public int list(CommandSourceStack source, String targetName, String filter, int page) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        // Resolve player (online or offline)
        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        ChatUI.showBackupList(executor, resolved.getUuid(), resolved.getName(), filter, page);
        return 1;
    }

    public int view(CommandSourceStack source, String targetName, String date) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        // Resolve player (online or offline)
        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        InventoryData invData = JsonFileHandler.load("inventory/" + resolved.getUuid() + "/", date, InventoryData.class);

        if (invData == null) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.backup_not_found", date).getString());
            return 0;
        }

        // Store original items for infinite copying
        java.util.Map<Integer, ItemStack> originalItems = invData.decode(executor.level().registryAccess());
        
        Container chestContainer = new SimpleContainer(54);

        // Map inventory slots to chest GUI slots
        originalItems.forEach((inventoryIndex, itemStack) -> {
            int chestSlot = mapInventoryToChestSlot(inventoryIndex);
            if (chestSlot >= 0 && chestSlot < 53) {  // 53 is reserved for back button
                chestContainer.setItem(chestSlot, itemStack.copy());
            }
        });

        MenuProvider chestMenuProvider = new SimpleMenuProvider(
                (id, playerInv, playerEntity) -> new ChestCopyableMenu(
                        MenuType.GENERIC_9x6, id, playerInv, chestContainer, 6, originalItems, resolved, executor),
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.preview.title", resolved.getName(), date))
                        .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GOLD))
        );

        executor.openMenu(chestMenuProvider);
        ChatUI.showInfo(executor, com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.info.viewing_copyable", date));

        return 1;
    }

    /**
     * View and edit current inventory of a player (online only)
     */
    public int viewCurrentInventory(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        // Resolve player (online or offline)
        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();

        // Only works for online players
        if (!resolved.isOnline()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_offline_cannot_view", resolved.getName()).getString());
            return 0;
        }

        ServerPlayer target = resolved.getOnlinePlayer();

        // Create an editable chest menu with player's inventory
        Container playerInventory = new SimpleContainer(54);

        // Copy player's inventory to the chest
        AtomicInteger slotId = new AtomicInteger();

        // Main inventory (0-35)
        for (int i = 0; i < target.getInventory().items.size() && slotId.get() < 36; i++) {
            playerInventory.setItem(slotId.getAndIncrement(), target.getInventory().items.get(i).copy());
        }

        // Armor slots (36-39)
        for (int i = 0; i < 4; i++) {
            playerInventory.setItem(slotId.getAndIncrement(), target.getInventory().getArmor(i).copy());
        }

        // Offhand (40)
        playerInventory.setItem(slotId.getAndIncrement(), target.getInventory().offhand.get(0).copy());

        MenuProvider chestMenuProvider = new SimpleMenuProvider(
                (id, playerInv, playerEntity) -> new ChestEditableMenu(
                        MenuType.GENERIC_9x6, id, playerInv, playerInventory, 6, target, executor),
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.player.title", target.getScoreboardName()))
                        .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.AQUA))
        );

        executor.openMenu(chestMenuProvider);
        ChatUI.showInfo(executor, com.pocky.invbackups.utils.TranslationHelper.translate(executor, "invbackups.info.viewing_player", target.getScoreboardName()));

        return 1;
    }

    // ==================== ENDER CHEST COMMANDS ====================

    public int setEnderChest(CommandSourceStack source, String targetName, String date) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        if (!InventoryConfig.general.enderChestEnabled.get()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.enderchest_disabled").getString());
            return 0;
        }

        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        EnderChestData ecData = JsonFileHandler.load("enderchest/" + resolved.getUuid() + "/", date, EnderChestData.class);

        if (ecData == null) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.backup_not_found", date).getString());
            return 0;
        }

        if (!resolved.isOnline()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_offline_cannot_restore", resolved.getName()).getString());
            return 0;
        }

        ServerPlayer target = resolved.getOnlinePlayer();
        EnderChestUtil.restoreEnderChest(target, ecData.decode(executor.level().registryAccess()));
        ChatUI.showSuccess(executor, Component.translatable("invbackups.success.enderchest_restored",
                Component.literal(date).withStyle(net.minecraft.ChatFormatting.WHITE),
                Component.literal(target.getScoreboardName()).withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
        ChatUI.showInfo(target, Component.translatable("invbackups.info.enderchest_restored",
                Component.literal(date).withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
        return 1;
    }

    public int copyEnderChest(CommandSourceStack source, String targetName, String date) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        if (!InventoryConfig.general.enderChestEnabled.get()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.enderchest_disabled").getString());
            return 0;
        }

        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        EnderChestData ecData = JsonFileHandler.load("enderchest/" + resolved.getUuid() + "/", date, EnderChestData.class);

        if (ecData == null) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.backup_not_found", date).getString());
            return 0;
        }

        Inventory executorInv = executor.getInventory();
        ecData.decode(executor.level().registryAccess()).forEach((index, itemStack) -> {
            if (!itemStack.isEmpty()) {
                if (!executorInv.add(itemStack.copy())) {
                    executor.drop(itemStack.copy(), false);
                }
            }
        });

        ChatUI.showSuccess(executor, Component.translatable("invbackups.success.enderchest_copied",
                Component.literal(date).withStyle(net.minecraft.ChatFormatting.WHITE),
                Component.literal(resolved.getName()).withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
        return 1;
    }

    public int listEnderChest(CommandSourceStack source, String targetName, String filter, int page) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        if (!InventoryConfig.general.enderChestEnabled.get()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.enderchest_disabled").getString());
            return 0;
        }

        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        ChatUI.showEnderChestBackupList(executor, resolved.getUuid(), resolved.getName(), filter, page);
        return 1;
    }

    public int viewEnderChest(CommandSourceStack source, String targetName, String date) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        if (!InventoryConfig.general.enderChestEnabled.get()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.enderchest_disabled").getString());
            return 0;
        }

        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        EnderChestData ecData = JsonFileHandler.load("enderchest/" + resolved.getUuid() + "/", date, EnderChestData.class);

        if (ecData == null) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.backup_not_found", date).getString());
            return 0;
        }

        Container chestContainer = ecData.toContainer(executor.level().registryAccess());

        MenuProvider chestMenuProvider = new SimpleMenuProvider(
                (id, playerInv, playerEntity) -> new ChestFakeMenu(MenuType.GENERIC_9x3, id, playerInv, chestContainer, 3),
                Component.translatable("invbackups.enderchest.preview.title", resolved.getName(), date)
                        .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.DARK_PURPLE))
        );

        executor.openMenu(chestMenuProvider);
        ChatUI.showInfo(executor, Component.translatable("invbackups.info.viewing_enderchest",
                Component.literal(date).withStyle(net.minecraft.ChatFormatting.WHITE)).getString());

        return 1;
    }

    public int viewCurrentEnderChest(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        if (!InventoryConfig.general.enderChestEnabled.get()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.enderchest_disabled").getString());
            return 0;
        }

        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();

        if (!resolved.isOnline()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_offline_cannot_view", resolved.getName()).getString());
            return 0;
        }

        ServerPlayer target = resolved.getOnlinePlayer();
        SimpleContainer targetEnderChest = target.getEnderChestInventory();

        // Create a copy for editing
        SimpleContainer editableEnderChest = new SimpleContainer(27);
        for (int i = 0; i < 27; i++) {
            editableEnderChest.setItem(i, targetEnderChest.getItem(i).copy());
        }

        MenuProvider chestMenuProvider = new SimpleMenuProvider(
                (id, playerInv, playerEntity) -> new EnderChestEditableMenu(
                        MenuType.GENERIC_9x3, id, playerInv, editableEnderChest, 3, target),
                Component.translatable("invbackups.enderchest.player.title", target.getScoreboardName())
                        .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.DARK_PURPLE))
        );

        executor.openMenu(chestMenuProvider);
        ChatUI.showInfo(executor, Component.translatable("invbackups.info.viewing_enderchest_player",
                Component.literal(target.getScoreboardName()).withStyle(net.minecraft.ChatFormatting.WHITE)).getString());

        return 1;
    }

    /**
     * Editable ender chest menu that syncs changes back to player's ender chest
     */
    private static class EnderChestEditableMenu extends ChestMenu {
        private final ServerPlayer targetPlayer;

        public EnderChestEditableMenu(MenuType<?> menuType, int containerId, Inventory playerInv,
                                     Container container, int rows, ServerPlayer targetPlayer) {
            super(menuType, containerId, playerInv, container, rows);
            this.targetPlayer = targetPlayer;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);

            if (targetPlayer != null && !targetPlayer.isRemoved()) {
                SimpleContainer targetEnderChest = targetPlayer.getEnderChestInventory();

                // Sync all items
                for (int i = 0; i < 27; i++) {
                    targetEnderChest.setItem(i, this.getContainer().getItem(i).copy());
                }

                ChatUI.showSuccess((ServerPlayer) player, Component.translatable("invbackups.success.enderchest_updated",
                        Component.literal(targetPlayer.getScoreboardName()).withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return targetPlayer != null && !targetPlayer.isRemoved();
        }
    }

    /**
     * Editable chest menu that syncs changes back to player's inventory
     */
    private static class ChestEditableMenu extends ChestMenu {
        private final ServerPlayer targetPlayer;
        private final Container chestContainer;
        private final ServerPlayer viewer;

        public ChestEditableMenu(MenuType<?> menuType, int containerId, Inventory playerInv,
                                Container container, int rows, ServerPlayer targetPlayer, ServerPlayer viewer) {
            super(menuType, containerId, playerInv, container, rows);
            this.targetPlayer = targetPlayer;
            this.chestContainer = container;
            this.viewer = viewer;
            
            // Count Curios items
            Map<Integer, ItemStack> curiosItems = CuriosHelper.collectCuriosItems(targetPlayer);
            long curiosCount = curiosItems.values().stream()
                .filter(stack -> !stack.isEmpty())
                .count();
            
            // Add Curios view button in slot 48 if Curios exist
            if (curiosCount > 0) {
                ItemStack curiosButton = new ItemStack(Items.ENDER_EYE);
                curiosButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.button.edit"))
                        .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                
                List<Component> curiosLore = new ArrayList<>();
                curiosLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.items", String.valueOf(curiosCount)))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
                curiosLore.add(Component.empty());
                curiosLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.edit"))
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
                
                curiosButton.set(net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(curiosLore));
                container.setItem(48, curiosButton);
            }
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            // Handle Curios edit button click (slot 48)
            if (slotId == 48) {
                ItemStack item = this.chestContainer.getItem(48);
                if (item.getItem() == Items.ENDER_EYE) {  // Curios button check
                    player.closeContainer();
                    if (player instanceof ServerPlayer sp) {
                        sp.getServer().execute(() -> {
                            openCuriosEdit(sp, targetPlayer, viewer);
                        });
                    }
                }
                return;
            }
            
            // Block placing items on button slot
            if (slotId == 48) {
                return;
            }
            
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public void removed(Player player) {
            super.removed(player);

            // When menu is closed, sync the changes back to the target player
            if (targetPlayer != null && !targetPlayer.isRemoved()) {
                Inventory targetInv = targetPlayer.getInventory();

                // Sync main inventory (slots 0-35)
                for (int i = 0; i < 36; i++) {
                    targetInv.items.set(i, this.getContainer().getItem(i).copy());
                }

                // Sync armor (slots 36-39)
                for (int i = 0; i < 4; i++) {
                    targetInv.armor.set(i, this.getContainer().getItem(36 + i).copy());
                }

                // Sync offhand (slot 40)
                targetInv.offhand.set(0, this.getContainer().getItem(40).copy());

                // Mark inventory as changed
                targetPlayer.inventoryMenu.broadcastChanges();

                ChatUI.showSuccess((ServerPlayer) player, Component.translatable("invbackups.success.player_inventory_updated",
                        Component.literal(targetPlayer.getScoreboardName()).withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return targetPlayer != null && !targetPlayer.isRemoved();
        }
    }

    /**
     * Copyable backup preview menu - allows dragging items to copy them infinitely
     * Items can only be copied OUT, not placed IN
     */
    private static class ChestCopyableMenu extends ChestMenu {
        private final java.util.Map<Integer, ItemStack> originalItems;
        private final Container chestContainer;
        private final int containerSize;
        private final PlayerResolver.ResolvedPlayer targetPlayer;
        private final ServerPlayer viewer;

        public ChestCopyableMenu(MenuType<?> menuType, int containerId,
                                 Inventory playerInv, Container container,
                                 int rows, java.util.Map<Integer, ItemStack> original,
                                 PlayerResolver.ResolvedPlayer target, ServerPlayer viewer) {
            super(menuType, containerId, playerInv, container, rows);
            this.originalItems = original;
            this.chestContainer = container;
            this.containerSize = rows * 9; // 54 slots for 6 rows
            this.targetPlayer = target;
            this.viewer = viewer;
            
            // Count Curios items
            long curiosCount = original.entrySet().stream()
                .filter(e -> e.getKey() >= 1000)
                .filter(e -> !e.getValue().isEmpty())
                .count();
            
            // Add Curios view button in slot 48 if Curios exist
            if (curiosCount > 0) {
                ItemStack curiosButton = new ItemStack(Items.ENDER_EYE);
                curiosButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.button.view"))
                        .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                
                List<Component> curiosLore = new ArrayList<>();
                curiosLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.total", "16"))
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
                curiosLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.items", String.valueOf(curiosCount)))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
                curiosLore.add(Component.empty());
                curiosLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.drag"))
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
                
                curiosButton.set(net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(curiosLore));
                container.setItem(48, curiosButton);
            }
            
            // Add back button in last slot (53)
            ItemStack backButton = new ItemStack(Items.BARRIER);
            backButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("◄ Back to Browser")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("Click to return to backup browser")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            backButton.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(lore));
            container.setItem(53, backButton);
        }

        @Override
        protected Slot addSlot(Slot slot) {
            // Backup item slots (upper chest) - based on slot index
            if (slot.container == this.chestContainer) {
                return super.addSlot(new CopyableBackupSlot(
                        slot.container,
                        slot.getSlotIndex(),
                        slot.x,
                        slot.y,
                        originalItems
                ));
            }
            // Player inventory slots (lower)
            return super.addSlot(slot);
        }
        
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            // Shift+click handling
            Slot slot = this.slots.get(index);
            if (!slot.hasItem()) {
                return ItemStack.EMPTY;
            }

            ItemStack slotStack = slot.getItem();

            // From backup slots to player inventory
            if (index < this.containerSize) {
                ItemStack copy = slotStack.copy();

                // Try to add to player inventory
                if (!this.moveItemStackTo(copy, this.containerSize, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }

                // Restore original item in backup slot (keep source)
                ItemStack original = originalItems.get(index);
                if (original != null) {
                    slot.set(original.copy());
                }

                // Success message
                if (player instanceof ServerPlayer sp) {
                    ChatUI.showSuccess(sp,
                            Component.translatable("invbackups.success.item_copied",
                                    Component.literal(copy.getHoverName().getString())
                                            .withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
                }

                return copy;
            }
            
            // Block shift+click from player inventory to backup container
            return ItemStack.EMPTY;
        }
        
        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            // Handle Curios view button click (slot 48)
            if (slotId == 48) {
                ItemStack item = this.chestContainer.getItem(48);
                if (item.getItem() == Items.ENDER_EYE) {  // Curios button check
                    player.closeContainer();
                    if (player instanceof ServerPlayer sp) {
                        sp.getServer().execute(() -> {
                            openCuriosView(sp, targetPlayer, originalItems, viewer);
                        });
                    }
                }
                return;
            }
            
            // Handle back button click (slot 53)
            if (slotId == 53) {
                player.closeContainer();
                if (player instanceof ServerPlayer sp) {
                    sp.getServer().execute(() -> {
                        // Reopen backup browser
                        try {
                            Path backupDir = Paths.get("InventoryLog/inventory/" + targetPlayer.getUuid() + "/");
                            File dir = backupDir.toFile();
                            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                            if (files != null && files.length > 0) {
                                List<File> backupFiles = new ArrayList<>(List.of(files));
                                backupFiles.sort(Comparator.comparingLong(File::lastModified).reversed());
                                openBrowserAtPage(sp, targetPlayer, backupFiles, 0);
                            }
                        } catch (Exception e) {
                            ChatUI.showError(sp, "Failed to open backup browser");
                        }
                    });
                }
                return;
            }
            
            // Block placing items in backup container slots (0-53)
            if (slotId >= 0 && slotId < this.containerSize) {
                // Block placing items on button slots
                if (slotId == 48 || slotId == 53) {
                    return;
                }
                
                ItemStack cursor = player.containerMenu.getCarried();
                if (!cursor.isEmpty()) {
                    // Player is trying to place an item, block it
                    return;
                }
            }
            
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        /**
         * Copyable backup slot - allows infinite copying
         */
        private static class CopyableBackupSlot extends Slot {
            private final java.util.Map<Integer, ItemStack> originalItems;

            public CopyableBackupSlot(Container container, int index,
                                      int x, int y, java.util.Map<Integer, ItemStack> original) {
                super(container, index, x, y);
                this.originalItems = original;
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;  // Cannot place items in backup slot
            }

            @Override
            public boolean mayPickup(Player player) {
                return true;  // ✅ Can pick up items!
            }

            @Override
            public void onTake(Player player, ItemStack stack) {
                // Give player a copy (with special handling for backpacks)
                ItemStack copy;
                if (SophisticatedBackpacksHelper.isSophisticatedBackpack(stack)) {
                    // For backpacks, create a copy with a new UUID to avoid conflicts
                    copy = SophisticatedBackpacksHelper.copyBackpackWithNewUuid(stack);
                } else {
                    copy = stack.copy();
                }

                // Restore original item in backup slot (infinite copying)
                ItemStack original = originalItems.get(this.getSlotIndex());
                if (original != null && !original.isEmpty()) {
                    this.container.setItem(this.getSlotIndex(), original.copy());
                }

                // Success message
                if (player instanceof ServerPlayer sp) {
                    ChatUI.showSuccess(sp,
                            Component.translatable("invbackups.success.item_copied",
                                    Component.literal(copy.getHoverName().getString())
                                            .withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
                }

                super.onTake(player, stack);
            }
        }
    }

    /**
     * Read-only preview menu (for ender chest)
     */
    private static class ChestFakeMenu extends ChestMenu {

        public ChestFakeMenu(MenuType<?> p_39229_, int p_39230_, Inventory p_39231_, Container p_39232_, int p_39233_) {
            super(p_39229_, p_39230_, p_39231_, p_39232_, p_39233_);
        }

        private ChestFakeMenu(MenuType<?> p_39224_, int p_39225_, Inventory p_39226_, int p_39227_) {
            this(p_39224_, p_39225_, p_39226_, new SimpleContainer(9 * p_39227_), p_39227_);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        protected Slot addSlot(Slot slot) {
            return super.addSlot(new Slot(slot.container, slot.getSlotIndex(), slot.x, slot.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }
            });
        }
    }

    /**
     * Open GUI backup browser
     */
    public int openBackupBrowser(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();

        // Resolve player (online or offline)
        Optional<PlayerResolver.ResolvedPlayer> resolvedOpt = PlayerResolver.resolvePlayer(
                source.getServer(), targetName);

        if (resolvedOpt.isEmpty()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.player_not_found", targetName).getString());
            return 0;
        }

        PlayerResolver.ResolvedPlayer resolved = resolvedOpt.get();
        
        // Get backup directory
        Path backupDir = Paths.get("InventoryLog/inventory/" + resolved.getUuid() + "/");
        File dir = backupDir.toFile();
        
        if (!dir.exists() || !dir.isDirectory()) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.no_backups").getString());
            return 0;
        }

        // Get all backup files
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            ChatUI.showError(executor, Component.translatable("invbackups.error.no_backups").getString());
            return 0;
        }

        // Sort by modification time (newest first)
        List<File> backupFiles = new ArrayList<>(List.of(files));
        backupFiles.sort(Comparator.comparingLong(File::lastModified).reversed());

        // Open backup browser GUI at page 0
        openBrowserAtPage(executor, resolved, backupFiles, 0);

        return 1;
    }

    /**
     * Open backup browser at specific page
     */
    private static void openBrowserAtPage(ServerPlayer viewer, 
                                          PlayerResolver.ResolvedPlayer target,
                                          List<File> backupFiles, int page) {
        MenuProvider browserProvider = new SimpleMenuProvider(
                (id, playerInv, playerEntity) -> new BackupBrowserMenu(
                        MenuType.GENERIC_9x6, id, playerInv, backupFiles, target, viewer, page),
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.browser.title", target.getName()))
                        .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GOLD))
        );

        viewer.openMenu(browserProvider);
        ChatUI.showInfo(viewer, com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.browser.opened", target.getName()));
    }

    /**
     * Backup browser menu - displays backups as items in chest GUI with pagination
     */
    private static class BackupBrowserMenu extends ChestMenu {
        private static final int ITEMS_PER_PAGE = 45; // 5 rows for backups, 1 row for controls
        private final List<File> backupFiles;
        private final PlayerResolver.ResolvedPlayer targetPlayer;
        private final ServerPlayer viewer;
        private final Container browserContainer;
        private int currentPage;

        public BackupBrowserMenu(MenuType<?> menuType, int containerId,
                                 Inventory playerInv, List<File> backups,
                                 PlayerResolver.ResolvedPlayer target, ServerPlayer viewer, int page) {
            super(menuType, containerId, playerInv, new SimpleContainer(54), 6);
            this.backupFiles = backups;
            this.targetPlayer = target;
            this.viewer = viewer;
            this.browserContainer = this.getContainer();
            this.currentPage = Math.max(0, Math.min(page, getTotalPages() - 1));
            
            populateBackupItems();
        }

        private int getTotalPages() {
            return (int) Math.ceil((double) backupFiles.size() / ITEMS_PER_PAGE);
        }

        private void populateBackupItems() {
            Container container = this.getContainer();
            
            // Clear container
            for (int i = 0; i < 54; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
            
            // Calculate pagination
            int startIndex = currentPage * ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, backupFiles.size());
            int totalPages = getTotalPages();
            
            // Display backups for current page (slots 0-44)
            for (int i = startIndex; i < endIndex; i++) {
                File file = backupFiles.get(i);
                String fileName = file.getName().replace(".json", "");
                
                // Determine backup type and icon
                ItemStack icon;
                String typeKey;
                
                if (fileName.contains("-death")) {
                    icon = new ItemStack(Items.SKELETON_SKULL);
                    typeKey = "invbackups.type.death";
                } else if (fileName.contains("-join")) {
                    icon = new ItemStack(Items.OAK_DOOR);
                    typeKey = "invbackups.type.join";
                } else if (fileName.contains("-quit")) {
                    icon = new ItemStack(Items.IRON_DOOR);
                    typeKey = "invbackups.type.quit";
                } else if (fileName.contains("-container-close")) {
                    icon = new ItemStack(Items.CHEST);
                    typeKey = "invbackups.gui.type.container";
                } else {
                    icon = new ItemStack(Items.CLOCK);
                    typeKey = "invbackups.type.auto";
                }
                
                // Set item name with backup date/time
                icon.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, 
                    Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.backup.item.title") + " ")
                        .append(Component.literal(fileName)
                            .withStyle(net.minecraft.ChatFormatting.YELLOW)));
                
                // Add lore with backup info
                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, typeKey))
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
                lore.add(Component.empty());
                lore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.backup.click"))
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
                
                icon.set(net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(lore));
                
                int slotIndex = i - startIndex;
                container.setItem(slotIndex, icon);
            }
            
            // Add pagination controls (last row: slots 45-53)
            // Previous page button (slot 45)
            if (currentPage > 0) {
                ItemStack prevButton = new ItemStack(Items.ARROW);
                prevButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.page.previous"))
                        .withStyle(net.minecraft.ChatFormatting.GREEN));
                container.setItem(45, prevButton);
            }
            
            // Page info (slot 49 - center)
            ItemStack pageInfo = new ItemStack(Items.PAPER);
            pageInfo.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.page.info", 
                    currentPage + 1, totalPages))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            
            List<Component> pageInfoLore = new ArrayList<>();
            pageInfoLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.page.showing",
                    startIndex + 1, endIndex, backupFiles.size()))
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            pageInfo.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(pageInfoLore));
            container.setItem(49, pageInfo);
            
            // Next page button (slot 53)
            if (currentPage < totalPages - 1) {
                ItemStack nextButton = new ItemStack(Items.ARROW);
                nextButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.gui.page.next"))
                        .withStyle(net.minecraft.ChatFormatting.GREEN));
                container.setItem(53, nextButton);
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            // Navigation buttons
            if (index == 45 && currentPage > 0) {
                // Previous page
                changePage(currentPage - 1);
                return ItemStack.EMPTY;
            } else if (index == 53 && currentPage < getTotalPages() - 1) {
                // Next page
                changePage(currentPage + 1);
                return ItemStack.EMPTY;
            }
            
            // Backup selection (slots 0-44)
            if (index < 45) {
                int backupIndex = (currentPage * ITEMS_PER_PAGE) + index;
                if (backupIndex < backupFiles.size()) {
                    File selectedBackup = backupFiles.get(backupIndex);
                    String backupName = selectedBackup.getName().replace(".json", "");
                    
                    // Close this menu
                    player.closeContainer();
                    
                    // Open backup preview
                    openBackupPreview(viewer, targetPlayer, backupName);
                }
            }
            
            return ItemStack.EMPTY;
        }

        private void changePage(int newPage) {
            // Close and reopen at new page
            viewer.closeContainer();
            viewer.getServer().execute(() -> {
                openBrowserAtPage(viewer, targetPlayer, backupFiles, newPage);
            });
        }

        @Override
        public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType clickType, Player player) {
            // Only handle left clicks
            if (clickType != net.minecraft.world.inventory.ClickType.PICKUP || button != 0) {
                return; // Block all other interactions
            }
            
            // Navigation buttons
            if (slotId == 45 && currentPage > 0) {
                // Previous page
                player.closeContainer();
                if (player instanceof ServerPlayer sp) {
                    sp.getServer().execute(() -> {
                        openBrowserAtPage(sp, targetPlayer, backupFiles, currentPage - 1);
                    });
                }
                return;
            } else if (slotId == 53 && currentPage < getTotalPages() - 1) {
                // Next page
                player.closeContainer();
                if (player instanceof ServerPlayer sp) {
                    sp.getServer().execute(() -> {
                        openBrowserAtPage(sp, targetPlayer, backupFiles, currentPage + 1);
                    });
                }
                return;
            }
            
            // Backup selection (slots 0-44)
            if (slotId >= 0 && slotId < 45) {
                int backupIndex = (currentPage * ITEMS_PER_PAGE) + slotId;
                if (backupIndex < backupFiles.size()) {
                    File selectedBackup = backupFiles.get(backupIndex);
                    String backupName = selectedBackup.getName().replace(".json", "");
                    
                    // Close current menu
                    player.closeContainer();
                    
                    // Open preview in next tick
                    if (player instanceof ServerPlayer sp) {
                        sp.getServer().execute(() -> {
                            openBackupPreview(sp, targetPlayer, backupName);
                        });
                    }
                }
            }
        }

        @Override
        protected Slot addSlot(Slot slot) {
            // Make all slots non-interactable except via clicked()
            if (slot.container == this.browserContainer) {
                return super.addSlot(new Slot(slot.container, slot.getSlotIndex(), slot.x, slot.y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false; // Cannot place items
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return false; // Cannot pickup items
                    }
                });
            }
            return super.addSlot(slot);
        }
        
        @Override
        public void removed(Player player) {
            super.removed(player);
            // Return any items that somehow got into the container
            if (player instanceof ServerPlayer sp) {
                for (int i = 0; i < this.browserContainer.getContainerSize(); i++) {
                    ItemStack stack = this.browserContainer.getItem(i);
                    if (!stack.isEmpty() && !isNavigationSlot(i) && !isBackupIcon(i)) {
                        sp.getInventory().placeItemBackInInventory(stack.copy());
                    }
                }
            }
        }
        
        private boolean isNavigationSlot(int slot) {
            return slot == 45 || slot == 49 || slot == 53;
        }
        
        private boolean isBackupIcon(int slot) {
            return slot < 45;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
        
        private static void openBackupPreview(ServerPlayer viewer, 
                                              PlayerResolver.ResolvedPlayer target, 
                                              String backupName) {
            try {
                InventoryData invData = JsonFileHandler.load(
                    "inventory/" + target.getUuid() + "/", backupName, InventoryData.class);

                if (invData == null) {
                    ChatUI.showError(viewer, 
                        com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.error.backup_not_found", backupName));
                    return;
                }

                // Store original items for infinite copying
                java.util.Map<Integer, ItemStack> originalItems = invData.decode(viewer.level().registryAccess());
                
                Container chestContainer = new SimpleContainer(54);
                
                // Map inventory slots to chest GUI slots
                originalItems.forEach((inventoryIndex, itemStack) -> {
                    int chestSlot = mapInventoryToChestSlot(inventoryIndex);
                    if (chestSlot >= 0 && chestSlot < 53) {  // 53 is reserved for back button
                        chestContainer.setItem(chestSlot, itemStack.copy());
                    }
                });

                MenuProvider chestMenuProvider = new SimpleMenuProvider(
                        (id, playerInv, playerEntity) -> new ChestCopyableMenu(
                                MenuType.GENERIC_9x6, id, playerInv, chestContainer, 6, originalItems, target, viewer),
                        Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.preview.title", target.getName(), backupName))
                                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GOLD))
                );

                viewer.openMenu(chestMenuProvider);
                ChatUI.showInfo(viewer, com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.info.viewing_copyable", backupName));
            } catch (Exception e) {
                ChatUI.showError(viewer, 
                    com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.error.backup_not_found", backupName));
            }
        }
    }
    
    /**
     * Curios-only view menu - displays all 16 Curios slots with infinite copying
     */
    private static class CuriosViewMenu extends ChestMenu {
        private final java.util.Map<Integer, ItemStack> originalItems;
        private final PlayerResolver.ResolvedPlayer targetPlayer;
        private final ServerPlayer viewer;
        private final Container curiosContainer;
        
        public CuriosViewMenu(MenuType<?> menuType, int containerId,
                              Inventory playerInv,
                              java.util.Map<Integer, ItemStack> original,
                              PlayerResolver.ResolvedPlayer target,
                              ServerPlayer viewer) {
            super(menuType, containerId, playerInv, new SimpleContainer(54), 6);
            this.originalItems = original;
            this.targetPlayer = target;
            this.viewer = viewer;
            this.curiosContainer = this.getContainer();
            
            populateCuriosSlots();
            addNavigationButtons();
        }
        
        private void populateCuriosSlots() {
            // Curios 아이템만 필터링 (인덱스 1000-1015, 16개)
            originalItems.entrySet().stream()
                .filter(e -> e.getKey() >= 1000 && e.getKey() < 1016)
                .forEach(entry -> {
                    int curiosIndex = entry.getKey() - 1000;  // 0-15로 변환
                    if (curiosIndex >= 0 && curiosIndex < 18) {  // 슬롯 0-17 범위 (16개 + 여유 2개)
                        this.curiosContainer.setItem(curiosIndex, entry.getValue().copy());
                    }
                });
        }
        
        private void addNavigationButtons() {
            // 정보 버튼 (슬롯 49)
            ItemStack infoButton = new ItemStack(Items.BOOK);
            infoButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.title"))
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
            
            long nonEmpty = originalItems.entrySet().stream()
                .filter(e -> e.getKey() >= 1000 && e.getKey() < 1016)
                .filter(e -> !e.getValue().isEmpty()).count();
            
            List<Component> infoLore = new ArrayList<>();
            infoLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.total", "16"))
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            infoLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.items", String.valueOf(nonEmpty)))
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
            infoLore.add(Component.empty());
            infoLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.drag"))
                .withStyle(net.minecraft.ChatFormatting.GREEN));
            
            infoButton.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(infoLore));
            this.curiosContainer.setItem(49, infoButton);
            
            // 뒤로 버튼 (슬롯 53)
            ItemStack backButton = new ItemStack(Items.BARRIER);
            backButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.button.back"))
                    .withStyle(net.minecraft.ChatFormatting.RED));
            List<Component> backLore = new ArrayList<>();
            backLore.add(Component.literal("Click to return to main preview")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            backButton.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(backLore));
            this.curiosContainer.setItem(53, backButton);
        }
        
        @Override
        protected Slot addSlot(Slot slot) {
            if (slot.container == this.curiosContainer) {
                int index = slot.getSlotIndex();
                
                // Curios 슬롯 (0-17)은 복사 가능
                if (index >= 0 && index < 18) {
                    return super.addSlot(new CuriosBackupSlot(
                        slot.container, index, slot.x, slot.y, originalItems));
                }
                
                // 버튼 슬롯 (49, 53)은 상호작용 불가
                if (index == 49 || index == 53) {
                    return super.addSlot(new Slot(slot.container, index, slot.x, slot.y) {
                        @Override
                        public boolean mayPlace(ItemStack stack) { return false; }
                        @Override
                        public boolean mayPickup(Player player) { return false; }
                    });
                }
            }
            
            return super.addSlot(slot);
        }
        
        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            // 뒤로 버튼 (슬롯 53)
            if (slotId == 53) {
                player.closeContainer();
                if (player instanceof ServerPlayer sp) {
                    sp.getServer().execute(() -> {
                        reopenMainPreview(sp, targetPlayer, originalItems, viewer);
                    });
                }
                return;
            }
            
            // 정보 버튼 (슬롯 49) - 클릭 무시
            if (slotId == 49) {
                return;
            }
            
            // 버튼 슬롯은 아이템 배치 불가
            if (slotId >= 0 && slotId < 54 && (slotId == 49 || slotId == 53)) {
                return;
            }
            
            super.clicked(slotId, button, clickType, player);
        }
        
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            // Shift+클릭 처리 (Curios 슬롯 0-17)
            if (index < 18) {
                Slot slot = this.slots.get(index);
                if (!slot.hasItem()) return ItemStack.EMPTY;
                
                ItemStack slotStack = slot.getItem();
                ItemStack copy = slotStack.copy();
                
                // 플레이어 인벤토리로 복사
                if (!this.moveItemStackTo(copy, 54, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
                
                // 원본 복원 (무한 복사)
                int curiosIndex = 1000 + index;
                ItemStack original = originalItems.get(curiosIndex);
                if (original != null) {
                    slot.set(original.copy());
                }
                
                // 성공 메시지
                if (player instanceof ServerPlayer sp) {
                    ChatUI.showSuccess(sp, Component.translatable(
                        "invbackups.success.item_copied",
                        Component.literal(copy.getHoverName().getString())
                            .withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
                }
                
                return copy;
            }
            
            return ItemStack.EMPTY;
        }
        
        @Override
        public boolean stillValid(Player player) {
            return true;
        }
        
        /**
         * Curios 백업 슬롯 - 무한 복사 가능
         */
        private static class CuriosBackupSlot extends Slot {
            private final java.util.Map<Integer, ItemStack> originalItems;
            
            public CuriosBackupSlot(Container container, int index,
                                    int x, int y, java.util.Map<Integer, ItemStack> original) {
                super(container, index, x, y);
                this.originalItems = original;
            }
            
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;  // 아이템 배치 불가
            }
            
            @Override
            public boolean mayPickup(Player player) {
                return true;  // 아이템 픽업 가능
            }
            
            @Override
            public void onTake(Player player, ItemStack stack) {
                // Give player a copy (with special handling for backpacks)
                ItemStack copy;
                if (SophisticatedBackpacksHelper.isSophisticatedBackpack(stack)) {
                    // For backpacks, create a copy with a new UUID to avoid conflicts
                    copy = SophisticatedBackpacksHelper.copyBackpackWithNewUuid(stack);
                } else {
                    copy = stack.copy();
                }
                
                // 원본 복원 (무한 복사)
                int curiosIndex = 1000 + this.getSlotIndex();
                ItemStack original = originalItems.get(curiosIndex);
                if (original != null && !original.isEmpty()) {
                    this.container.setItem(this.getSlotIndex(), original.copy());
                }
                
                // 성공 메시지
                if (player instanceof ServerPlayer sp) {
                    ChatUI.showSuccess(sp,
                        Component.translatable("invbackups.success.item_copied",
                            Component.literal(copy.getHoverName().getString())
                                .withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
                }
                
                super.onTake(player, stack);
            }
        }
    }
    
    /**
     * Curios-only editable menu - displays and allows editing all 16 Curios slots
     */
    private static class CuriosEditableMenu extends ChestMenu {
        private final ServerPlayer targetPlayer;
        private final ServerPlayer viewer;
        private final Container curiosContainer;
        
        public CuriosEditableMenu(MenuType<?> menuType, int containerId,
                                  Inventory playerInv,
                                  ServerPlayer target,
                                  ServerPlayer viewer) {
            super(menuType, containerId, playerInv, new SimpleContainer(54), 6);
            this.targetPlayer = target;
            this.viewer = viewer;
            this.curiosContainer = this.getContainer();
            
            populateCuriosSlots();
            addNavigationButtons();
        }
        
        private void populateCuriosSlots() {
            // Load current Curios items from target player
            Map<Integer, ItemStack> curiosItems = CuriosHelper.collectCuriosItems(targetPlayer);
            
            curiosItems.entrySet().stream()
                .filter(e -> e.getKey() >= 1000 && e.getKey() < 1016)
                .forEach(entry -> {
                    int curiosIndex = entry.getKey() - 1000;  // Convert to 0-15
                    if (curiosIndex >= 0 && curiosIndex < 18) {
                        this.curiosContainer.setItem(curiosIndex, entry.getValue().copy());
                    }
                });
        }
        
        private void addNavigationButtons() {
            // Info button (slot 49)
            ItemStack infoButton = new ItemStack(Items.BOOK);
            infoButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.title"))
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
            
            List<Component> infoLore = new ArrayList<>();
            infoLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.total", "16"))
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            infoLore.add(Component.empty());
            infoLore.add(Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.info.edit"))
                .withStyle(net.minecraft.ChatFormatting.GREEN));
            
            infoButton.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(infoLore));
            this.curiosContainer.setItem(49, infoButton);
            
            // Back button (slot 53)
            ItemStack backButton = new ItemStack(Items.BARRIER);
            backButton.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.button.back"))
                    .withStyle(net.minecraft.ChatFormatting.RED));
            List<Component> backLore = new ArrayList<>();
            backLore.add(Component.literal("Click to return to main inventory")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            backButton.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(backLore));
            this.curiosContainer.setItem(53, backButton);
        }
        
        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            // Back button (slot 53)
            if (slotId == 53) {
                player.closeContainer();
                if (player instanceof ServerPlayer sp) {
                    sp.getServer().execute(() -> {
                        reopenPlayerInventory(sp, targetPlayer, viewer);
                    });
                }
                return;
            }
            
            // Info button (slot 49) - ignore clicks
            if (slotId == 49) {
                return;
            }
            
            super.clicked(slotId, button, clickType, player);
        }
        
        @Override
        public void removed(Player player) {
            super.removed(player);
            
            // Sync changes back to target player's Curios
            if (targetPlayer != null && !targetPlayer.isRemoved()) {
                Map<Integer, ItemStack> curiosItems = new java.util.HashMap<>();
                
                // Collect items from GUI slots 0-17
                for (int i = 0; i < 18; i++) {
                    ItemStack item = this.curiosContainer.getItem(i);
                    curiosItems.put(1000 + i, item.copy());
                }
                
                // Restore to target player
                CuriosHelper.restoreCuriosItems(targetPlayer, curiosItems);
                
                ChatUI.showSuccess((ServerPlayer) player, 
                    Component.translatable("invbackups.success.curios_updated",
                        Component.literal(targetPlayer.getScoreboardName())
                            .withStyle(net.minecraft.ChatFormatting.WHITE)).getString());
            }
        }
        
        @Override
        public boolean stillValid(Player player) {
            return targetPlayer != null && !targetPlayer.isRemoved();
        }
    }
    
    /**
     * Open Curios-only view menu (for backups - read-only with copy)
     */
    private static void openCuriosView(ServerPlayer viewer,
                                       PlayerResolver.ResolvedPlayer target,
                                       java.util.Map<Integer, ItemStack> originalItems,
                                       ServerPlayer originalViewer) {
        MenuProvider curiosProvider = new SimpleMenuProvider(
            (id, playerInv, playerEntity) -> new CuriosViewMenu(
                MenuType.GENERIC_9x6, id, playerInv, originalItems, target, viewer),
            Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.view.title", target.getName()))
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE)
        );
        
        viewer.openMenu(curiosProvider);
    }
    
    /**
     * Reopen main preview from Curios view
     */
    private static void reopenMainPreview(ServerPlayer viewer,
                                          PlayerResolver.ResolvedPlayer target,
                                          java.util.Map<Integer, ItemStack> originalItems,
                                          ServerPlayer originalViewer) {
        Container chestContainer = new SimpleContainer(54);
        
        originalItems.forEach((inventoryIndex, itemStack) -> {
            int chestSlot = mapInventoryToChestSlot(inventoryIndex);
            if (chestSlot >= 0 && chestSlot < 53) {
                chestContainer.setItem(chestSlot, itemStack.copy());
            }
        });
        
        MenuProvider mainProvider = new SimpleMenuProvider(
            (id, playerInv, playerEntity) -> new ChestCopyableMenu(
                MenuType.GENERIC_9x6, id, playerInv, chestContainer, 6,
                originalItems, target, viewer),
            Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.preview.title", target.getName(), ""))
                .withStyle(net.minecraft.ChatFormatting.GOLD)
        );
        
        viewer.openMenu(mainProvider);
    }
    
    /**
     * Open Curios editable menu (for live player inventory)
     */
    private static void openCuriosEdit(ServerPlayer viewer,
                                       ServerPlayer target,
                                       ServerPlayer originalViewer) {
        MenuProvider curiosProvider = new SimpleMenuProvider(
            (id, playerInv, playerEntity) -> new CuriosEditableMenu(
                MenuType.GENERIC_9x6, id, playerInv, target, viewer),
            Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.curios.edit.title", target.getScoreboardName()))
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE)
        );
        
        viewer.openMenu(curiosProvider);
    }
    
    /**
     * Reopen player inventory from Curios edit
     */
    private static void reopenPlayerInventory(ServerPlayer viewer,
                                              ServerPlayer target,
                                              ServerPlayer originalViewer) {
        Container playerInventory = new SimpleContainer(54);
        
        // Copy player's inventory to the chest
        AtomicInteger slotId = new AtomicInteger();
        
        // Main inventory (0-35)
        for (int i = 0; i < target.getInventory().items.size() && slotId.get() < 36; i++) {
            playerInventory.setItem(slotId.getAndIncrement(), target.getInventory().items.get(i).copy());
        }
        
        // Armor slots (36-39)
        for (int i = 0; i < 4; i++) {
            playerInventory.setItem(slotId.getAndIncrement(), target.getInventory().getArmor(i).copy());
        }
        
        // Offhand (40)
        playerInventory.setItem(slotId.getAndIncrement(), target.getInventory().offhand.get(0).copy());
        
        MenuProvider mainProvider = new SimpleMenuProvider(
            (id, playerInv, playerEntity) -> new ChestEditableMenu(
                MenuType.GENERIC_9x6, id, playerInv, playerInventory, 6, target, viewer),
            Component.literal(com.pocky.invbackups.utils.TranslationHelper.translate(viewer, "invbackups.player.title", target.getScoreboardName()))
                .withStyle(net.minecraft.ChatFormatting.AQUA)
        );
        
        viewer.openMenu(mainProvider);
    }
    
    /**
     * Maps inventory slot indices to chest GUI slot indices
     * Inventory layout: 0-35 (inventory), 100-103 (armor), -106 (offhand), 1000+ (curios)
     * Chest GUI layout: 0-35 (inventory rows 1-4), 36-39 (armor row 5), 40 (offhand row 5)
     * Note: Curios are NOT displayed in main preview - use the Curios view button instead
     */
    private static int mapInventoryToChestSlot(int inventoryIndex) {
        if (inventoryIndex >= 0 && inventoryIndex <= 35) {
            // Regular inventory and hotbar: direct mapping (rows 1-4)
            return inventoryIndex;
        } else if (inventoryIndex >= 100 && inventoryIndex <= 103) {
            // Armor slots: 100-103 -> 36-39 (row 5, left side)
            return inventoryIndex - 64;  // 100->36, 101->37, 102->38, 103->39
        } else if (inventoryIndex == -106) {
            // Offhand: -106 -> 40 (row 5, after armor)
            return 40;
        } else if (inventoryIndex >= 1000) {
            // Curios slots: NOT displayed in main preview
            // Use the Curios view button (slot 48) to access Curios in dedicated screen
            return -1;
        }
        return -1;  // Unknown slot, skip
    }
    
    // ========== Tab Completion Suggestion Methods ==========
    
    /**
     * Suggest player names for tab completion
     * Includes: online players + players with backups
     */
    private static CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> context, 
            SuggestionsBuilder builder) {
        
        Set<String> playerNames = new HashSet<>();
        
        // Add online players
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            playerNames.add(player.getScoreboardName());
        }
        
        // Add players with inventory backups
        try {
            Path inventoryDir = BACKUP_DIR.resolve("inventory");
            if (Files.exists(inventoryDir) && Files.isDirectory(inventoryDir)) {
                try (Stream<Path> playerDirs = Files.list(inventoryDir)) {
                    playerDirs.filter(Files::isDirectory)
                            .forEach(dir -> {
                                try {
                                    UUID uuid = UUID.fromString(dir.getFileName().toString());
                                    Optional<PlayerResolver.ResolvedPlayer> resolved = 
                                        PlayerResolver.resolvePlayer(context.getSource().getServer(), uuid);
                                    resolved.ifPresent(p -> playerNames.add(p.getName()));
                                } catch (IllegalArgumentException e) {
                                    // Invalid UUID, skip
                                }
                            });
                }
            }
        } catch (Exception e) {
            // Error reading directory, continue with online players only
        }
        
        // Add ender chest backups (might have different players)
        try {
            Path enderchestDir = BACKUP_DIR.resolve("enderchest");
            if (Files.exists(enderchestDir) && Files.isDirectory(enderchestDir)) {
                try (Stream<Path> playerDirs = Files.list(enderchestDir)) {
                    playerDirs.filter(Files::isDirectory)
                            .forEach(dir -> {
                                try {
                                    UUID uuid = UUID.fromString(dir.getFileName().toString());
                                    Optional<PlayerResolver.ResolvedPlayer> resolved = 
                                        PlayerResolver.resolvePlayer(context.getSource().getServer(), uuid);
                                    resolved.ifPresent(p -> playerNames.add(p.getName()));
                                } catch (IllegalArgumentException e) {
                                    // Invalid UUID, skip
                                }
                            });
                }
            }
        } catch (Exception e) {
            // Error reading directory, continue
        }
        
        // Suggest matching names
        String input = builder.getRemaining().toLowerCase();
        playerNames.stream()
                .filter(name -> name.toLowerCase().startsWith(input))
                .sorted()
                .forEach(builder::suggest);
        
        return builder.buildFuture();
    }
}
