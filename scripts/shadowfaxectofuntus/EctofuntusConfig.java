package net.runelite.client.plugins.microbot.shadowfaxectofuntus;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(EctofuntusConfig.GROUP)
public interface EctofuntusConfig extends Config {

    String GROUP = "shadowfaxectofuntus";

    @ConfigSection(
            name = "Run",
            description = "What to do after worshipping",
            position = 0
    )
    String runSection = "run";

    @ConfigSection(
            name = "General",
            description = "Antiban and overlay",
            position = 1
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "claimTokens",
            name = "Claim ecto-tokens",
            description = "Collect the ecto-tokens from a ghost disciple after each inventory. The script won't start "
                    + "without a ghostspeak amulet worn (or carried, to put on) while this is on",
            position = 0,
            section = runSection
    )
    default boolean claimTokens() {
        return true;
    }

    @ConfigItem(
            keyName = "bankAndRepeat",
            name = "Bank and repeat",
            description = "After each inventory: Catherby teleport (tablet) to the Catherby bank, take out coins for the "
                    + "charter, the tablets, and pots, buckets and bones; charter a ship to Port Phasmatys; walk to the "
                    + "Ectofuntus and go again. Stops when the bank runs out of any of them",
            position = 2,
            section = runSection
    )
    default boolean bankAndRepeat() {
        return true;
    }

    @ConfigItem(
            keyName = "bones",
            name = "Bones",
            description = "Exact name of the bones to take from the bank, e.g. Bones, Big bones, Dragon bones",
            position = 3,
            section = runSection
    )
    default String bones() {
        return "Bones";
    }

    @ConfigItem(
            keyName = "loadsPerTrip",
            name = "Offerings per trip",
            description = "Bones, pots and buckets taken out per trip (each offering takes one of each). 8 leaves room "
                    + "for the coins, teleport tablets and ecto-tokens",
            position = 4,
            section = runSection
    )
    default int loadsPerTrip() {
        return 8;
    }

    @ConfigItem(
            keyName = "charterCoins",
            name = "Coins for the charter",
            description = "Coins to carry from the Catherby bank for the ship to Port Phasmatys (the walker's charter "
                    + "list has it at 3,500)",
            position = 5,
            section = runSection
    )
    default int charterCoins() {
        return 3_500;
    }

    @ConfigItem(
            keyName = "logoutWhenDone",
            name = "Log out when done",
            description = "Log out when the run ends (out of supplies, or after one inventory without banking)",
            position = 6,
            section = runSection
    )
    default boolean logoutWhenDone() {
        return false;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Apply the general antiban template: natural mouse, play styles and micro-break chances",
            position = 0,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show stats overlay",
            description = "Draw the Shadowfax Ectofuntus stats panel on screen",
            position = 1,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
