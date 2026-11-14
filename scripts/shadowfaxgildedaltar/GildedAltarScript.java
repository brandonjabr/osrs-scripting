package net.runelite.client.plugins.microbot.shadowfaxgildedaltar;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.poh.HouseAdvertisementBoard;
import net.runelite.client.plugins.microbot.util.poh.PohPresence;
import net.runelite.client.plugins.microbot.util.poh.data.HouseLocation;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Offers bones on a gilded altar in another player's house.
 * <p>
 * BANK → TELEPORT → ENTER_HOUSE → OFFER → LEAVE → BANK, or with a Rimmington house and "Unnote at Phials":
 * BANK → TELEPORT → UNNOTE → ENTER_HOUSE → OFFER → LEAVE → UNNOTE → … (the bank only when notes or coins run out).
 * <ul>
 *   <li><b>BANK</b>: nearest bank; keeps the Teleport to house tablets, fills the rest with bones.</li>
 *   <li><b>TELEPORT</b>: breaks a tablet with its "Outside" option, landing by the House Advertisement board.</li>
 *   <li><b>ENTER_HOUSE</b>: Visit-Last when a host is known, otherwise View the board and enter the preferred host,
 *       or the top listed house that is home and has a gilded altar.</li>
 *   <li><b>OFFER</b>: uses bones on the gilded altar; offering repeats by itself until interrupted. A house without
 *       a gilded altar is left and not picked again. Being kicked out (host left) goes back to ENTER_HOUSE.</li>
 *   <li><b>LEAVE</b>: out through the house's exit portal, then back to the bank (or to Phials).</li>
 *   <li><b>UNNOTE</b>: Phials, beside the Rimmington general store, swaps noted bones for real ones at 5 coins each.</li>
 * </ul>
 * Runs until the bank is out of the chosen bones or tablets.
 */
@Slf4j
@Singleton
public class GildedAltarScript extends StateMachineScript<GildedAltarScript.State> {

    enum State { BANK, TELEPORT, UNNOTE, ENTER_HOUSE, OFFER, LEAVE, FINISH }

    private static final int HOUSE_TAB = ItemID.POH_TABLET_TELEPORTTOHOUSE;
    private static final int[] GILDED_ALTARS = {
            ObjectID.POH_ALTAR_SARADOMIN_7, ObjectID.POH_ALTAR_ZAMORAK_7, ObjectID.POH_ALTAR_GUTHIX_7
    };
    // A house still without a gilded altar this long after arriving doesn't have one.
    private static final long ALTAR_SEARCH_MS = 10_000;
    // Offering repeats on its own; only re-click once bones stop going down for this long.
    private static final long OFFER_IDLE_MS = 3_500;
    private static final int ENTER_TIMEOUT_MS = 10_000;
    private static final long EMPTY_BOARD_RETRY_MS = 15_000;

    // Phials stands just outside the Rimmington general store, a few steps from the house portal.
    private static final String PHIALS = "Phials";
    private static final WorldPoint PHIALS_SPOT = new WorldPoint(2950, 3213, 0);
    private static final int UNNOTE_COST = 5;
    private static final int COINS = ItemID.COINS;

    @Inject
    private GildedAltarStats stats;

    private GildedAltarConfig config;

    // Flags read by transition guards.
    private boolean bankDone;
    // Phials can't unnote (out of coins): restock at the bank.
    private boolean needBank;
    private String outOfSupplies;
    private boolean finishing;

    // House choice.
    private String host = "";
    private boolean visitLastUsable;
    private final Set<String> badHosts = new HashSet<>();
    private long emptyBoardAt;

    // Offering.
    private long arrivedAt;
    private int lastBoneCount;
    private long lastOfferProgressAt;

    @Override
    protected State initialState() {
        if (inHouse()) return hasBones() ? State.OFFER : State.LEAVE;
        if (hasBones() && nearBoard()) return State.ENTER_HOUSE;
        if (phialsMode() && hasNotedBones() && nearBoard()) return State.UNNOTE;
        if (hasBones() && hasTab()) return State.TELEPORT;
        return State.BANK;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.BANK)
                        .when(() -> outOfSupplies != null, "outOfSupplies != null")
                        .because("Bank is out of bones or house tablets")
                        .goTo(State.FINISH),
                Transition.<State>from(State.BANK)
                        .when(() -> bankDone, "bankDone")
                        .because("Bones and a house tablet ready")
                        .goTo(State.TELEPORT),

                Transition.<State>from(State.TELEPORT)
                        .when(() -> nearBoard() && !inHouse() && !hasBones() && phialsMode() && hasNotedBones(),
                                "nearBoard() && !hasBones() && phialsMode() && hasNotedBones()")
                        .because("Outside the Rimmington portal with noted bones")
                        .goTo(State.UNNOTE),
                Transition.<State>from(State.TELEPORT)
                        .when(() -> nearBoard() && !inHouse(), "nearBoard() && !inHouse()")
                        .because("Outside a house portal by the advertisement board")
                        .goTo(State.ENTER_HOUSE),

                Transition.<State>from(State.UNNOTE)
                        .when(() -> needBank, "needBank")
                        .because("Not enough coins for Phials")
                        .goTo(State.BANK),
                Transition.<State>from(State.UNNOTE)
                        .when(() -> hasBones() && (Rs2Inventory.isFull() || !hasNotedBones()),
                                "hasBones() && (inventory full || no notes left)")
                        .because("Bones unnoted")
                        .goTo(State.ENTER_HOUSE),

                Transition.<State>from(State.ENTER_HOUSE)
                        .when(this::inHouse, "inHouse()")
                        .because("Entered a house")
                        .goTo(State.OFFER),

                Transition.<State>from(State.OFFER)
                        .when(() -> !hasBones(), "!hasBones()")
                        .because("All bones offered")
                        .goTo(State.LEAVE),
                Transition.<State>from(State.OFFER)
                        .when(() -> !inHouse(), "!inHouse()")
                        .because("Out of the house with bones left (host left, kicked, or no gilded altar)")
                        .goTo(State.ENTER_HOUSE),

                Transition.<State>from(State.LEAVE)
                        .when(() -> !inHouse() && phialsMode() && hasNotedBones(), "!inHouse() && phialsMode() && hasNotedBones()")
                        .because("Left the house; noted bones left for Phials")
                        .goTo(State.UNNOTE),
                Transition.<State>from(State.LEAVE)
                        .when(() -> !inHouse(), "!inHouse()")
                        .because("Left the house")
                        .goTo(State.BANK)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        bankDone = false;
        needBank = false;
        if (to == State.OFFER) {
            arrivedAt = System.currentTimeMillis();
            lastBoneCount = boneCount();
            lastOfferProgressAt = 0;
        }
        if (to == State.BANK || (to == State.UNNOTE && from == State.LEAVE)) {
            stats.addTrip();
        }
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case BANK:
                bank();
                break;
            case TELEPORT:
                teleport();
                break;
            case UNNOTE:
                unnote();
                break;
            case ENTER_HOUSE:
                enterHouse();
                break;
            case OFFER:
                offer();
                break;
            case LEAVE:
                leaveHouse();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    // --- States ---

    private void bank() {
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Walking to the bank";
            Rs2Bank.walkToBankAndUseBank();
            return;
        }
        if (!Rs2Bank.setWithdrawAsItem()) return;

        int bone = config.bones().itemId;
        int noted = notedBone();
        if (Rs2Inventory.items(i -> !isKept(i.getId())).findAny().isPresent()) {
            Microbot.status = "Depositing";
            Rs2Bank.depositAll(i -> !isKept(i.getId()));
            sleepUntil(() -> Rs2Inventory.items(i -> !isKept(i.getId())).findAny().isEmpty(), 3000);
            return;
        }

        if (!hasTab()) {
            if (Rs2Bank.count(HOUSE_TAB) <= 0) {
                outOfSupplies = "no Teleport to house tablets left";
                return;
            }
            Microbot.status = "Withdrawing house tablets";
            Rs2Bank.withdrawAll(HOUSE_TAB);
            sleepUntil(this::hasTab, 3000);
            return;
        }

        if (phialsMode()) {
            if (Rs2Bank.count(COINS) > 0) {
                Microbot.status = "Withdrawing coins for Phials";
                Rs2Bank.withdrawAll(COINS);
                sleepUntil(() -> !Rs2Bank.hasItem(COINS), 3000);
                return;
            }
            if (Rs2Bank.count(bone) > 0 && noted > 0) {
                Microbot.status = "Withdrawing noted " + config.bones().displayName;
                if (!Rs2Bank.setWithdrawAs(true)) return;
                Rs2Bank.withdrawAll(bone);
                sleepUntil(this::hasNotedBones, 3000);
                Rs2Bank.setWithdrawAs(false);
                return;
            }
            if (!hasBones() && !hasNotedBones()) {
                outOfSupplies = "no " + config.bones().displayName + " left";
                return;
            }
            if (Rs2Inventory.itemQuantity(COINS) < UNNOTE_COST && !hasBones()) {
                outOfSupplies = "not enough coins for Phials (5 per bone)";
                return;
            }
            stats.setSupplies(Rs2Bank.count(bone) + Rs2Inventory.itemQuantity(noted),
                    Rs2Inventory.itemQuantity(HOUSE_TAB) + Rs2Bank.count(HOUSE_TAB));
            Rs2Bank.closeBank();
            bankDone = true;
            return;
        }

        if (!Rs2Inventory.isFull()) {
            if (Rs2Bank.count(bone) > 0) {
                Microbot.status = "Withdrawing " + config.bones().displayName;
                Rs2Bank.withdrawAll(bone);
                sleepUntil(Rs2Inventory::isFull, 3000);
                return;
            }
            if (!hasBones()) {
                outOfSupplies = "no " + config.bones().displayName + " left";
                return;
            }
        }

        stats.setSupplies(Rs2Bank.count(bone), Rs2Inventory.itemQuantity(HOUSE_TAB) + Rs2Bank.count(HOUSE_TAB));
        Rs2Bank.closeBank();
        bankDone = true;
    }

    private void teleport() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (inHouse()) {
            // The tablet's default action took us inside our own house: walk out through the portal.
            exitThroughPortal();
            return;
        }
        if (nearBoard()) return;
        if (!hasTab()) {
            bankDone = false;
            return;
        }
        Microbot.status = "Teleporting outside the house";
        if (Rs2Inventory.interact(HOUSE_TAB, "Outside")) {
            sleepUntil(() -> nearBoard() || inHouse(), 8000);
        }
    }

    /** Uses the noted bones on Phials and picks "Exchange All". */
    private void unnote() {
        if (Rs2Dialogue.hasSelectAnOption()) {
            String option = exchangeAllOption();
            Microbot.status = "Unnoting bones";
            if (option != null && Rs2Dialogue.keyPressForDialogueOption(option, true)) {
                sleepUntil(this::hasBones, 3000);
            }
            return;
        }
        if (Rs2Dialogue.hasContinue()) {
            String text = String.valueOf(Rs2Dialogue.getDialogueText()).toLowerCase();
            if (text.contains("coins") && (text.contains("enough") || text.contains("afford"))) {
                needBank = true;
            }
            Rs2Dialogue.clickContinue();
            return;
        }
        if (Rs2Inventory.itemQuantity(COINS) < UNNOTE_COST) {
            log.info("[GildedAltar] Out of coins for Phials; restocking at the bank");
            needBank = true;
            return;
        }

        Rs2NpcModel phials = Microbot.getRs2NpcCache().query().withName(PHIALS).nearestReachableOnClientThread();
        if (phials == null || phials.getWorldLocation() == null
                || phials.getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) > 12) {
            Microbot.status = "Walking to Phials";
            Rs2Walker.walkTo(PHIALS_SPOT, 3);
            return;
        }

        Microbot.status = "Handing Phials the noted bones";
        if (Rs2Inventory.use(notedBone())
                && sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1500)
                && phials.click()) {
            sleepUntil(() -> Rs2Dialogue.hasSelectAnOption() || Rs2Dialogue.hasContinue(), 5000);
        }
    }

    /** "Exchange All: N coins" (or anything offering "all"), as shown when using notes on Phials. */
    private static String exchangeAllOption() {
        for (Widget option : Rs2Dialogue.getDialogueOptions()) {
            String text = Rs2UiHelper.stripColTags(option.getText());
            if (text != null && text.toLowerCase().contains("all")) return text;
        }
        return null;
    }

    private void enterHouse() {
        if (inHouse()) {
            // Already inside; the transition to OFFER happens next tick. Never work the board from in here.
            return;
        }
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        if (HouseAdvertisementBoard.isOpen()) {
            pickFromBoard();
            return;
        }

        if (config.useVisitLast() && visitLastUsable && !host.isEmpty()) {
            Microbot.status = "Visiting " + host + " again";
            HouseAdvertisementBoard.visitLast();
            if (!sleepUntil(this::inHouse, ENTER_TIMEOUT_MS)) {
                log.info("[GildedAltar] Visit-Last didn't get into {}'s house; choosing from the board", host);
                visitLastUsable = false;
            }
            return;
        }

        if (System.currentTimeMillis() - emptyBoardAt < EMPTY_BOARD_RETRY_MS) {
            Microbot.status = "No suitable house listed; checking again shortly";
            return;
        }
        Microbot.status = "Viewing the House Advertisement board";
        HouseAdvertisementBoard.view();
    }

    /** Preferred host if listed and home, else the top house that's home with a gilded altar. */
    private void pickFromBoard() {
        List<HouseAdvertisementBoard.Listing> listings = HouseAdvertisementBoard.listings();
        String preferred = config.preferredHost().trim();

        HouseAdvertisementBoard.Listing choice = null;
        if (!preferred.isEmpty()) {
            choice = listings.stream()
                    .filter(l -> l.canEnter() && l.owner.equalsIgnoreCase(preferred))
                    .findFirst().orElse(null);
        }
        if (choice == null) {
            choice = listings.stream()
                    .filter(l -> l.canEnter() && l.hasGildedAltar() && !badHosts.contains(l.owner.toLowerCase()))
                    .findFirst().orElse(null);
        }
        if (choice == null) {
            log.info("[GildedAltar] No enterable house with a gilded altar on the board: {}", listings);
            emptyBoardAt = System.currentTimeMillis();
            HouseAdvertisementBoard.close();
            return;
        }

        Microbot.status = "Entering " + choice.owner + "'s house";
        host = choice.owner;
        stats.setHost(host);
        if (HouseAdvertisementBoard.enter(choice) && sleepUntil(this::inHouse, ENTER_TIMEOUT_MS)) {
            log.info("[GildedAltar] Entered {}'s house", host);
            visitLastUsable = true;
        }
    }

    private void offer() {
        if (Rs2Dialogue.hasContinue()) {
            // e.g. a level-up message, which interrupts offering.
            Rs2Dialogue.clickContinue();
            return;
        }

        long now = System.currentTimeMillis();
        int count = boneCount();
        if (count < lastBoneCount) {
            stats.addBonesOffered(lastBoneCount - count);
            lastOfferProgressAt = now;
        }
        lastBoneCount = count;

        Rs2TileObjectModel altar = Microbot.getRs2TileObjectCache().query()
                .withIds(GILDED_ALTARS)
                .nearestOnClientThread();
        if (altar == null) {
            if (now - arrivedAt < ALTAR_SEARCH_MS) {
                Microbot.status = "Looking for the gilded altar";
                return;
            }
            log.info("[GildedAltar] {}'s house has no gilded altar; leaving and skipping it from now on", host);
            if (!host.isEmpty()) badHosts.add(host.toLowerCase());
            visitLastUsable = false;
            exitThroughPortal();
            return;
        }

        // Offering continues by itself; only click again once it has stopped.
        if (now - lastOfferProgressAt < OFFER_IDLE_MS || Rs2Player.isAnimating()) {
            Microbot.status = "Offering " + config.bones().displayName + " (" + count + " left)";
            return;
        }

        Microbot.status = "Using " + config.bones().displayName + " on the altar";
        int before = count;
        if (Rs2Inventory.use(config.bones().itemId)
                && sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1500)
                && altar.click()) {
            if (sleepUntil(() -> boneCount() < before, 6000)) {
                lastOfferProgressAt = System.currentTimeMillis();
            }
        }
    }

    private void leaveHouse() {
        Microbot.status = "Leaving the house";
        exitThroughPortal();
    }

    private void finish() {
        if (finishing) return;
        finishing = true;
        log.info("[GildedAltar] Stopping: {}", outOfSupplies);
        Microbot.status = "Done: " + outOfSupplies;
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
        if (config.logoutWhenDone()) {
            Rs2Player.logoutWithoutAutoLogin();
        }
        Microbot.stopPlugin(GildedAltarPlugin.class);
    }

    // --- Helpers ---

    /**
     * Leaves the house through its exit portal. In big houses the portal can be out of render range; then breaking a
     * house tablet with "Outside" gets out instead (landing by our own portal).
     */
    private void exitThroughPortal() {
        Rs2TileObjectModel portal = Microbot.getRs2TileObjectCache().query()
                .withId(ObjectID.POH_EXIT_PORTAL)
                .nearestOnClientThread();
        if (portal != null) {
            if (portal.click("Enter")) sleepUntil(() -> !inHouse(), 8000);
            return;
        }
        if (hasTab()) {
            Microbot.status = "Exit portal not in view; teleporting outside";
            if (Rs2Inventory.interact(HOUSE_TAB, "Outside")) sleepUntil(() -> !inHouse(), 8000);
        } else {
            Microbot.status = "Can't see the exit portal and no house tablet to leave with";
        }
    }

    /**
     * In a player-owned house. Not {@link PohPresence#isInHouse()}: that also needs the exit portal loaded in the
     * scene, which misses in bigger houses (Microbot's own walker notes this), so the script never noticed it had
     * arrived. Houses are instances, and nothing else this script visits is.
     */
    private boolean inHouse() {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getTopLevelWorldView().getScene().isInstance())
                .orElse(false);
    }

    private boolean nearBoard() {
        return HouseAdvertisementBoard.find() != null;
    }

    private boolean hasBones() {
        return Rs2Inventory.hasItem(config.bones().itemId);
    }

    /** Phials only works for a Rimmington house: he stands next to its portal. */
    private boolean phialsMode() {
        return config.usePhials() && HouseLocation.getHouseLocation() == HouseLocation.RIMMINGTON;
    }

    private int notedBone() {
        return ItemProcurer.notedId(config.bones().itemId);
    }

    private boolean hasNotedBones() {
        int noted = notedBone();
        return noted > 0 && Rs2Inventory.hasItem(noted);
    }

    /** Inventory items the bank trip keeps: tablets, bones (noted or not) and, for Phials, coins. */
    private boolean isKept(int itemId) {
        return itemId == HOUSE_TAB || itemId == config.bones().itemId || itemId == notedBone()
                || (phialsMode() && itemId == COINS);
    }

    private boolean hasTab() {
        return Rs2Inventory.hasItem(HOUSE_TAB);
    }

    private int boneCount() {
        return Rs2Inventory.count(config.bones().itemId);
    }

    // --- Lifecycle ---

    public boolean run(GildedAltarConfig config) {
        this.config = config;
        stats.reset();
        bankDone = false;
        outOfSupplies = null;
        finishing = false;
        host = "";
        visitLastUsable = false;
        badHosts.clear();
        emptyBoardAt = 0;

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyGeneralBasicSetup();
        }

        StuckGuard.attach("Shadowfax Gilded Altar", GildedAltarPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error would cancel the scheduled task without a log line.
                log.error("[GildedAltar] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(GildedAltarPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
