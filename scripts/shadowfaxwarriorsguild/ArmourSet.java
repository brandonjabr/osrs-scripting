package net.runelite.client.plugins.microbot.shadowfaxwarriorsguild;

import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import java.util.List;

/**
 * Armour sets for the Magical Animator: a full helm, platebody and platelegs of one metal, and the Warrior guild
 * tokens the animated armour drops when it's beaten.
 */
public enum ArmourSet {
    BRONZE("Bronze", 5),
    IRON("Iron", 10),
    STEEL("Steel", 15),
    BLACK("Black", 20),
    MITHRIL("Mithril", 25),
    ADAMANT("Adamant", 30),
    RUNE("Rune", 40);

    final String metal;
    final int tokens;

    ArmourSet(String metal, int tokens) {
        this.metal = metal;
        this.tokens = tokens;
    }

    List<String> pieces() {
        return List.of(metal + " full helm", metal + " platebody", metal + " platelegs");
    }

    /** All three pieces are in the inventory (unnoted). */
    boolean carried() {
        for (String piece : pieces()) {
            if (!Rs2Inventory.hasItem(piece, true)) return false;
        }
        return true;
    }

    /** True for one of this set's pieces. */
    boolean isPiece(String name) {
        if (name == null) return false;
        for (String piece : pieces()) {
            if (piece.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** The best set whose three pieces are all in the inventory, or null. */
    static ArmourSet bestCarried() {
        ArmourSet[] sets = values();
        for (int i = sets.length - 1; i >= 0; i--) {
            if (sets[i].carried()) return sets[i];
        }
        return null;
    }

    @Override
    public String toString() {
        return metal + " (" + tokens + " tokens)";
    }
}
