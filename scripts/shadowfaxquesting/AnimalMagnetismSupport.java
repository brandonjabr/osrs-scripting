package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.ectofuntus.Ectofuntus;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.DialogueAnswer;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Animal Magnetism helpers. Quest Helper drives most of the quest (Ava, Alice and Malcolm, the Old crone, the witch,
 * Turael and the undead trees); these cover the parts its guide only describes, cross-checked with the OSRS Wiki and
 * a public DreamBot implementation:
 * <ul>
 *   <li><b>Ecto-tokens</b>: Malcolm sells the two undead chickens for 20 ecto-tokens, which can't be traded. They
 *       come from worshipping the Ectofuntus 4 times (5 tokens each): bones go in the grinder's loader upstairs
 *       (it grinds them into the carried pots by itself), empty buckets are filled at the pool of slime in the
 *       basement, then each bonemeal + slime pair is offered at the Ectofuntus and the tokens are claimed from a
 *       ghost disciple while wearing the ghostspeak amulet. Unclaimed tokens are read from their varbit.</li>
 *   <li><b>The bar magnet</b>: the hammer has to be used on the selected iron in Rimmington mine while the player
 *       faces north, so it steps one tile north first (orientation 1024 is north).</li>
 *   <li><b>The research notes</b>: a switch puzzle. Per the wiki every switch except the 2nd, 5th and 9th is
 *       turned off; Quest Helper's solver lists the six to click (interface 480). Its step points at the wrong
 *       item id for the garbled notes, so the generic executor can't open them.</li>
 *   <li><b>The container</b>: polished buttons used on the pattern (with hard leather carried) makes it.</li>
 * </ul>
 */
@Slf4j
final class AnimalMagnetismSupport {

    // --- Ectofuntus ---
    static final int TOKENS_NEEDED = 20;
    static final int WORSHIPS = EctoTokenSupport.worshipsFor(TOKENS_NEEDED);

    // --- Magnet ---
    private static final int NORTH = 1024;
    private static final int FACING_TOLERANCE = 64;
    // Open ground near the middle of the mine, with space to step north.
    private static final WorldPoint MINE_SPOT = new WorldPoint(2977, 3237, 0);

    // --- Research notes ---
    private static final int NOTES_GROUP = 480;
    private static final int[] SWITCHES_TO_TURN_OFF = {26, 31, 34, 40, 43, 46};
    private static final int MAX_NOTE_TRIES = 3;

    static final StepOverride ECTO_TOKENS = new StepOverride("earning ecto-tokens at the Ectofuntus",
            ctx -> ctx.stepText().toLowerCase(Locale.ROOT).contains("buy two undead chickens")
                    && Ectofuntus.tokens() < TOKENS_NEEDED,
            AnimalMagnetismSupport::earnTokens);

    static final StepOverride BAR_MAGNET = StepOverride.onStepText("making the bar magnet (facing north)",
            "use the hammer on the Selected iron", AnimalMagnetismSupport::makeMagnet);

    static final StepOverride TRANSLATE_NOTES = new StepOverride("translating the research notes",
            ctx -> ctx.has(ItemID.ANMA_GARB_NOTES),
            AnimalMagnetismSupport::translateNotes);

    static final StepOverride BUILD_CONTAINER = new StepOverride("making the container",
            ctx -> ctx.has(ItemID.ANMA_PATTERN) && ctx.has(ItemID.HARD_LEATHER) && ctx.has(ItemID.ANMA_P_BUTTONS),
            AnimalMagnetismSupport::buildContainer);

    /** The ghost disciple's info menu: leave it, the tokens are handed over on their own when there are some. */
    static final DialogueAnswer DISCIPLE_MENU = (ctx, options) -> {
        boolean discipleMenu = options.stream().anyMatch(o -> o.toLowerCase(Locale.ROOT).contains("ectotokens"));
        if (!discipleMenu) return null;
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith("thanks for your time"))
                .findFirst().orElse(null);
    };

    private AnimalMagnetismSupport() {
    }

    // --- Ecto-tokens ---

    private static boolean earnTokens(QuestContext ctx) {
        return EctoTokenSupport.earn(ctx, TOKENS_NEEDED, "Animal Magnetism");
    }

    // --- Bar magnet ---

    private static boolean makeMagnet(QuestContext ctx) {
        if (!ctx.has(ItemID.ANMA_IRON_BAR)) return false;
        if (ctx.continueDialogue()) return true;

        int orientation = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player me = Microbot.getClient().getLocalPlayer();
            return me == null ? -1 : me.getOrientation();
        }).orElse(-1);
        if (orientation >= 0 && Math.abs(orientation - NORTH) <= FACING_TOLERANCE) {
            Microbot.status = "Facing north: hammering the selected iron";
            if (Rs2Inventory.combine("Hammer", "Selected iron")) {
                sleepUntil(() -> ctx.has(ItemID.ANMA_MAGNET), 3_000);
            }
            return true;
        }

        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return true;
        int tries = ctx.get("magnetSteps", 0);
        ctx.set("magnetSteps", tries + 1);
        if (tries >= 4) {
            // Rocks in the way: start again from open ground in the middle of the mine.
            ctx.set("magnetSteps", 0);
            Microbot.status = "Moving to open ground in the mine";
            Rs2Walker.walkTo(MINE_SPOT, 0);
            return true;
        }
        // Stepping north turns the player north. Odd tries step south first, to make room if north is blocked.
        boolean south = tries % 2 == 1;
        WorldPoint step = new WorldPoint(here.getX(), here.getY() + (south ? -1 : 1), here.getPlane());
        Microbot.status = south ? "Stepping back to turn north" : "Stepping north to face north";
        Rs2Walker.walkFastCanvas(step);
        sleepUntil(() -> !here.equals(Rs2Player.getWorldLocation()), 2_400);
        sleepUntil(() -> !Rs2Player.isMoving(), 1_800);
        return true;
    }

    // --- Research notes ---

    private static boolean translateNotes(QuestContext ctx) {
        if (ctx.continueDialogue()) return true;
        Set<Integer> clicked = ctx.get("notesClicked", new HashSet<Integer>());
        ctx.set("notesClicked", clicked);

        if (!Rs2Widget.isWidgetVisible(NOTES_GROUP, 0)) {
            int tries = ctx.get("notesTries", 0);
            if (tries >= MAX_NOTE_TRIES) {
                log.warn("[Questing] Animal Magnetism: the notes still aren't translated after {} tries", tries);
                return false;
            }
            Microbot.status = "Opening the research notes";
            clicked.clear();
            if (Rs2Inventory.interact(ItemID.ANMA_GARB_NOTES, "Translate")) {
                sleepUntil(() -> Rs2Widget.isWidgetVisible(NOTES_GROUP, 0), 3_000);
            }
            return true;
        }

        for (int child : SWITCHES_TO_TURN_OFF) {
            if (clicked.contains(child)) continue;
            Microbot.status = "Turning off switch " + (clicked.size() + 1) + " of " + SWITCHES_TO_TURN_OFF.length;
            if (Rs2Widget.clickWidget(NOTES_GROUP, child)) {
                clicked.add(child);
                sleep(500, 1_100);
            }
            return true;
        }

        // All six turned off: the notes should now read as translated.
        if (sleepUntil(() -> !ctx.has(ItemID.ANMA_GARB_NOTES) || ctx.has(ItemID.ANMA_TRANS_NOTES), 3_000)) {
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            return true;
        }
        int tries = ctx.get("notesTries", 0) + 1;
        ctx.set("notesTries", tries);
        log.info("[Questing] Animal Magnetism: switches clicked but the notes aren't translated yet; reopening (try {})",
                tries);
        Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
        sleepUntil(() -> !Rs2Widget.isWidgetVisible(NOTES_GROUP, 0), 1_800);
        return true;
    }

    // --- Container ---

    private static boolean buildContainer(QuestContext ctx) {
        Microbot.status = "Making the container";
        if (Rs2Inventory.combine(ItemID.ANMA_P_BUTTONS, ItemID.ANMA_PATTERN)) {
            sleepUntil(() -> ctx.has(ItemID.ANMA_CONTAINER), 3_000);
        }
        return true;
    }
}
