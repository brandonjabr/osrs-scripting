package net.runelite.client.plugins.microbot.cowkiller;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("cowkiller")
public interface CowKillerConfig extends Config {

    @ConfigSection(
            name = "Food",
            description = "Eating and cooking raw beef",
            position = 0
    )
    String foodSection = "food";

    @ConfigSection(
            name = "Scimitar upgrades",
            description = "Selling cowhides on the Grand Exchange and buying scimitars from Zeke",
            position = 1
    )
    String upgradeSection = "upgrade";

    @Range(min = 10, max = 90)
    @ConfigItem(
            keyName = "eatAtPercent",
            name = "Eat at HP %",
            description = "Eat (or go cook raw beef) when health drops to this percentage",
            position = 0,
            section = foodSection
    )
    default int eatAtPercent() {
        return 50;
    }

    @Range(min = 5, max = 50)
    @ConfigItem(
            keyName = "restAtPercent",
            name = "Stop fighting at HP %",
            description = "With no food left, stop attacking at this HP and just gather raw beef until health regenerates",
            position = 1,
            section = foodSection
    )
    default int restAtPercent() {
        return 30;
    }

    @Range(min = 1, max = 14)
    @ConfigItem(
            keyName = "foodAmount",
            name = "Raw beef to gather",
            description = "How many pieces of raw beef to gather before cooking a batch on the Lumbridge range",
            position = 2,
            section = foodSection
    )
    default int foodAmount() {
        return 6;
    }

    @ConfigItem(
            keyName = "buryBones",
            name = "Bury bones",
            description = "Pick up and bury cow bones for Prayer experience",
            position = 3,
            section = foodSection
    )
    default boolean buryBones() {
        return false;
    }

    @ConfigItem(
            keyName = "lootCowhides",
            name = "Loot cowhides",
            description = "Pick up and bank cowhides. Scimitar upgrades are paid for by selling these, so with this off they rely on banked coins only",
            position = 0,
            section = upgradeSection
    )
    default boolean lootCowhides() {
        return true;
    }

    @ConfigItem(
            keyName = "upgradeScimitars",
            name = "Upgrade scimitars",
            description = "Sell cowhides on the Grand Exchange and buy the next scimitar from Zeke in Al Kharid",
            position = 1,
            section = upgradeSection
    )
    default boolean upgradeScimitars() {
        return true;
    }

    @Range(min = 1, max = 50)
    @ConfigItem(
            keyName = "levelsPerUpgrade",
            name = "Attack levels per upgrade",
            description = "Attack levels to gain (since the script started or the last upgrade) before buying the next scimitar",
            position = 2,
            section = upgradeSection
    )
    default int levelsPerUpgrade() {
        return 10;
    }

    @Range(min = 1, max = 500)
    @ConfigItem(
            keyName = "hidePriceEstimate",
            name = "Cowhide price estimate",
            description = "Coins per cowhide assumed when deciding whether banked hides can pay for the next scimitar",
            position = 3,
            section = upgradeSection
    )
    default int hidePriceEstimate() {
        return 80;
    }

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
            description = "Draw the Cow Killer stats panel on screen",
            position = 1,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }

    // Attack level at the last upgrade (or first start). Persisted so a restart doesn't reset the 10-level countdown.
    @ConfigItem(
            keyName = CowKillerScript.LAST_UPGRADE_LEVEL_KEY,
            name = "",
            description = "",
            hidden = true
    )
    default int lastUpgradeAttackLevel() {
        return 0;
    }
}
