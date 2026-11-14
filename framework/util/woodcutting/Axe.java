package net.runelite.client.plugins.microbot.util.woodcutting;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;

/**
 * Woodcutting axes, worst to best, with the Woodcutting level to chop with one and the Attack level to wield it
 * (below that it still works from the inventory). The last few can't be bought on the Grand Exchange and are only
 * used if owned.
 */
public enum Axe {
    BRONZE(ItemID.BRONZE_AXE, "Bronze axe", 1, 1, true),
    IRON(ItemID.IRON_AXE, "Iron axe", 1, 1, true),
    STEEL(ItemID.STEEL_AXE, "Steel axe", 6, 5, true),
    BLACK(ItemID.BLACK_AXE, "Black axe", 11, 10, true),
    MITHRIL(ItemID.MITHRIL_AXE, "Mithril axe", 21, 20, true),
    ADAMANT(ItemID.ADAMANT_AXE, "Adamant axe", 31, 30, true),
    RUNE(ItemID.RUNE_AXE, "Rune axe", 41, 40, true),
    DRAGON(ItemID.DRAGON_AXE, "Dragon axe", 61, 60, true),
    INFERNAL(ItemID.INFERNAL_AXE, "Infernal axe", 61, 60, false),
    INFERNAL_EMPTY(ItemID.INFERNAL_AXE_EMPTY, "Infernal axe (uncharged)", 61, 60, false),
    CRYSTAL(ItemID.CRYSTAL_AXE, "Crystal axe", 71, 70, false),
    CRYSTAL_INACTIVE(ItemID.CRYSTAL_AXE_INACTIVE, "Crystal axe (inactive)", 71, 70, false);

    private final int itemId;
    private final String displayName;
    private final int woodcuttingLevel;
    private final int attackLevel;
    private final boolean buyable;

    Axe(int itemId, String displayName, int woodcuttingLevel, int attackLevel, boolean buyable) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.woodcuttingLevel = woodcuttingLevel;
        this.attackLevel = attackLevel;
        this.buyable = buyable;
    }

    public int itemId() {
        return itemId;
    }

    public String displayName() {
        return displayName;
    }

    public int woodcuttingLevel() {
        return woodcuttingLevel;
    }

    public int attackLevel() {
        return attackLevel;
    }

    public boolean buyable() {
        return buyable;
    }

    /** Speed tier: the uncharged/inactive variants chop like the tier below them. */
    public int tier() {
        switch (this) {
            case INFERNAL:
                return DRAGON.ordinal();
            case INFERNAL_EMPTY:
            case CRYSTAL_INACTIVE:
                return DRAGON.ordinal();
            case CRYSTAL:
                return DRAGON.ordinal() + 1;
            default:
                return ordinal();
        }
    }

    public boolean canUse() {
        return Rs2Player.getRealSkillLevel(Skill.WOODCUTTING) >= woodcuttingLevel;
    }

    public boolean canWield() {
        return Rs2Player.getRealSkillLevel(Skill.ATTACK) >= attackLevel;
    }

    public boolean isWorn() {
        return Rs2Equipment.isWearing(itemId);
    }

    public boolean isCarried() {
        return Rs2Inventory.hasItem(itemId) || isWorn();
    }

    /** Carried, worn or in the bank (as the bank was last seen). */
    public boolean isOwned() {
        return ItemProcurer.ownedCount(itemId, true) > 0;
    }

    public static boolean isAxe(int itemId) {
        return byItemId(itemId) != null;
    }

    public static Axe byItemId(int itemId) {
        for (Axe axe : values()) {
            if (axe.itemId == itemId) return axe;
        }
        return null;
    }

    /** The best usable axe carried or worn, or null. */
    public static Axe bestCarried() {
        Axe best = null;
        for (Axe axe : values()) {
            if (axe.canUse() && axe.isCarried() && (best == null || axe.tier() >= best.tier())) best = axe;
        }
        return best;
    }

    /** The best usable axe owned anywhere (inventory, worn, bank), or null. */
    public static Axe bestOwned() {
        Axe best = null;
        for (Axe axe : values()) {
            if (axe.canUse() && axe.isOwned() && (best == null || axe.tier() >= best.tier())) best = axe;
        }
        return best;
    }
}
