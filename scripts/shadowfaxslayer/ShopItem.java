package net.runelite.client.plugins.microbot.shadowfaxslayer;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import java.util.Locale;

/**
 * Slayer Equipment shop items the tasks need, with the shop price, where they're worn (null when carried), and the
 * owned items that do the same job (a slayer helmet covers the earmuffs, nose peg and facemask).
 */
public enum ShopItem {
    ENCHANTED_GEM("Enchanted gem", ItemID.SLAYER_GEM, 1, null, false),
    EARMUFFS("Earmuffs", ItemID.SLAYER_EARMUFFS, 200, EquipmentInventorySlot.HEAD, true),
    NOSE_PEG("Nose peg", ItemID.SLAYER_NOSEPEG, 200, EquipmentInventorySlot.HEAD, true),
    FACEMASK("Facemask", ItemID.SLAYER_FACEMASK, 200, EquipmentInventorySlot.HEAD, true),
    MIRROR_SHIELD("Mirror shield", ItemID.SLAYER_MIRROR_SHIELD, 5_000, EquipmentInventorySlot.SHIELD, false,
            "V's shield"),
    ROCK_HAMMER("Rock hammer", ItemID.SLAYER_ROCK_HAMMER, 500, null, false,
            "Rock thrownhammer", "Granite hammer"),
    ICE_COOLER("Ice cooler", ItemID.SLAYER_ICY_WATER, 1, null, false),
    LEAF_BLADED_SPEAR("Leaf-bladed spear", ItemID.SLAYER_LEAFBLADED_SPEAR, 31_000, EquipmentInventorySlot.WEAPON, false,
            "Leaf-bladed sword", "Leaf-bladed battleaxe");

    private final String itemName;
    private final int itemId;
    private final int price;
    private final EquipmentInventorySlot slot;
    private final boolean slayerHelmCovers;
    private final String[] alternatives;

    ShopItem(String itemName, int itemId, int price, EquipmentInventorySlot slot, boolean slayerHelmCovers,
             String... alternatives) {
        this.itemName = itemName;
        this.itemId = itemId;
        this.price = price;
        this.slot = slot;
        this.slayerHelmCovers = slayerHelmCovers;
        this.alternatives = alternatives;
    }

    public String itemName() {
        return itemName;
    }

    public int itemId() {
        return itemId;
    }

    public int price() {
        return price;
    }

    /** Equipment slot it's worn in, or null for carried items. */
    public EquipmentInventorySlot slot() {
        return slot;
    }

    public boolean worn() {
        return slot != null;
    }

    /** Names that count as this item: itself and the alternatives. */
    public boolean matches(String name) {
        if (name == null) return false;
        if (name.equalsIgnoreCase(itemName)) return true;
        for (String alt : alternatives) {
            if (name.equalsIgnoreCase(alt)) return true;
        }
        return false;
    }

    /** True when a slayer helmet is on and this is one of the items it replaces. */
    public boolean coveredBySlayerHelm() {
        return slayerHelmCovers && wearingSlayerHelm();
    }

    public static boolean wearingSlayerHelm() {
        return Rs2Equipment.isWearing(item -> item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains("slayer helmet"));
    }

    /** Worn, or in the inventory: the item or an alternative. */
    public boolean carried() {
        return wornNow() || carriedCount() > 0;
    }

    public boolean wornNow() {
        return Rs2Equipment.isWearing(item -> matches(item.getName()));
    }

    public int carriedCount() {
        return Rs2Inventory.items(item -> matches(item.getName())).mapToInt(i -> Math.max(1, i.getQuantity())).sum();
    }

    /** In the bank (as last seen): the item or an alternative. */
    public int bankedCount() {
        return Rs2Bank.count(item -> matches(item.getName()));
    }

    /** The name of whichever matching item the bank holds, preferring the shop item itself. */
    public String bankedName() {
        if (Rs2Bank.count(itemName, true) > 0) return itemName;
        for (String alt : alternatives) {
            if (Rs2Bank.count(alt, true) > 0) return alt;
        }
        return itemName;
    }
}
