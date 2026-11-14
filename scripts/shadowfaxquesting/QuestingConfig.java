package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(QuestingConfig.GROUP)
public interface QuestingConfig extends Config {

    String GROUP = "shadowfaxquesting";
    String LAST_QUEST_KEY = "lastQuest";

    @ConfigSection(
            name = "Free-to-play quests",
            description = "Quests to do, in this order. Ones whose requirements aren't met yet (e.g. quest points) wait until they are",
            position = 0
    )
    String f2pSection = "f2p";

    @ConfigSection(
            name = "Members quests",
            description = "Only run on a members world",
            position = 1
    )
    String membersSection = "members";

    @ConfigSection(
            name = "Shopping & food",
            description = "Buying quest items on the Grand Exchange and food for quests with fights",
            position = 2
    )
    String shoppingSection = "shopping";

    @ConfigSection(
            name = "General",
            description = "Failure handling, antiban and overlay",
            position = 3
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "cooksAssistant",
            name = "Cook's Assistant",
            description = "Include Cook's Assistant in the run",
            position = 0,
            section = f2pSection
    )
    default boolean cooksAssistant() {
        return true;
    }

    @ConfigItem(
            keyName = "sheepShearer",
            name = "Sheep Shearer",
            description = "Include Sheep Shearer in the run",
            position = 1,
            section = f2pSection
    )
    default boolean sheepShearer() {
        return true;
    }

    @ConfigItem(
            keyName = "romeoAndJuliet",
            name = "Romeo & Juliet",
            description = "Include Romeo & Juliet in the run",
            position = 2,
            section = f2pSection
    )
    default boolean romeoAndJuliet() {
        return true;
    }

    @ConfigItem(
            keyName = "restlessGhost",
            name = "The Restless Ghost",
            description = "Include The Restless Ghost in the run",
            position = 3,
            section = f2pSection
    )
    default boolean restlessGhost() {
        return true;
    }

    @ConfigItem(
            keyName = "runeMysteries",
            name = "Rune Mysteries",
            description = "Include Rune Mysteries in the run",
            position = 4,
            section = f2pSection
    )
    default boolean runeMysteries() {
        return true;
    }

    @ConfigItem(
            keyName = "impCatcher",
            name = "Imp Catcher",
            description = "Include Imp Catcher in the run",
            position = 5,
            section = f2pSection
    )
    default boolean impCatcher() {
        return true;
    }

    @ConfigItem(
            keyName = "doricsQuest",
            name = "Doric's Quest",
            description = "Include Doric's Quest in the run",
            position = 6,
            section = f2pSection
    )
    default boolean doricsQuest() {
        return true;
    }

    @ConfigItem(
            keyName = "witchsPotion",
            name = "Witch's Potion",
            description = "Include Witch's Potion in the run",
            position = 7,
            section = f2pSection
    )
    default boolean witchsPotion() {
        return true;
    }

    @ConfigItem(
            keyName = "goblinDiplomacy",
            name = "Goblin Diplomacy",
            description = "Include Goblin Diplomacy in the run",
            position = 8,
            section = f2pSection
    )
    default boolean goblinDiplomacy() {
        return true;
    }

    @ConfigItem(
            keyName = "ernestTheChicken",
            name = "Ernest the Chicken",
            description = "Include Ernest the Chicken in the run",
            position = 9,
            section = f2pSection
    )
    default boolean ernestTheChicken() {
        return true;
    }

    @ConfigItem(
            keyName = "xMarksTheSpot",
            name = "X Marks the Spot",
            description = "Include X Marks the Spot in the run",
            position = 10,
            section = f2pSection
    )
    default boolean xMarksTheSpot() {
        return true;
    }

    @ConfigItem(
            keyName = "misthalinMystery",
            name = "Misthalin Mystery",
            description = "Include Misthalin Mystery in the run",
            position = 11,
            section = f2pSection
    )
    default boolean misthalinMystery() {
        return true;
    }

    @ConfigItem(
            keyName = "idesOfMilk",
            name = "The Ides of Milk",
            description = "Include The Ides of Milk in the run",
            position = 12,
            section = f2pSection
    )
    default boolean idesOfMilk() {
        return true;
    }

    @ConfigItem(
            keyName = "piratesTreasure",
            name = "Pirate's Treasure",
            description = "Include Pirate's Treasure in the run",
            position = 13,
            section = f2pSection
    )
    default boolean piratesTreasure() {
        return true;
    }

    @ConfigItem(
            keyName = "corsairCurse",
            name = "The Corsair Curse",
            description = "Include The Corsair Curse in the run",
            position = 14,
            section = f2pSection
    )
    default boolean corsairCurse() {
        return true;
    }

    @ConfigItem(
            keyName = "vampyreSlayer",
            name = "Vampyre Slayer",
            description = "Include Vampyre Slayer in the run",
            position = 15,
            section = f2pSection
    )
    default boolean vampyreSlayer() {
        return true;
    }

    @ConfigItem(
            keyName = "princeAliRescue",
            name = "Prince Ali Rescue",
            description = "Include Prince Ali Rescue in the run (jail guards, level 26, can attack)",
            position = 16,
            section = f2pSection
    )
    default boolean princeAliRescue() {
        return true;
    }

    @ConfigItem(
            keyName = "demonSlayer",
            name = "Demon Slayer",
            description = "Include Demon Slayer in the run (fights Delrith, level 27, and dark wizards; combat 15+)",
            position = 17,
            section = f2pSection
    )
    default boolean demonSlayer() {
        return true;
    }

    @ConfigItem(
            keyName = "knightsSword",
            name = "The Knight's Sword",
            description = "Include The Knight's Sword in the run",
            position = 18,
            section = f2pSection
    )
    default boolean knightsSword() {
        return true;
    }

    @ConfigItem(
            keyName = "blackKnightsFortress",
            name = "Black Knights' Fortress",
            description = "Include Black Knights' Fortress in the run",
            position = 19,
            section = f2pSection
    )
    default boolean blackKnightsFortress() {
        return true;
    }

    @ConfigItem(
            keyName = "belowIceMountain",
            name = "Below Ice Mountain",
            description = "Include Below Ice Mountain in the run",
            position = 20,
            section = f2pSection
    )
    default boolean belowIceMountain() {
        return true;
    }

    @ConfigItem(
            keyName = "dragonSlayer",
            name = "Dragon Slayer I",
            description = "Include Dragon Slayer I in the run (32 quest points, combat 45+). Fights a lesser demon "
                    + "and Elvarg; buys melee gear for your levels within the shopping budget, so raise the budget "
                    + "for better gear",
            position = 21,
            section = f2pSection
    )
    default boolean dragonSlayer() {
        return true;
    }

    @ConfigItem(
            keyName = "waterfallQuest",
            name = "Waterfall Quest",
            description = "Include Waterfall Quest in the run",
            position = 0,
            section = membersSection
    )
    default boolean waterfallQuest() {
        return true;
    }

    @ConfigItem(
            keyName = "fightArena",
            name = "Fight Arena",
            description = "Include Fight Arena in the run",
            position = 1,
            section = membersSection
    )
    default boolean fightArena() {
        return true;
    }

    @ConfigItem(
            keyName = "priestInPeril",
            name = "Priest in Peril",
            description = "Include Priest in Peril in the run (two level 30 fights; essence fetched in two trips)",
            position = 2,
            section = membersSection
    )
    default boolean priestInPeril() {
        return true;
    }

    @ConfigItem(
            keyName = "natureSpirit",
            name = "Nature Spirit",
            description = "Include Nature Spirit in the run (needs Priest in Peril and The Restless Ghost)",
            position = 3,
            section = membersSection
    )
    default boolean natureSpirit() {
        return true;
    }

    @ConfigItem(
            keyName = "ghostsAhoy",
            name = "Ghosts Ahoy",
            description = "Include Ghosts Ahoy in the run (needs Priest in Peril, The Restless Ghost, 25 Agility, 20 Cooking)",
            position = 4,
            section = membersSection
    )
    default boolean ghostsAhoy() {
        return true;
    }

    @ConfigItem(
            keyName = "animalMagnetism",
            name = "Animal Magnetism",
            description = "Include Animal Magnetism in the run (needs Priest in Peril, Restless Ghost, Ernest the "
                    + "Chicken, 18 Slayer, 19 Crafting, 30 Ranged, 35 Woodcutting)",
            position = 5,
            section = membersSection
    )
    default boolean animalMagnetism() {
        return true;
    }

    @ConfigItem(
            keyName = "lostCity",
            name = "Lost City",
            description = "Include Lost City in the run (needs 31 Crafting and 36 Woodcutting; fought with magic, "
                    + "since Entrana allows no weapons or armour; the Tree Spirit is level 101)",
            position = 6,
            section = membersSection
    )
    default boolean lostCity() {
        return true;
    }

    @ConfigItem(
            keyName = "fairytaleI",
            name = "Fairytale I - Growing Pains",
            description = "Include Fairytale I in the run (needs Lost City and Nature Spirit; the Tanglefoot is level 111)",
            position = 7,
            section = membersSection
    )
    default boolean fairytaleI() {
        return true;
    }

    @ConfigItem(
            keyName = "buyItems",
            name = "Buy quest items on the GE",
            description = "Buy quest items on the Grand Exchange when a quest step needs something that isn't carried or in "
                    + "the bank (the whole quest's missing kit on the first trip). Quests start with what you carry first. "
                    + "When off, quests use what's in your bank and gather the rest in-game",
            position = 0,
            section = shoppingSection
    )
    default boolean buyItems() {
        return true;
    }

    @Range(min = 1_000, max = 10_000_000)
    @ConfigItem(
            keyName = "budget",
            name = "Shopping budget (gp)",
            description = "Most coins to spend on quest items and food in total",
            position = 1,
            section = shoppingSection
    )
    default int budget() {
        return 100_000;
    }

    @Range(min = 0, max = 200)
    @ConfigItem(
            keyName = "markupPercent",
            name = "Offer markup %",
            description = "How far above the current instant-buy price to place first offers. Unfilled offers are raised automatically",
            position = 2,
            section = shoppingSection
    )
    default int markupPercent() {
        return 20;
    }

    @ConfigItem(
            keyName = "foodName",
            name = "Food",
            description = "Exact name of the food to bring to quests with fights",
            position = 3,
            section = shoppingSection
    )
    default String foodName() {
        return "Tuna";
    }

    @Range(min = 0, max = 26)
    @ConfigItem(
            keyName = "foodAmount",
            name = "Food per fight quest",
            description = "How much food to carry into quests with fights. 0 brings none",
            position = 4,
            section = shoppingSection
    )
    default int foodAmount() {
        return 8;
    }

    @Range(min = 10, max = 90)
    @ConfigItem(
            keyName = "eatAtPercent",
            name = "Eat at HP %",
            description = "Eat any carried food at or below this health",
            position = 5,
            section = shoppingSection
    )
    default int eatAtPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "useStaminas",
            name = "Use stamina potions",
            description = "Take stamina potions from the bank (if it has any; they aren't bought) and drink one when run "
                    + "energy is low. Members worlds only: stamina potions can't be used on free-to-play worlds",
            position = 6,
            section = shoppingSection
    )
    default boolean useStaminas() {
        return true;
    }

    @Range(min = 1, max = 5)
    @ConfigItem(
            keyName = "staminaCount",
            name = "Stamina potions to carry",
            description = "How many stamina potions to take out of the bank for each quest",
            position = 7,
            section = shoppingSection
    )
    default int staminaCount() {
        return 1;
    }

    @Range(min = 5, max = 90)
    @ConfigItem(
            keyName = "staminaAtEnergy",
            name = "Drink at run energy %",
            description = "Drink a stamina potion when run energy drops to this and no stamina effect is active",
            position = 8,
            section = shoppingSection
    )
    default int staminaAtEnergy() {
        return 30;
    }

    @ConfigItem(
            keyName = "logoutWhenDone",
            name = "Log out when done",
            description = "Log out once every selected quest is finished or skipped. A run that gets stuck always stops and logs out",
            position = 1,
            section = generalSection
    )
    default boolean logoutWhenDone() {
        return false;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Apply the general antiban template: natural mouse, play styles and micro-break chances",
            position = 2,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Draw the Shadowfax Questing progress panel on screen",
            position = 3,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }

    @ConfigItem(
            keyName = LAST_QUEST_KEY,
            name = "Last quest",
            description = "The quest being played when the script last ran, so a restart picks it back up",
            hidden = true
    )
    default String lastQuest() {
        return "";
    }
}
