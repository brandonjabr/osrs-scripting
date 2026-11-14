package net.runelite.client.plugins.microbot.shadowfaxchaosdruids;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(ChaosDruidConfig.GROUP)
public interface ChaosDruidConfig extends Config {

    String GROUP = "shadowfaxchaosdruids";

    @ConfigSection(
            name = "Food",
            description = "Eating and restocking food at Falador west bank",
            position = 0
    )
    String foodSection = "food";

    @ConfigSection(
            name = "Loot",
            description = "Which drops to pick up",
            position = 1
    )
    String lootSection = "loot";

    @ConfigSection(
            name = "Stamina",
            description = "Stamina potions from the bank for the walks to and from the bank",
            position = 2
    )
    String staminaSection = "stamina";

    @ConfigItem(
            keyName = "useStaminas",
            name = "Use stamina potions",
            description = "Take stamina potions from the bank (if it has any; they aren't bought) and drink one on the "
                    + "way to or from the bank when run energy is low. Members worlds only: stamina potions can't be "
                    + "used on free-to-play worlds",
            position = 0,
            section = staminaSection
    )
    default boolean useStaminas() {
        return true;
    }

    @Range(min = 1, max = 5)
    @ConfigItem(
            keyName = "staminaCount",
            name = "Stamina potions to carry",
            description = "How many stamina potions to keep in the inventory; topped up on each bank trip",
            position = 1,
            section = staminaSection
    )
    default int staminaCount() {
        return 1;
    }

    @Range(min = 5, max = 90)
    @ConfigItem(
            keyName = "staminaAtEnergy",
            name = "Drink at run energy %",
            description = "Drink a stamina potion while travelling when run energy drops to this and no stamina "
                    + "effect is active",
            position = 2,
            section = staminaSection
    )
    default int staminaAtEnergy() {
        return 30;
    }

    @ConfigSection(
            name = "General",
            description = "Antiban and on-screen overlay",
            position = 3
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "useFood",
            name = "Use food",
            description = "Withdraw and buy food. When off, the bot only eats food already in the inventory; "
                    + "once that's gone and health drops below 25% it walks to Falador west bank and logs out",
            position = 0,
            section = foodSection
    )
    default boolean useFood() {
        return true;
    }

    @Range(min = 10, max = 90)
    @ConfigItem(
            keyName = "eatAtPercent",
            name = "Eat at HP %",
            description = "Eat any food in the inventory when health drops to this percentage. With no food left, bank at this percentage",
            position = 1,
            section = foodSection
    )
    default int eatAtPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "foodName",
            name = "Food to withdraw",
            description = "Exact name of the food to take from the bank, e.g. Trout or Lobster. Leave blank to take whatever food the bank has",
            position = 2,
            section = foodSection
    )
    default String foodName() {
        return "";
    }

    @Range(min = 0, max = 27)
    @ConfigItem(
            keyName = "foodAmount",
            name = "Food per trip",
            description = "How much food to carry out of the bank each trip. 0 means never withdraw food",
            position = 3,
            section = foodSection
    )
    default int foodAmount() {
        return 8;
    }

    @ConfigItem(
            keyName = "restockFromGe",
            name = "Buy food on the GE",
            description = "When the bank has no food left, buy fish at the Grand Exchange, selling some herbs if coins run short. "
                    + "Buys tuna up to 20 Hitpoints, lobster up to 40, swordfish above that. Needs 100 total level",
            position = 4,
            section = foodSection
    )
    default boolean restockFromGe() {
        return true;
    }

    @Range(min = 1_000, max = 1_000_000)
    @ConfigItem(
            keyName = "restockBudget",
            name = "Food budget (gp)",
            description = "Most coins to spend on fish per Grand Exchange trip",
            position = 5,
            section = foodSection
    )
    default int restockBudget() {
        return 30_000;
    }

    @Range(min = 1, max = 1_000)
    @ConfigItem(
            keyName = "restockAmount",
            name = "Fish to buy",
            description = "How many fish to buy per Grand Exchange trip, if the budget allows",
            position = 6,
            section = foodSection
    )
    default int restockAmount() {
        return 100;
    }

    @Range(min = 0, max = 100_000)
    @ConfigItem(
            keyName = "minHerbValue",
            name = "Minimum herb value (gp)",
            description = "Only pick up grimy herbs whose Grand Exchange price is at least this much each",
            position = 0,
            section = lootSection
    )
    default int minHerbValue() {
        return 1000;
    }

    @ConfigItem(
            keyName = "extraLoot",
            name = "Also loot",
            description = "Comma-separated item names to pick up regardless of value, e.g. Law rune, Nature rune",
            position = 1,
            section = lootSection
    )
    default String extraLoot() {
        return "";
    }

    @ConfigItem(
            keyName = "buryBones",
            name = "Bury bones",
            description = "Pick up and bury druid bones for Prayer experience",
            position = 2,
            section = lootSection
    )
    default boolean buryBones() {
        return false;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Apply the combat antiban template: action cooldowns, natural mouse, play styles and micro-break chances",
            position = 0,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show stats overlay",
            description = "Draw the Shadowfax Chaos Druids stats panel on screen",
            position = 1,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
