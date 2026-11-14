package net.runelite.client.plugins.microbot.shadowfaxboltenchanter;

import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;

/** The gem-tipped bolts Enchant Crossbow Bolt works on, lowest level first. Each covers regular and dragon bolts. */
public enum BoltType {
    OPAL("Opal", 4, 9, Rs2Spells.ENCHANT_OPAL_BOLTS),
    SAPPHIRE("Sapphire", 7, 17.5, Rs2Spells.ENCHANT_SAPPHIRE_BOLT),
    JADE("Jade", 14, 19, Rs2Spells.ENCHANT_JADE_BOLT),
    PEARL("Pearl", 24, 29, Rs2Spells.ENCHANT_PEARL_BOLT),
    EMERALD("Emerald", 27, 37, Rs2Spells.ENCHANT_EMERALD_BOLT),
    TOPAZ("Topaz", 29, 33, Rs2Spells.ENCHANT_TOPAZ_BOLT),
    RUBY("Ruby", 49, 59, Rs2Spells.ENCHANT_RUBY_BOLT),
    DIAMOND("Diamond", 57, 67, Rs2Spells.ENCHANT_DIAMOND_BOLT),
    DRAGONSTONE("Dragonstone", 68, 78, Rs2Spells.ENCHANT_DRAGONSTONE_BOLT),
    ONYX("Onyx", 87, 97, Rs2Spells.ENCHANT_ONYX_BOLT);

    final String gem;
    final int level;
    final double xp;
    final Rs2Spells spell;

    BoltType(String gem, int level, double xp, Rs2Spells spell) {
        this.gem = gem;
        this.level = level;
        this.xp = xp;
        this.spell = spell;
    }

    /** Unenchanted inventory names, e.g. "Ruby bolts" and "Ruby dragon bolts" (never the "(e)" ones). */
    String[] itemNames() {
        return new String[]{gem + " bolts", gem + " dragon bolts"};
    }

    @Override
    public String toString() {
        return gem;
    }
}
