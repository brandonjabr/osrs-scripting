package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Gertrude's Cat: the kittens hide in one of the crates on the Lumberyard's ground floor. Quest Helper's step points
 * at the mewing kittens (an NPC that can't be clicked), so this searches the crates in turn, the one nearest the
 * mewing first when it's in view, until the kitten is in the inventory. Before that, the doogle leaves for the
 * seasoned sardine are picked up behind Gertrude's house and used on the sardine.
 */
@Slf4j
final class GertrudesCatSupport {

    private static final WorldPoint LUMBERYARD = new WorldPoint(3306, 3505, 0);
    private static final int SEARCH_RADIUS = 14;

    static final StepOverride SEARCH_CRATES = new StepOverride("searching the Lumberyard crates for the kitten",
            ctx -> ctx.stepText().contains("Search different crates") && !Rs2Inventory.hasItem(ItemID.GERTRUDEKITTENS),
            GertrudesCatSupport::searchCrates);

    // Doogle leaves are ground spawns (60s respawn) outside, behind Gertrude's house: the wiki's five spawn tiles.
    // Quest Helper's pickup step has no location, so the generic executor never goes there. The house wall is
    // between its inside and the spawns, so the player has to stand among them, not just nearby.
    private static final WorldPoint[] DOOGLE_SPAWNS = {
            new WorldPoint(3151, 3399, 0), new WorldPoint(3152, 3399, 0), new WorldPoint(3152, 3401, 0),
            new WorldPoint(3156, 3401, 0), new WorldPoint(3157, 3400, 0)
    };
    private static final WorldPoint DOOGLE_GARDEN = new WorldPoint(3154, 3400, 0);
    private static final int DOOGLE_RADIUS = 8;

    static final StepOverride PICK_DOOGLE = new StepOverride("picking up doogle leaves behind Gertrude's house",
            ctx -> ctx.stepText().contains("Doogle Leaves south of Gertrude")
                    && !Rs2Inventory.hasItem(ItemID.DOOGLELEAVES) && !Rs2Inventory.hasItem(ItemID.SEASONED_SARDINE),
            GertrudesCatSupport::pickDoogle);

    static final StepOverride SEASON_SARDINE = new StepOverride("seasoning the sardine with doogle leaves",
            ctx -> ctx.stepText().contains("Doogle Leaves on")
                    && Rs2Inventory.hasItem(ItemID.DOOGLELEAVES) && Rs2Inventory.hasItem(ItemID.RAW_SARDINE),
            ctx -> {
                Microbot.status = "Using the doogle leaves on the sardine";
                if (Rs2Inventory.combine(ItemID.DOOGLELEAVES, ItemID.RAW_SARDINE)) {
                    sleepUntil(() -> Rs2Inventory.hasItem(ItemID.SEASONED_SARDINE), 3_000);
                }
                return true;
            });

    private GertrudesCatSupport() {
    }

    /** Standing within two tiles of a spawn, i.e. outside in the garden rather than in the house. */
    private static boolean inGarden(WorldPoint here) {
        for (WorldPoint spawn : DOOGLE_SPAWNS) {
            if (here.distanceTo(spawn) <= 2) return true;
        }
        return false;
    }

    private static boolean pickDoogle(QuestContext ctx) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getPlane() != 0 || !inGarden(here)) {
            Microbot.status = "Walking behind Gertrude's house for doogle leaves";
            ctx.walkTo(DOOGLE_GARDEN, 1);
            return true;
        }
        Rs2TileItemModel leaves = Microbot.getRs2TileItemCache().query()
                .withId(ItemID.DOOGLELEAVES)
                .where(i -> !i.isDespawned())
                .nearestReachableOnClientThread(DOOGLE_RADIUS);
        if (leaves == null) {
            // Someone took them; they're back within a minute.
            Microbot.status = "Waiting for the doogle leaves to respawn";
            return true;
        }
        Microbot.status = "Picking up doogle leaves";
        if (leaves.pickup()) {
            sleepUntil(() -> Rs2Inventory.hasItem(ItemID.DOOGLELEAVES), 6_000);
        }
        return true;
    }

    private static boolean searchCrates(QuestContext ctx) {
        if (ctx.continueDialogue()) return true;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getPlane() != 0 || here.distanceTo(LUMBERYARD) > SEARCH_RADIUS) {
            Microbot.status = "Walking to the Lumberyard";
            ctx.walkTo(LUMBERYARD, 3);
            return true;
        }

        Set<WorldPoint> searched = ctx.get("gertrudeCrates", new HashSet<WorldPoint>());
        ctx.set("gertrudeCrates", searched);
        List<Rs2TileObjectModel> crates = Microbot.getRs2TileObjectCache().query()
                .withAction("Search").within(LUMBERYARD, SEARCH_RADIUS).toListOnClientThread();
        if (crates.isEmpty()) {
            Microbot.status = "Looking for the Lumberyard crates";
            return false;
        }
        crates.removeIf(c -> searched.contains(c.getWorldLocation()));
        if (crates.isEmpty()) {
            // Every crate tried: the kittens move between searches, so start over.
            log.info("[Questing] Gertrude's Cat: searched every crate without the kitten; going round again");
            searched.clear();
            return true;
        }

        Rs2NpcModel mew = Microbot.getRs2NpcCache().query().withId(NpcID.KITTENS_MEW).nearestOnClientThread();
        WorldPoint near = mew != null ? mew.getWorldLocation() : here;
        Rs2TileObjectModel crate = crates.stream()
                .min(Comparator.comparingInt(c -> c.getWorldLocation().distanceTo(near))).orElse(null);
        if (crate == null) return true;

        Microbot.status = "Searching a crate for the kitten";
        searched.add(crate.getWorldLocation());
        if (crate.click("Search")) {
            sleepUntil(() -> Rs2Inventory.hasItem(ItemID.GERTRUDEKITTENS) || Rs2Dialogue.isInDialogue(), 5_000);
        }
        return true;
    }
}
