package net.runelite.client.plugins.microbot.shadowfaxectofuntus;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.walker.Rs2CharterShips;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.ectofuntus.Ectofuntus;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.safety.StuckGuard;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Offers bones at the Ectofuntus, inventory after inventory. Start by the Ectofuntus wearing a ghostspeak amulet
 * (the script won't start without one while claiming tokens is on), with bones, empty pots and empty buckets, or
 * with them in the bank.
 * <p>
 * GRIND → SLIME → WORSHIP → CLAIM → BANK → GRIND …, each skipped when there's nothing for it:
 * <ul>
 *   <li><b>GRIND</b>: up the tower, bones on the loader until the bones or the empty pots run out.</li>
 *   <li><b>SLIME</b>: down through the trapdoor, empty buckets on the pool of slime.</li>
 *   <li><b>WORSHIP</b>: back at the Ectofuntus, worship until the bonemeal or the slime runs out.</li>
 *   <li><b>CLAIM</b>: the ecto-tokens from a ghost disciple, wearing the ghostspeak amulet (optional).</li>
 *   <li><b>BANK</b>: breaks a Catherby teleport tablet and banks at Catherby: everything but the trip's items
 *       goes in; out come the charter coins, the tablets, and pots, buckets and bones (8 of each by default).</li>
 *   <li><b>SAIL</b>: walks to the Catherby docks and charters a ship to Port Phasmatys; GRIND then walks from
 *       the docks to the grinder.</li>
 *   <li><b>FINISH</b>: when the bank is out of bones, pots or buckets (or banking is off): optionally log out,
 *       then stop.</li>
 * </ul>
 * The shared steps live in {@link Ectofuntus}, which the questing script also uses for Animal Magnetism.
 */
@Slf4j
@Singleton
public class EctofuntusScript extends StateMachineScript<EctofuntusScript.State> {

    enum State { GRIND, SLIME, WORSHIP, CLAIM, BANK, SAIL, FINISH }

    // The trip: Catherby teleport tablet, Catherby bank, charter ship from the Catherby docks to Port Phasmatys.
    private static final int CATHERBY_TABLET = ItemID.LUNAR_TABLET_CATHERBY_TELEPORT;
    private static final WorldPoint CATHERBY_DOCKS = new WorldPoint(2792, 3414, 0);
    private static final String CHARTER_DESTINATION = "Port Phasmatys";
    private static final int MAX_CHARTER_TRIES = 3;
    private static final int MAX_TELEPORT_TRIES = 3;

    // Watchdog: a state making no progress (xp, movement, inventory, dialogue) this long means stuck.
    private static final long STALL_MS = 120_000;

    @Inject
    private EctofuntusStats stats;

    private EctofuntusConfig config;
    private final Ectofuntus ecto = new Ectofuntus();

    // Set when a step can't find what it needs (loader, pool, Ectofuntus, disciple): finish with this reason.
    private String failure;
    private boolean finishing;
    private int lastReady = -1;
    private int lastTokens = -1;

    // Banking.
    private boolean bankDone;
    private String outOfSupplies;
    private int trips;
    private boolean ghostspeakChecked;
    private int teleportTries;
    private int charterTries;

    private State watchedState;
    private String progressSignature = "";
    private long lastProgressAt;

    @Override
    protected State initialState() {
        // Supplies in hand but not in Morytania yet (e.g. started at the Catherby bank): sail first.
        if (!inMorytania() && config.bankAndRepeat()) {
            return Ectofuntus.canGrind() ? State.SAIL : State.BANK;
        }
        if (Ectofuntus.canGrind()) return State.GRIND;
        if (Ectofuntus.buckets() > 0 && Ectofuntus.bonemeal() > Ectofuntus.slime()) return State.SLIME;
        if (Ectofuntus.offeringsReady() > 0) return State.WORSHIP;
        if (wantClaim()) return State.CLAIM;
        return config.bankAndRepeat() ? State.BANK : State.FINISH;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.GRIND)
                        .when(() -> failure != null, "failure != null")
                        .because("Couldn't use the grinder")
                        .goTo(State.FINISH),
                Transition.<State>from(State.GRIND)
                        .when(this::grindDone, "!canGrind() && grinder idle")
                        .because("Bones or empty pots used up")
                        .goTo(State.SLIME),

                Transition.<State>from(State.SLIME)
                        .when(() -> failure != null, "failure != null")
                        .because("Couldn't use the pool of slime")
                        .goTo(State.FINISH),
                Transition.<State>from(State.SLIME)
                        .when(this::slimeDone, "no empty buckets (or slime for all the bonemeal)")
                        .because("Buckets filled")
                        .goTo(State.WORSHIP),

                Transition.<State>from(State.WORSHIP)
                        .when(() -> failure != null, "failure != null")
                        .because("Couldn't use the Ectofuntus")
                        .goTo(State.FINISH),
                Transition.<State>from(State.WORSHIP)
                        .when(() -> worshipDone() && wantClaim(), "offeringsReady() == 0 && wantClaim()")
                        .because("Everything offered; tokens to claim")
                        .goTo(State.CLAIM),
                Transition.<State>from(State.WORSHIP)
                        .when(() -> worshipDone() && config.bankAndRepeat(), "offeringsReady() == 0 && bankAndRepeat")
                        .because("Everything offered; banking for the next round")
                        .goTo(State.BANK),
                Transition.<State>from(State.WORSHIP)
                        .when(this::worshipDone, "offeringsReady() == 0")
                        .because("Everything offered")
                        .goTo(State.FINISH),

                Transition.<State>from(State.CLAIM)
                        .when(() -> failure != null, "failure != null")
                        .because("Couldn't claim the tokens")
                        .goTo(State.FINISH),
                Transition.<State>from(State.CLAIM)
                        .when(() -> Ectofuntus.unclaimed() == 0 && config.bankAndRepeat(),
                                "unclaimed() == 0 && bankAndRepeat")
                        .because("Tokens claimed; banking for the next round")
                        .goTo(State.BANK),
                Transition.<State>from(State.CLAIM)
                        .when(() -> Ectofuntus.unclaimed() == 0, "unclaimed() == 0")
                        .because("Tokens claimed")
                        .goTo(State.FINISH),

                Transition.<State>from(State.BANK)
                        .when(() -> outOfSupplies != null || failure != null, "outOfSupplies != null")
                        .because("The bank is out of supplies")
                        .goTo(State.FINISH),
                Transition.<State>from(State.BANK)
                        .when(() -> bankDone, "bankDone")
                        .because("Coins, tablets, bones, pots and buckets ready")
                        .goTo(State.SAIL),

                Transition.<State>from(State.SAIL)
                        .when(() -> failure != null, "failure != null")
                        .because("Couldn't charter the ship")
                        .goTo(State.FINISH),
                Transition.<State>from(State.SAIL)
                        .when(EctofuntusScript::inMorytania, "inMorytania()")
                        .because("Landed at Port Phasmatys")
                        .goTo(State.GRIND)
        );
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case GRIND:
                if (!ecto.grind()) failure = "no grinder loader found at the top of the tower";
                break;
            case SLIME:
                if (!ecto.fillBuckets()) failure = "no pool of slime found in the basement";
                break;
            case WORSHIP:
                if (!ecto.worship()) failure = "no Ectofuntus found";
                break;
            case CLAIM:
                if (!ecto.claim()) failure = "couldn't claim the tokens (no ghostspeak amulet or no disciple)";
                break;
            case BANK:
                bank();
                break;
            case SAIL:
                sail();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        if (to == State.BANK) {
            bankDone = false;
            teleportTries = 0;
            trips++;
        }
        if (to == State.SAIL) {
            charterTries = 0;
        }
    }

    // --- Banking ---

    /**
     * One action per tick: Catherby teleport, walk into the Catherby bank, bank everything but the trip's items,
     * then take out the charter coins, the tablets, and the same number of pots, buckets and bones.
     */
    private void bank() {
        if (!Rs2Bank.isOpen()) {
            if (!nearCatherby() && Rs2Inventory.hasItem(CATHERBY_TABLET) && teleportTries < MAX_TELEPORT_TRIES) {
                teleportTries++;
                Microbot.status = "Teleporting to Catherby";
                WorldPoint before = Rs2Player.getWorldLocation();
                if (Rs2Inventory.interact(CATHERBY_TABLET, "Break")) {
                    sleepUntil(() -> {
                        WorldPoint now = Rs2Player.getWorldLocation();
                        return now != null && !now.equals(before) && nearCatherby();
                    }, 8_000);
                }
                return;
            }
            if (!nearCatherby() && teleportTries == 0) {
                log.info("[Ectofuntus] No Catherby teleport tablet carried; walking to the Catherby bank");
                teleportTries = MAX_TELEPORT_TRIES;
            }
            Microbot.status = "Walking to the Catherby bank";
            Rs2Bank.walkToBankAndUseBank(BankLocation.CATHERBY);
            return;
        }
        if (!Rs2Bank.setWithdrawAsItem()) return;

        String bones = config.bones().trim();
        if (Rs2Inventory.items(i -> !keepWhenBanking(i, bones)).findAny().isPresent()) {
            Microbot.status = "Banking leftovers";
            Rs2Bank.depositAllExcept(i -> keepWhenBanking(i, bones));
            sleepUntil(() -> Rs2Inventory.items(i -> !keepWhenBanking(i, bones)).findAny().isEmpty(), 3_000);
            return;
        }

        // Coins for the charter (one slot), then the tablets for the next trip back (one slot).
        int coinsNeeded = Math.max(0, config.charterCoins());
        int coins = Rs2Inventory.itemQuantity(ItemID.COINS);
        if (coins < coinsNeeded) {
            int inBank = Rs2Bank.count(ItemID.COINS);
            if (coins + inBank < coinsNeeded) {
                outOfSupplies = "not enough coins for the charter (" + (coins + inBank) + " of " + coinsNeeded + ")";
                return;
            }
            withdraw(ItemID.COINS, "coins for the charter", coinsNeeded - coins);
            return;
        }
        if (!Rs2Inventory.hasItem(CATHERBY_TABLET)) {
            if (Rs2Bank.count(CATHERBY_TABLET) <= 0) {
                outOfSupplies = "no Catherby teleport tablets left";
                return;
            }
            Microbot.status = "Withdrawing Catherby teleport tablets";
            Rs2Bank.withdrawAll(CATHERBY_TABLET);
            sleepUntil(() -> Rs2Inventory.hasItem(CATHERBY_TABLET), 3_000);
            return;
        }

        int pots = Ectofuntus.pots();
        int buckets = Ectofuntus.buckets();
        int carriedBones = Rs2Inventory.count(bones, true);
        int room = Rs2Inventory.emptySlotCount() + pots + buckets + carriedBones;
        int target = Math.min(Math.max(1, config.loadsPerTrip()), room / 3);
        int bankPots = Rs2Bank.count(ItemID.POT_EMPTY);
        int bankBuckets = Rs2Bank.count(ItemID.BUCKET_EMPTY);
        int bankBones = Rs2Bank.count(bones, true);
        int loads = Math.min(target, Math.min(pots + bankPots, Math.min(buckets + bankBuckets, carriedBones + bankBones)));
        if (loads <= 0) {
            outOfSupplies = carriedBones + bankBones == 0 ? "no " + bones + " left in the bank"
                    : pots + bankPots == 0 ? "no empty pots left" : buckets + bankBuckets == 0 ? "no empty buckets left"
                    : "no room in the inventory";
            return;
        }
        if (loads < target) {
            log.info("[Ectofuntus] Only {} offerings' worth left in the bank (wanted {})", loads, target);
        }

        if (pots < loads) {
            withdraw(ItemID.POT_EMPTY, "empty pots", loads - pots);
            return;
        }
        if (buckets < loads) {
            withdraw(ItemID.BUCKET_EMPTY, "empty buckets", loads - buckets);
            return;
        }
        if (carriedBones < loads) {
            Microbot.status = "Withdrawing " + bones;
            Rs2Bank.withdrawX(bones, loads - carriedBones, true);
            sleepUntil(() -> Rs2Inventory.count(bones, true) > carriedBones, 3_000);
            return;
        }

        log.info("[Ectofuntus] Trip {}: {} offerings, {} coins; sailing to Port Phasmatys", trips, loads,
                Rs2Inventory.itemQuantity(ItemID.COINS));
        Rs2Bank.closeBank();
        ecto.reset();
        bankDone = true;
    }

    /** Walks to the Catherby docks and charters a ship to Port Phasmatys; GRIND takes over once landed. */
    private void sail() {
        if (inMorytania()) return;
        if (Rs2Inventory.itemQuantity(ItemID.COINS) < config.charterCoins()) {
            failure = "not enough coins carried for the charter";
            return;
        }
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getPlane() != 0 || here.distanceTo(CATHERBY_DOCKS) > 6) {
            Microbot.status = "Walking to the Catherby docks";
            Rs2Walker.walkTo(CATHERBY_DOCKS, 3);
            return;
        }
        if (charterTries >= MAX_CHARTER_TRIES) {
            failure = "chartering a ship to Port Phasmatys failed " + charterTries + " times";
            return;
        }
        charterTries++;
        int coins = Rs2Inventory.itemQuantity(ItemID.COINS);
        if (Rs2CharterShips.charter(CHARTER_DESTINATION)) {
            sleepUntil(EctofuntusScript::inMorytania, 20_000);
            if (inMorytania()) {
                log.info("[Ectofuntus] Landed at Port Phasmatys (charter cost {} coins)",
                        coins - Rs2Inventory.itemQuantity(ItemID.COINS));
            }
        }
    }

    private static boolean nearCatherby() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.getX() >= 2780 && here.getX() <= 2830 && here.getY() >= 3400
                && here.getY() <= 3470;
    }

    /**
     * East of the Salve: Port Phasmatys, its docks (and the ship moored there), the Ectofuntus, and the slime
     * basement under it.
     */
    private static boolean inMorytania() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getX() < 3400) return false;
        return (here.getY() >= 3400 && here.getY() < 4000) || (here.getY() >= 9800 && here.getY() < 10000);
    }

    private void withdraw(int itemId, String name, int amount) {
        int before = Rs2Inventory.count(itemId);
        Microbot.status = "Withdrawing " + name;
        Rs2Bank.withdrawX(itemId, amount);
        sleepUntil(() -> Rs2Inventory.count(itemId) > before, 3_000);
    }

    /** Items kept in the inventory at the bank: the trip's coins and tablets, the next round's supplies, tokens. */
    private static boolean keepWhenBanking(Rs2ItemModel item, String bones) {
        int id = item.getId();
        return id == ItemID.POT_EMPTY || id == ItemID.BUCKET_EMPTY || id == ItemID.ECTOTOKEN
                || id == ItemID.COINS || id == CATHERBY_TABLET
                || id == ItemID.ECTOPHIAL || id == ItemID.ECTOPHIAL_EMPTY || id == ItemID.AMULET_OF_GHOSTSPEAK
                || id == ItemID.AMULET_OF_GHOSTSPEAK_ENCHANTED
                || (item.getName() != null && item.getName().equalsIgnoreCase(bones) && !item.isNoted());
    }

    // --- Ghostspeak ---

    /**
     * Before anything else: with token claiming on, a ghostspeak amulet must be worn (one carried is put on). Without
     * one, the script stops right away instead of offering bones whose tokens it can't collect.
     */
    private boolean ghostspeakReady() {
        if (ghostspeakChecked || !config.claimTokens()) return true;
        if (Ectofuntus.wearingGhostspeak()) {
            ghostspeakChecked = true;
            return true;
        }
        int amulet = Ectofuntus.carriedGhostspeak();
        if (amulet != -1) {
            Microbot.status = "Putting on the ghostspeak amulet";
            Rs2Inventory.wear(amulet);
            sleepUntil(Ectofuntus::wearingGhostspeak, 2_400);
            return false;
        }
        String reason = "Wear a ghostspeak amulet to collect the ecto-tokens (or turn off \"Claim ecto-tokens\")";
        log.warn("[Ectofuntus] Not starting: {}", reason);
        Microbot.status = reason;
        Microbot.stopPlugin(EctofuntusPlugin.class);
        return false;
    }

    // --- Guards ---

    private boolean grindDone() {
        // The last bone's cycle ends after it leaves the inventory: wait it out so its bonemeal isn't left behind.
        return !Ectofuntus.canGrind() && !ecto.isGrinding();
    }

    private boolean slimeDone() {
        // Stop filling once there's slime for every pot of bonemeal, so leftover buckets aren't a reason to wait.
        return (Ectofuntus.buckets() == 0 || Ectofuntus.slime() >= Ectofuntus.bonemeal() && Ectofuntus.bonemeal() > 0)
                && !ecto.isFillingBuckets();
    }

    private boolean worshipDone() {
        return Ectofuntus.offeringsReady() == 0 && !ecto.isWorshipping();
    }

    private boolean wantClaim() {
        return config.claimTokens() && Ectofuntus.unclaimed() > 0 && Ectofuntus.hasGhostspeak();
    }

    // --- Finish ---

    private void finish() {
        if (finishing) return;
        finishing = true;
        String reason = failure != null ? "Stopped: " + failure
                : outOfSupplies != null ? "Done: " + outOfSupplies
                : "Done: everything carried has been offered";
        log.info("[Ectofuntus] {} ({} worships over {} bank trips, {} tokens claimed, {} unclaimed)", reason,
                stats.worships(), trips, stats.tokensClaimed(), Ectofuntus.unclaimed());
        Microbot.status = reason;
        if (config.logoutWhenDone()) {
            Rs2Player.logoutWithoutAutoLogin();
        }
        Microbot.stopPlugin(EctofuntusPlugin.class);
    }

    // --- Tracking ---

    /** Counts worships (bonemeal + slime pairs used up) and tokens arriving in the inventory. */
    private void track() {
        int ready = Ectofuntus.offeringsReady();
        if (getCurrentState() == State.WORSHIP && lastReady >= 0 && ready < lastReady) {
            stats.addWorships(lastReady - ready);
        }
        lastReady = ready;
        int tokens = Ectofuntus.tokens();
        if (lastTokens >= 0 && tokens > lastTokens) stats.addTokensClaimed(tokens - lastTokens);
        lastTokens = tokens;
        stats.refreshSupplies();
    }

    // --- Watchdog ---

    /** Logs out and stops when a state makes no progress for 2 minutes. */
    private boolean runWatchdog() {
        State state = getCurrentState();
        if (state == null || state == State.FINISH) return false;
        long now = System.currentTimeMillis();
        String signature = progressSignature();
        if (state != watchedState || !signature.equals(progressSignature)) {
            watchedState = state;
            progressSignature = signature;
            lastProgressAt = now;
            return false;
        }
        if (now - lastProgressAt < STALL_MS) return false;
        String reason = "no progress in " + state + " for " + (now - lastProgressAt) / 1000 + "s";
        log.warn("[Ectofuntus] Watchdog: {}; logging out and stopping", reason);
        StuckGuard.trip(reason);
        return true;
    }

    private String progressSignature() {
        int xp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.PRAYER))
                .orElse(0);
        int inventory = Rs2Inventory.items().mapToInt(Rs2ItemModel::getQuantity).sum()
                + 31 * Rs2Inventory.items().mapToInt(Rs2ItemModel::getId).sum();
        return xp + "|" + Rs2Player.getWorldLocation() + "|" + inventory + "|" + Ectofuntus.unclaimed()
                + "|" + Rs2Dialogue.isInDialogue();
    }

    // --- Lifecycle ---

    public boolean run(EctofuntusConfig config) {
        this.config = config;
        stats.reset();
        ecto.reset();
        failure = null;
        finishing = false;
        lastReady = -1;
        lastTokens = -1;
        bankDone = false;
        outOfSupplies = null;
        trips = 0;
        ghostspeakChecked = false;
        watchedState = null;
        progressSignature = "";

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyGeneralBasicSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Ectofuntus", EctofuntusPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                if (!ghostspeakReady()) return;
                track();
                if (runWatchdog()) return;
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error would cancel the scheduled task without a log line.
                log.error("[Ectofuntus] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(EctofuntusPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
