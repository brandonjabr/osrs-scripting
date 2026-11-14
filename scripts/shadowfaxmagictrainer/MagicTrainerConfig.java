package net.runelite.client.plugins.microbot.shadowfaxmagictrainer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(MagicTrainerConfig.GROUP)
public interface MagicTrainerConfig extends Config {

    String GROUP = "shadowfaxmagictrainer";

    @ConfigSection(
            name = "Spells",
            description = "Which splashing spell to use and when to stop",
            position = 0
    )
    String spellSection = "spells";

    @ConfigSection(
            name = "Runes",
            description = "Withdrawing and buying runes at the Grand Exchange",
            position = 1
    )
    String runeSection = "runes";

    @ConfigSection(
            name = "General",
            description = "Antiban and on-screen overlay",
            position = 2
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "maxSpell",
            name = "Highest spell",
            description = "Always casts the best spell your Magic level allows, up to this one: "
                    + "Wind Strike (1), Confuse (3), Weaken (11), Curse (19)",
            position = 0,
            section = spellSection
    )
    default SplashSpell maxSpell() {
        return SplashSpell.CURSE;
    }

    @Range(min = 0, max = 99)
    @ConfigItem(
            keyName = "stopAtLevel",
            name = "Stop at Magic level",
            description = "Stop once Magic reaches this level. 0 keeps going",
            position = 1,
            section = spellSection
    )
    default int stopAtLevel() {
        return 0;
    }

    @ConfigItem(
            keyName = "logoutWhenDone",
            name = "Log out when done",
            description = "Log out when the target level is reached or runes can't be restocked",
            position = 2,
            section = spellSection
    )
    default boolean logoutWhenDone() {
        return true;
    }

    @Range(min = 50, max = 20_000)
    @ConfigItem(
            keyName = "castsPerTrip",
            name = "Casts per trip",
            description = "How many casts' worth of runes to take from the bank or buy on each Grand Exchange trip",
            position = 0,
            section = runeSection
    )
    default int castsPerTrip() {
        return 1000;
    }

    @ConfigItem(
            keyName = "buyRunes",
            name = "Buy runes on the GE",
            description = "Buy whatever runes the bank is missing at the Grand Exchange. Needs 100 total level",
            position = 1,
            section = runeSection
    )
    default boolean buyRunes() {
        return true;
    }

    @Range(min = 1_000, max = 10_000_000)
    @ConfigItem(
            keyName = "runeBudget",
            name = "Rune budget (gp)",
            description = "Most coins to spend on runes per Grand Exchange trip",
            position = 2,
            section = runeSection
    )
    default int runeBudget() {
        return 30_000;
    }

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Apply the general antiban template: action cooldowns, natural mouse and micro-break chances",
            position = 0,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show stats overlay",
            description = "Draw the Shadowfax Magic Trainer stats panel on screen",
            position = 1,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
