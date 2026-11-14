package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.questhelper.requirements.Requirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.ectofuntus.Ectofuntus;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;

import java.util.ArrayList;
import java.util.List;

/**
 * Earning ecto-tokens for a quest step (Animal Magnetism's chickens, Ghosts Ahoy's barrier and boat fares). Tokens
 * can't be traded, so they come from the Ectofuntus: grind bones, fill buckets with slime, worship, claim from a
 * ghost disciple. One call does one action; missing bones, pots or buckets are fetched (and bought) from the bank.
 */
@Slf4j
final class EctoTokenSupport {

    // Shared grinder/slime/worship/claim steps; one at a time, since only one quest runs.
    private static final Ectofuntus ECTO = new Ectofuntus();

    private EctoTokenSupport() {
    }

    /** Worships needed to earn {@code tokens} from nothing. */
    static int worshipsFor(int tokens) {
        return (tokens + Ectofuntus.TOKENS_PER_WORSHIP - 1) / Ectofuntus.TOKENS_PER_WORSHIP;
    }

    /** Ecto-tokens the active step asks for (0 when it lists none). */
    static int stepNeed(QuestContext ctx) {
        QuestStep step = ctx.activeStep();
        if (!(step instanceof DetailedQuestStep)) return 0;
        int need = 0;
        for (Requirement req : ((DetailedQuestStep) step).getRequirements()) {
            if (!(req instanceof ItemRequirement)) continue;
            ItemRequirement item = (ItemRequirement) req;
            List<Integer> ids = item.getAllIds();
            if (ids != null && ids.contains(ItemID.ECTOTOKEN)) need = Math.max(need, item.getQuantity());
        }
        return need;
    }

    /**
     * Works towards carrying {@code target} tokens: claims once earned + unclaimed reach it, otherwise grinds, fills
     * slime and worships for the offerings still needed. False if there's nothing it can do.
     */
    static boolean earn(QuestContext ctx, int target, String label) {
        if (ctx.continueDialogue()) return true;

        int tokens = Ectofuntus.tokens();
        // Tokens already in the bank beat a trip round the Ectofuntus: fetch them first (once per shortfall, from the
        // bank as last seen, since untradeable tokens can't be bought if the guess is wrong).
        int banked = Rs2Bank.count(ItemID.ECTOTOKEN);
        if (tokens < target && banked > 0 && ctx.get("tokensFetchedAt", -1) != tokens) {
            ctx.set("tokensFetchedAt", tokens);
            int take = Math.min(banked, target - tokens);
            log.info("[Questing] {}: {} of {} tokens carried, {} in the bank; withdrawing {}",
                    label, tokens, target, banked, take);
            ctx.requestItems(List.of(ItemNeed.of(ItemID.ECTOTOKEN, "Ecto-token", tokens + take)), false);
            return true;
        }
        int unclaimed = Ectofuntus.unclaimed();
        int bonemeal = Ectofuntus.bonemeal();
        int slime = Ectofuntus.slime();
        int bones = Ectofuntus.bones();
        int pots = Ectofuntus.pots();
        int buckets = Ectofuntus.buckets();
        // Offerings still to make, counting ones already made but not yet offered.
        int needed = Math.max(0, worshipsFor(target - tokens - unclaimed));

        if (tokens + unclaimed >= target || (unclaimed > 0 && needed > 0 && bonemeal == 0 && slime == 0
                && (bones == 0 || pots == 0) && buckets == 0)) {
            Microbot.status = "Claiming ecto-tokens (" + (tokens + unclaimed) + "/" + target + ")";
            return ECTO.claim();
        }

        // Let a running grinder or slime fill finish before moving on (the last bone is still in the grinder).
        if (ECTO.isGrinding()) return ECTO.grind();
        if (ECTO.isFillingBuckets()) return ECTO.fillBuckets();
        if (bonemeal < needed && bones > 0 && pots > 0) return ECTO.grind();
        if (slime < needed && buckets > 0) return ECTO.fillBuckets();
        if (bonemeal > 0 && slime > 0) return ECTO.worship();

        // Out of materials for the offerings still needed: fetch (and buy) bones, pots and buckets.
        int mealShort = Math.max(0, needed - bonemeal);
        int slimeShort = Math.max(0, needed - slime);
        List<ItemNeed> fetch = new ArrayList<>();
        if (mealShort > bones) fetch.add(ItemNeed.of(ItemID.BONES, "Bones", mealShort));
        if (mealShort > pots) fetch.add(ItemNeed.of(ItemID.POT_EMPTY, "Pot", mealShort));
        if (slimeShort > buckets) fetch.add(ItemNeed.of(ItemID.BUCKET_EMPTY, "Bucket", slimeShort));
        if (fetch.isEmpty()) return false;
        log.info("[Questing] {}: {} tokens (+{} unclaimed) of {}; fetching {} for the Ectofuntus",
                label, tokens, unclaimed, target, fetch);
        ctx.requestItems(fetch, false);
        return true;
    }

    /** Fills one bucket of slime at the pool (for a quest item, not an offering), fetching a bucket if needed. */
    static boolean fillOneBucket(QuestContext ctx) {
        if (Ectofuntus.buckets() == 0) {
            ctx.requestItems(List.of(ItemNeed.of(ItemID.BUCKET_EMPTY, "Bucket", 1)), false);
            return true;
        }
        return ECTO.fillBuckets();
    }
}
