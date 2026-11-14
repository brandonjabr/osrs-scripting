package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/** Small one-action helpers the quest overrides share, beyond what {@code QuestContext} offers. */
final class QuestActions {

    private QuestActions() {
    }

    /** Uses an inventory item on an NPC. */
    static boolean useItemOnNpc(int itemId, Rs2NpcModel npc) {
        if (npc == null || !Rs2Inventory.hasItem(itemId)) return false;
        if (!Rs2Inventory.use(itemId)) return false;
        if (!sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1_500)) return false;
        return npc.click();
    }

    /**
     * Picks up an item lying within {@code radius} tiles, including ones on tables and benches the "reachable"
     * check rejects. True if it tried.
     */
    static boolean pickUpNearby(int itemId, int radius) {
        Rs2TileItemModel item = Microbot.getRs2TileItemCache().query()
                .withId(itemId)
                .nearestOnClientThread(radius);
        if (item == null) return false;
        int before = Rs2Inventory.itemQuantity(itemId);
        Microbot.status = "Picking up " + item.getName();
        if (item.pickup()) sleepUntil(() -> Rs2Inventory.itemQuantity(itemId) > before, 6_000);
        return true;
    }

    /** True once standing exactly on {@code tile}; otherwise walks there (minimap, then a canvas click). */
    static boolean standOn(WorldPoint tile) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (tile.equals(here)) return true;
        if (here == null || here.getPlane() != tile.getPlane() || here.distanceTo(tile) > 6) {
            Rs2Walker.walkTo(tile, 0);
        } else {
            Rs2Walker.walkFastCanvas(tile);
            sleepUntil(() -> tile.equals(Rs2Player.getWorldLocation()), 3_000);
        }
        return false;
    }

    /** Wears the item if it's in the inventory and not worn. True if it acted. */
    static boolean wearIfCarried(int itemId) {
        if (Rs2Equipment.isWearing(itemId) || !Rs2Inventory.hasItem(itemId)) return false;
        Microbot.status = "Wearing " + Rs2Inventory.get(itemId).getName();
        Rs2Inventory.wear(itemId);
        sleepUntil(() -> Rs2Equipment.isWearing(itemId), 2_000);
        return true;
    }
}
