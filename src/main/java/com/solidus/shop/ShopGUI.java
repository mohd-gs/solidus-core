package com.solidus.shop;

import com.solidus.SolidusMod;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shop GUI Builder - Constructs the virtual Chest GUI for the shop.
 *
 * Layout: GENERIC_9x6 (54 slots)
 *
 * Main Menu Layout:
 * ┌─────────────────────────────────────────────────────────┐
 * │  [0] [1] [2] ... Section Icons ... [7] [8]            │  Row 0: Section buttons
 * │  [9] ... [44]   Section Items (7 rows x 9 columns)     │  Rows 1-4: Items
 * │  [45] ... [52]   More Items / Page Content              │  Row 5: More items
 * │  [53]  [◄ Prev Page] [Next Page ►]                     │  Bottom-right: Navigation
 * └─────────────────────────────────────────────────────────┘
 *
 * Section Page Layout:
 * ┌─────────────────────────────────────────────────────────┐
 * │  [0]  [Back to Menu]  [Section Name]  ... [8]          │  Row 0: Header
 * │  [9] ... [44]   Shop Items with price lore             │  Rows 1-4: Items
 * │  [45] ... [52]   More Items                            │  Row 5: More items
 * │  [53]  [◄ Prev Page] [Next Page ►]                    │  Navigation
 * └─────────────────────────────────────────────────────────┘
 *
 * ALL items are Display-Only. Clicking triggers transactional logic.
 * Moving, dragging, or shifting items is BLOCKED programmatically.
 */
public final class ShopGUI {

    private static final int INVENTORY_SIZE = 54; // 9x6
    private static final int ITEMS_PER_PAGE = 45; // Rows 1-5 (slots 9-53)
    private static final int HEADER_ROW_START = 0;
    private static final int ITEMS_START = 9;

    // Special slot indices
    private static final int BACK_BUTTON_SLOT = 0;
    private static final int PREV_PAGE_SLOT = 48;
    private static final int NEXT_PAGE_SLOT = 50;
    private static final int CLOSE_BUTTON_SLOT = 53;

    // Navigation item tags (stored in display name for identification)
    // Declared as public static final for use as switch case constants
    public static final String NAV_BACK = "NAV_BACK";
    public static final String NAV_PREV = "NAV_PREV";
    public static final String NAV_NEXT = "NAV_NEXT";
    public static final String NAV_CLOSE = "NAV_CLOSE";

    private ShopGUI() {}

    /**
     * Opens the main shop menu showing all sections as clickable icons.
     */
    public static void openMainMenu(ServerPlayer player, ShopManager shopManager) {
        List<GuiSlot> slots = new ArrayList<>();

        // Build section buttons in the header row and below
        int slotIndex = 0;
        for (Map.Entry<String, ShopManager.ShopSection> entry : shopManager.getSections().entrySet()) {
            ShopManager.ShopSection section = entry.getValue();
            ItemStack icon = createSectionIcon(section);
            slots.add(new GuiSlot(slotIndex, icon, GuiSlot.Type.SECTION_BUTTON, entry.getKey()));
            slotIndex++;
            if (slotIndex >= 9) break; // Max 9 sections in header
        }

        // If more than 9 sections, continue in the next rows
        if (shopManager.getSections().size() > 9) {
            int entryIndex = 0;
            slotIndex = ITEMS_START;
            for (Map.Entry<String, ShopManager.ShopSection> entry : shopManager.getSections().entrySet()) {
                if (entryIndex < 9) { entryIndex++; continue; } // Skip already placed in header
                ShopManager.ShopSection section = entry.getValue();
                ItemStack icon = createSectionIcon(section);
                slots.add(new GuiSlot(slotIndex, icon, GuiSlot.Type.SECTION_BUTTON, entry.getKey()));
                slotIndex++;
                entryIndex++;
            }
        }

        // Fill remaining slots with glass pane separators
        fillEmptySlots(slots, INVENTORY_SIZE);

        // Open the screen handler
        ShopScreenHandler.openScreen(player, TextUtil.shopTitle("Solidus Shop"), slots, shopManager, null, 0);
    }

    /**
     * Opens a specific shop section with pagination.
     */
    public static void openSection(ServerPlayer player, ShopManager shopManager,
                                    ShopManager.ShopSection section, int page) {
        List<GuiSlot> slots = new ArrayList<>();

        // Header: Back button
        ItemStack backItem = createNavigationItem(Items.ARROW,
            TextUtil.styledBold("<< Back to Menu", ChatFormatting.RED),
            TextUtil.loreLine("Click to return to sections"));
        slots.add(new GuiSlot(BACK_BUTTON_SLOT, backItem, GuiSlot.Type.NAVIGATION, NAV_BACK));

        // Header: Section name
        ItemStack nameItem = createNavigationItem(Items.BOOK,
            section.displayName().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
            TextUtil.loreLine("Items in this category"));
        slots.add(new GuiSlot(4, nameItem, GuiSlot.Type.DISPLAY_ONLY, null));

        // Items with pagination
        List<ShopManager.ShopItem> items = section.items();
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, items.size());

        for (int i = startIndex; i < endIndex; i++) {
            ShopManager.ShopItem shopItem = items.get(i);
            int slot = ITEMS_START + (i - startIndex);
            ItemStack displayItem = createShopItemDisplay(shopItem);
            slots.add(new GuiSlot(slot, displayItem, GuiSlot.Type.SHOP_ITEM, shopItem.material()));
        }

        // Navigation: Previous page
        if (page > 0) {
            ItemStack prevItem = createNavigationItem(Items.SPECTRAL_ARROW,
                TextUtil.styledBold("< Previous Page", ChatFormatting.YELLOW),
                TextUtil.loreLine("Page " + page + " of " + getTotalPages(items.size())));
            slots.add(new GuiSlot(PREV_PAGE_SLOT, prevItem, GuiSlot.Type.NAVIGATION, NAV_PREV));
        }

        // Navigation: Next page
        if (endIndex < items.size()) {
            ItemStack nextItem = createNavigationItem(Items.SPECTRAL_ARROW,
                TextUtil.styledBold("Next Page >", ChatFormatting.YELLOW),
                TextUtil.loreLine("Page " + (page + 2) + " of " + getTotalPages(items.size())));
            slots.add(new GuiSlot(NEXT_PAGE_SLOT, nextItem, GuiSlot.Type.NAVIGATION, NAV_NEXT));
        }

        // Close button
        ItemStack closeItem = createNavigationItem(Items.BARRIER,
            TextUtil.styledBold("Close Shop", ChatFormatting.RED),
            TextUtil.loreLine("Click to close"));
        slots.add(new GuiSlot(CLOSE_BUTTON_SLOT, closeItem, GuiSlot.Type.NAVIGATION, NAV_CLOSE));

        // Fill empty slots
        fillEmptySlots(slots, INVENTORY_SIZE);

        // Open the screen handler
        ShopScreenHandler.openScreen(player, TextUtil.shopTitle("Solidus Shop - " + section.displayName().getString()),
            slots, shopManager, section.key(), page);
    }

    /**
     * Creates a section icon ItemStack for the main menu.
     */
    private static ItemStack createSectionIcon(ShopManager.ShopSection section) {
        ItemStack icon;
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(new net.minecraft.resources.ResourceLocation(section.icon().toLowerCase()));
            icon = new ItemStack(item);
        } catch (Exception e) {
            icon = new ItemStack(Items.CHEST);
        }

        // Set display name
        icon.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, section.displayName());

        // Add lore
        List<Component> lore = new ArrayList<>();
        lore.add(TextUtil.loreLine("Click to browse this section"));
        lore.add(TextUtil.styled(section.items().size() + " items available", ChatFormatting.AQUA));
        icon.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore));

        return icon;
    }

    /**
     * Creates a shop item display ItemStack with price information in the lore.
     * Left-click = Buy 1, Right-click = Sell 1, Shift+Left = Buy 64, Shift+Right = Sell All
     */
    private static ItemStack createShopItemDisplay(ShopManager.ShopItem shopItem) {
        ItemStack display;
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(new net.minecraft.resources.ResourceLocation(shopItem.material().toLowerCase()));
            display = new ItemStack(item);
        } catch (Exception e) {
            display = new ItemStack(Items.PAPER);
        }

        // Set display name with the item's actual name
        Component itemName = display.getHoverName().copy().withStyle(ChatFormatting.WHITE);
        display.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, itemName);

        // Build lore with pricing information
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(""));

        // Buy price
        if (shopItem.buyPrice() > 0) {
            lore.add(TextUtil.buyPriceLore(shopItem.buyPrice()));
            lore.add(TextUtil.loreLine("  Shift+Left-Click: Buy x64 for " + CurrencyUtil.format(shopItem.buyPrice() * 64)));
        } else {
            lore.add(TextUtil.styled("Buy: Not Available", ChatFormatting.GRAY));
        }

        // Sell price
        if (shopItem.sellPrice() > 0) {
            lore.add(TextUtil.sellPriceLore(shopItem.sellPrice()));
            lore.add(TextUtil.loreLine("  Right-Click: Sell 1 | Shift+Right-Click: Sell All"));
        } else {
            lore.add(TextUtil.styled("Sell: Not Available", ChatFormatting.GRAY));
        }

        lore.add(Component.literal(""));
        lore.add(TextUtil.styled("Left-Click: Buy 1 | Right-Click: Sell 1", ChatFormatting.DARK_GRAY));

        display.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore));

        return display;
    }

    /**
     * Creates a navigation item (arrow, barrier, etc.) with custom name and lore.
     */
    private static ItemStack createNavigationItem(net.minecraft.world.item.Item itemType,
                                                   Component name, Component loreLine) {
        ItemStack item = new ItemStack(itemType);
        item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
        List<Component> lore = new ArrayList<>();
        lore.add(loreLine);
        item.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore));
        return item;
    }

    /**
     * Fills empty slots with dark glass pane separators.
     */
    private static void fillEmptySlots(List<GuiSlot> slots, int totalSlots) {
        java.util.Set<Integer> occupiedSlots = new java.util.HashSet<>();
        for (GuiSlot slot : slots) {
            occupiedSlots.add(slot.index());
        }

        ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        glassPane.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            Component.literal(" ")); // Invisible name

        for (int i = 0; i < totalSlots; i++) {
            if (!occupiedSlots.contains(i)) {
                slots.add(new GuiSlot(i, glassPane.copy(), GuiSlot.Type.FILLER, null));
            }
        }
    }

    private static int getTotalPages(int totalItems) {
        return Math.max(1, (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE));
    }

    // ── Navigation Constants ──────────────────────────────
    // Navigation constants are declared at the top of the class as public static final
    // for use as compile-time constants in switch case labels

    /**
     * Data class representing a single slot in the virtual shop GUI.
     */
    public record GuiSlot(
        int index,
        ItemStack displayStack,
        Type type,
        String actionKey
    ) {
        public enum Type {
            SHOP_ITEM,        // Clickable shop item (buy/sell)
            SECTION_BUTTON,   // Clickable section category
            NAVIGATION,       // Navigation button (prev/next/back/close)
            DISPLAY_ONLY,     // Non-interactive display element
            FILLER            // Empty glass pane filler
        }
    }
}
