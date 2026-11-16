package net.runelite.client.plugins.microbot.util.questing;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.QuestScript;
import net.runelite.client.plugins.microbot.questhelper.logic.IQuest;
import net.runelite.client.plugins.microbot.questhelper.logic.PiratesTreasure;
import net.runelite.client.plugins.microbot.questhelper.logic.QuestRegistry;
import net.runelite.client.plugins.microbot.questhelper.requirements.Requirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.StepNpcResolver;
import net.runelite.client.plugins.microbot.questhelper.steps.NpcStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.choice.DialogChoiceStep;
import net.runelite.client.plugins.microbot.questhelper.steps.tools.DefinedPoint;
import net.runelite.client.plugins.microbot.questhelper.steps.widget.WidgetHighlight;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Warnings;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Plays one quest, one short action per {@link #tick()}, on top of Microbot's Quest Helper data. Each tick, in order:
 * <ol>
 *   <li>finished? → {@link Result#COMPLETED}; helper not selected yet → select it</li>
 *   <li>stall watchdog: a stalled step short of kit items it lists → {@link Result#NEEDS_ITEMS}; otherwise up to
 *       {@value #MAX_RECOVERY_ATTEMPTS} recovery tries (open a blocking door/gate, step aside,
 *       reload the guide), then {@link Result#BLOCKED}</li>
 *   <li>eat if health is low</li>
 *   <li>dialogue, cutscenes included: the step's expected answer or a quest option, otherwise space to continue or
 *       the number key of the first sensible option; {@value #MAX_DIALOGUE_FAILURES} presses that don't move a
 *       dialogue on → {@link Result#BLOCKED}</li>
 *   <li>wait out cutscenes</li>
 *   <li>pick up items the step needs from the ground nearby (drops like a rat's tail), waiting briefly for drops after a kill</li>
 *   <li>the quest's {@link StepOverride}s, then Microbot's existing per-quest custom logic</li>
 *   <li>widgets the step highlights, then Quest Helper's generic executor for the active step</li>
 * </ol>
 */
@Slf4j
public class QuestRunner {

    public enum Result { RUNNING, COMPLETED, NEEDS_ITEMS, BLOCKED }

    private static final int CUTSCENE_VARBIT = 4606;
    private static final long SELECT_RETRY_MS = 5_000;
    private static final String CLICK_TO_CONTINUE = "Click here to continue";
    // A new option menu gets this long for an expected answer to be recognised before the fallback is pressed.
    private static final long OPTION_SETTLE_MS = 1_500;
    // A press that hasn't changed the dialogue within this long didn't work.
    private static final int DIALOGUE_REACT_MS = 3_000;
    // Presses that leave the same dialogue on screen (keyboard, then mouse, then walking away) before giving up.
    private static final int MAX_DIALOGUE_FAILURES = 3;
    // Refusals skipped when picking an option for an unrecognised menu.
    private static final Pattern REFUSAL = Pattern.compile(
            "(?i)\\b(no|nope|never|nothing|goodbye|bye|leave|busy|not now|not interested|maybe later|i'?ll pass|cancel)\\b");

    // Standing still (no movement, animation, items, xp, dialogue or step change) this long: one recovery try.
    private static final long STILL_ATTEMPT_MS = 90_000;
    // Same step with no item or xp change, even while walking or talking (going in circles): one recovery try.
    private static final long STEP_ATTEMPT_MS = 10 * 60_000;
    // After the last skilling animation, wait this long (randomised up to double) before acting again: production
    // like spinning pauses between items, and a person doesn't re-click the instant their character goes idle.
    private static final int BUSY_GRACE_MS = 2_400;
    // The same line of conversation coming round this many times on one step (no item or xp change) is a loop, e.g.
    // an NPC asking again for items we don't have. High enough for repeated rounds like rock-paper-scissors.
    private static final int MAX_DIALOGUE_REPEATS = 6;
    // Recovery tries without real progress before the quest is reported blocked.
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    // Getting this far from where the recovery tries started counts as unstuck.
    private static final int UNSTUCK_DISTANCE = 12;
    private static final int DOOR_SEARCH_RADIUS = 5;

    private final QuestDefinition definition;
    private final QuestScript executor;
    private final QuestContext ctx;
    private final List<ItemNeed> kit;
    private final int eatAtPercent;

    @Getter
    private String stepText = "";
    @Getter
    private String blockReason;
    /**
     * Kit items the stalled step lists and the player is short of (set when {@link Result#NEEDS_ITEMS} is returned).
     * Steps also list what they produce (shearing lists the wool), so a listed item only counts as needed once the
     * step has stopped getting anywhere without it.
     */
    @Getter
    private List<ItemNeed> missingItems = List.of();

    private long selectRequestedAt;
    // Dialogue guard.
    private String dialogueSnapshot = "";
    private long dialogueSince;
    // A line showing "Please wait.." instead of "Click here to continue" is handing over to a scripted move (a ship
    // setting sail, a fade to a cutscene): no press does anything, so it is waited out for up to this long.
    private static final long PLEASE_WAIT_MAX_MS = 45_000;
    private long pleaseWaitSince;
    // The conversation on screen: the step it started under and the NPC last heard in it. When Quest Helper moves
    // on to another NPC or task mid-conversation, the rest of it is left behind instead of answered in circles.
    private boolean inConversation;
    private String conversationStep = "";
    private String speaker = "";
    // Presses that left a dialogue unchanged, per dialogue; kept if the same dialogue comes back after walking away.
    private final Map<String, Integer> dialogueFailures = new HashMap<>();
    // Answers given to each option menu since the last real progress (step, inventory or xp change). The same menu
    // coming back and getting the same answer again means the answer has done all it can: Quest Helper keeps
    // suggesting it, but the step's next move is its own action (Nature Spirit: answer Filliman once, then use the
    // mirror on him), or for a talk-only step, a different option.
    private final Map<String, Set<String>> answeredMenus = new HashMap<>();
    private String answerProgress = "";

    // Stall watchdog.
    private String progressSignature = "";
    private long lastProgressAt = System.currentTimeMillis();
    private String stepSignature = "";
    private long stepProgressAt = System.currentTimeMillis();
    private final List<String> recoveries = new ArrayList<>();
    private WorldPoint recoveryAnchor;
    // Times each line of conversation has come up on the current step, and the line last seen.
    private final Map<String, Integer> dialogueVisits = new HashMap<>();
    private String lastDialogueLine = "";

    // Kit items carried at some point on the current step: if one is gone, the step used it, so it isn't fetched.
    private String itemStep = "";
    private final Set<Integer> heldOnStep = new HashSet<>();

    // Shop buying: failed "Buy 1" tries on the current step before giving up (out of stock, not enough coins).
    private static final int MAX_SHOP_BUY_FAILURES = 3;
    private String shopBuyStep = "";
    private int shopBuyFailures;

    // Quest-item looting.
    private static final int LOOT_RADIUS = 12;
    private static final int MAX_PICKUP_ATTEMPTS = 3;
    // After a fight on a "kill" step, wait this long for the drop to appear before attacking again.
    private static final long DROP_WAIT_MS = 2_400;
    private final Map<String, Integer> pickupFailures = new HashMap<>();
    // Items that failed to pick up MAX_PICKUP_ATTEMPTS times; never retried during this quest.
    private final Set<String> skippedLoot = new HashSet<>();
    private long lastCombatAt;
    // Skilling detection.
    private long lastAnimatingAt;
    private long busyGraceMs = BUSY_GRACE_MS;

    /**
     * @param kit          the items prepared for this quest; a step needing one that's gone triggers a re-kit
     * @param eatAtPercent eat any carried food at or below this health
     */
    public QuestRunner(QuestDefinition definition, QuestScript executor, List<ItemNeed> kit, int eatAtPercent) {
        this(definition, executor, kit, null, eatAtPercent);
    }

    /** @param food the kit's food item (for overrides that restock it), or null */
    public QuestRunner(QuestDefinition definition, QuestScript executor, List<ItemNeed> kit, ItemNeed food,
                       int eatAtPercent) {
        this.definition = definition;
        this.executor = executor;
        this.ctx = new QuestContext(definition, executor);
        this.ctx.setKit(kit, food);
        this.kit = kit;
        this.eatAtPercent = eatAtPercent;
    }

    /**
     * True when the last {@link Result#NEEDS_ITEMS} came from an override asking for room: the bank trip should bank
     * everything else and take out only {@link #getMissingItems()}.
     */
    @Getter
    private boolean makeRoom;

    public QuestDefinition getDefinition() {
        return definition;
    }

    public Result tick() {
        if (QuestHelperBridge.isFinished(definition.getQuest())) {
            return Result.COMPLETED;
        }

        if (!QuestHelperBridge.isSelected(definition.getQuest())) {
            long now = System.currentTimeMillis();
            if (now - selectRequestedAt > SELECT_RETRY_MS) {
                selectRequestedAt = now;
                QuestHelperBridge.select(definition.getQuest());
            }
            Microbot.status = "Loading the quest guide for " + definition.getLabel();
            return Result.RUNNING;
        }

        QuestStep step = QuestHelperBridge.activeStep();
        ctx.setActiveStep(step);
        stepText = ctx.stepText();

        if (stalled(step)) {
            return Result.BLOCKED;
        }
        if (!missingItems.isEmpty()) {
            return Result.NEEDS_ITEMS;
        }
        if (eatIfNeeded()) {
            return Result.RUNNING;
        }
        // A step's gate or entrance opened a warning screen ("Mort Myre is dangerous, are you sure?"): accept it,
        // or the step sits behind it forever (the walker does this on its own routes, not on steps' clicks).
        if (Rs2Warnings.accept()) {
            return Result.RUNNING;
        }
        // Before the cutscene wait: many cutscenes are dialogue that only moves on when continued.
        if (handleDialogue(step)) {
            return blockReason != null ? Result.BLOCKED : Result.RUNNING;
        }
        if (Microbot.getVarbitValue(CUTSCENE_VARBIT) > 0) {
            Microbot.status = "Watching a cutscene";
            return Result.RUNNING;
        }

        if (missingBeforeActing(step)) {
            return Result.NEEDS_ITEMS;
        }
        if (lootStepItems(step)) {
            return Result.RUNNING;
        }

        if (stillBusy()) {
            return Result.RUNNING;
        }

        Result shopping = buyHighlightedShopItems(step);
        if (shopping != null) {
            return shopping;
        }

        for (StepOverride override : definition.getOverrides()) {
            if (override.appliesTo(ctx)) {
                Microbot.status = definition.getLabel() + ": " + override.name();
                boolean acted = override.run(ctx);
                List<ItemNeed> requested = ctx.takeRequestedItems();
                if (requested != null) {
                    missingItems = requested;
                    makeRoom = ctx.requestMakesRoom();
                    log.info("[Questing] {}: {} asks for {}{}", definition.getLabel(), override.name(), requested,
                            makeRoom ? " (banking everything else first)" : "");
                    return Result.NEEDS_ITEMS;
                }
                if (acted) return Result.RUNNING;
                break;
            }
        }

        if (usePassages(step)) {
            return Result.RUNNING;
        }
        if (leaveZanaris(step)) {
            return Result.RUNNING;
        }
        if (runExistingCustomLogic()) {
            return Result.RUNNING;
        }
        if (clickHighlightedWidget(step)) {
            return Result.RUNNING;
        }
        if (Rs2Widget.isProductionWidgetOpen()) {
            // A make-X menu the guide didn't highlight an option in: space makes the default (first) item.
            Microbot.status = "Choosing what to make";
            Global.sleep(300, 900);
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            sleepUntil(Rs2Player::isAnimating, 3000);
            return Result.RUNNING;
        }
        if (step == null) {
            Microbot.status = "Waiting for the next quest step";
            return Result.RUNNING;
        }

        Microbot.status = shortText(stepText);
        executor.applyStep(step);
        // Let the click start, then let short actions finish before the next decision (fights are re-checked each
        // tick, and longer skilling is waited out by stillBusy()).
        sleepUntil(() -> Rs2Player.isMoving() || Rs2Player.isInteracting() || Rs2Player.isAnimating()
                || Rs2Dialogue.isInDialogue() || Rs2Widget.isProductionWidgetOpen(), 1200);
        sleepUntil(() -> !Rs2Player.isMoving(), 3000);
        return Result.RUNNING;
    }

    /**
     * True while the player is skilling (spinning, cooking, smithing...) or has only just stopped: the step isn't
     * re-clicked mid-batch. The grace after the last animation is randomised each time so the pause before the next
     * action varies.
     */
    private boolean stillBusy() {
        long now = System.currentTimeMillis();
        if (Rs2Player.isAnimating()) {
            if (now - lastAnimatingAt > busyGraceMs) {
                busyGraceMs = BUSY_GRACE_MS + ThreadLocalRandom.current().nextInt(BUSY_GRACE_MS);
            }
            lastAnimatingAt = now;
        }
        if (now - lastAnimatingAt >= busyGraceMs || Rs2Combat.inCombat()) return false;
        Microbot.status = "Busy: " + shortText(stepText);
        return true;
    }

    // --- Dialogue ---

    /**
     * True if this tick went to dialogue. Each press is checked: if the dialogue hasn't changed within
     * {@value #DIALOGUE_REACT_MS} ms it counts as a failed try for that dialogue.
     * <ul>
     *   <li>Option menus: Quest Helper's expected answer or a known quest option; otherwise, after a short beat, the
     *       number key of the first option that isn't a refusal (usually "1"). A menu that comes back from the same
     *       NPC and would get the same answer again with nothing gained (step, quest progress, items, xp) is a
     *       loop: a step with its own action (item on the NPC, an object, a fight) leaves the conversation so the
     *       action runs; a talk-only step tries an option it hasn't picked yet.</li>
     *   <li>"Click here to continue" (NPC, player, item, level-up, message boxes): space straight away.</li>
     *   <li>Failed tries escalate: keyboard, then a mouse click, then walking away. A dialogue still stuck after
     *       {@value #MAX_DIALOGUE_FAILURES} tries sets {@link #blockReason}.</li>
     * </ul>
     */
    private boolean handleDialogue(QuestStep step) {
        boolean options = Rs2Dialogue.hasSelectAnOption();
        boolean canContinue = !options && hasAnyContinue();
        if (!options && !canContinue) {
            dialogueSnapshot = "";
            pleaseWaitSince = 0;
            inConversation = false;
            return false;
        }
        if (!inConversation) {
            inConversation = true;
            conversationStep = stepText;
            speaker = "";
        }
        String name = npcChatName();
        if (!name.isEmpty()) speaker = name;

        long now = System.currentTimeMillis();
        if (canContinue && showsPleaseWait()) {
            if (pleaseWaitSince == 0) {
                pleaseWaitSince = now;
                log.info("[Questing] {}: dialogue says \"Please wait\", waiting for the game to move on", definition.getLabel());
            }
            if (now - pleaseWaitSince < PLEASE_WAIT_MAX_MS) {
                Microbot.status = "Waiting (sailing, cutscene...)";
                dialogueSince = now;
                return true;
            }
            // Waited long enough: something else is wrong, so treat it as an ordinary stuck dialogue below.
        } else {
            pleaseWaitSince = 0;
        }
        String snapshot = dialogueSnapshot(options);
        if (!snapshot.equals(dialogueSnapshot)) {
            dialogueSnapshot = snapshot;
            dialogueSince = now;
        }
        int failures = dialogueFailures.getOrDefault(snapshot, 0);
        if (failures >= MAX_DIALOGUE_FAILURES) {
            blockReason = "Dialogue didn't move on after " + failures + " tries (keyboard, mouse, walking away): \""
                    + shortText(Rs2UiHelper.stripColTags(snapshot)) + "\"";
            log.warn("[Questing] {} blocked: {}", definition.getLabel(), blockReason);
            return true;
        }

        String how;
        if (options) {
            how = answerOptions(step, failures, now - dialogueSince);
            if (how == null) return true;
        } else if (failures == 0) {
            how = "space";
            Microbot.status = "Continuing dialogue";
            Global.sleep(250, 900);
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        } else if (failures == 1) {
            how = "clicking continue";
            Microbot.status = "Clicking continue";
            if (!Rs2Widget.clickWidget(CLICK_TO_CONTINUE)) Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        } else {
            how = "walking away";
            leaveDialogue();
        }

        if (sleepUntil(() -> !currentDialogue().equals(snapshot), DIALOGUE_REACT_MS)) {
            dialogueFailures.remove(snapshot);
        } else {
            int count = dialogueFailures.merge(snapshot, 1, Integer::sum);
            log.info("[Questing] {}: dialogue didn't move on after {} (try {}/{}): \"{}\"", definition.getLabel(), how,
                    count, MAX_DIALOGUE_FAILURES, shortText(Rs2UiHelper.stripColTags(snapshot)));
        }
        return true;
    }

    /**
     * Presses an answer for the option menu on screen and says how, or returns null when it is still giving the menu
     * a moment. Expected answers are only tried before any failure; after that the fallback option is pressed by
     * key, then clicked, then the conversation is walked away from.
     */
    private String answerOptions(QuestStep step, int failures, long shownMs) {
        String menu = optionMenuKey();
        Set<String> answered = answeredFor(menu);
        if (failures == 0) {
            String quest = questAnswerText();
            String guide = quest == null ? stepOptionText(step) : null;
            String planned = quest != null ? quest : guide;
            if (planned != null && answered.contains(planned)) {
                String handled = answerLoop(step, menu, planned, answered);
                if (handled != null) return handled;
            } else if (quest != null) {
                answered.add(quest);
                if (pressOption(quest)) return "the quest's answer";
            } else if (guide != null) {
                answered.add(guide);
                if (pressOption(guide)) return "the guide's answer";
            }
            if (Rs2Dialogue.handleQuestOptionDialogueSelection()) return "a quest option";
            if (Rs2Dialogue.acceptQuestStartDialogue()) return "accepting the quest";
            if (shownMs < OPTION_SETTLE_MS) {
                Microbot.status = "Reading dialogue options";
                return null;
            }
        }
        if (failures >= 2) {
            leaveDialogue();
            return "walking away";
        }
        if (conversationLeftBehind(step)) {
            return endConversation(failures);
        }
        int index = fallbackOptionIndex();
        String text = optionText(index);
        if (failures == 0 && answered.contains(text)) {
            String handled = answerLoop(step, menu, text, answered);
            if (handled != null) return handled;
        }
        if (!text.isEmpty()) answered.add(text);
        if (failures == 0) {
            Microbot.status = "Pressing " + index + ": \"" + text + "\"";
            log.info("[Questing] {}: no expected answer on screen, pressing {} (\"{}\")", definition.getLabel(), index, text);
            Global.sleep(300, 1000);
            if (!Rs2Dialogue.keyPressForDialogueOption(index)) Rs2Keyboard.keyPress((char) ('0' + index));
            return "pressing " + index;
        }
        Microbot.status = "Clicking \"" + text + "\"";
        if (text.isEmpty() || !Rs2Dialogue.clickOption(text, true)) Rs2Dialogue.keyPressForDialogueOption(index);
        return "clicking \"" + text + "\"";
    }

    // --- Repeated option menus ---

    /**
     * The options on screen and who they're from, as one key (the question above them can vary between visits).
     * A different NPC with the same menu is a new menu: the Ghosts Ahoy villagers each sign the petition.
     */
    private static String optionMenuKey() {
        int partner = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            net.runelite.api.Player me = Microbot.getClient().getLocalPlayer();
            net.runelite.api.Actor target = me == null ? null : me.getInteracting();
            return target instanceof net.runelite.api.NPC ? ((net.runelite.api.NPC) target).getIndex() : -1;
        }).orElse(-1);
        StringBuilder sb = new StringBuilder("npc").append(partner);
        for (Widget option : Rs2Dialogue.getDialogueOptions()) {
            sb.append('|').append(Rs2UiHelper.stripColTags(option.getText()));
        }
        return sb.toString();
    }

    /** Answers already given to {@code menu} since the last progress; cleared when step, quest progress, items or xp change. */
    private Set<String> answeredFor(String menu) {
        int questVar = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> definition.getQuest().getVar(Microbot.getClient())).orElse(-1);
        String progress = stepText + "|" + questVar + "|" + Rs2Inventory.items().map(i -> i.getId() + "x" + i.getQuantity())
                .collect(Collectors.joining(",")) + "|" + totalXp();
        if (!progress.equals(answerProgress)) {
            answerProgress = progress;
            answeredMenus.clear();
        }
        return answeredMenus.computeIfAbsent(menu, k -> new HashSet<>());
    }

    /**
     * The menu came back and would get {@code repeat} again with nothing gained. A step with its own action leaves
     * the conversation so that action runs next; a talk-only step tries an option not picked yet. Null when
     * neither applies (every option tried): the usual handling, and the stall watchdog, take it from there.
     */
    private String answerLoop(QuestStep step, String menu, String repeat, Set<String> answered) {
        if (stepHasOwnAction(step)) {
            log.info("[Questing] {}: \"{}\" came round again with nothing gained; leaving the conversation for the "
                    + "step's own action (\"{}\")", definition.getLabel(), repeat, shortText(stepText));
            return endConversationForAction();
        }
        List<Widget> options = Rs2Dialogue.getDialogueOptions();
        for (int i = 0; i < options.size(); i++) {
            String text = Rs2UiHelper.stripColTags(options.get(i).getText());
            if (text == null || text.isBlank() || answered.contains(text) || REFUSAL.matcher(text).find()) continue;
            answered.add(text);
            log.info("[Questing] {}: \"{}\" came round again with nothing gained; trying \"{}\" instead",
                    definition.getLabel(), repeat, text);
            Microbot.status = "Trying \"" + text + "\"";
            Global.sleep(300, 900);
            if (!Rs2Dialogue.keyPressForDialogueOption(i + 1)) Rs2Keyboard.keyPress((char) ('1' + i));
            return "trying another option";
        }
        log.info("[Questing] {}: every option of a repeating menu tried with nothing gained; ending the conversation",
                definition.getLabel());
        return endConversationForAction();
    }

    /**
     * True when the step is more than talking: an item to use on the NPC, an object to click, a fight, or items
     * to use in the inventory. Leaving the conversation is then what lets the step go on.
     */
    private static boolean stepHasOwnAction(QuestStep step) {
        if (step == null) return false;
        String text = step.getText() == null ? "" : String.join(" ", step.getText()).toLowerCase();
        if (text.contains("kill") || text.startsWith("use ") || text.contains(" use the ")) return true;
        if (step instanceof DetailedQuestStep && ((DetailedQuestStep) step).getIconItemID() != -1) return true;
        if (step instanceof net.runelite.client.plugins.microbot.questhelper.steps.ObjectStep) return true;
        if (step instanceof DetailedQuestStep && !(step instanceof NpcStep)) {
            for (Requirement req : ((DetailedQuestStep) step).getRequirements()) {
                if (req instanceof ItemRequirement
                        && ((ItemRequirement) req).shouldHighlightInInventory(Microbot.getClient())) return true;
            }
        }
        return false;
    }

    /** Leaves the conversation: a goodbye-style option if the menu has one, otherwise by walking away. */
    private String endConversationForAction() {
        List<Widget> options = Rs2Dialogue.getDialogueOptions();
        for (int i = 0; i < options.size(); i++) {
            String text = Rs2UiHelper.stripColTags(options.get(i).getText());
            if (text != null && REFUSAL.matcher(text).find()) {
                Microbot.status = "Ending the conversation: \"" + text + "\"";
                Global.sleep(300, 900);
                if (!Rs2Dialogue.keyPressForDialogueOption(i + 1)) Rs2Keyboard.keyPress((char) ('1' + i));
                return "ending the conversation (\"" + text + "\")";
            }
        }
        leaveDialogue();
        return "walking away to do the step";
    }

    private static boolean pressOption(String text) {
        Microbot.status = "Choosing \"" + text + "\"";
        Global.sleep(250, 800);
        return Rs2Dialogue.keyPressForDialogueOption(text, false);
    }

    /**
     * True when Quest Helper has moved on since this conversation started and the step is no longer about the NPC
     * we're talking to (Demon Slayer: Aris accepts us, the step becomes "Talk to Sir Prysin", and Aris's remaining
     * questions would otherwise be answered with option 1 forever). Unknown speakers count as the step's NPC.
     */
    private boolean conversationLeftBehind(QuestStep step) {
        if (stepText.equals(conversationStep) || speaker.isEmpty()) return false;
        if (!(step instanceof NpcStep)) return true;
        NpcStep npcStep = (NpcStep) step;
        // By name, not Quest Helper's tracked list: that's empty when the port's id is wrong, and talking to the
        // right NPC must not count as the guide having moved on.
        return !StepNpcResolver.names(npcStep).contains(speaker.toLowerCase(java.util.Locale.ROOT))
                && !Microbot.getClientThread().runOnClientThreadOptional(() -> npcStep.getNpcs().stream()
                .anyMatch(npc -> npc != null && speaker.equalsIgnoreCase(Rs2UiHelper.stripColTags(npc.getName()))))
                .orElse(false);
    }

    /** Ends a left-behind conversation: a goodbye-style option if there is one, otherwise by walking away. */
    private String endConversation(int failures) {
        log.info("[Questing] {}: the guide moved on to \"{}\"; ending the conversation with {}",
                definition.getLabel(), shortText(stepText), speaker);
        List<Widget> options = Rs2Dialogue.getDialogueOptions();
        for (int i = 0; i < options.size() && failures == 0; i++) {
            String text = Rs2UiHelper.stripColTags(options.get(i).getText());
            if (text != null && REFUSAL.matcher(text).find()) {
                Microbot.status = "Ending the conversation: \"" + text + "\"";
                Global.sleep(300, 900);
                if (!Rs2Dialogue.keyPressForDialogueOption(i + 1)) Rs2Keyboard.keyPress((char) ('1' + i));
                return "saying \"" + text + "\"";
            }
        }
        leaveDialogue();
        return "walking away (the guide moved on)";
    }

    /** The name over the NPC's chat head, or empty when the NPC isn't speaking. */
    private static String npcChatName() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget name = Microbot.getClient().getWidget(InterfaceID.ChatLeft.NAME);
            if (name == null || name.isHidden() || name.getText() == null) return "";
            return Rs2UiHelper.stripColTags(name.getText()).trim();
        }).orElse("");
    }

    /** 1-based index of the first option that isn't a refusal ("No", "Goodbye", ...), or 1. */
    private static int fallbackOptionIndex() {
        List<Widget> options = Rs2Dialogue.getDialogueOptions();
        for (int i = 0; i < options.size(); i++) {
            String text = Rs2UiHelper.stripColTags(options.get(i).getText());
            if (text != null && !text.isBlank() && !REFUSAL.matcher(text).find()) return i + 1;
        }
        return 1;
    }

    private static String optionText(int index) {
        List<Widget> options = Rs2Dialogue.getDialogueOptions();
        if (index < 1 || index > options.size()) return "";
        String text = Rs2UiHelper.stripColTags(options.get(index - 1).getText());
        return text == null ? "" : text;
    }

    /**
     * True when the continue slot of the NPC or player chat box says "Please wait..": the game is about to move the
     * player itself (travel, cutscene) and ignores keys and clicks until then. {@link Rs2Dialogue#hasContinue()} only
     * checks that the slot is visible, so without this the wait looked like a stuck dialogue.
     */
    private static boolean showsPleaseWait() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            for (int group : new int[]{231, 217}) { // NPC chat, player chat
                Widget slot = Microbot.getClient().getWidget(group, 5);
                if (slot == null || slot.isHidden()) continue;
                String text = Rs2UiHelper.stripColTags(slot.getText());
                if (text != null && text.toLowerCase().startsWith("please wait")) return true;
            }
            return false;
        }).orElse(false);
    }

    /** Any "Click here to continue" in the chatbox, including boxes {@link Rs2Dialogue#hasContinue()} doesn't know (level-ups). */
    private static boolean hasAnyContinue() {
        return Rs2Dialogue.hasContinue() || Rs2Widget.hasWidgetText(CLICK_TO_CONTINUE, InterfaceID.Chatbox.UNIVERSE, false);
    }

    /** The dialogue on screen now, as compared between presses; empty when there is none. */
    private static String currentDialogue() {
        boolean options = Rs2Dialogue.hasSelectAnOption();
        if (!options && !hasAnyContinue()) return "";
        return dialogueSnapshot(options);
    }

    private static String dialogueSnapshot(boolean options) {
        String text = Rs2Dialogue.getDialogueText();
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        if (options) {
            for (Widget option : Rs2Dialogue.getDialogueOptions()) sb.append('|').append(option.getText());
        } else {
            sb.append("|continue");
        }
        return sb.toString();
    }

    /** Closes a conversation by taking a step, which ends any dialogue. */
    private void leaveDialogue() {
        log.info("[Questing] {}: walking away from a stuck dialogue", definition.getLabel());
        Microbot.status = "Leaving a stuck dialogue";
        Rs2Walker.walkFastCanvas(Rs2Player.getWorldLocation());
    }

    /** The answer a quest definition's {@link DialogueAnswer} gives for the menu on screen, or null. */
    private String questAnswerText() {
        if (definition.getAnswers().isEmpty()) return null;
        List<String> options = Rs2Dialogue.getDialogueOptions().stream()
                .map(w -> Rs2UiHelper.stripColTags(w.getText()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        for (DialogueAnswer answer : definition.getAnswers()) {
            String text = answer.choose(ctx, options);
            if (text != null && Rs2Dialogue.hasDialogueOption(text, false)) return text;
        }
        return null;
    }

    /** The option Quest Helper expects for the active step, as shown on screen, or null. */
    private static String stepOptionText(QuestStep step) {
        if (step == null || step.getChoices() == null) return null;
        for (DialogChoiceStep choice : step.getChoices().getChoices()) {
            String text = choice.getChoice();
            if (text == null || text.isEmpty()) continue;
            if (choice.getExcludedStrings() != null && choice.getExcludedStrings().stream().anyMatch(Rs2Widget::hasWidget)) {
                continue;
            }
            if (choice.getExpectedPreviousLine() != null && !Rs2Widget.hasWidget(choice.getExpectedPreviousLine())) {
                continue;
            }
            if (Rs2Dialogue.hasDialogueOption(text, false)) {
                // The option's own text on screen, so it matches the menu key and the fallback's text.
                for (Widget option : Rs2Dialogue.getDialogueOptions()) {
                    String shown = Rs2UiHelper.stripColTags(option.getText());
                    if (shown != null && shown.toLowerCase().contains(text.toLowerCase())) return shown;
                }
                return text;
            }
        }
        return null;
    }

    // --- Items & food ---

    /**
     * Picks up items the active step asks for that are lying nearby: a rat's tail after killing the rat, an item a
     * step drops or spawns, a kit item dropped by mistake. Only items the step still needs, reachable, within
     * {@value #LOOT_RADIUS} tiles and ours to take; an item that fails to pick up twice is skipped for a while.
     * Right after a fight on a "kill" step it also waits briefly for the drop rather than attacking the next target.
     *
     * @return true if this tick went to looting (or waiting for a drop)
     */
    private boolean lootStepItems(QuestStep step) {
        long now = System.currentTimeMillis();
        if (Rs2Combat.inCombat()) lastCombatAt = now;

        Set<Integer> wanted = stillNeededStepItems(step);
        if (wanted.isEmpty()) return false;

        Rs2TileItemModel item = Microbot.getRs2TileItemCache().query()
                .where(Rs2TileItemModel::isLootAble)
                .where(i -> wanted.contains(i.getId()) && !i.isDespawned() && !skippedLoot.contains(lootKey(i)))
                .nearestReachableOnClientThread(LOOT_RADIUS);

        if (item == null) {
            boolean killStep = stepText.toLowerCase().contains("kill");
            if (killStep && now - lastCombatAt < DROP_WAIT_MS && !Rs2Combat.inCombat()) {
                Microbot.status = "Waiting for the drop";
                return true;
            }
            return false;
        }

        if (Rs2Inventory.isFull() && !(item.isStackable() && Rs2Inventory.hasItem(item.getId()))) {
            if (Rs2Inventory.getInventoryFood().isEmpty()) return false;
            Microbot.status = "Eating to make room for " + item.getName();
            int food = Rs2Inventory.getInventoryFood().size();
            if (Rs2Player.useFood()) sleepUntil(() -> Rs2Inventory.getInventoryFood().size() < food, 1800);
            return true;
        }

        String key = lootKey(item);
        int id = item.getId();
        int before = Rs2Inventory.itemQuantity(id);
        Microbot.status = "Picking up " + item.getName();
        if (item.pickup() && sleepUntil(() -> Rs2Inventory.itemQuantity(id) > before, 6000)) {
            pickupFailures.remove(key);
            log.info("[Questing] {}: picked up {}", definition.getLabel(), item.getName());
        } else if (pickupFailures.merge(key, 1, Integer::sum) >= MAX_PICKUP_ATTEMPTS) {
            pickupFailures.remove(key);
            skippedLoot.add(key);
            log.warn("[Questing] {}: couldn't pick up {} at {} after {} tries; leaving it",
                    definition.getLabel(), item.getName(), item.getWorldLocation(), MAX_PICKUP_ATTEMPTS);
        }
        return true;
    }

    /** Item ids (all accepted variants) the active step requires and the player doesn't carry enough of. */
    private static Set<Integer> stillNeededStepItems(QuestStep step) {
        Set<Integer> wanted = new HashSet<>();
        if (!(step instanceof DetailedQuestStep)) return wanted;
        for (Requirement req : ((DetailedQuestStep) step).getRequirements()) {
            if (!(req instanceof ItemRequirement)) continue;
            ItemRequirement item = (ItemRequirement) req;
            List<Integer> ids = item.getAllIds();
            if (ids == null || ids.isEmpty()) continue;
            int have = ids.stream().filter(id -> id != null && id > 0).mapToInt(ItemProcurer::carriedUsable).sum();
            if (have < Math.max(1, item.getQuantity())) {
                ids.stream().filter(id -> id != null && id > 0).forEach(wanted::add);
            }
        }
        return wanted;
    }

    private static String lootKey(Rs2TileItemModel item) {
        return item.getId() + "@" + item.getWorldLocation();
    }

    /** Kit items the step lists that the player isn't carrying (unnoted) or wearing enough of, at the step's quantity. */
    public static List<ItemNeed> missingKitItems(QuestStep step, List<ItemNeed> kit) {
        return missingKitItems(step, kit, Map.of());
    }

    /**
     * Kit items the step lists that aren't carried in full. A listed item the quest carries a substitute for (cooked
     * meat for burnt meat) is covered while either is carried; otherwise the substitute is what's missing.
     */
    public static List<ItemNeed> missingKitItems(QuestStep step, List<ItemNeed> kit, Map<Integer, ItemNeed> substitutes) {
        if (!(step instanceof DetailedQuestStep)) return List.of();
        List<ItemNeed> missing = new ArrayList<>();
        for (Requirement req : ((DetailedQuestStep) step).getRequirements()) {
            if (!(req instanceof ItemRequirement)) continue;
            ItemRequirement item = (ItemRequirement) req;
            List<Integer> ids = item.getAllIds();
            if (ids == null) continue;
            // Noted copies don't count: quest NPCs (e.g. Doric) only take unnoted items.
            int have = ids.stream().mapToInt(ItemProcurer::carriedUsable).sum();
            int wanted = Math.max(1, item.getQuantity());
            ItemNeed kitItem = kit.stream().filter(n -> ids.contains(n.getItemId())).findFirst().orElse(null);
            if (kitItem != null) {
                if (have < wanted) missing.add(kitItem.withQuantity(wanted));
                continue;
            }
            ItemNeed substitute = ids.stream().map(substitutes::get).filter(Objects::nonNull)
                    .findFirst().orElse(null);
            if (substitute != null && have < wanted
                    && ItemProcurer.carriedUsable(substitute.getItemId()) < substitute.getQuantity()) {
                missing.add(substitute);
            }
        }
        return missing;
    }

    /**
     * Before acting on a step: if it lists kit items that aren't carried at all, fetch them now instead of acting
     * without them. Quest Helper moves on by quest state, not inventory (Witch's Potion goes to "bring the
     * ingredients" once the rat's tail is in hand), so acting anyway just talks to the NPC in circles.
     * <p>
     * Left to the stall watchdog instead: steps a quest override handles, partly used stacks, and items carried
     * earlier on this step (the step used them, e.g. runes placed on pillars).
     */
    private boolean missingBeforeActing(QuestStep step) {
        if (!stepText.equals(itemStep)) {
            itemStep = stepText;
            heldOnStep.clear();
        }
        for (ItemNeed need : kit) {
            if (ItemProcurer.carriedUsable(need.getItemId()) > 0) heldOnStep.add(need.getItemId());
        }
        for (StepOverride override : definition.getOverrides()) {
            if (override.appliesTo(ctx)) return false;
        }
        List<ItemNeed> absent = missingKitItems(step, kit, definition.getSubstitutes()).stream()
                .filter(n -> !heldOnStep.contains(n.getItemId()) && ItemProcurer.carriedUsable(n.getItemId()) == 0)
                .collect(Collectors.toList());
        if (absent.isEmpty()) return false;
        missingItems = absent;
        makeRoom = false;
        log.info("[Questing] {}: \"{}\" needs {}, which aren't carried; fetching them before going on",
                definition.getLabel(), shortText(stepText), absent);
        return true;
    }

    // --- Zanaris ---

    // Zanaris and the cosmic altar area around it.
    private static final int ZANARIS_MIN_X = 2368, ZANARIS_MAX_X = 2495, ZANARIS_MIN_Y = 4352, ZANARIS_MAX_Y = 4479;
    // The ring by the entrance, which goes back to the Lumbridge shed.
    private static final WorldPoint ZANARIS_EXIT_RING = new WorldPoint(2452, 4473, 0);

    private static boolean inZanaris(WorldPoint p) {
        return p != null && p.getPlane() == 0 && p.getX() >= ZANARIS_MIN_X && p.getX() <= ZANARIS_MAX_X
                && p.getY() >= ZANARIS_MIN_Y && p.getY() <= ZANARIS_MAX_Y;
    }

    /**
     * The quest's own passages (see {@link QuestPassage}): the first whose near side the player is on and whose far
     * side holds the step's target gets the tick. This is where quests teach the runner their locked doors, crumbled
     * walls and hedge paths, which the walker can't route through. True if one took the tick.
     */
    private boolean usePassages(QuestStep step) {
        if (definition.getPassages().isEmpty()) return false;
        WorldPoint here = Rs2Player.getWorldLocation();
        WorldPoint target = null;
        if (step instanceof DetailedQuestStep) {
            DefinedPoint point = ((DetailedQuestStep) step).getDefinedPoint();
            if (point != null) target = point.getWorldPoint();
        }
        for (QuestPassage passage : definition.getPassages()) {
            if (!passage.appliesTo(ctx, here, target)) continue;
            if (passage.run(ctx)) {
                if (!passage.name().equals(lastPassage)) {
                    lastPassage = passage.name();
                    log.info("[Questing] {}: {} (step \"{}\")", definition.getLabel(), passage.name(), shortText(stepText));
                }
                return true;
            }
            return false;
        }
        lastPassage = null;
        return false;
    }

    private String lastPassage;

    /**
     * In Zanaris with a step somewhere else: leave through the ring by the entrance. The walker plans its route
     * through that ring but doesn't use it, so it stands on the ring's tile until StuckGuard stops the script
     * (Fairytale I after Lost City). True if it took the tick.
     */
    private boolean leaveZanaris(QuestStep step) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (!inZanaris(here) || !(step instanceof DetailedQuestStep)) return false;
        DefinedPoint target = ((DetailedQuestStep) step).getDefinedPoint();
        if (target == null || inZanaris(target.getWorldPoint())) return false;

        Rs2TileObjectModel ring = Microbot.getRs2TileObjectCache().query().withId(ObjectID.FAIRY_MUSHROOM_RING)
                .nearestOnClientThread(15);
        if (ring == null) {
            Microbot.status = "Walking to the Zanaris exit";
            Rs2Walker.walkTo(ZANARIS_EXIT_RING, 2);
            return true;
        }
        Microbot.status = "Leaving Zanaris through the fairy ring";
        log.info("[Questing] {}: leaving Zanaris for \"{}\"", definition.getLabel(), shortText(stepText));
        if (ring.click("Use") || ring.click()) {
            sleepUntil(() -> !inZanaris(Rs2Player.getWorldLocation()), 8_000);
        }
        return true;
    }

    private boolean eatIfNeeded() {
        if (Rs2Player.getHealthPercentage() > eatAtPercent) return false;
        List<Rs2ItemModel> food = Rs2Inventory.getInventoryFood();
        if (food.isEmpty()) return false;
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return true;
        }
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return true;
        }
        Microbot.status = "Eating";
        int before = food.size();
        if (Rs2Player.useFood()) {
            sleepUntil(() -> Rs2Inventory.getInventoryFood().size() < before, 1800);
        }
        return true;
    }

    // --- Existing helpers in Microbot's quest package ---

    /** Microbot's own per-quest scripts (Romeo & Juliet, Rune Mysteries, Pirate's Treasure). True if it took the tick. */
    private boolean runExistingCustomLogic() {
        IQuest logic = QuestRegistry.getQuest(definition.getQuest().getId());
        if (logic == null) return false;
        if (logic instanceof PiratesTreasure) {
            ((PiratesTreasure) logic).setMQuestPlugin(QuestHelperBridge.plugin());
        }
        // executeCustomLogic returns false when it handled the tick itself.
        return !logic.executeCustomLogic();
    }

    private boolean clickHighlightedWidget(QuestStep step) {
        if (step == null || step.getWidgetsToHighlight() == null || step.getWidgetsToHighlight().isEmpty()) return false;
        WidgetHighlight highlight = step.getWidgetsToHighlight().stream()
                .filter(h -> h instanceof WidgetHighlight)
                .map(h -> (WidgetHighlight) h)
                .filter(h -> Rs2Widget.isWidgetVisible(h.getInterfaceID()))
                .findFirst().orElse(null);
        if (highlight == null) return false;
        Widget widget = Rs2Widget.getWidget(highlight.getInterfaceID());
        if (widget == null) return false;
        if (highlight.getItemIdRequirement() != null) {
            // The highlight marks one item inside the widget (a shop slot, a make-X option): click that item's
            // child, never the middle of the whole container.
            Widget child = childHoldingItem(widget, highlight.getItemIdRequirement());
            return child != null && Rs2Widget.clickWidget(child);
        }
        if (highlight.getChildChildId() != -1) {
            Widget[] children = widget.getChildren();
            if (children != null && highlight.getChildChildId() < children.length && children[highlight.getChildChildId()] != null) {
                return Rs2Widget.clickWidget(children[highlight.getChildChildId()].getId());
            }
            return false;
        }
        if (highlight.getNameToCheckFor() != null && !highlight.getNameToCheckFor().isEmpty()) {
            return Rs2Widget.clickWidget(highlight.getNameToCheckFor());
        }
        return Rs2Widget.clickWidget(widget.getId());
    }

    /**
     * Buys the shop items the step highlights (Zambo's rum, a pot from the Lumbridge store, Asyff's priest set) with
     * the shop's "Buy 1" option, one item per tick, and closes the shop once they're all carried. Returns null when
     * no shop is open or the step marks nothing in it, and BLOCKED after {@value #MAX_SHOP_BUY_FAILURES} buys that
     * didn't land (out of stock, not enough coins) so it doesn't sit clicking in the shop.
     */
    private Result buyHighlightedShopItems(QuestStep step) {
        if (step == null || step.getWidgetsToHighlight() == null || !Rs2Shop.isOpen()) return null;
        List<Integer> wanted = step.getWidgetsToHighlight().stream()
                .filter(h -> h instanceof WidgetHighlight)
                .map(h -> (WidgetHighlight) h)
                .filter(h -> h.getInterfaceID() == InterfaceID.Shopmain.ITEMS && h.getItemIdRequirement() != null)
                .map(WidgetHighlight::getItemIdRequirement)
                .distinct()
                .collect(Collectors.toList());
        if (wanted.isEmpty()) return null;

        if (!stepText.equals(shopBuyStep)) {
            shopBuyStep = stepText;
            shopBuyFailures = 0;
        }
        Integer itemId = wanted.stream()
                .filter(id -> ItemProcurer.carriedUsable(id) == 0)
                .findFirst().orElse(null);
        if (itemId == null) {
            Microbot.status = "Closing the shop";
            Rs2Shop.closeShop();
            return Result.RUNNING;
        }

        String name = Rs2Shop.shopItems.stream()
                .filter(item -> item.getId() == itemId)
                .map(Rs2ItemModel::getName)
                .findFirst().orElse("item " + itemId);
        if (shopBuyFailures >= MAX_SHOP_BUY_FAILURES) {
            Rs2Shop.closeShop();
            blockReason = "Couldn't buy " + name + " from the shop after " + shopBuyFailures
                    + " tries (out of stock, or not enough coins?)";
            return Result.BLOCKED;
        }

        Microbot.status = "Buying " + name;
        // A moment to find the item in the shop, as a person would.
        Global.sleep(400, 1100);
        int before = Rs2Inventory.itemQuantity(itemId);
        boolean bought = Rs2Shop.buyItem(itemId, "1")
                && sleepUntil(() -> Rs2Inventory.itemQuantity(itemId) > before, 3000);
        if (bought) {
            log.info("[Quest] Bought {} from the shop", name);
        } else {
            shopBuyFailures++;
            log.warn("[Quest] Buying {} from the shop didn't work ({}/{})", name, shopBuyFailures, MAX_SHOP_BUY_FAILURES);
            Global.sleep(600, 1400);
        }
        return Result.RUNNING;
    }

    /** The child (or grandchild) of the widget showing this item, or null. */
    private static Widget childHoldingItem(Widget parent, int itemId) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> findItemChild(parent, itemId)).orElse(null);
    }

    private static Widget findItemChild(Widget parent, int itemId) {
        for (Widget[] group : new Widget[][]{parent.getDynamicChildren(), parent.getStaticChildren()}) {
            if (group == null) continue;
            for (Widget child : group) {
                if (child == null || child.isHidden()) continue;
                if (child.getItemId() == itemId) return child;
                Widget nested = findItemChild(child, itemId);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    // --- Watchdog ---

    /**
     * Two clocks spot a quest that isn't getting anywhere:
     * <ul>
     *   <li><b>Standing still</b>: no animation or fighting and no change in position, inventory, xp, dialogue
     *       text or step for
     *       {@value #STILL_ATTEMPT_MS} ms.</li>
     *   <li><b>Going in circles</b>: no change in step, inventory or xp for {@value #STEP_ATTEMPT_MS} ms, even while
     *       moving or talking.</li>
     * </ul>
     * When one runs out and the step lists kit items the player is short of, those are fetched first
     * ({@link Result#NEEDS_ITEMS}). Otherwise one recovery is tried, in order: open a closed door or gate nearby (a path the walker
     * couldn't open), reset the walker and step aside, reload the quest guide. Recovery tries only reset on real
     * progress (a new step, an item or xp change) or after moving well away from where they started; once
     * {@value #MAX_RECOVERY_ATTEMPTS} have been tried, the next stall blocks the quest with a list of what was tried.
     * Cutscenes count as progress.
     *
     * @return true when the quest should be reported {@link Result#BLOCKED}
     */
    private boolean stalled(QuestStep activeStep) {
        long now = System.currentTimeMillis();
        boolean cutscene = Microbot.getVarbitValue(CUTSCENE_VARBIT) > 0;
        // Contents, not a count: spinning turns wool into balls one for one.
        String inventory = Rs2Inventory.items().map(i -> i.getId() + "x" + i.getQuantity()).collect(Collectors.joining(","));
        long xp = totalXp();
        boolean busy = Rs2Player.isAnimating() || Rs2Combat.inCombat();
        WorldPoint here = Rs2Player.getWorldLocation();

        String step = stepText + "|" + inventory + "|" + xp;
        if (cutscene || !step.equals(stepSignature)) {
            stepSignature = step;
            stepProgressAt = now;
            clearRecoveries();
            dialogueVisits.clear();
        }
        if (recoveryAnchor != null && here != null && here.distanceTo(recoveryAnchor) > UNSTUCK_DISTANCE) {
            clearRecoveries();
        }
        String dialogue = Rs2Dialogue.isInDialogue() ? String.valueOf(Rs2Dialogue.getDialogueText()) : "";
        String still = step + "|" + here + "|" + dialogue;
        if (cutscene || busy || !still.equals(progressSignature)) {
            progressSignature = still;
            lastProgressAt = now;
        }

        // Lines with no text ("Click here to continue" between speakers) say nothing about where a conversation is.
        String line = Rs2Dialogue.isInDialogue() ? currentDialogue() : "";
        if (!line.equals(lastDialogueLine)) {
            lastDialogueLine = line;
            if (!line.isEmpty() && !line.startsWith("|")) dialogueVisits.merge(line, 1, Integer::sum);
        }
        int repeats = dialogueVisits.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        boolean talkingInCircles = repeats >= MAX_DIALOGUE_REPEATS;

        boolean standingStill = now - lastProgressAt >= STILL_ATTEMPT_MS;
        boolean circling = now - stepProgressAt >= STEP_ATTEMPT_MS || talkingInCircles;
        if (!standingStill && !circling) return false;
        dialogueVisits.clear();

        List<ItemNeed> shortItems = missingKitItems(activeStep, kit, definition.getSubstitutes());
        if (!shortItems.isEmpty()) {
            missingItems = shortItems;
            makeRoom = false;
            log.info("[Questing] {}: stalled at \"{}\" without {}; fetching them", definition.getLabel(),
                    shortText(stepText), shortItems);
            return false;
        }

        String problem = standingStill
                ? "no progress for " + (now - lastProgressAt) / 1000 + "s"
                : talkingInCircles
                ? "the same conversation came round " + repeats + " times with no item or xp change"
                : "same step for " + (now - stepProgressAt) / 60_000 + " min with no item or xp change";
        if (recoveries.size() >= MAX_RECOVERY_ATTEMPTS) {
            blockReason = "Still stuck (" + problem + ") after trying: " + String.join(", ", recoveries)
                    + ". Step: " + shortText(stepText);
            log.warn("[Questing] {} blocked: {}", definition.getLabel(), blockReason);
            return true;
        }

        if (recoveryAnchor == null) recoveryAnchor = here;
        String recovery = recover(standingStill ? recoveries.size() : 2);
        recoveries.add(recovery);
        log.warn("[Questing] {}: {}; recovery {}/{}: {}", definition.getLabel(), problem, recoveries.size(),
                MAX_RECOVERY_ATTEMPTS, recovery);
        // Give the recovery a full window before judging it.
        lastProgressAt = now;
        stepProgressAt = now;
        return false;
    }

    private void clearRecoveries() {
        recoveries.clear();
        recoveryAnchor = null;
    }

    /** Runs recovery {@code rung} (0 door/gate, 1 step aside, 2 reload the guide) and says what it did. */
    private String recover(int rung) {
        if (rung == 0) {
            String opened = openNearbyDoorOrGate();
            if (opened != null) return "opening the " + opened;
            rung = 1;
        }
        resetMovementAndInterfaces();
        if (rung == 1) {
            stepAside();
            return "stepping aside";
        }
        QuestHelperBridge.deselect();
        return "reloading the quest guide";
    }

    /** Opens the nearest closed door or gate within {@value #DOOR_SEARCH_RADIUS} tiles; returns its name, or null. */
    private String openNearbyDoorOrGate() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return null;
        Rs2TileObjectModel blocker = Microbot.getRs2TileObjectCache().query()
                .withAction("Open")
                .within(here, DOOR_SEARCH_RADIUS)
                .where(o -> {
                    String name = o.getName();
                    if (name == null) return false;
                    String lower = name.toLowerCase();
                    return lower.contains("door") || lower.contains("gate");
                })
                .nearestOnClientThread();
        if (blocker == null) return null;
        Microbot.status = "Opening the " + blocker.getName();
        Rs2Walker.setTarget(null, "Questing: opening a blocking door");
        if (blocker.click("Open")) {
            sleepUntil(Rs2Player::isMoving, 1200);
            sleepUntil(() -> !Rs2Player.isMoving(), 3000);
        }
        return blocker.getName();
    }

    /** Walks to a random reachable tile a few steps away to get off a path the walker keeps failing on. */
    private void stepAside() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return;
        List<WorldPoint> tiles = new ArrayList<>(Rs2Tile.getWalkableTilesAroundTile(here, 6));
        tiles.removeIf(t -> t.distanceTo(here) < 3 || !Rs2Tile.isTileReachable(t));
        if (tiles.isEmpty()) return;
        WorldPoint target = tiles.get(ThreadLocalRandom.current().nextInt(tiles.size()));
        Microbot.status = "Stepping aside to re-route";
        Rs2Walker.walkFastCanvas(target);
        sleepUntil(() -> !Rs2Player.isMoving(), 4000);
    }

    private static void resetMovementAndInterfaces() {
        Microbot.status = "Unsticking";
        Rs2Walker.setTarget(null, "Questing stall recovery");
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
        if (Rs2GrandExchange.isOpen()) Rs2GrandExchange.closeExchange();
    }

    private static long totalXp() {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getOverallExperience())
                .orElse(0L);
    }

    private static String shortText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.length() > 90 ? text.substring(0, 87) + "…" : text;
    }
}
