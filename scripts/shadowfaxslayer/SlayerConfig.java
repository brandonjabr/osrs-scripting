package net.runelite.client.plugins.microbot.shadowfaxslayer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup(SlayerConfig.GROUP)
public interface SlayerConfig extends Config {

    String GROUP = "shadowfaxslayer";

    enum MasterChoice {
        AUTOMATIC("Automatic (best for my combat)", null),
        TURAEL("Turael", Master.TURAEL),
        VANNAKA("Vannaka", Master.VANNAKA),
        NIEVE("Nieve", Master.NIEVE);

        private final String label;
        private final Master master;

        MasterChoice(String label, Master master) {
            this.label = label;
            this.master = master;
        }

        public Master master() {
            return master;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum UnsupportedTask {
        TURAEL_SKIP("Turael skip (resets streak)"),
        STOP("Log out and stop");

        private final String label;

        UnsupportedTask(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum StyleChoice {
        TASK_DEFAULT("Best for each task", null),
        MELEE("Always melee", CombatStyle.MELEE),
        RANGED("Always ranged", CombatStyle.RANGED),
        MAGIC("Always magic", CombatStyle.MAGIC);

        private final String label;
        private final CombatStyle style;

        StyleChoice(String label, CombatStyle style) {
            this.label = label;
            this.style = style;
        }

        public CombatStyle style() {
            return style;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigSection(name = "Slayer master", description = "Who gives the tasks and what to do with ones the script can't do", position = 0)
    String masterSection = "master";

    @ConfigSection(name = "Gear & combat", description = "The melee, ranged and magic setups and how they're used", position = 1)
    String gearSection = "gear";

    @ConfigSection(name = "Supplies", description = "Food, potions and slayer shop items", position = 2)
    String supplySection = "supplies";

    @ConfigSection(name = "Loot", description = "What to pick up", position = 3)
    String lootSection = "loot";

    @ConfigSection(name = "General", description = "Stopping, antiban and overlay", position = 4)
    String generalSection = "general";

    // --- Master ---

    @ConfigItem(
            keyName = "master",
            name = "Slayer master",
            description = "Who to get tasks from. Automatic uses Turael until combat 40, Vannaka until combat 85, then "
                    + "Nieve, moving up when a task ends",
            position = 0,
            section = masterSection
    )
    default MasterChoice master() {
        return MasterChoice.AUTOMATIC;
    }

    @ConfigItem(
            keyName = "unsupportedTask",
            name = "Task it can't do",
            description = "For tasks the script has no plan for (dragons, Wilderness or quest-locked areas): get a new "
                    + "one from Turael (resets your task streak), or log out and stop so you can do it yourself. A "
                    + "task from Turael himself can't be Turael-skipped, so that always stops",
            position = 1,
            section = masterSection
    )
    default UnsupportedTask unsupportedTask() {
        return UnsupportedTask.TURAEL_SKIP;
    }

    @ConfigItem(
            keyName = "skipTasks",
            name = "Always skip these",
            description = "Comma-separated task names to treat as unsupported, e.g. \"Lizards, Kalphite\"",
            position = 2,
            section = masterSection
    )
    default String skipTasks() {
        return "";
    }

    // --- Gear ---

    @ConfigItem(
            keyName = "openGearSetup",
            name = "Gear setup window at start",
            description = "Show the window for saving your melee, ranged and magic gear when the script starts. It "
                    + "always shows when no setup is saved yet. Put the gear on in game, then press Save for that style",
            position = 0,
            section = gearSection
    )
    default boolean openGearSetup() {
        return true;
    }

    @ConfigItem(
            keyName = "gearHotkey",
            name = "Gear setup hotkey",
            description = "Opens the gear setup window while the script runs",
            position = 1,
            section = gearSection
    )
    default Keybind gearHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(
            keyName = "style",
            name = "Combat style",
            description = "Which saved setup to fight with. \"Best for each task\" uses melee unless the task says "
                    + "otherwise, falling back to any setup you've saved",
            position = 2,
            section = gearSection
    )
    default StyleChoice style() {
        return StyleChoice.TASK_DEFAULT;
    }

    @ConfigItem(
            keyName = "protectionPrayers",
            name = "Protection prayers",
            description = "Pray against the task monster's attack style while fighting it (needs the prayer level for "
                    + "it), drinking prayer potions when low",
            position = 3,
            section = gearSection
    )
    default boolean protectionPrayers() {
        return false;
    }

    @ConfigItem(
            keyName = "prayerPotions",
            name = "Prayer potions",
            description = "Prayer potions to take per trip when protection prayers are on",
            position = 4,
            section = gearSection
    )
    default int prayerPotions() {
        return 4;
    }

    // --- Supplies ---

    @ConfigItem(
            keyName = "foodName",
            name = "Food",
            description = "Food to take, e.g. Lobster. Empty takes whatever food is in the bank",
            position = 0,
            section = supplySection
    )
    default String foodName() {
        return "";
    }

    @ConfigItem(
            keyName = "foodAmount",
            name = "Food per trip",
            description = "How much food to take on each trip",
            position = 1,
            section = supplySection
    )
    default int foodAmount() {
        return 12;
    }

    @ConfigItem(
            keyName = "eatAtPercent",
            name = "Eat at health %",
            description = "Eat when health falls to this percent",
            position = 2,
            section = supplySection
    )
    default int eatAtPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "bankAtPercent",
            name = "Leave at health %",
            description = "With no food left, go to the bank at this health percent",
            position = 3,
            section = supplySection
    )
    default int bankAtPercent() {
        return 35;
    }

    @ConfigItem(
            keyName = "extraItems",
            name = "Extra items",
            description = "Other things to take each trip, as name:amount separated by commas, e.g. "
                    + "\"Super strength(4):1, Games necklace(8):1\"",
            position = 4,
            section = supplySection
    )
    default String extraItems() {
        return "";
    }

    @ConfigItem(
            keyName = "buySlayerItems",
            name = "Buy slayer equipment",
            description = "Buy the enchanted gem, earmuffs, nose peg, facemask, mirror shield, rock hammer, ice "
                    + "coolers and leaf-bladed spear from the slayer master when a task needs them and you don't own them",
            position = 5,
            section = supplySection
    )
    default boolean buySlayerItems() {
        return true;
    }

    @ConfigItem(
            keyName = "slayerItemBudget",
            name = "Slayer item budget (gp)",
            description = "Most to spend on any one slayer shop item. A leaf-bladed spear costs 31k, a mirror shield 5k",
            position = 6,
            section = supplySection
    )
    default int slayerItemBudget() {
        return 40_000;
    }

    @ConfigItem(
            keyName = "buyFood",
            name = "Buy food on the GE",
            description = "When the bank is out of food, buy fish for your Hitpoints level on the Grand Exchange",
            position = 7,
            section = supplySection
    )
    default boolean buyFood() {
        return true;
    }

    @ConfigItem(
            keyName = "foodBudget",
            name = "Food budget (gp)",
            description = "Most to spend on one food restock",
            position = 8,
            section = supplySection
    )
    default int foodBudget() {
        return 60_000;
    }

    @ConfigItem(
            keyName = "staminaPotions",
            name = "Stamina potions",
            description = "Stamina potions to take per trip when the bank has them (never bought). One is drunk "
                    + "when run energy is low and no stamina effect is active. 0 = none",
            position = 9,
            section = supplySection
    )
    default int staminaPotions() {
        return 2;
    }

    // --- Loot ---

    @ConfigItem(
            keyName = "lootMinValue",
            name = "Loot worth at least (gp)",
            description = "Pick up drops worth at least this much per stack on the GE. 0 turns value looting off",
            position = 0,
            section = lootSection
    )
    default int lootMinValue() {
        return 1_000;
    }

    @ConfigItem(
            keyName = "alwaysLoot",
            name = "Always loot",
            description = "Comma-separated item names to pick up whatever they're worth, e.g. \"Coins, Clue scroll\"",
            position = 1,
            section = lootSection
    )
    default String alwaysLoot() {
        return "Coins, Clue scroll (easy), Clue scroll (medium), Clue scroll (hard), Ensouled";
    }

    // --- General ---

    @ConfigItem(
            keyName = "stopAfterTasks",
            name = "Stop after tasks",
            description = "Log out and stop after this many finished tasks. 0 never stops",
            position = 0,
            section = generalSection
    )
    default int stopAfterTasks() {
        return 0;
    }

    @ConfigItem(
            keyName = "stopAtLevel",
            name = "Stop at Slayer level",
            description = "Log out and stop once this Slayer level is reached and the task is done. 0 never stops",
            position = 1,
            section = generalSection
    )
    default int stopAtLevel() {
        return 0;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Combat antiban template plus the Shadowfax habits: idle mouse drifting, uneven reactions "
                    + "between kills, the odd camera turn, checking the gem now and then, and micro-breaks",
            position = 2,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show stats overlay",
            description = "Draw the Shadowfax Slayer stats panel on screen",
            position = 3,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
