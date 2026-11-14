package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

/** A block of items inside a tab. Sections appear in constant order within their tab. */
public enum BankSection {
    RUNES(BankCategory.RUNES_AND_TELEPORTS, "Runes"),
    TELEPORT_ITEMS(BankCategory.RUNES_AND_TELEPORTS, "Tablets & scrolls"),
    TELEPORT_JEWELLERY(BankCategory.RUNES_AND_TELEPORTS, "Teleport jewellery"),

    MELEE_ARMOUR(BankCategory.WEAPONS_AND_ARMOUR, "Melee armour"),
    MELEE_WEAPONS(BankCategory.WEAPONS_AND_ARMOUR, "Melee weapons"),
    RANGED_ARMOUR(BankCategory.WEAPONS_AND_ARMOUR, "Ranged armour"),
    RANGED_WEAPONS(BankCategory.WEAPONS_AND_ARMOUR, "Ranged weapons"),
    AMMO(BankCategory.WEAPONS_AND_ARMOUR, "Ammunition"),
    MAGIC_ARMOUR(BankCategory.WEAPONS_AND_ARMOUR, "Magic armour"),
    MAGIC_WEAPONS(BankCategory.WEAPONS_AND_ARMOUR, "Magic weapons"),
    JEWELLERY(BankCategory.WEAPONS_AND_ARMOUR, "Jewellery"),
    CAPES(BankCategory.WEAPONS_AND_ARMOUR, "Capes"),
    OTHER_GEAR(BankCategory.WEAPONS_AND_ARMOUR, "Other gear"),

    FOOD(BankCategory.FOOD_AND_POTIONS, "Food"),
    POTIONS(BankCategory.FOOD_AND_POTIONS, "Potions"),

    TOOLS(BankCategory.SKILLING, "Tools"),
    ORES_AND_BARS(BankCategory.SKILLING, "Ores & bars"),
    LOGS(BankCategory.SKILLING, "Logs & planks"),
    FISHING(BankCategory.SKILLING, "Raw fish & bait"),
    HERBLORE(BankCategory.SKILLING, "Herbs, seeds & secondaries"),
    GEMS(BankCategory.SKILLING, "Gems"),
    CRAFTING(BankCategory.SKILLING, "Crafting"),
    RUNECRAFTING(BankCategory.SKILLING, "Essence & talismans"),
    OTHER_RESOURCES(BankCategory.SKILLING, "Other resources"),

    QUEST(BankCategory.QUEST_ITEMS, "Quest items"),

    CURRENCY(BankCategory.MISC, "Coins"),
    CLUES(BankCategory.MISC, "Clues"),
    OTHER(BankCategory.MISC, "Other");

    final BankCategory category;
    final String label;

    BankSection(BankCategory category, String label) {
        this.category = category;
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
