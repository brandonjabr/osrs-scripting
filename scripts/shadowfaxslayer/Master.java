package net.runelite.client.plugins.microbot.shadowfaxslayer;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * The slayer masters the script works with: the cheapest to reach at each stage of the game.
 * <ul>
 *   <li><b>Turael</b> (Burthorpe): no requirements, tiny tasks. Also does "Turael skips".</li>
 *   <li><b>Vannaka</b> (Edgeville Dungeon): combat 40, no quests, next to a bank and a games-necklace teleport,
 *       a much wider task list than Mazchna for the same effort.</li>
 *   <li><b>Nieve / Steve</b> (Tree Gnome Stronghold): combat 85, no quests, right outside the Stronghold Slayer
 *       Cave where many of her tasks live, with a bank a few steps away.</li>
 * </ul>
 * All three sell the same Slayer Equipment shop.
 */
public enum Master {
    TURAEL("Turael", new String[]{"Turael", "Aya"}, new WorldPoint(2931, 3536, 0), 3, 1),
    VANNAKA("Vannaka", new String[]{"Vannaka"}, new WorldPoint(3145, 9914, 0), 40, 3),
    NIEVE("Nieve", new String[]{"Nieve", "Steve"}, new WorldPoint(2432, 3423, 0), 85, 6);

    private final String label;
    private final String[] npcNames;
    private final WorldPoint location;
    private final int combatRequired;
    // Value of the SLAYER_MASTER varbit for a task from this master.
    private final int varbitValue;

    Master(String label, String[] npcNames, WorldPoint location, int combatRequired, int varbitValue) {
        this.label = label;
        this.npcNames = npcNames;
        this.location = location;
        this.combatRequired = combatRequired;
        this.varbitValue = varbitValue;
    }

    public String label() {
        return label;
    }

    public String[] npcNames() {
        return npcNames;
    }

    public WorldPoint location() {
        return location;
    }

    public int combatRequired() {
        return combatRequired;
    }

    public int varbitValue() {
        return varbitValue;
    }

    public boolean canUse() {
        return Rs2Player.getCombatLevel() >= combatRequired;
    }

    /** The best master the player's combat level allows. */
    public static Master bestForCombat() {
        Master best = TURAEL;
        for (Master m : values()) {
            if (m.canUse()) best = m;
        }
        return best;
    }

    public static Master byVarbit(int value) {
        for (Master m : values()) {
            if (m.varbitValue == value) return m;
        }
        return null;
    }
}
