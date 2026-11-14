package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Fairytale I - Growing Pains helpers, for what Quest Helper leaves open (checked against the OSRS Wiki):
 * <ul>
 *   <li><b>Five farmers</b>: Quest Helper's step names five but only targets whichever is in view. This walks to
 *       Frizzy (Port Sarim), Elstan (Falador allotment), Heskel (Falador Park), Dreven (Champions' Guild) and
 *       Treznor (Varrock Castle) in turn (Alain in Taverley and Dantaera in Catherby as spares) and asks each about
 *       the Group of Advanced Gardeners, then lets Quest Helper send it back to Martin.</li>
 *   <li><b>Malignius Mortifer's three items</b>: random, but always three in a row from the wiki's list of 31.
 *       Varbit 1804 holds the first one's place in the list (Quest Helper's notes: 8 = proboscis, jangerberries,
 *       potato cactus; 15 = babydragon bones, uncut diamond, raw cave eel; 14 = volencia moss...). They're bought
 *       before going to the Nature Spirit with the secateurs.</li>
 *   <li><b>Wielding</b>: the dramen staff to get into Zanaris, the magic secateurs for the Tanglefoot (it can only
 *       be hurt with them).</li>
 * </ul>
 */
@Slf4j
final class FairytaleSupport {

    private static final class Farmer {
        final String name;
        final WorldPoint spot;

        Farmer(String name, WorldPoint spot) {
            this.name = name;
            this.spot = spot;
        }
    }

    private static final Farmer[] FARMERS = {
            new Farmer("Frizzy Skernip", new WorldPoint(3060, 3257, 0)),
            new Farmer("Elstan", new WorldPoint(3053, 3306, 0)),
            new Farmer("Heskel", new WorldPoint(3004, 3374, 0)),
            new Farmer("Dreven", new WorldPoint(3181, 3357, 0)),
            new Farmer("Treznor", new WorldPoint(3228, 3458, 0)),
            // Spares, in case one of the five isn't where expected.
            new Farmer("Alain", new WorldPoint(2936, 3438, 0)),
            new Farmer("Dantaera", new WorldPoint(2811, 3463, 0)),
    };
    private static final int FARMERS_NEEDED = 5;

    // Malignius's list, in the wiki's order; varbit 1804 is the 0-based place of the first of three.
    private static final int MORTIFER_VARBIT = 1804;
    private static final int[] MORTIFER_ITEMS = {
            ItemID.WHITE_BERRIES, ItemID.MORTMYREPEAR, ItemID.MORTMYREBUDDINGSTEM, ItemID.MORTMYREMUSHROOM,
            ItemID.NATURE_TALISMAN, ItemID.AVANTOE, ItemID.IRIT_LEAF, ItemID.BLUE_DRAGON_SCALE,
            ItemID.MOSQUITO_PROBOSCIS, ItemID.JANGERBERRIES, ItemID.CACTUS_POTATO, ItemID.CRUSHED_GEMSTONE,
            ItemID.SNAPDRAGON, ItemID.BUCKET_SUPERCOMPOST, ItemID.VOLENCIA_MOSS, ItemID.BABYDRAGON_BONES,
            ItemID.UNCUT_DIAMOND, ItemID.RAW_CAVE_EEL, ItemID.EDIBLE_SEAWEED, ItemID.OYSTERSHELL, ItemID.CHARCOAL,
            ItemID.RED_VINE_WORM, ItemID.SNAIL_CORPSE3, ItemID.RED_SPIDERS_EGGS, ItemID.MORT_SLIMEY_EEL,
            ItemID.GRAPES, ItemID.UNCUT_RUBY, ItemID.TBWT_JOGRE_BONES, ItemID.KING_WORM, ItemID.SNAPE_GRASS,
            ItemID.LIME
    };
    private static final String[] MORTIFER_NAMES = {
            "White berries", "Mort myre pear", "Mort myre stem", "Mort myre fungus", "Nature talisman",
            "Avantoe", "Irit leaf", "Blue dragon scale", "Proboscis", "Jangerberries", "Potato cactus",
            "Crushed gem", "Snapdragon", "Supercompost", "Volencia moss", "Babydragon bones", "Uncut diamond",
            "Raw cave eel", "Edible seaweed", "Oyster", "Charcoal", "Red vine worm", "Fat snail",
            "Red spiders' eggs", "Raw slimy eel", "Grapes", "Uncut ruby", "Jogre bones", "King worm",
            "Snape grass", "Lime"
    };

    // Quest Helper moves on to "Return to Martin" only if it catches the fifth farmer's closing line; until then
    // this keeps the step, and goes back to Martin itself once five have answered.
    static final StepOverride FARMERS_STEP = new StepOverride("asking five farmers about the crops",
            ctx -> ctx.stepText().contains("Talk to 5 farmers"), FairytaleSupport::talkToFarmers);

    private static final String GARDENERS_QUESTION = "Group of Advanced Gardeners";
    private static final WorldPoint MARTIN = new WorldPoint(3078, 3256, 0);

    static final StepOverride MORTIFER_ITEMS_STEP = new StepOverride("getting Malignius Mortifer's three items",
            ctx -> {
                String text = ctx.stepText();
                return text.contains("Get the items Mortifer tells you") || text.startsWith("Talk to the Nature Spirit.");
            }, FairytaleSupport::mortiferItems);

    static final StepOverride WIELD = new StepOverride("wielding the right item",
            ctx -> toWield(ctx) > 0, ctx -> QuestActions.wearIfCarried(toWield(ctx)));

    private FairytaleSupport() {
    }

    /** The item to wield before this step, or -1: magic secateurs for the Tanglefoot, the dramen staff for Zanaris. */
    private static int toWield(QuestContext ctx) {
        String text = ctx.stepText().toLowerCase(Locale.ROOT);
        boolean travel = text.contains("travel to zanaris") || (text.contains("zanaris") && !inZanaris());
        if (travel && !inZanaris()) {
            return Rs2Inventory.hasItem(ItemID.DRAMEN_STAFF) ? ItemID.DRAMEN_STAFF : -1;
        }
        if (text.contains("tanglefoot")) {
            return Rs2Inventory.hasItem(ItemID.FAIRY_ENCHANTED_SECATEURS) ? ItemID.FAIRY_ENCHANTED_SECATEURS : -1;
        }
        return -1;
    }

    private static boolean inZanaris() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.getX() >= 2368 && here.getX() <= 2495 && here.getY() >= 4353
                && here.getY() <= 4479;
    }

    // --- Farmers ---

    private static Set<String> farmersDone(QuestContext ctx) {
        Set<String> done = ctx.get("fairytaleFarmers", new HashSet<String>());
        ctx.set("fairytaleFarmers", done);
        return done;
    }

    /**
     * Asks each farmer in turn, running the conversation here so the right question gets asked and the answer is
     * read. A farmer who says he's "already told you all I know" was asked before (a restart forgets who), so he
     * counts too. Once five have answered, or a farmer's reply says the investigation is over, it's back to Martin.
     * Never hands the step back: Quest Helper's own version just talks to the nearest farmer again.
     */
    private static boolean talkToFarmers(QuestContext ctx) {
        Set<String> done = farmersDone(ctx);
        Set<String> skipped = ctx.get("fairytaleFarmersSkipped", new HashSet<String>());
        ctx.set("fairytaleFarmersSkipped", skipped);
        if (Rs2Dialogue.isInDialogue()) {
            // A conversation the runner opened: finish it as ours.
            noteReply(ctx, converse(GARDENERS_QUESTION), ctx.get("fairytaleAsking", ""));
            return true;
        }
        if (ctx.get("fairytaleFarmersFinished", false) || done.size() >= FARMERS_NEEDED) return backToMartin(ctx);

        for (Farmer farmer : FARMERS) {
            if (done.contains(farmer.name) || skipped.contains(farmer.name)) continue;
            if (!ctx.walkTo(farmer.spot, 6)) {
                Microbot.status = "Walking to " + farmer.name;
                return true;
            }
            Rs2NpcModel npc = ctx.npc(farmer.name);
            if (npc == null) {
                log.info("[Questing] Fairytale I: {} isn't at {}; skipping to the next farmer", farmer.name,
                        farmer.spot);
                skipped.add(farmer.name);
                return true;
            }
            Microbot.status = "Asking " + farmer.name + " about the crops";
            ctx.set("fairytaleAsking", farmer.name);
            if (ctx.talkTo(npc)) noteReply(ctx, converse(GARDENERS_QUESTION), farmer.name);
            return true;
        }
        // Every farmer asked or missing: Martin says whether it was enough.
        return backToMartin(ctx);
    }

    /** Counts the farmer as asked if he answered the question (or had already), and spots the last answer. */
    private static void noteReply(QuestContext ctx, String said, String farmer) {
        if (farmer.isEmpty()) return;
        Set<String> done = farmersDone(ctx);
        if (said.contains("already told you") || said.contains("advanced gardeners") || said.contains("crops")) {
            done.add(farmer);
        }
        if (said.contains("thanks for your input") || said.contains("crops are failing")) {
            ctx.set("fairytaleFarmersFinished", true);
        }
        log.info("[Questing] Fairytale I: {} answered; farmers done: {}", farmer, done);
    }

    private static boolean backToMartin(QuestContext ctx) {
        if (!ctx.walkTo(MARTIN, 5)) {
            Microbot.status = "Going back to Martin";
            return true;
        }
        Rs2NpcModel martin = ctx.npc(NpcID.MARTIN_THE_MASTER_FARMER);
        if (martin == null) {
            ctx.walkTo(MARTIN, 1);
            return true;
        }
        Microbot.status = "Telling Martin what the farmers said";
        if (ctx.talkTo(martin)) converse("Ask about the quest");
        return true;
    }

    /**
     * Runs the open conversation to its end, answering with the first option containing one of {@code answers}.
     * Stops at a menu it has no answer for (the runner takes it from there). Returns everything said, lower case.
     */
    private static String converse(String... answers) {
        StringBuilder said = new StringBuilder();
        long until = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < until && Rs2Dialogue.isInDialogue()) {
            String text = String.valueOf(Rs2Dialogue.getDialogueText());
            said.append(text.toLowerCase(Locale.ROOT)).append('\n');
            if (Rs2Dialogue.hasContinue()) {
                Rs2Dialogue.clickContinue();
            } else {
                String answer = null;
                for (String a : answers) {
                    if (Rs2Dialogue.hasDialogueOption(a, false)) {
                        answer = a;
                        break;
                    }
                }
                if (answer == null) break;
                Rs2Dialogue.keyPressForDialogueOption(answer, false);
            }
            sleepUntil(() -> !Rs2Dialogue.isInDialogue() || !text.equals(String.valueOf(Rs2Dialogue.getDialogueText())),
                    3_000);
        }
        return said.toString();
    }

    // --- Mortifer's items ---

    private static boolean mortiferItems(QuestContext ctx) {
        int first = Microbot.getVarbitValue(MORTIFER_VARBIT);
        if (first < 0 || first + 2 >= MORTIFER_ITEMS.length) {
            log.warn("[Questing] Fairytale I: unexpected item varbit {}; leaving it to Quest Helper", first);
            return false;
        }
        List<ItemNeed> missing = new ArrayList<>();
        for (int i = first; i < first + 3; i++) {
            if (!ctx.has(MORTIFER_ITEMS[i])) missing.add(ItemNeed.of(MORTIFER_ITEMS[i], MORTIFER_NAMES[i], 1));
        }
        if (missing.isEmpty()) return false;
        log.info("[Questing] Fairytale I: Malignius wants {}; fetching {}",
                List.of(MORTIFER_NAMES[first], MORTIFER_NAMES[first + 1], MORTIFER_NAMES[first + 2]), missing);
        ctx.requestItems(missing, false);
        return true;
    }
}
