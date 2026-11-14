package net.runelite.client.plugins.microbot.shadowfaxwoodcutting;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.plugins.microbot.util.woodcutting.TreeType;

@ConfigGroup(WoodcuttingConfig.GROUP)
public interface WoodcuttingConfig extends Config {

    String GROUP = "shadowfaxwoodcutting";

    enum TreeChoice {
        AUTOMATIC("Automatic (best for my level)", null),
        TREE("Tree", TreeType.TREE),
        OAK("Oak", TreeType.OAK),
        WILLOW("Willow", TreeType.WILLOW),
        TEAK("Teak", TreeType.TEAK),
        MAPLE("Maple", TreeType.MAPLE),
        YEW("Yew", TreeType.YEW),
        MAGIC("Magic", TreeType.MAGIC);

        private final String label;
        private final TreeType tree;

        TreeChoice(String label, TreeType tree) {
            this.label = label;
            this.tree = tree;
        }

        public TreeType tree() {
            return tree;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum LogHandling {
        BANK("Bank them"),
        DROP("Shift-drop them");

        private final String label;

        LogHandling(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigSection(
            name = "Trees",
            description = "What to chop and what to do with the logs",
            position = 0
    )
    String treeSection = "trees";

    @ConfigSection(
            name = "Axes",
            description = "Upgrading the axe as Woodcutting goes up",
            position = 1
    )
    String axeSection = "axes";

    @ConfigSection(
            name = "General",
            description = "Antiban, stopping and overlay",
            position = 2
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "tree",
            name = "Tree",
            description = "Which tree to chop. Automatic picks the best tree your level and world allow (oaks at 15, "
                    + "willows at 30, maples at 45 on members, yews at 60, magics at 75 on members) and moves up "
                    + "as you level",
            position = 0,
            section = treeSection
    )
    default TreeChoice tree() {
        return TreeChoice.AUTOMATIC;
    }

    @ConfigItem(
            keyName = "logHandling",
            name = "Full inventory",
            description = "Bank the logs at the spot's bank, or drop them on the spot with shift-click (turned on in "
                    + "the game settings if it's off). Dropping is faster xp",
            position = 1,
            section = treeSection
    )
    default LogHandling logHandling() {
        return LogHandling.BANK;
    }

    @ConfigItem(
            keyName = "pickUpNests",
            name = "Pick up bird nests",
            description = "Pick up bird nests that fall from the trees (they're never dropped)",
            position = 2,
            section = treeSection
    )
    default boolean pickUpNests() {
        return true;
    }

    @ConfigItem(
            keyName = "upgradeAxes",
            name = "Upgrade axes",
            description = "Use the best axe your Woodcutting level allows: one you own is taken from the bank, otherwise "
                    + "it's bought on the Grand Exchange within the budget. Wielded once your Attack level allows, "
                    + "carried until then",
            position = 0,
            section = axeSection
    )
    default boolean upgradeAxes() {
        return true;
    }

    @ConfigItem(
            keyName = "axeBudget",
            name = "Axe budget (gp)",
            description = "Most to spend on any one axe. About 15k covers a rune axe; a dragon axe needs well over a "
                    + "million",
            position = 1,
            section = axeSection
    )
    default int axeBudget() {
        return 50_000;
    }

    @ConfigItem(
            keyName = "stopAtLevel",
            name = "Stop at level",
            description = "Stop (and log out) at this Woodcutting level. 0 never stops",
            position = 0,
            section = generalSection
    )
    default int stopAtLevel() {
        return 0;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Woodcutting antiban template plus the Shadowfax habits: uneven reaction times after a tree "
                    + "falls, sometimes picking a tree that isn't the closest, idle mouse drifting, the odd camera "
                    + "turn, and micro-breaks",
            position = 1,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show stats overlay",
            description = "Draw the Shadowfax Woodcutting stats panel on screen",
            position = 2,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
