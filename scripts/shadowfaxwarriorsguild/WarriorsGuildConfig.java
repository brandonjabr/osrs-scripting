package net.runelite.client.plugins.microbot.shadowfaxwarriorsguild;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(WarriorsGuildConfig.GROUP)
public interface WarriorsGuildConfig extends Config {

    String GROUP = "shadowfaxwarriorsguild";

    enum ArmourChoice {
        BEST("Best set I have", null),
        BRONZE("Bronze", ArmourSet.BRONZE),
        IRON("Iron", ArmourSet.IRON),
        STEEL("Steel", ArmourSet.STEEL),
        BLACK("Black", ArmourSet.BLACK),
        MITHRIL("Mithril", ArmourSet.MITHRIL),
        ADAMANT("Adamant", ArmourSet.ADAMANT),
        RUNE("Rune", ArmourSet.RUNE);

        private final String label;
        final ArmourSet set;

        ArmourChoice(String label, ArmourSet set) {
            this.label = label;
            this.set = set;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigSection(
            name = "Tokens",
            description = "Animating armour on the ground floor for Warrior guild tokens",
            position = 0
    )
    String tokenSection = "tokens";

    @ConfigSection(
            name = "Defenders",
            description = "Cyclopes on the top floor (bronze to rune) and in the basement (dragon)",
            position = 1
    )
    String defenderSection = "defenders";

    @ConfigSection(
            name = "Food & banking",
            description = "Eating, and when to bank at the Warriors' Guild",
            position = 2
    )
    String foodSection = "food";

    @ConfigSection(
            name = "General",
            description = "Antiban and on-screen overlay",
            position = 3
    )
    String generalSection = "general";

    // --- Tokens ---

    @ConfigItem(
            keyName = "armourSet",
            name = "Armour set",
            description = "Set to animate: a full helm, platebody and platelegs of one metal, in the inventory or the "
                    + "bank. Better sets give more tokens (rune 40 a kill) but the armour hits harder",
            position = 0,
            section = tokenSection
    )
    default ArmourChoice armourSet() {
        return ArmourChoice.BEST;
    }

    @Range(min = 100, max = 5000)
    @ConfigItem(
            keyName = "tokensToCollect",
            name = "Collect tokens up to",
            description = "Animate armour until you have this many tokens, then go to the cyclopes. Entering needs "
                    + "100, and the room uses 10 a minute",
            position = 1,
            section = tokenSection
    )
    default int tokensToCollect() {
        return 300;
    }

    @Range(min = 0, max = 100)
    @ConfigItem(
            keyName = "leaveAtTokens",
            name = "Leave the cyclopes at",
            description = "Leave the room and go back to animating armour when tokens drop to this (a defender on "
                    + "the floor is picked up first)",
            position = 2,
            section = tokenSection
    )
    default int leaveAtTokens() {
        return 10;
    }

    // --- Defenders ---

    @ConfigItem(
            keyName = "stopAt",
            name = "Stop at",
            description = "Stop once you own this defender. Dragon means: rune on the top floor, then the basement",
            position = 0,
            section = defenderSection
    )
    default Defender stopAt() {
        return Defender.DRAGON;
    }

    @ConfigItem(
            keyName = "wearDefender",
            name = "Wear the best defender",
            description = "Equip each new defender in the shield slot (when the slot is empty or holds a defender). "
                    + "Older defenders go to the bank",
            position = 1,
            section = defenderSection
    )
    default boolean wearDefender() {
        return true;
    }

    @ConfigItem(
            keyName = "showKamfreena",
            name = "Show each defender to Kamfreena",
            description = "After every new defender, leave the room, use it on Kamfreena so she releases the next "
                    + "cyclopes, then go back in",
            position = 2,
            section = defenderSection
    )
    default boolean showKamfreena() {
        return true;
    }

    // --- Food & banking ---

    @ConfigItem(
            keyName = "foodName",
            name = "Food",
            description = "Food to take from the bank. Leave empty to take whatever food the bank has",
            position = 0,
            section = foodSection
    )
    default String foodName() {
        return "";
    }

    @Range(min = 0, max = 26)
    @ConfigItem(
            keyName = "foodAmount",
            name = "Food to carry",
            description = "Food to take on each bank trip. Leave room for the armour set and a defender",
            position = 1,
            section = foodSection
    )
    default int foodAmount() {
        return 10;
    }

    @Range(min = 10, max = 90)
    @ConfigItem(
            keyName = "eatAtPercent",
            name = "Eat at health %",
            description = "Eat when health drops to this",
            position = 2,
            section = foodSection
    )
    default int eatAtPercent() {
        return 50;
    }

    @Range(min = 5, max = 80)
    @ConfigItem(
            keyName = "bankAtPercent",
            name = "Bank at health %",
            description = "Out of food and below this: leave the fight and bank at the Warriors' Guild",
            position = 3,
            section = foodSection
    )
    default int bankAtPercent() {
        return 40;
    }

    // --- General ---

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Short random pauses, micro-breaks and mouse wandering between actions",
            position = 0,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Draw the Shadowfax Warrior's Guild panel on screen",
            position = 1,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
