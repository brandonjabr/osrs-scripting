package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

/**
 * The bank tabs, in tab order (constant order). Which tab an item lands in, and where inside it, is decided by
 * {@link BankItemClassifier} through the item's {@link BankSection}.
 */
public enum BankCategory {
    RUNES_AND_TELEPORTS("Runes & teleports"),
    WEAPONS_AND_ARMOUR("Weapons & armour"),
    FOOD_AND_POTIONS("Food & potions"),
    SKILLING("Skilling"),
    QUEST_ITEMS("Quest items"),
    MISC("Misc");

    final String label;

    BankCategory(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
