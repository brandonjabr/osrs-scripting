package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Priest in Peril helpers, for the two steps Quest Helper only describes (checked against the OSRS Wiki):
 * <ul>
 *   <li><b>The iron key</b>: under the temple, one of the seven monuments around the well holds the iron key; using
 *       the golden key on it swaps them. The wiki puts it on the southern monument, so that's tried first, then the
 *       others in turn.</li>
 *   <li><b>Drezel's 50 essence</b>: unnoted only and no pouches, so it takes two trips. Each time the inventory has
 *       none, everything else is banked and a load of pure essence (bought if the bank lacks it) is taken; Drezel
 *       takes what's carried. The count still owed comes from the quest's varp, as Quest Helper reads it.</li>
 * </ul>
 */
@Slf4j
final class PriestInPerilSupport {

    private static final int[] MONUMENTS = {
            ObjectID.PRIESTPERIL_GRAVE_BASE1, ObjectID.PRIESTPERIL_GRAVE_BASE2, ObjectID.PRIESTPERIL_GRAVE_BASE3,
            ObjectID.PRIESTPERIL_GRAVE_BASE4, ObjectID.PRIESTPERIL_GRAVE_BASE5, ObjectID.PRIESTPERIL_GRAVE_BASE6,
            ObjectID.PRIESTPERIL_GRAVE_BASE7
    };
    private static final WorldPoint WELL = new WorldPoint(3423, 9890, 0);
    // Varp 302 counts up to 60 as essence is handed over (Quest Helper: owed = 60 - value).
    private static final int ESSENCE_VARP = 302;
    private static final int ESSENCE_TOTAL = 50;
    private static final int LOAD = 27;

    static final StepOverride SWAP_KEY = StepOverride.onStepText("swapping the golden key for the iron key",
            "study the monuments", PriestInPerilSupport::swapKey);

    static final StepOverride FETCH_ESSENCE = new StepOverride("fetching essence for Drezel",
            ctx -> ctx.stepText().contains("UNNOTED rune/pure essence") && carriedEssence() == 0,
            PriestInPerilSupport::fetchEssence);

    private PriestInPerilSupport() {
    }

    private static int carriedEssence() {
        return Rs2Inventory.count(ItemID.BLANKRUNE) + Rs2Inventory.count(ItemID.BLANKRUNE_HIGH);
    }

    private static boolean swapKey(QuestContext ctx) {
        if (ctx.has(ItemID.PIPKEY_IRON)) return false;
        if (!ctx.has(ItemID.PIPKEY_GOLD)) return false;
        if (!ctx.walkTo(WELL, 8)) return true;

        Set<Long> tried = ctx.get("pipTriedMonuments", new HashSet<Long>());
        ctx.set("pipTriedMonuments", tried);
        List<Rs2TileObjectModel> monuments = Microbot.getRs2TileObjectCache().query()
                .withIds(MONUMENTS)
                .toListOnClientThread();
        // The southern monument first (the wiki's), then the rest, skipping ones already tried.
        Rs2TileObjectModel next = monuments.stream()
                .filter(m -> !tried.contains(m.getHash()))
                .min(Comparator.comparingInt(m -> m.getWorldLocation().getY()))
                .orElse(null);
        if (next == null) {
            if (tried.isEmpty()) return false;
            log.info("[Questing] Priest in Peril: no monument took the golden key; trying them all again");
            tried.clear();
            return true;
        }
        Microbot.status = "Using the golden key on the monument at " + next.getWorldLocation();
        if (ctx.useItemOn(ItemID.PIPKEY_GOLD, next)
                && sleepUntil(() -> ctx.has(ItemID.PIPKEY_IRON), 8_000)) {
            log.info("[Questing] Priest in Peril: iron key from the monument at {}", next.getWorldLocation());
        } else {
            tried.add(next.getHash());
        }
        return true;
    }

    private static boolean fetchEssence(QuestContext ctx) {
        int owed = Math.max(1, Math.min(ESSENCE_TOTAL, 60 - Microbot.getVarbitPlayerValue(ESSENCE_VARP)));
        int load = Math.min(owed, LOAD);
        log.info("[Questing] Priest in Peril: Drezel still wants {} essence; fetching {}", owed, load);
        ctx.requestItems(List.of(ItemNeed.of(ItemID.BLANKRUNE_HIGH, "Pure essence", load)), true);
        return true;
    }
}
