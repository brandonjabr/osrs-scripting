package net.runelite.client.plugins.microbot.shadowfaxboltenchanter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("shadowfaxboltenchanter")
public interface BoltEnchanterConfig extends Config {

    enum Bolts {
        AUTO("Best in inventory"),
        OPAL("Opal"),
        SAPPHIRE("Sapphire"),
        JADE("Jade"),
        PEARL("Pearl"),
        EMERALD("Emerald"),
        TOPAZ("Topaz"),
        RUBY("Ruby"),
        DIAMOND("Diamond"),
        DRAGONSTONE("Dragonstone"),
        ONYX("Onyx");

        private final String label;

        Bolts(String label) {
            this.label = label;
        }

        /** The bolt type, or null for AUTO. */
        BoltType type() {
            return this == AUTO ? null : BoltType.valueOf(name());
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigItem(
            keyName = "bolts",
            name = "Bolts",
            description = "Which bolts to enchant. Best in inventory picks the highest-level bolts you have the "
                    + "level and runes for, moving down to the next kind when those run out",
            position = 0
    )
    default Bolts bolts() {
        return Bolts.AUTO;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Use Microbot's antiban, and now and then move the mouse off the game window while a "
                    + "batch of bolts is enchanting, like a hand leaving the mouse",
            position = 1
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show stats overlay",
            description = "Draw the Shadowfax Bolt Enchanter stats panel on screen",
            position = 2
    )
    default boolean showOverlay() {
        return true;
    }
}
