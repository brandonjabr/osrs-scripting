package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.misc.AttemptGuard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.StaminaPotions;
import net.runelite.client.plugins.microbot.util.questing.QuestDefinition;
import net.runelite.client.plugins.microbot.questhelper.requirements.Requirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import net.runelite.client.plugins.microbot.util.questing.QuestHelperBridge;
import net.runelite.client.plugins.microbot.util.questing.QuestJournal;
import net.runelite.client.plugins.microbot.util.questing.QuestPlanner;
import net.runelite.client.plugins.microbot.util.questing.QuestRunner;
import net.runelite.client.plugins.microbot.util.questing.QuestStepExecutor;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.http.api.item.ItemPrice;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.shadowfaxquesting.QuestingStats.Status;

/**
 * Runs the selected quests one after another.
 * <p>
 * SETUP → PICK → PREPARE (→ SHOP → PREPARE) → RUN → PICK … → FINISH
 * <ul>
 *   <li><b>SETUP</b>: readies Quest Helper (its own auto-player off, puzzle solutions on) and marks finished quests.</li>
 *   <li><b>PICK</b>: the first selected quest that isn't done and whose requirements are met; quests waiting on quest
 *       points get another look after each completion.</li>
 *   <li><b>PREPARE</b>: a quest already under way is first synced with the game (guide loaded, its journal read,
 *       the current step known). Questing then starts straight away with what's carried. The bank is only visited
 *       when a step needs items that aren't carried (or a fresh fight quest has no food or space, or a resumed
 *       quest's current step is a fight without food): there it
 *       withdraws the kit, coins and staminas and wears gear; stalled-step items the bank lacks go to SHOP.</li>
 *   <li><b>SHOP</b>: one Grand Exchange trip for just the items the stalled step was short of.</li>
 *   <li><b>RUN</b>: {@link QuestRunner} plays the quest. Quest Helper steps list what they produce as well as what
 *       they use (shearing lists the wool), so items are only fetched once a step stops getting anywhere without
 *       them; then it goes back to PREPARE.</li>
 * </ul>
 * Nothing is repeated blindly: an action tried {@value #MAX_ATTEMPTS} times with nothing changing, a quest that stays
 * stuck after {@value #MAX_ATTEMPTS} recovery tries, or items that can't be got stop the run, log what failed and log
 * out.
 */
@Slf4j
@Singleton
public class QuestingScript extends StateMachineScript<QuestingScript.State> {

    enum State { SETUP, PICK, PREPARE, SHOP, RUN, FINISH }

    private static final int MAX_ATTEMPTS = 3;
    // Retries of an action sooner than this are the same try still in flight.
    private static final long ATTEMPT_GAP_MS = 6_000;
    // Walks get longer: the walker may stand still while it works out a path.
    private static final long WALK_GAP_MS = 20_000;
    // Questing straight from the inventory needs this much room for quest items picked up on the way.
    private static final int MIN_FREE_SLOTS = 6;

    @Inject
    private ConfigManager configManager;

    @Inject
    private QuestingStats stats;

    private QuestingConfig config;
    private final QuestStepExecutor executor = new QuestStepExecutor();
    private final AttemptGuard guard = new AttemptGuard(MAX_ATTEMPTS, ATTEMPT_GAP_MS);

    private List<QuestCatalog.Entry> selected = List.of();
    private ItemNeed food;

    // Flags read by transition guards.
    private boolean ready;
    private boolean allDone;
    private boolean needShop;
    private boolean shopFinished;
    private boolean prepared;
    private boolean questFinished;
    private boolean needsRekit;
    // Set once the run has to stop; the reason is logged and the account logged out.
    private String failure;

    private QuestDefinition current;
    private List<ItemNeed> currentKit = List.of();
    // The quest hadn't been started when picked: the inventory can be cleared of things it doesn't use.
    private boolean freshAtPick;
    // Items a stalled step was short of; empty until the runner reports one.
    private List<ItemNeed> toFetch = List.of();
    private String stalledStep = "";
    // This PREPARE has decided the bank is needed (and keeps going until the kit is out).
    private boolean bankVisit;
    private boolean deposited;
    // An override asked for a bank trip that banks everything but the requested items (e.g. food for a boss).
    private boolean makeRoom;
    private boolean roomMade;
    private boolean shopTried;
    private String shopFailure;
    private List<ItemNeed> toBuy = List.of();
    private ItemProcurer procurer;
    private QuestRunner runner;
    private final Map<String, Integer> rekits = new HashMap<>();
    private boolean finishing;
    // A resumed quest is synced with the game (guide loaded, journal read, active step known) before anything is
    // decided, so a restart carries on with the step at hand instead of heading off to bank or shop.
    private boolean synced;
    private boolean journalRead;
    private long syncStartedAt;
    private long lastSelectAt;

    @Override
    protected State initialState() {
        return State.SETUP;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                stopFrom(State.SETUP),
                Transition.<State>from(State.SETUP)
                        .when(() -> ready, "ready")
                        .because("Quest Helper ready")
                        .goTo(State.PICK),

                Transition.<State>from(State.PICK)
                        .when(() -> allDone, "allDone")
                        .because("No quests left to do")
                        .goTo(State.FINISH),
                Transition.<State>from(State.PICK)
                        .when(() -> current != null, "current != null")
                        .because("Next quest chosen")
                        .goTo(State.PREPARE),

                stopFrom(State.PREPARE),
                Transition.<State>from(State.PREPARE)
                        .when(() -> needShop, "needShop")
                        .because("The current step needs items that aren't carried or in the bank")
                        .goTo(State.SHOP),
                Transition.<State>from(State.PREPARE)
                        .when(() -> prepared, "prepared")
                        .because("The current step can be done")
                        .goTo(State.RUN),

                stopFrom(State.SHOP),
                Transition.<State>from(State.SHOP)
                        .when(() -> shopFinished, "shopFinished")
                        .because("Shopping finished")
                        .goTo(State.PREPARE),

                stopFrom(State.RUN),
                Transition.<State>from(State.RUN)
                        .when(() -> needsRekit, "needsRekit")
                        .because("A step needs a kit item that isn't carried")
                        .goTo(State.PREPARE),
                Transition.<State>from(State.RUN)
                        .when(() -> questFinished, "questFinished")
                        .because("Quest completed")
                        .goTo(State.PICK)
        );
    }

    private Transition<State> stopFrom(State state) {
        return Transition.<State>from(state)
                .when(() -> failure != null, "failure != null")
                .because("Stuck after " + MAX_ATTEMPTS + " tries; stopping")
                .goTo(State.FINISH);
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        shopFinished = false;
        prepared = false;
        questFinished = false;
        needsRekit = false;
        needShop = false;
        guard.reset();
        if (to != State.SHOP) {
            procurer = null;
        }
        if (to == State.PREPARE && from != State.SHOP) {
            bankVisit = false;
            shopTried = false;
            shopFailure = null;
        }
        stats.setPhase(to.name());
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case SETUP:
                setup();
                break;
            case PICK:
                pick();
                break;
            case PREPARE:
                prepare();
                break;
            case SHOP:
                shop();
                break;
            case RUN:
                runQuest();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    // --- States ---

    private void setup() {
        if (!QuestHelperBridge.ensureReady(configManager)) {
            Microbot.status = "Starting Quest Helper";
            tryAgain("start the Quest Helper plugin", "", 5_000);
            return;
        }
        food = resolveFood();
        for (QuestCatalog.Entry entry : selected) {
            if (QuestHelperBridge.isFinished(entry.definition.getQuest())) {
                stats.setStatus(entry.definition.getLabel(), Status.ALREADY_DONE, "");
            }
        }
        ready = true;
    }

    /**
     * The next quest: one already under way first (the one played last, then any other started one), so a restart
     * carries on where it left off instead of preparing a fresh quest; then the first selected quest that isn't done
     * and can be started now.
     */
    private void pick() {
        current = null;
        boolean anyWaiting = false;
        for (QuestCatalog.Entry entry : resumeOrder()) {
            QuestDefinition def = entry.definition;
            Status status = stats.statusOf(def.getLabel());
            if (status == Status.DONE || status == Status.ALREADY_DONE || status == Status.SKIPPED) continue;
            if (QuestHelperBridge.isFinished(def.getQuest())) {
                stats.setStatus(def.getLabel(), Status.ALREADY_DONE, "");
                continue;
            }
            List<String> unmet = QuestPlanner.unmetRequirements(def);
            if (!unmet.isEmpty()) {
                stats.setStatus(def.getLabel(), Status.WAITING, "needs " + String.join(", ", unmet));
                anyWaiting = true;
                continue;
            }
            current = def;
            currentKit = QuestPlanner.kit(def, foodFor(def), config.budget() - stats.gpSpent());
            freshAtPick = QuestHelperBridge.state(def.getQuest()) == QuestState.NOT_STARTED;
            synced = freshAtPick;
            journalRead = false;
            syncStartedAt = 0;
            toFetch = List.of();
            deposited = false;
            stats.setStatus(def.getLabel(), Status.RUNNING, "");
            stats.setCurrent(def.getLabel(), "Preparing");
            configManager.setConfiguration(QuestingConfig.GROUP, QuestingConfig.LAST_QUEST_KEY, def.getLabel());
            if (freshAtPick) {
                log.info("[Questing] Starting {} with kit {}", def.getLabel(), currentKit);
            } else {
                log.info("[Questing] Resuming {} where it was left (kit {})", def.getLabel(), currentKit);
            }
            return;
        }

        if (anyWaiting) {
            // Nothing runnable is left, so their requirements can't be met by this run.
            for (QuestCatalog.Entry entry : selected) {
                QuestDefinition def = entry.definition;
                if (stats.statusOf(def.getLabel()) == Status.WAITING) {
                    stats.setStatus(def.getLabel(), Status.SKIPPED, "needs " + String.join(", ", QuestPlanner.unmetRequirements(def)));
                }
            }
        }
        allDone = true;
    }

    /**
     * Quest first, bank second, GE last. One action per tick:
     * <ol>
     *   <li>nothing to fetch (and food for a fight quest, some free space): start questing with what's carried;</li>
     *   <li>otherwise at the bank: deposit what the quest doesn't use (fresh quests, or a full inventory), unnote
     *       noted kit items, withdraw every kit item the bank has, coins and staminas;</li>
     *   <li>if the stalled step is still short of something, buy just that (once), or stop and say what's missing;</li>
     *   <li>wear gear, then start.</li>
     * </ol>
     */
    private void prepare() {
        String label = current.getLabel();
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return;
        }

        if (!synced) {
            syncQuest();
            return;
        }

        if (!bankVisit) {
            String reason = bankReason();
            if (reason == null) {
                log.info("[Questing] {}: questing with what's carried; items are fetched only when a step needs them", label);
                startRunning();
                return;
            }
            bankVisit = true;
            log.info("[Questing] {}: going to the bank ({})", label, reason);
        }

        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Walking to a bank for " + label;
            if (!tryAgain("walk to a bank and open it", AttemptGuard.playerSignature(), WALK_GAP_MS)) return;
            Rs2Bank.walkToBankAndUseBank();
            return;
        }

        // An override's restock: bank everything but coins, the requested items and gear still to be worn.
        if (makeRoom && !roomMade) {
            roomMade = true;
            Set<Integer> keep = toFetch.stream().map(ItemNeed::getItemId).collect(Collectors.toSet());
            currentKit.stream().filter(ItemNeed::isEquip).forEach(n -> keep.add(n.getItemId()));
            keep.add(ItemID.COINS);
            if (Rs2Inventory.items().anyMatch(i -> !keep.contains(i.getId()))) {
                Microbot.status = "Making room for " + names(toFetch);
                Rs2Bank.depositAllExcept(i -> keep.contains(i.getId()));
                sleepUntil(() -> Rs2Inventory.items().allMatch(i -> keep.contains(i.getId())), 3000);
                return;
            }
        }

        // Make room, but keep anything the quest uses.
        if (!deposited) {
            deposited = true;
            // Mid-quest, only when what's being fetched wouldn't fit: anything carried may be a quest item.
            if (freshAtPick || Rs2Inventory.emptySlotCount() < slotsFor(toFetch)) {
                Set<Integer> keep = currentKit.stream().map(ItemNeed::getItemId).collect(Collectors.toSet());
                keep.addAll(stepItemIds());
                keep.add(ItemID.COINS);
                if (Rs2Inventory.items().anyMatch(i -> !keep.contains(i.getId()))) {
                    Microbot.status = "Depositing items the quest doesn't use";
                    Rs2Bank.depositAllExcept(i -> keep.contains(i.getId()));
                    sleepUntil(() -> Rs2Inventory.items().allMatch(i -> keep.contains(i.getId())), 3000);
                    return;
                }
            }
        }

        if (!Rs2Bank.setWithdrawAsItem()) {
            tryAgain("switch the bank to withdraw items", AttemptGuard.playerSignature());
            return;
        }

        // Noted copies can't be handed in or used, so bank them and take them back out unnoted.
        for (ItemNeed need : currentKit) {
            int noted = ItemProcurer.notedId(need.getItemId());
            if (noted > 0 && Rs2Inventory.hasItem(noted)) {
                Microbot.status = "Banking noted " + need.getName();
                if (!tryAgain("bank noted " + need.getName(), AttemptGuard.playerSignature())) return;
                Rs2Bank.depositAll(noted);
                sleepUntil(() -> !Rs2Inventory.hasItem(noted), 3000);
                return;
            }
        }

        // Coins first: they take one slot and fares or quest NPCs need them.
        int coins = QuestPlanner.coinsNeeded(current);
        int carried = Rs2Inventory.itemQuantity(ItemID.COINS);
        if (coins > carried && Rs2Bank.count(ItemID.COINS) > 0) {
            Microbot.status = "Withdrawing coins";
            if (!tryAgain("withdraw coins", AttemptGuard.playerSignature())) return;
            Rs2Bank.withdrawX(ItemID.COINS, Math.min(coins - carried, Rs2Bank.count(ItemID.COINS)));
            sleepUntil(() -> Rs2Inventory.itemQuantity(ItemID.COINS) > carried, 3000);
            return;
        }

        // The stalled step's items at its quantity, then every other kit item the bank has (saves coming back),
        // smallest first and only as many as fit: 25 bones mustn't crowd out the food or a bucket.
        // A make-room restock takes out only what was asked for, so the space stays free.
        List<ItemNeed> extras = makeRoom ? new ArrayList<>() : new ArrayList<>(currentKit);
        extras.sort(Comparator.comparingInt(n -> ItemProcurer.isStackable(n.getItemId()) ? 0 : n.getQuantity()));
        List<ItemNeed> wanted = new ArrayList<>(toFetch);
        wanted.addAll(extras);
        for (ItemNeed need : wanted) {
            int have = ItemProcurer.carriedUsable(need.getItemId());
            if (have >= need.getQuantity()) continue;
            int inBank = Rs2Bank.count(need.getItemId());
            if (inBank <= 0) continue;
            int amount = Math.min(need.getQuantity() - have, inBank);
            if (!ItemProcurer.isStackable(need.getItemId())) {
                amount = Math.min(amount, Rs2Inventory.emptySlotCount());
            } else if (have == 0 && Rs2Inventory.emptySlotCount() == 0) {
                amount = 0;
            }
            if (amount <= 0) continue;
            Microbot.status = "Withdrawing " + need.getName() + " x" + amount;
            if (!tryAgain("withdraw " + need.getName(), AttemptGuard.playerSignature())) return;
            Rs2Bank.withdrawX(need.getItemId(), amount);
            sleepUntil(() -> ItemProcurer.carriedUsable(need.getItemId()) > have, 3000);
            return;
        }

        if (config.useStaminas() && StaminaPotions.usable()
                && StaminaPotions.carried() < config.staminaCount() && StaminaPotions.inBank()) {
            Microbot.status = "Withdrawing a stamina potion";
            if (!tryAgain("withdraw a stamina potion", AttemptGuard.playerSignature())) return;
            StaminaPotions.withdrawOne();
            return;
        }

        List<ItemNeed> stillShort = toFetch.stream()
                .filter(n -> ItemProcurer.carriedUsable(n.getItemId()) < n.getQuantity())
                .collect(Collectors.toList());
        if (!stillShort.isEmpty()) {
            List<ItemNeed> buyable = QuestPlanner.buyable(stillShort);
            if (config.buyItems() && !shopTried && buyable.size() == stillShort.size()) {
                toBuy = buyable;
                shopTried = true;
                needShop = true;
                return;
            }
            fail("the step \"" + shortText(stalledStep) + "\" stalled without " + names(stillShort)
                    + ", which isn't carried or in the bank, and " + whyNotBought(stillShort, buyable));
            return;
        }

        for (ItemNeed need : currentKit) {
            if (need.isEquip() && Rs2Inventory.hasItem(need.getItemId())) {
                Rs2Bank.closeBank();
                Microbot.status = "Wearing " + need.getName();
                if (!tryAgain("wear " + need.getName(), AttemptGuard.playerSignature())) return;
                Rs2Inventory.wear(need.getItemId());
                return;
            }
        }

        Rs2Bank.closeBank();
        startRunning();
    }

    /** Buys {@link #toBuy} for the current quest. */
    private void shop() {
        if (procurer == null) {
            log.info("[Questing] Shopping for {}: {}", current.getLabel(), toBuy);
            procurer = new ItemProcurer(toBuy, config.budget() - stats.gpSpent(), config.markupPercent());
        }

        // Waiting at the open GE for offers isn't a repeated action; the procurer reprices and gives up on its own.
        boolean waiting = procurer.isWaitingForOffers() && Rs2GrandExchange.isOpen();
        if (!waiting && !tryAgain("shopping: " + procurer.getStatus(), AttemptGuard.playerSignature(), WALK_GAP_MS)) {
            return;
        }

        ItemProcurer.Status status = procurer.tick();
        Microbot.status = "Shopping: " + procurer.getStatus();
        if (status == ItemProcurer.Status.WORKING) return;

        stats.addGpSpent(procurer.getGpSpent());
        if (status == ItemProcurer.Status.FAILED) {
            // PREPARE decides whether the quest can go on without it.
            shopFailure = procurer.getFailureReason();
            log.warn("[Questing] Shopping failed: {}", shopFailure);
        }
        shopFinished = true;
    }

    private void runQuest() {
        QuestRunner.Result result = runner.tick();
        stats.setCurrent(current.getLabel(), runner.getStepText());
        switch (result) {
            case COMPLETED:
                log.info("[Questing] Completed {}", current.getLabel());
                stats.setStatus(current.getLabel(), Status.DONE, "");
                endQuest();
                break;
            case NEEDS_ITEMS:
                String missing = names(runner.getMissingItems());
                int count = rekits.merge(current.getLabel() + "|" + missing, 1, Integer::sum);
                if (count > MAX_ATTEMPTS) {
                    fail("kept needing " + missing + " again after fetching it " + MAX_ATTEMPTS + " times");
                } else {
                    log.info("[Questing] {}: step needs {} (fetch {}/{})", current.getLabel(), missing, count, MAX_ATTEMPTS);
                    toFetch = runner.getMissingItems();
                    stalledStep = runner.getStepText();
                    makeRoom = runner.isMakeRoom();
                    roomMade = false;
                    needsRekit = true;
                }
                break;
            case BLOCKED:
                fail(runner.getBlockReason());
                break;
            default:
                break;
        }
    }

    private void startRunning() {
        ItemNeed kitFood = food == null ? null : currentKit.stream()
                .filter(n -> n.getItemId() == food.getItemId()).findFirst().orElse(null);
        runner = new QuestRunner(current, executor, currentKit, kitFood, config.eatAtPercent());
        makeRoom = false;
        prepared = true;
    }

    private void endQuest() {
        QuestHelperBridge.deselect();
        current = null;
        runner = null;
        stats.setCurrent("", "");
        questFinished = true;
    }

    private void finish() {
        if (finishing) return;
        finishing = true;
        long done = stats.count(Status.DONE);
        QuestHelperBridge.deselect();
        QuestHelperBridge.restore(configManager);
        if (failure != null) {
            Microbot.status = "Stopped: " + failure;
            log.info("[Questing] Run stopped after {} completed quests; logging out", done);
            Rs2Player.logoutWithoutAutoLogin();
        } else {
            Microbot.status = "Finished: " + done + " quests completed";
            log.info("[Questing] Run finished: {} completed, {} skipped", done, stats.count(Status.SKIPPED));
            if (config.logoutWhenDone()) {
                Rs2Player.logoutWithoutAutoLogin();
            }
        }
        Microbot.stopPlugin(QuestingPlugin.class);
    }

    // --- Failure handling ---

    /** Records an attempt at {@code action}; on the try after {@value #MAX_ATTEMPTS} failed ones, stops the run. */
    private boolean tryAgain(String action, String signature) {
        return tryAgain(action, signature, ATTEMPT_GAP_MS);
    }

    private boolean tryAgain(String action, String signature, long gapMs) {
        if (guard.attempt(action, signature, gapMs)) return true;
        fail("couldn't " + guard.getFailure());
        // The same action keeps failing with nothing changing: stuck. Log out (with retries) and stop.
        StuckGuard.trip("couldn't " + guard.getFailure());
        return false;
    }

    /** Stops the run (FINISH logs out) and logs what failed, once. */
    private void fail(String what) {
        if (failure != null) return;
        failure = what;
        String quest = current != null ? current.getLabel() : "setup";
        String step = runner != null ? runner.getStepText() : "";
        log.error("[Questing] Stopping in {}: {}{}", quest, what, step.isEmpty() ? "" : " (step: " + step + ")");
        Microbot.log("Shadowfax Questing stopped in " + quest + ": " + what);
        if (current != null) stats.setStatus(current.getLabel(), Status.STUCK, what);
    }

    private String whyNotBought(List<ItemNeed> missing, List<ItemNeed> buyable) {
        if (buyable.size() < missing.size()) {
            List<ItemNeed> untradeable = missing.stream()
                    .filter(n -> !ItemProcurer.isTradeable(n.getItemId())).collect(Collectors.toList());
            return names(untradeable) + " can't be bought on the Grand Exchange";
        }
        if (!config.buyItems()) return "buying quest items is turned off";
        if (shopFailure != null) return "buying it failed: " + shopFailure;
        return "it was still missing after shopping";
    }

    // --- Planning helpers ---

    /**
     * Resuming: load the guide, read the quest's journal in game (Quest Helper re-syncs journal-based conditions),
     * and wait for the active step, one action per tick. Only then is anything fetched.
     */
    private void syncQuest() {
        String label = current.getLabel();
        long now = System.currentTimeMillis();
        if (syncStartedAt == 0) syncStartedAt = now;
        if (!QuestHelperBridge.isSelected(current.getQuest())) {
            Microbot.status = "Loading the quest guide for " + label;
            if (now - lastSelectAt > 3_000) {
                lastSelectAt = now;
                QuestHelperBridge.select(current.getQuest());
            }
            if (now - syncStartedAt < 30_000) return;
            log.warn("[Questing] {}: the quest guide didn't load; going on without syncing", label);
            synced = true;
            return;
        }
        if (!journalRead) {
            journalRead = true;
            Microbot.status = "Checking the " + label + " journal";
            QuestJournal.read(current.getQuest());
            return;
        }
        QuestStep step = QuestHelperBridge.activeStep();
        if (step == null && now - syncStartedAt < 15_000) {
            Microbot.status = "Waiting for the quest guide's current step";
            return;
        }
        synced = true;
        log.info("[Questing] {}: synced with the game; current step: \"{}\"", label,
                shortText(step == null ? "" : String.join(" ", step.getText())));
    }

    /**
     * Why PREPARE should visit the bank before questing, or null to start with what's carried. A stalled step's
     * items always go. A fresh quest also wants food (if it has fights) and some free space. A resumed quest only
     * goes for food when the step at hand is a fight; everything else is fetched when a step asks for it.
     */
    private String bankReason() {
        if (!toFetch.isEmpty()) return "a step needs " + names(toFetch);
        if (freshAtPick) {
            if (!carriesFoodIfNeeded()) return "food for the quest's fights";
            if (Rs2Inventory.emptySlotCount() < MIN_FREE_SLOTS) return "inventory space";
            return null;
        }
        if (!carriesFoodIfNeeded() && stepIsFight()) return "food: the current step is a fight";
        return null;
    }

    private static boolean stepIsFight() {
        QuestStep step = QuestHelperBridge.activeStep();
        if (step == null || step.getText() == null) return false;
        String text = String.join(" ", step.getText()).toLowerCase();
        return text.contains("kill") || text.contains("defeat") || text.contains("fight");
    }

    /** Item ids the active step lists, so a bank visit never deposits them. */
    private static Set<Integer> stepItemIds() {
        Set<Integer> ids = new java.util.HashSet<>();
        QuestStep step = QuestHelperBridge.activeStep();
        if (!(step instanceof DetailedQuestStep)) return ids;
        for (Requirement req : ((DetailedQuestStep) step).getRequirements()) {
            if (!(req instanceof ItemRequirement)) continue;
            List<Integer> all = ((ItemRequirement) req).getAllIds();
            if (all != null) all.stream().filter(id -> id != null && id > 0).forEach(ids::add);
        }
        return ids;
    }

    /** Free slots needed to take out {@code items} on top of what's carried. */
    private static int slotsFor(List<ItemNeed> items) {
        int slots = 0;
        for (ItemNeed need : items) {
            int have = ItemProcurer.carriedUsable(need.getItemId());
            if (have >= need.getQuantity()) continue;
            slots += ItemProcurer.isStackable(need.getItemId()) ? (have > 0 ? 0 : 1) : need.getQuantity() - have;
        }
        return slots;
    }

    private boolean carriesFoodIfNeeded() {
        return !current.isBringFood() || food == null || !Rs2Inventory.getInventoryFood().isEmpty();
    }

    private ItemNeed foodFor(QuestDefinition def) {
        return def.isBringFood() ? food : null;
    }

    /** The configured food as an item, looked up by its exact name among GE items. */
    private ItemNeed resolveFood() {
        String name = config.foodName().trim();
        if (name.isEmpty() || config.foodAmount() <= 0) return null;
        List<ItemPrice> matches = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getItemManager().search(name))
                .orElse(List.of());
        for (ItemPrice match : matches) {
            if (match.getName().equalsIgnoreCase(name)) {
                return ItemNeed.of(match.getId(), match.getName(), config.foodAmount());
            }
        }
        log.warn("[Questing] No tradeable food called '{}'; fight quests will start without food", name);
        return null;
    }

    private static String names(List<ItemNeed> items) {
        return items.stream().map(n -> n.getName() + " x" + n.getQuantity()).collect(Collectors.joining(", "));
    }

    private static String shortText(String text) {
        if (text == null) return "";
        return text.length() > 90 ? text.substring(0, 87) + "…" : text;
    }

    /** Selected quests with started ones first: the one played last, then the rest in catalog order. */
    private List<QuestCatalog.Entry> resumeOrder() {
        String last = config.lastQuest();
        Map<QuestCatalog.Entry, Integer> rank = new HashMap<>();
        for (QuestCatalog.Entry entry : selected) {
            boolean started = QuestHelperBridge.state(entry.definition.getQuest()) == QuestState.IN_PROGRESS;
            rank.put(entry, !started ? 2 : entry.definition.getLabel().equals(last) ? 0 : 1);
        }
        List<QuestCatalog.Entry> order = new ArrayList<>(selected);
        // Stable: equal ranks keep the catalog's order.
        order.sort(Comparator.comparingInt(rank::get));
        return order;
    }

    // --- Lifecycle ---

    public boolean run(QuestingConfig config) {
        this.config = config;
        selected = QuestCatalog.ENTRIES.stream().filter(e -> e.isEnabled(config)).collect(Collectors.toList());
        stats.reset(selected.stream().map(e -> e.definition.getLabel()).collect(Collectors.toList()));
        ready = allDone = needShop = shopFinished = prepared = questFinished = needsRekit = finishing = false;
        failure = null;
        current = null;
        runner = null;
        procurer = null;
        guard.reset();
        rekits.clear();

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyGeneralBasicSetup();
        }

        StuckGuard.attach("Shadowfax Questing", QuestingPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                if (config.useStaminas() && getCurrentState() == State.RUN
                        && StaminaPotions.drinkIfNeeded(config.staminaAtEnergy())) {
                    return;
                }
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error (e.g. a client-thread AssertionError) would otherwise
                // cancel the scheduled task without any log line.
                log.error("[Questing] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        executor.bindTo(mainScheduledFuture);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(QuestingPlugin.class);
        super.shutdown();
        QuestHelperBridge.deselect();
        QuestHelperBridge.restore(configManager);
        Rs2Antiban.resetAntibanSettings();
    }
}
