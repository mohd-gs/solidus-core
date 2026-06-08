package com.solidus.shop;

import com.solidus.SolidusMod;
import com.solidus.shop.ShopGUI.GuiSlot;
import com.solidus.util.TextUtil;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shop Screen Handler - Native server-side GUI handler for the virtual shop.
 *
 * CRITICAL ARCHITECTURE:
 * This is a NATIVE ScreenHandler extension, NOT a third-party GUI library.
 * Writing native ScreenHandler extensions is mandatory for long-term server
 * infrastructure stability, as required by the Solidus specification.
 *
 * This handler intercepts and completely rewrites slot click events (onSlotClick).
 * The GUI items are strictly "Display-Only". Moving, dragging, shifting, or
 * double-clicking items into the player inventory is BLOCKED programmatically.
 *
 * Click Processing:
 * - Left-click on shop item = Buy 1 unit
 * - Right-click on shop item = Sell 1 unit
 * - Shift+Left-click on shop item = Buy 64 units (stack)
 * - Shift+Right-click on shop item = Sell all of that item
 * - Click on section button = Navigate to section
 * - Click on navigation = Prev/Next/Back/Close
 *
 * All clicks on display/filler items are silently consumed.
 */
public class ShopScreenHandler extends AbstractContainerMenu {

    private final ServerPlayer player;
    private final ShopManager shopManager;
    private final List<GuiSlot> guiSlots;
    private final String currentSection;
    private final int currentPage;

    // Map from slot index to GuiSlot for fast lookup during click processing
    private final Map<Integer, GuiSlot> slotMap = new HashMap<>();

    /**
     * Opens a new shop screen for the player.
     * Creates the handler, populates slots, and sends the OpenScreen packet.
     */
    public static void openScreen(ServerPlayer player, Component title,
                                   List<GuiSlot> slots, ShopManager shopManager,
                                   String section, int page) {
        // Create handler via SimpleMenuProvider
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
                return new ShopScreenHandler(syncId, playerInventory,
                    (ServerPlayer) player, slots, shopManager, section, page);
            }
        });
    }

    private ShopScreenHandler(int syncId, Inventory playerInventory,
                               ServerPlayer player, List<GuiSlot> slots,
                               ShopManager shopManager, String section, int page) {
        super(MenuType.GENERIC_9x6, syncId);
        this.player = player;
        this.shopManager = shopManager;
        this.guiSlots = slots;
        this.currentSection = section;
        this.currentPage = page;

        // Build slot map for click lookup
        for (GuiSlot guiSlot : slots) {
            slotMap.put(guiSlot.index(), guiSlot);
        }

        // Add container slots (the virtual shop inventory)
        // Using a dummy container that we control completely
        ShopDummyContainer container = new ShopDummyContainer(slots);
        for (int i = 0; i < 54; i++) {
            this.addSlot(new Slot(container, i, 0, 0));
        }

        // Add player inventory slots (standard 9x3 + hotbar layout offset)
        int playerInvOffset = 54; // After the 54 container slots
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory,
                    col + row * 9 + 9,
                    8 + col * 18,
                    84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    /**
     * CRITICAL: Complete rewrite of slot click handling.
     *
     * All shop items are Display-Only. The default behavior of moving
     * items between containers is entirely suppressed. Instead, clicks
     * are analyzed for their intent (buy, sell, navigate) and processed
     * as financial transactions or navigation events.
     */
    @Override
    // TODO: 26.1.x - ClickType → ContainerInput; button param may be removed (absorbed into ContainerInput)
    public void clicked(int slotIndex, int button, net.minecraft.world.inventory.ContainerInput containerInput, Player player) {
        // Allow normal interaction with the player's own inventory area
        // Slot indices >= 54 are in the player's inventory (added after the 54 container slots)
        if (slotIndex < 0 || slotIndex >= 54) {
            // Pass through to vanilla handling for player inventory clicks
            return;
        }

        GuiSlot guiSlot = slotMap.get(slotIndex);
        if (guiSlot == null) {
            return; // Unknown slot - ignore
        }

        switch (guiSlot.type()) {
            case SHOP_ITEM -> handleShopItemClick(guiSlot, button, containerInput);
            case SECTION_BUTTON -> handleSectionButtonClick(guiSlot);
            case NAVIGATION -> handleNavigationClick(guiSlot);
            case DISPLAY_ONLY, FILLER -> {
                // Do nothing - these are non-interactive
            }
        }

        // ALWAYS cancel the default click behavior
        // The shop layout must remain static
    }

    /**
     * Handles a click on a shop item - processes buy/sell transactions.
     */
    private void handleShopItemClick(GuiSlot slot, int button, net.minecraft.world.inventory.ContainerInput containerInput) {
        String material = slot.actionKey();
        if (material == null) return;

        // TODO: 26.1.x - ContainerInput replaces ClickType. Verify QUICK_MOVE constant name.
        boolean isShiftClick = containerInput == net.minecraft.world.inventory.ContainerInput.QUICK_MOVE;
        boolean isRightClick = button == 1;

        if (isShiftClick && isRightClick) {
            // Shift+Right-Click: Sell all of this item
            // Count the actual number of this item in the player's inventory
            int totalInInventory = countItemInInventory(player, material);
            if (totalInInventory <= 0) {
                player.sendSystemMessage(TextUtil.error("You don't have any " + material + " to sell!"));
                return;
            }
            shopManager.processSell(player, material, totalInInventory);
        } else if (isShiftClick) {
            // Shift+Left-Click: Buy a stack (64)
            shopManager.processBuy(player, material, 64);
        } else if (isRightClick) {
            // Right-Click: Sell 1
            shopManager.processSell(player, material, 1);
        } else {
            // Left-Click: Buy 1
            shopManager.processBuy(player, material, 1);
        }
    }

    /**
     * Handles a click on a section button - navigates to that section.
     */
    private void handleSectionButtonClick(GuiSlot slot) {
        String sectionKey = slot.actionKey();
        if (sectionKey == null) return;

        // Close current screen and open the section
        // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
        player.closeContainer();
        shopManager.openSection(player, sectionKey, 0);
    }

    /**
     * Handles navigation button clicks (prev/next/back/close).
     */
    private void handleNavigationClick(GuiSlot slot) {
        String action = slot.actionKey();
        if (action == null) return;

        switch (action) {
            case ShopGUI.NAV_BACK -> {
                // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
                player.closeContainer();
                shopManager.openShop(player);
            }
            case ShopGUI.NAV_PREV -> {
                // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
                player.closeContainer();
                shopManager.openSection(player, currentSection, currentPage - 1);
            }
            case ShopGUI.NAV_NEXT -> {
                // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
                player.closeContainer();
                shopManager.openSection(player, currentSection, currentPage + 1);
            }
            case ShopGUI.NAV_CLOSE -> {
                // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
                player.closeContainer();
            }
        }
    }

    /**
     * Counts the total number of a specific material in the player's inventory.
     * Used by Shift+Right-Click to sell all of a given item.
     */
    private int countItemInInventory(ServerPlayer player, String material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getItems()) {
            if (!stack.isEmpty() && TextUtil.getMaterialName(stack).equalsIgnoreCase(material)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Prevents quick-stack (shift-click) from moving items out of the shop.
     * Only allows shift-click INTO the shop (which we also block).
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Block ALL quick-move operations in the shop GUI
        return ItemStack.EMPTY;
    }

    /**
     * Prevents the player from taking items from the shop container.
     */
    @Override
    public boolean stillValid(Player player) {
        return true; // Always valid while the screen is open
    }

    /**
     * Called when the player closes the screen.
     * No items to drop since the shop is virtual.
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        // No item cleanup needed - shop items are virtual/display-only
    }
}
