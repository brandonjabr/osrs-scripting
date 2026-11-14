package net.runelite.client.plugins.microbot.shadowfaxwarriorsguild;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.reachable.Rs2Reachable;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Warriors' Guild defenders, start to finish.
 * <p>
 * BANK → TOKENS → ENTER → CYCLOPS → SHOW → ENTER → CYCLOPS … Tokens come from animating an armour set on the ground
 * floor (the armour pieces and tokens drop back when it's beaten). With enough tokens the script climbs to
 * Kamfreena's cyclopes on the top floor, which drop the defender one tier above the best one we have. Every new
 * defender is picked up, then (by default) taken out to Kamfreena and used on her so she releases the next
 * cyclopes, worn, and the room re-entered. Once a rune defender is owned, the cyclopes in the basement (past
 * Lorelai) drop the dragon defender instead. Running low on tokens goes back to the armour; out of food at low
 * health goes to the guild bank. Stops at the configured defender.
 * <p>
 * The best defender owned is tracked from the inventory, the equipment and (whenever it's open) the bank.
 */
@Slf4j
@Singleton
public class WarriorsGuildScript extends StateMachineScript<WarriorsGuildScript.State> {

    enum State {
        BANK,
        TOKENS,
        ENTER,
        CYCLOPS,
        SHOW,
        FINISH
    }

    // --- Ground floor: the armour room ---
    private static final WorldPoint ANIMATOR_TILE = new WorldPoint(2851, 3536, 0);
    private static final int ANIMATOR_ID = ObjectID.WARGUILD_ANIMATOR_CENT;
    private static final int[] ARMOURY_DOOR_IDS = {ObjectID.WARGUILD_DOOR_ARMOUR_L, ObjectID.WARGUILD_DOOR_ARMOUR_R};
    private static final String ANIMATED_PREFIX = "animated ";

    // --- Top floor: Kamfreena's lobby and the cyclops room around it ---
    private static final WorldPoint TOP_LOBBY = new WorldPoint(2842, 3540, 2);
    // Just inside the room's west door (the door is at x 2847, Kamfreena's side is west of it).
    private static final WorldPoint TOP_INSIDE_DOOR = new WorldPoint(2849, 3540, 2);
    private static final int[] TOP_DOOR_IDS = {ObjectID.WARGUILD_DOOR_ARMOUR_L, ObjectID.WARGUILD_DOOR_ARMOUR_R};
    private static final Set<Integer> TOP_CYCLOPES = Set.of(
            NpcID.WARGUILD_CYCLOPS1, NpcID.WARGUILD_CYCLOPS2, NpcID.WARGUILD_CYCLOPS3,
            NpcID.WARGUILD_CYCLOPS4, NpcID.WARGUILD_CYCLOPS5, NpcID.WARGUILD_CYCLOPS6);

    // --- Basement: below the ladder west of the guild's back door ---
    private static final WorldPoint BASEMENT_LADDER = new WorldPoint(2907, 9968, 0);
    private static final int BASEMENT_DOOR_ID = ObjectID.WARGUILD_BASEMENT_DOOR;
    private static final Set<Integer> BASEMENT_CYCLOPES = Set.of(
            NpcID.WARGUILD_CYCLOPS1_HIGH, NpcID.WARGUILD_CYCLOPS2_HIGH, NpcID.WARGUILD_CYCLOPS3_HIGH,
            NpcID.WARGUILD_CYCLOPS4_HIGH, NpcID.WARGUILD_CYCLOPS5_HIGH, NpcID.WARGUILD_CYCLOPS6_HIGH);

    private static final int TOKEN_ID = ItemID.WARGUILD_TOKENS;
    // Kamfreena and Lorelai won't let anyone in with fewer.
    private static final int MIN_ENTRY_TOKENS = 100;
    // Out of food: rest at the bank until health is back to this.
    private static final int REST_UNTIL_PERCENT = 60;
    private static final int MAX_SHOW_ATTEMPTS = 3;
    private static final long DIALOGUE_TIMEOUT_MS = 15_000;

    // Antiban cooldown cap, as in the other Shadowfax combat scripts.
    private static final long MIN_COOLDOWN_MS = 2_000;
    private static final long MAX_COOLDOWN_MS = 7_000;
    private long cooldownDeadline;

    // Watchdog: any state making no progress this long restarts from BANK.
    private static final long STALL_RESTART_MS = 120_000;
    private static final long INTENTIONAL_WAIT_GRACE_MS = 2_000;
    private State watchedState;
    private long lastProgressAt;
    private long lastIntentionalWaitAt;
    private String progressSignature = "";

    @Inject
    private WarriorsGuildStats stats;

    private WarriorsGuildConfig config;

    // Shown on the overlay.
    volatile Defender bestOwned;
    volatile Defender lastShown;
    volatile int tokens;
    volatile ArmourSet armourSet;

    // Collecting tokens until the target, or spending them on cyclopes.
    private volatile boolean collecting;
    // A new defender that still has to be shown to Kamfreena.
    private volatile Defender pendingShow;
    private int lastTokens = -1;

    // Per-state progress, reset on every transition.
    private boolean bankDone;
    private boolean foodWithdrawTried;
    private boolean armourWithdrawTried;
    private boolean armourMissing;
    private boolean showDone;
    private int showAttempts;
    private long dialogueSince;

    @Override
    protected State initialState() {
        // Already carrying the token target: skip the armour and go straight up to the cyclopes.
        int carried = Rs2Inventory.itemQuantity(TOKEN_ID);
        if (enoughTokens(carried)) {
            collecting = false;
            log.info("[WarriorsGuild] Starting with {} tokens (target {}); going straight to the cyclopes",
                    carried, config.tokensToCollect());
            return State.ENTER;
        }
        // Otherwise start at the bank: it's where tokens, defenders and the armour set are found and checked.
        return State.BANK;
    }

    /** At or above the token target (and never below what it takes to get in). */
    private boolean enoughTokens(int amount) {
        return amount >= Math.max(MIN_ENTRY_TOKENS, config.tokensToCollect());
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                // Done once the target defender is owned, from anywhere but the bank (which may still be holding
                // the inventory open) and FINISH itself.
                Transition.<State>from(State.TOKENS).when(this::reachedGoal, "reachedGoal()")
                        .because("Got the target defender").goTo(State.FINISH),
                Transition.<State>from(State.ENTER).when(this::reachedGoal, "reachedGoal()")
                        .because("Got the target defender").goTo(State.FINISH),
                Transition.<State>from(State.CYCLOPS).when(this::reachedGoal, "reachedGoal()")
                        .because("Got the target defender").goTo(State.FINISH),
                Transition.<State>from(State.SHOW).when(this::reachedGoal, "reachedGoal()")
                        .because("Got the target defender").goTo(State.FINISH),
                Transition.<State>from(State.BANK).when(() -> bankDone && reachedGoal(), "bankDone && reachedGoal()")
                        .because("Already own the target defender").goTo(State.FINISH),

                Transition.<State>from(State.BANK)
                        .when(() -> bankDone && (collecting || tokens < MIN_ENTRY_TOKENS), "bankDone && collecting")
                        .because("Banked; collecting tokens").goTo(State.TOKENS),
                Transition.<State>from(State.BANK)
                        .when(() -> bankDone, "bankDone")
                        .because("Banked; going to the cyclopes").goTo(State.ENTER),

                Transition.<State>from(State.TOKENS).when(this::needsBank, "needsBank()")
                        .because("Out of food at low health, or no armour set to animate").goTo(State.BANK),
                Transition.<State>from(State.TOKENS).when(() -> !collecting, "!collecting")
                        .because("Enough tokens; going to the cyclopes").goTo(State.ENTER),

                Transition.<State>from(State.ENTER).when(this::needsBank, "needsBank()")
                        .because("Out of food at low health").goTo(State.BANK),
                Transition.<State>from(State.ENTER).when(() -> inRoom(wantedFloor()), "inRoom(wantedFloor())")
                        .because("In the cyclops room").goTo(State.CYCLOPS),
                Transition.<State>from(State.ENTER).when(() -> tokens < MIN_ENTRY_TOKENS && !inAnyRoom(),
                                "tokens < " + MIN_ENTRY_TOKENS)
                        .because("Not enough tokens to get in").goTo(State.TOKENS),

                Transition.<State>from(State.CYCLOPS).when(() -> pendingShow != null && !defenderOnGround(),
                                "pendingShow != null")
                        .because("New defender; showing it to Kamfreena").goTo(State.SHOW),
                Transition.<State>from(State.CYCLOPS).when(this::needsBank, "needsBank()")
                        .because("Out of food at low health").goTo(State.BANK),
                Transition.<State>from(State.CYCLOPS)
                        .when(() -> tokens <= config.leaveAtTokens() && !defenderOnGround(), "tokens <= leaveAt")
                        .because("Low on tokens; back to the armour").goTo(State.TOKENS),
                Transition.<State>from(State.CYCLOPS).when(() -> !inRoom(wantedFloor()), "!inRoom(wantedFloor())")
                        .because("Not in the right cyclops room (kicked out, or ready for the basement)")
                        .goTo(State.ENTER),

                Transition.<State>from(State.SHOW).when(() -> showDone, "showDone")
                        .because("Kamfreena has seen the defender").goTo(State.ENTER)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        bankDone = false;
        foodWithdrawTried = false;
        armourWithdrawTried = false;
        armourMissing = false;
        showDone = false;
        showAttempts = 0;
        dialogueSince = 0;
        if (to == State.TOKENS) {
            // Whatever sent us here (low in the room, kicked out, too few to enter), collect up to the target.
            collecting = true;
        }
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case BANK:
                bank();
                break;
            case TOKENS:
                collectTokens();
                break;
            case ENTER:
                enter();
                break;
            case CYCLOPS:
                fightCyclopes();
                break;
            case SHOW:
                show();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    // --- Events (client thread, from the plugin) ---

    void onNpcDeath(NPC npc) {
        Player me = Microbot.getClient().getLocalPlayer();
        if (me == null || npc == null) return;
        if (npc.getInteracting() != me && me.getInteracting() != npc) return;
        String name = npc.getName() == null ? "" : npc.getName().toLowerCase(Locale.ROOT);
        if (TOP_CYCLOPES.contains(npc.getId()) || BASEMENT_CYCLOPES.contains(npc.getId())) {
            stats.addCyclopsKill();
        } else if (name.startsWith(ANIMATED_PREFIX)) {
            stats.addArmourKill();
        }
    }

    // --- States ---

    private void bank() {
        if (inAnyRoom()) {
            exitRoom();
            return;
        }
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Walking to the Warriors' Guild bank";
            Rs2Bank.walkToBankAndUseBank(BankLocation.WARRIORS_GUILD);
            return;
        }

        // What the bank holds counts towards the best defender owned.
        Defender inBank = null;
        for (Rs2ItemModel item : Rs2Bank.bankItems()) {
            inBank = Defender.max(inBank, Defender.of(item));
        }
        updateBestOwned(inBank);

        if (Rs2Inventory.items(this::isBankable).findAny().isPresent()) {
            Microbot.status = "Banking";
            Rs2Bank.depositAll(this::isBankable);
            if (sleepUntil(() -> Rs2Inventory.items(this::isBankable).findAny().isEmpty(), 4_000)) {
                stats.addBankTrip();
            }
            return;
        }

        // Tokens in the bank count too: take them all, and skip the armour if that's already enough.
        if (Rs2Bank.hasItem(TOKEN_ID)) {
            Microbot.status = "Withdrawing Warrior guild tokens";
            int before = Rs2Inventory.itemQuantity(TOKEN_ID);
            if (Rs2Bank.withdrawAll(TOKEN_ID)) {
                sleepUntil(() -> Rs2Inventory.itemQuantity(TOKEN_ID) > before, 3_000);
            }
            trackTokens();
            return;
        }
        if (collecting && enoughTokens(tokens)) {
            log.info("[WarriorsGuild] Already have {} tokens; going straight to the cyclopes", tokens);
            collecting = false;
        }

        // Carry the best defender: the cyclopes drop the tier above the best one on us (and Kamfreena can see it).
        Defender carried = carriedBest();
        if (inBank != null && (carried == null || inBank.ordinal() > carried.ordinal())) {
            final Defender want = inBank;
            Microbot.status = "Withdrawing " + want;
            if (Rs2Bank.withdrawOne(want.itemId) || Rs2Bank.withdrawX(want.itemName(), 1, true)) {
                sleepUntil(() -> carriedBest() == want, 3_000);
            }
            return;
        }

        if (needsArmour() && !armourWithdrawTried) {
            ArmourSet set = chooseArmourSet();
            // Remember it, so the deposit step doesn't bank the pieces we just took out.
            if (set != null) armourSet = set;
            if (set != null && !set.carried()) {
                armourWithdrawTried = true;
                Microbot.status = "Withdrawing the " + set.metal.toLowerCase(Locale.ROOT) + " armour set";
                for (String piece : set.pieces()) {
                    if (Rs2Inventory.hasItem(piece, true)) continue;
                    if (Rs2Bank.withdrawX(piece, 1, true)) {
                        sleepUntil(() -> Rs2Inventory.hasItem(piece, true), 2_400);
                    }
                }
                if (!set.carried()) log.info("[WarriorsGuild] Couldn't withdraw the whole {} set", set.metal);
                return;
            }
        }

        int missing = config.foodAmount() - foodCount();
        if (missing > 0 && !foodWithdrawTried) {
            foodWithdrawTried = true;
            Microbot.status = "Withdrawing food";
            int before = foodCount();
            String name = config.foodName().trim();
            boolean withdrew = !name.isEmpty() && Rs2Bank.hasItem(name, true)
                    ? Rs2Bank.withdrawX(name, missing, true)
                    : Rs2Bank.withdrawX(Rs2ItemModel::isFood, missing);
            if (!withdrew || !sleepUntil(() -> foodCount() > before, 3_000)) {
                log.info("[WarriorsGuild] Couldn't withdraw {} from the bank", name.isEmpty() ? "any food" : name);
            }
            return;
        }

        if (needsArmour() && tokens < MIN_ENTRY_TOKENS) {
            ArmourSet set = chooseArmourSet();
            if (set == null || (armourWithdrawTried && !set.carried())) {
                stop("Fewer than " + MIN_ENTRY_TOKENS + " tokens and no " + (set == null ? "" : set.metal + " ")
                        + "armour set (full helm, platebody and platelegs) to animate, in the inventory or the bank");
                return;
            }
        }

        if (!hasFood() && Rs2Player.getHealthPercentage() < REST_UNTIL_PERCENT) {
            Microbot.status = "No food; resting at the bank until " + REST_UNTIL_PERCENT + "% health";
            markIntentionalWait();
            return;
        }

        Rs2Bank.closeBank();
        bankDone = true;
    }

    /** Animates the armour set, fights it and loots the pieces and tokens back, until the token target. */
    private void collectTokens() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (inAnyRoom()) {
            exitRoom();
            return;
        }
        if (!inArmourRoom()) {
            walkToArmourRoom();
            return;
        }

        Rs2TileItemModel loot = findArmourLoot();
        Rs2NpcModel armour = ourArmour();
        if (armour != null) {
            if (Rs2Player.isInteracting()) {
                Microbot.status = "Fighting the animated armour";
                return;
            }
            Microbot.status = "Attacking the animated armour";
            if (armour.click("Attack")) sleepUntil(Rs2Player::isInteracting, 2_400);
            return;
        }
        if (loot != null) {
            pickUp(loot);
            return;
        }

        if (enoughTokens(tokens)) {
            log.info("[WarriorsGuild] {} tokens; going to the cyclopes", tokens);
            collecting = false;
            return;
        }

        ArmourSet set = armourSet != null && armourSet.carried() ? armourSet : chooseArmourSet();
        if (set == null || !set.carried()) {
            if (tokens >= MIN_ENTRY_TOKENS) {
                log.info("[WarriorsGuild] No armour set to animate; going in with the {} tokens we have", tokens);
                collecting = false;
                return;
            }
            armourMissing = true;
            Microbot.status = "Armour set missing";
            return;
        }
        armourSet = set;

        Rs2TileObjectModel animator = Microbot.getRs2TileObjectCache().query()
                .withId(ANIMATOR_ID)
                .nearestOnClientThread(12);
        if (animator == null) {
            walkToArmourRoom();
            return;
        }
        Microbot.status = "Animating the " + set.metal.toLowerCase(Locale.ROOT) + " armour";
        if (animator.click("Animate")) {
            sleepUntil(() -> ourArmour() != null, 6_000);
            antibanAfterAction();
        }
    }

    /** Walks to Kamfreena's lobby or the basement door and goes in, clicking through the entry dialogue. */
    private void enter() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        boolean basement = wantedFloor();
        if (inAnyRoom()) {
            // In the other room (rune defender while still upstairs): leave it first.
            exitRoom();
            return;
        }
        if (continueDialogue()) return;
        if (wearBestDefender()) return;

        if (basement) {
            if (!inBasement() || distanceTo(BASEMENT_LADDER) > 20) {
                Microbot.status = "Walking to the guild basement";
                walk(BASEMENT_LADDER, 2);
                return;
            }
            Rs2TileObjectModel door = Microbot.getRs2TileObjectCache().query()
                    .withId(BASEMENT_DOOR_ID)
                    .nearestOnClientThread(20);
            if (door == null) {
                Microbot.status = "Looking for the basement door";
                walk(BASEMENT_LADDER, 1);
                return;
            }
            Microbot.status = "Opening the basement cyclops door";
            if (door.click("Open")) {
                sleepUntil(() -> inRoom(true) || Rs2Dialogue.isInDialogue(), 5_000);
            }
            return;
        }

        if (Rs2Player.getWorldLocation() == null || Rs2Player.getWorldLocation().getPlane() != 2
                || distanceTo(TOP_LOBBY) > 6) {
            Microbot.status = "Walking up to Kamfreena";
            walk(TOP_LOBBY, 2);
            return;
        }
        Rs2TileObjectModel door = topDoor();
        if (door == null) {
            Microbot.status = "Looking for the cyclops room door";
            return;
        }
        Microbot.status = "Opening the cyclops room door";
        if (door.click("Open")) {
            sleepUntil(() -> inRoom(false) || Rs2Dialogue.isInDialogue(), 5_000);
        }
    }

    /** Picks up defenders first, then fights cyclopes; the transitions leave for tokens, food or the next room. */
    private void fightCyclopes() {
        if (continueDialogue()) return;

        Rs2TileItemModel defender = findDefenderLoot();
        if (defender != null) {
            lootDefender(defender);
            return;
        }
        if (pendingShow == null && wearBestDefender()) return;

        if (Rs2Player.isInteracting()) {
            Microbot.status = "Fighting a cyclops";
            return;
        }
        Rs2NpcModel cyclops = findCyclops(wantedFloor());
        if (cyclops == null) {
            Microbot.status = "Waiting for a free cyclops";
            markIntentionalWait();
            return;
        }
        Microbot.status = "Attacking a cyclops";
        if (cyclops.click("Attack") && sleepUntil(Rs2Player::isInteracting, 3_000)) {
            antibanAfterAction();
        }
    }

    /**
     * Takes the new defender out to Kamfreena and uses it on her, clicking through what she says, then wears it.
     * The cyclopes also check the defenders you carry at the door, so if she won't take it after a few tries the
     * script carries on regardless.
     */
    private void show() {
        Defender defender = pendingShow;
        if (defender == null) {
            showDone = true;
            return;
        }
        if (inAnyRoom()) {
            exitRoom();
            return;
        }
        if (Rs2Dialogue.isInDialogue()) {
            String text = Rs2Dialogue.getDialogueText();
            String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
            if (lower.contains("released") || lower.contains("toddle in") || lower.contains("well done")
                    || lower.contains("highest defender") || lower.contains("lorelai")) {
                markShown(defender);
            }
            if (continueDialogue()) return;
            if (dialogueSince > 0 && System.currentTimeMillis() - dialogueSince > DIALOGUE_TIMEOUT_MS) {
                // Stuck on an option menu: close it and try again.
                Rs2Dialogue.clickOption("Bye", false);
            }
            return;
        }
        if (lastShown == defender) {
            wearBestDefender();
            pendingShow = null;
            showDone = true;
            return;
        }
        if (showAttempts >= MAX_SHOW_ATTEMPTS) {
            log.info("[WarriorsGuild] Kamfreena didn't take the {} after {} tries; the door checks it anyway",
                    defender, showAttempts);
            pendingShow = null;
            wearBestDefender();
            showDone = true;
            return;
        }

        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getPlane() != 2 || here.distanceTo(TOP_LOBBY) > 6) {
            Microbot.status = "Walking to Kamfreena";
            walk(TOP_LOBBY, 2);
            return;
        }
        Rs2NpcModel kamfreena = Microbot.getRs2NpcCache().query()
                .withId(NpcID.WARGUILD_KAMFREENA)
                .nearestOnClientThread(12);
        if (kamfreena == null) {
            Microbot.status = "Looking for Kamfreena";
            return;
        }
        if (!Rs2Inventory.hasItem(defender.itemId)) {
            if (Rs2Equipment.isWearing(defender.itemId)) {
                Microbot.status = "Taking off the " + defender + " to show it";
                Rs2Equipment.unEquip(defender.itemId);
                sleepUntil(() -> Rs2Inventory.hasItem(defender.itemId), 2_400);
            } else {
                log.info("[WarriorsGuild] The {} isn't on us any more; skipping Kamfreena", defender);
                pendingShow = null;
                showDone = true;
            }
            return;
        }

        showAttempts++;
        Microbot.status = "Showing Kamfreena the " + defender;
        log.info("[WarriorsGuild] Using the {} on Kamfreena (try {})", defender, showAttempts);
        if (Rs2Inventory.use(defender.itemId) && sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1_500)) {
            kamfreena.click("");
            sleepUntil(Rs2Dialogue::isInDialogue, 5_000);
        }
    }

    private void finish() {
        if (Rs2Dialogue.isInDialogue() && continueDialogue()) return;
        if (inAnyRoom()) {
            Microbot.status = "Leaving the cyclops room";
            exitRoom();
            return;
        }
        wearBestDefender();
        stop("Got the " + bestOwned + " — done");
    }

    // --- Defenders ---

    private void lootDefender(Rs2TileItemModel item) {
        String name = item.getName();
        if (Rs2Inventory.isFull()) {
            if (hasFood()) {
                Microbot.status = "Eating to make room for the " + name;
                eatFood();
            } else {
                Microbot.status = "Inventory full: can't pick up the " + name;
            }
            return;
        }
        Microbot.status = "Picking up the " + name;
        Defender tier = Defender.ofName(name);
        int before = Rs2Inventory.count(item.getId());
        if (!(item.pickup() && sleepUntil(() -> Rs2Inventory.count(item.getId()) > before, 5_000))) return;

        log.info("[WarriorsGuild] Picked up a {}", name);
        if (tier == null) return;
        stats.addDefender(tier);
        Defender previous = bestOwned;
        updateBestOwned(tier);
        if (previous == null || tier.ordinal() > previous.ordinal()) {
            log.info("[WarriorsGuild] New best defender: {} (next: {})", tier, Defender.nextAfter(tier));
            // Dragon is the last one; the basement needs nothing shown for it.
            if (config.showKamfreena() && tier != Defender.DRAGON) {
                pendingShow = tier;
            }
        }
    }

    /**
     * Equips the best defender in the inventory when it beats the one worn and the shield slot is free (or holds a
     * defender), and the weapon isn't two-handed. True when this tick was spent on it.
     */
    private boolean wearBestDefender() {
        if (!config.wearDefender()) return false;
        Rs2ItemModel best = Rs2Inventory.items(item -> Defender.of(item) != null)
                .max((a, b) -> Integer.compare(Defender.of(a).ordinal(), Defender.of(b).ordinal()))
                .orElse(null);
        if (best == null) return false;
        Defender tier = Defender.of(best);
        Rs2ItemModel shield = Rs2Equipment.get(EquipmentInventorySlot.SHIELD);
        Defender worn = Defender.of(shield);
        if (shield != null && worn == null) return false; // a real shield: leave it
        if (worn != null && worn.ordinal() >= tier.ordinal()) return false;
        if (twoHandedWeapon()) return false;
        Microbot.status = "Wearing the " + tier;
        if (Rs2Inventory.wield(best.getId())) {
            sleepUntil(() -> Rs2Equipment.isWearing(best.getId()), 2_400);
        }
        return true;
    }

    private boolean twoHandedWeapon() {
        Rs2ItemModel weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON);
        if (weapon == null) return false;
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            ItemStats stats = Microbot.getItemManager().getItemStats(weapon.getId());
            return stats != null && stats.getEquipment() != null && stats.getEquipment().isTwoHanded();
        }).orElse(false);
    }

    private void updateBestOwned(Defender found) {
        Defender best = Defender.max(bestOwned, Defender.max(found, carriedBest()));
        if (best != bestOwned) {
            bestOwned = best;
            log.info("[WarriorsGuild] Best defender owned: {}; the cyclopes drop {} next", best, Defender.nextAfter(best));
        }
    }

    /** The best defender in the inventory or worn. */
    private static Defender carriedBest() {
        Defender best = null;
        for (Rs2ItemModel item : Rs2Inventory.items(item -> Defender.of(item) != null).toArray(Rs2ItemModel[]::new)) {
            best = Defender.max(best, Defender.of(item));
        }
        return Defender.max(best, Defender.of(Rs2Equipment.get(EquipmentInventorySlot.SHIELD)));
    }

    private void markShown(Defender defender) {
        if (lastShown != defender) {
            lastShown = defender;
            log.info("[WarriorsGuild] Kamfreena has seen the {}", defender);
        }
    }

    private boolean reachedGoal() {
        Defender best = bestOwned;
        return best != null && best.ordinal() >= config.stopAt().ordinal();
    }

    /** True for the basement: the next defender is the dragon one. */
    private boolean wantedFloor() {
        return Defender.nextAfter(bestOwned).dropsInBasement();
    }

    private boolean defenderOnGround() {
        return findDefenderLoot() != null;
    }

    private Rs2TileItemModel findDefenderLoot() {
        if (!inAnyRoom()) return null;
        return Microbot.getRs2TileItemCache().query()
                .where(Rs2TileItemModel::isLootAble)
                .where(item -> !item.isDespawned() && Defender.ofName(item.getName()) != null)
                .nearestOnClientThread(20);
    }

    private Rs2NpcModel findCyclops(boolean basement) {
        Set<Integer> ids = basement ? BASEMENT_CYCLOPES : TOP_CYCLOPES;
        Player me = Microbot.getClient().getLocalPlayer();
        Rs2NpcModel attacker = Microbot.getRs2NpcCache().query()
                .where(npc -> ids.contains(npc.getId()) && !npc.isDead() && npc.getInteracting() == me)
                .nearestOnClientThread(20);
        if (attacker != null) return attacker;
        return Microbot.getRs2NpcCache().query()
                .where(npc -> ids.contains(npc.getId()) && !npc.isDead() && npc.getHealthRatio() != 0)
                .where(npc -> npc.getInteracting() == null || npc.getInteracting() == me)
                .where(npc -> Rs2Reachable.isReachableFromPlayer(npc.getWorldLocation()))
                .nearestOnClientThread(20);
    }

    // --- Armour & tokens ---

    private boolean needsArmour() {
        return collecting || tokens < MIN_ENTRY_TOKENS;
    }

    /** The configured set, or the best one in the inventory, or (with the bank open) the best one in the bank. */
    private ArmourSet chooseArmourSet() {
        ArmourSet configured = config.armourSet().set;
        if (configured != null) return configured;
        ArmourSet carried = ArmourSet.bestCarried();
        if (carried != null) return carried;
        if (!Rs2Bank.isOpen()) return armourSet;
        ArmourSet[] sets = ArmourSet.values();
        for (int i = sets.length - 1; i >= 0; i--) {
            boolean all = true;
            for (String piece : sets[i].pieces()) {
                if (!Rs2Inventory.hasItem(piece, true) && !Rs2Bank.hasItem(piece, true)) {
                    all = false;
                    break;
                }
            }
            if (all) return sets[i];
        }
        return null;
    }

    /** The animated armour attacking us (or that we're attacking). */
    private Rs2NpcModel ourArmour() {
        Player me = Microbot.getClient().getLocalPlayer();
        String target = me == null || me.getInteracting() == null ? null : me.getInteracting().getName();
        boolean attackingArmour = target != null && target.toLowerCase(Locale.ROOT).startsWith(ANIMATED_PREFIX);
        return Microbot.getRs2NpcCache().query()
                .where(npc -> npc.getName() != null && npc.getName().toLowerCase(Locale.ROOT).startsWith(ANIMATED_PREFIX))
                .where(npc -> !npc.isDead() && (npc.getInteracting() == me || attackingArmour))
                .nearestOnClientThread(10);
    }

    /** Our armour pieces and tokens on the floor of the armour room. */
    private Rs2TileItemModel findArmourLoot() {
        ArmourSet set = armourSet != null ? armourSet : chooseArmourSet();
        return Microbot.getRs2TileItemCache().query()
                .where(Rs2TileItemModel::isLootAble)
                .where(item -> !item.isDespawned() && item.isOwned())
                .where(item -> item.getId() == TOKEN_ID || (set != null && set.isPiece(item.getName())))
                .nearestOnClientThread(10);
    }

    private void pickUp(Rs2TileItemModel item) {
        String name = item.getName();
        boolean stacks = item.getId() == TOKEN_ID && Rs2Inventory.hasItem(TOKEN_ID);
        if (Rs2Inventory.isFull() && !stacks) {
            if (hasFood()) {
                Microbot.status = "Eating to make room for " + name;
                eatFood();
            } else {
                Microbot.status = "Inventory full: can't pick up " + name;
            }
            return;
        }
        Microbot.status = "Picking up " + name;
        int id = item.getId();
        int before = id == TOKEN_ID ? Rs2Inventory.itemQuantity(TOKEN_ID) : Rs2Inventory.count(id);
        if (item.pickup()) {
            sleepUntil(() -> (id == TOKEN_ID ? Rs2Inventory.itemQuantity(TOKEN_ID) : Rs2Inventory.count(id)) > before,
                    4_000);
        }
    }

    /** Follows token changes outside the bank: rises are earned, drops are what the cyclopes cost. */
    private void trackTokens() {
        int now = Rs2Inventory.itemQuantity(TOKEN_ID);
        tokens = now;
        if (lastTokens >= 0 && now != lastTokens && !Rs2Bank.isOpen()) {
            if (now > lastTokens) stats.addTokensEarned(now - lastTokens);
            else stats.addTokensSpent(lastTokens - now);
        }
        lastTokens = now;
    }

    // --- Where we are ---

    private static boolean inArmourRoom() {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getPlane() == 0 && p.getX() >= 2849 && p.getX() <= 2860
                && p.getY() >= 3534 && p.getY() <= 3545;
    }

    private void walkToArmourRoom() {
        Microbot.status = "Walking to the armour room";
        if (distanceTo(ANIMATOR_TILE) > 12 || Rs2Player.getWorldLocation().getPlane() != 0) {
            walk(ANIMATOR_TILE, 3);
            return;
        }
        // Close by but outside: the armoury door.
        Rs2TileObjectModel door = Microbot.getRs2TileObjectCache().query()
                .where(o -> o.getId() == ARMOURY_DOOR_IDS[0] || o.getId() == ARMOURY_DOOR_IDS[1])
                .nearestOnClientThread(10);
        if (door != null && door.click("Open")) {
            sleepUntil(WarriorsGuildScript::inArmourRoom, 4_000);
            return;
        }
        walk(ANIMATOR_TILE, 2);
    }

    /** Top floor: everything on floor 2 of the guild except Kamfreena's corner by the stairs. */
    private static boolean inTopRoom() {
        WorldPoint p = Rs2Player.getWorldLocation();
        if (p == null || p.getPlane() != 2) return false;
        boolean inGuild = p.getX() >= 2837 && p.getX() <= 2877 && p.getY() >= 3532 && p.getY() <= 3557;
        boolean inLobby = p.getX() <= 2846 && p.getY() <= 3545;
        return inGuild && !inLobby;
    }

    private static boolean inBasement() {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getPlane() == 0 && p.getX() >= 2880 && p.getX() <= 2950
                && p.getY() >= 9930 && p.getY() <= 10010;
    }

    /** Basement room: past Lorelai's door, so she (or the ladder) can't be walked to. */
    private static boolean inBasementRoom() {
        if (!inBasement()) return false;
        Rs2NpcModel lorelai = Microbot.getRs2NpcCache().query().withId(NpcID.WARGUILD_LORELAI).nearestOnClientThread(30);
        if (lorelai != null) return !Rs2Reachable.isReachableFromPlayer(lorelai.getWorldLocation());
        return !Rs2Reachable.isReachableFromPlayer(BASEMENT_LADDER);
    }

    private static boolean inRoom(boolean basement) {
        return basement ? inBasementRoom() : inTopRoom();
    }

    private static boolean inAnyRoom() {
        return inTopRoom() || inBasementRoom();
    }

    private static Rs2TileObjectModel topDoor() {
        return Microbot.getRs2TileObjectCache().query()
                .where(o -> o.getId() == TOP_DOOR_IDS[0] || o.getId() == TOP_DOOR_IDS[1])
                .where(o -> o.getWorldLocation() != null && o.getWorldLocation().getPlane() == 2)
                .nearestOnClientThread(15);
    }

    /** Leaves whichever cyclops room we're in through its door. */
    private void exitRoom() {
        if (continueDialogue()) return;
        boolean basement = inBasementRoom();
        Rs2TileObjectModel door = basement
                ? Microbot.getRs2TileObjectCache().query().withId(BASEMENT_DOOR_ID).nearestOnClientThread(30)
                : topDoor();
        if (door == null) {
            // Too far from the door to see it: walk towards it.
            Microbot.status = "Walking to the door";
            walk(basement ? BASEMENT_LADDER : TOP_INSIDE_DOOR, 2);
            return;
        }
        Microbot.status = "Leaving the cyclops room";
        if (door.click("Open")) {
            sleepUntil(() -> !inAnyRoom(), 5_000);
        }
    }

    // --- Helpers ---

    /** Clicks through an NPC's chat. True when this tick was spent on it. */
    private boolean continueDialogue() {
        if (!Rs2Dialogue.isInDialogue()) {
            dialogueSince = 0;
            return false;
        }
        if (dialogueSince == 0) dialogueSince = System.currentTimeMillis();
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            sleepUntil(() -> !Rs2Dialogue.hasContinue(), 1_800);
            return true;
        }
        return false;
    }

    private void walk(WorldPoint target, int distance) {
        if (Rs2Walker.walkWithState(target, distance) == WalkerState.UNREACHABLE) {
            log.warn("[WarriorsGuild] {} unreachable from {}", target, Rs2Player.getWorldLocation());
        }
    }

    private static int distanceTo(WorldPoint target) {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here == null ? Integer.MAX_VALUE : here.distanceTo(target);
    }

    private boolean needsBank() {
        return (config.foodAmount() > 0 && !hasFood() && Rs2Player.getHealthPercentage() < config.bankAtPercent())
                || armourMissing;
    }

    /** Everything goes in the bank except food, tokens, the armour set in use and the best defender. */
    private boolean isBankable(Rs2ItemModel item) {
        if (item.isFood() || item.getId() == TOKEN_ID) return false;
        ArmourSet set = armourSet != null ? armourSet
                : config.armourSet().set != null ? config.armourSet().set : ArmourSet.bestCarried();
        if (set != null && set.isPiece(item.getName())) return false;
        Defender tier = Defender.of(item);
        if (tier != null) {
            Defender carried = carriedBest();
            return carried != null && tier.ordinal() < carried.ordinal();
        }
        return true;
    }

    private boolean hasFood() {
        return !Rs2Inventory.getInventoryFood().isEmpty();
    }

    private int foodCount() {
        return Rs2Inventory.getInventoryFood().size();
    }

    private boolean eatIfNeeded() {
        if (!hasFood() || Rs2Player.getHealthPercentage() > config.eatAtPercent()) return false;
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return true;
        }
        eatFood();
        return true;
    }

    private void eatFood() {
        Microbot.status = "Eating";
        int before = foodCount();
        if (Rs2Player.useFood()) {
            sleepUntil(() -> foodCount() < before, 1_800);
        }
    }

    private void antibanAfterAction() {
        if (!config.antiban()) return;
        Rs2Antiban.actionCooldown();
        Rs2Antiban.takeMicroBreakByChance();
    }

    private void markIntentionalWait() {
        lastIntentionalWaitAt = System.currentTimeMillis();
    }

    private void stop(String reason) {
        log.info("[WarriorsGuild] Stopping: {}", reason);
        Microbot.status = reason;
        Microbot.stopPlugin(WarriorsGuildPlugin.class);
    }

    // --- Watchdog ---

    /** Logs out and stops when a state makes no progress (xp, movement, inventory or a chosen wait) for 2 minutes. */
    private boolean runWatchdog() {
        State state = getCurrentState();
        if (state == null) return false;
        long now = System.currentTimeMillis();
        String signature = progressSignature();
        boolean progressed = !signature.equals(progressSignature)
                || now - lastIntentionalWaitAt < INTENTIONAL_WAIT_GRACE_MS;
        progressSignature = signature;
        if (state != watchedState || progressed) {
            watchedState = state;
            lastProgressAt = now;
            return false;
        }
        if (now - lastProgressAt < STALL_RESTART_MS) return false;

        String reason = "no progress in " + state + " for " + (now - lastProgressAt) / 1000 + "s";
        log.warn("[WarriorsGuild] Watchdog: {}; logging out and stopping", reason);
        stats.addRecovery();
        StuckGuard.trip(reason);
        return true;
    }

    private String progressSignature() {
        long xp = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            long total = 0;
            for (Skill skill : WarriorsGuildStats.TRACKED_SKILLS) {
                total += Microbot.getClient().getSkillExperience(skill);
            }
            return total;
        }).orElse(0L);
        int inventory = Rs2Inventory.items().mapToInt(Rs2ItemModel::getQuantity).sum();
        return xp + "|" + Rs2Player.getWorldLocation() + "|" + inventory + "|" + Rs2Dialogue.isInDialogue();
    }

    // --- Lifecycle ---

    public boolean run(WarriorsGuildConfig config) {
        this.config = config;
        bestOwned = null;
        lastShown = null;
        pendingShow = null;
        armourSet = null;
        lastTokens = -1;
        tokens = 0;
        collecting = true;
        watchedState = null;
        progressSignature = "";
        stats.reset();

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyCombatSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Warrior's Guild", WarriorsGuildPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                trackTokens();
                updateBestOwned(null);
                if (runWatchdog()) return;
                if (eatIfNeeded()) return;
                if (Rs2AntibanSettings.actionCooldownActive) {
                    // The cooldown only counts down out of combat; wait it out briefly when idle, and cancel it in
                    // a fight or once it has run a few seconds.
                    long now = System.currentTimeMillis();
                    if (cooldownDeadline == 0) {
                        cooldownDeadline = now + ThreadLocalRandom.current().nextLong(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS);
                    }
                    if (!Rs2Combat.inCombat() && now < cooldownDeadline) {
                        Microbot.status = "Antiban pause";
                        return;
                    }
                    Rs2AntibanSettings.actionCooldownActive = false;
                }
                cooldownDeadline = 0;
                step();
            } catch (Throwable ex) {
                log.error("[WarriorsGuild] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(WarriorsGuildPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
