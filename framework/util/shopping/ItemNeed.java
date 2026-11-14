package net.runelite.client.plugins.microbot.util.shopping;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One item a script needs on hand: which item, how many, and whether it should be worn once withdrawn.
 * Immutable; combine lists of needs with {@link #merge(Collection)}.
 */
public final class ItemNeed {

    private final int itemId;
    private final String name;
    private final int quantity;
    private final boolean equip;

    public ItemNeed(int itemId, String name, int quantity, boolean equip) {
        this.itemId = itemId;
        this.name = name == null ? "item " + itemId : name;
        this.quantity = Math.max(1, quantity);
        this.equip = equip;
    }

    public static ItemNeed of(int itemId, String name, int quantity) {
        return new ItemNeed(itemId, name, quantity, false);
    }

    public static ItemNeed worn(int itemId, String name) {
        return new ItemNeed(itemId, name, 1, true);
    }

    public int getItemId() {
        return itemId;
    }

    public String getName() {
        return name;
    }

    public int getQuantity() {
        return quantity;
    }

    public boolean isEquip() {
        return equip;
    }

    public ItemNeed withQuantity(int newQuantity) {
        return new ItemNeed(itemId, name, newQuantity, equip);
    }

    /**
     * Combines needs for the same item. Quantities add up, so pass needs for <em>different</em> uses
     * (e.g. two quests that each consume an egg). Worn items keep the flag.
     */
    public static List<ItemNeed> merge(Collection<ItemNeed> needs) {
        Map<Integer, ItemNeed> merged = new LinkedHashMap<>();
        for (ItemNeed need : needs) {
            merged.merge(need.itemId, need, (a, b) ->
                    new ItemNeed(a.itemId, a.name, a.quantity + b.quantity, a.equip || b.equip));
        }
        return List.copyOf(merged.values());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemNeed)) return false;
        ItemNeed other = (ItemNeed) o;
        return itemId == other.itemId && quantity == other.quantity && equip == other.equip;
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, quantity, equip);
    }

    @Override
    public String toString() {
        return name + " x" + quantity + (equip ? " (worn)" : "");
    }
}
