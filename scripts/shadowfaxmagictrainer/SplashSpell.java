package net.runelite.client.plugins.microbot.shadowfaxmagictrainer;

import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Free-to-play splashing spells, weakest first. Rune costs are kept here rather than read from
 * Rs2Spells, whose Curse entry has the earth and water counts swapped.
 */
public enum SplashSpell {
    WIND_STRIKE("Wind Strike", MagicAction.WIND_STRIKE, 1, 5.5, runes(Runes.AIR, 1, Runes.MIND, 1)),
    CONFUSE("Confuse", MagicAction.CONFUSE, 3, 13, runes(Runes.WATER, 3, Runes.EARTH, 2, Runes.BODY, 1)),
    WEAKEN("Weaken", MagicAction.WEAKEN, 11, 21, runes(Runes.WATER, 3, Runes.EARTH, 2, Runes.BODY, 1)),
    CURSE("Curse", MagicAction.CURSE, 19, 29, runes(Runes.EARTH, 3, Runes.WATER, 2, Runes.BODY, 1));

    final String displayName;
    final MagicAction magicAction;
    final int level;
    final double xp;
    /** Runes per cast, in the order they should be bought. */
    final Map<Runes, Integer> runes;

    SplashSpell(String displayName, MagicAction magicAction, int level, double xp, Map<Runes, Integer> runes) {
        this.displayName = displayName;
        this.magicAction = magicAction;
        this.level = level;
        this.xp = xp;
        this.runes = runes;
    }

    @Override
    public String toString() {
        return displayName;
    }

    private static Map<Runes, Integer> runes(Object... runeAmountPairs) {
        Map<Runes, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < runeAmountPairs.length; i += 2) {
            map.put((Runes) runeAmountPairs[i], (Integer) runeAmountPairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    /** "Body rune" from Runes.BODY. */
    static String runeName(Runes rune) {
        String s = rune.name().toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + " rune";
    }
}
