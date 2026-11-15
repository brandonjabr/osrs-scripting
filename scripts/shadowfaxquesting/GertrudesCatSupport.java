package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
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
 * mewing first when it's in view, until the kitten is in the inventory.
 */
@Slf4j
final class GertrudesCatSupport {

    private static final WorldPoint LUMBERYARD = new WorldPoint(3306, 3505, 0);
    private static final int SEARCH_RADIUS = 14;

    static final StepOverride SEARCH_CRATES = new StepOverride("searching the Lumberyard crates for the kitten",
            ctx -> ctx.stepText().contains("Search different crates") && !Rs2Inventory.hasItem(ItemID.GERTRUDEKITTENS),
            GertrudesCatSupport::searchCrates);

    private GertrudesCatSupport() {
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
