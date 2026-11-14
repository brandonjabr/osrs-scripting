package net.runelite.client.plugins.microbot.shadowfaxgildedaltar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(GildedAltarConfig.GROUP)
public interface GildedAltarConfig extends Config {

    String GROUP = "shadowfaxgildedaltar";

    @ConfigSection(
            name = "Offering",
            description = "Which bones to offer and whose house to use",
            position = 0
    )
    String offeringSection = "offering";

    @ConfigSection(
            name = "General",
            description = "Stopping, antiban and overlay",
            position = 1
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "bones",
            name = "Bones",
            description = "Bones to withdraw from the bank and offer",
            position = 0,
            section = offeringSection
    )
    default BoneType bones() {
        return BoneType.DRAGON_BONES;
    }

    @ConfigItem(
            keyName = "preferredHost",
            name = "Preferred host",
            description = "Player name to pick from the House Advertisement board when listed. "
                    + "Leave blank to take the top house with a gilded altar",
            position = 1,
            section = offeringSection
    )
    default String preferredHost() {
        return "";
    }

    @ConfigItem(
            keyName = "useVisitLast",
            name = "Use Visit-Last",
            description = "After the first visit, use the board's Visit-Last option to go straight back to the same house. "
                    + "Falls back to the board list if that house is gone",
            position = 2,
            section = offeringSection
    )
    default boolean useVisitLast() {
        return true;
    }

    @ConfigItem(
            keyName = "usePhials",
            name = "Unnote at Phials",
            description = "With your house in Rimmington: carry noted bones and coins, and have Phials (next to the "
                    + "general store, by the portal) unnote them for 5 coins each between trips instead of walking to "
                    + "a bank. Only goes to the bank when the noted bones or coins run out",
            position = 3,
            section = offeringSection
    )
    default boolean usePhials() {
        return true;
    }

    @ConfigItem(
            keyName = "logoutWhenDone",
            name = "Log out when done",
            description = "Log out when the bank runs out of bones or Teleport to house tablets",
            position = 0,
            section = generalSection
    )
    default boolean logoutWhenDone() {
        return true;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Apply the general antiban template: natural mouse, play styles and micro-break chances",
            position = 1,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show stats overlay",
            description = "Draw the Shadowfax Gilded Altar stats panel on screen",
            position = 2,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
