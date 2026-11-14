package net.runelite.client.plugins.microbot.shadowfaxwarriorsguild;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import java.util.Locale;

/**
 * The defender ladder. Bronze to rune drop from the top-floor cyclopes, one tier above the best defender you carry
 * (or have shown Kamfreena); dragon drops only in the basement once you have a rune defender.
 */
public enum Defender {
    BRONZE("Bronze", ItemID.BRONZE_PARRYINGDAGGER),
    IRON("Iron", ItemID.IRON_PARRYINGDAGGER),
    STEEL("Steel", ItemID.STEEL_PARRYINGDAGGER),
    BLACK("Black", ItemID.BLACK_PARRYINGDAGGER),
    MITHRIL("Mithril", ItemID.MITHRIL_PARRYINGDAGGER),
    ADAMANT("Adamant", ItemID.ADAMANT_PARRYINGDAGGER),
    RUNE("Rune", ItemID.RUNE_PARRYINGDAGGER),
    DRAGON("Dragon", ItemID.DRAGON_PARRYINGDAGGER);

    final String metal;
    final int itemId;

    Defender(String metal, int itemId) {
        this.metal = metal;
        this.itemId = itemId;
    }

    String itemName() {
        return metal + " defender";
    }

    /** The tier the cyclopes drop next for someone whose best defender is {@code best} (null: none yet). */
    static Defender nextAfter(Defender best) {
        if (best == null) return BRONZE;
        if (best == DRAGON) return DRAGON;
        return values()[best.ordinal() + 1];
    }

    /** Dragon defenders only drop in the basement. */
    boolean dropsInBasement() {
        return this == DRAGON;
    }

    /**
     * The tier of a defender item: the plain item, or a variant named "<metal> defender ..." such as the trimmed
     * "(t)" or ornament versions. Broken defenders don't count. Null for anything else.
     */
    static Defender of(Rs2ItemModel item) {
        if (item == null || item.isNoted()) return null;
        for (Defender d : values()) {
            if (item.getId() == d.itemId) return d;
        }
        return ofName(item.getName());
    }

    static Defender ofName(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("broken")) return null;
        for (Defender d : values()) {
            if (lower.startsWith(d.metal.toLowerCase(Locale.ROOT) + " defender")) return d;
        }
        return null;
    }

    static Defender max(Defender a, Defender b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    @Override
    public String toString() {
        return itemName();
    }
}
