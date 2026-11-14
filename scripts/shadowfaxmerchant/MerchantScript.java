package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.shadowfaxgemcrab.GemCrabPlugin;
import net.runelite.client.plugins.microbot.shadowfaxmerchant.Opportunity.Strategy;
import net.runelite.client.plugins.microbot.shadowfaxmerchant.Position.Phase;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeRequest;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.market.GeTax;
import net.runelite.client.plugins.microbot.util.market.WikiPriceApi.ItemMapping;
import net.runelite.client.plugins.microbot.util.misc.AttemptGuard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.LoginManager;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Flips and dip-buys on the Grand Exchange with the opportunities {@link MarketScanner} finds.
 * <p>
 * SETUP → TRADE → FINISH (SCAN in scan-only mode).
 * <ul>
 *   <li><b>SETUP</b>: waits for prices, walks to the GE, takes coins up to the capital out of the bank and checks
 *       saved positions against the GE and the inventory (offers keep filling while logged out).</li>
 *   <li><b>TRADE</b>: one GE action per tick, most urgent first: collect finished offers, list bought items for
 *       sale, review stale offers (rebid, cut the ask, stop-loss), then use free slots and coins on the best new
 *       opportunity. Between actions it closes the GE and waits like a player watching offers.</li>
 *   <li><b>FINISH</b>: wind-down done (everything sold) or something broke; logged, plugin stops. Live offers are
 *       left as they are.</li>
 * </ul>
 * Coins and bought items stay in the inventory (bought items collect as notes), so selling never needs the bank.
 * Only offers this script placed are touched; anything else in the GE is left alone and its slot isn't used.
 */
@Slf4j
@Singleton
public class MerchantScript extends StateMachineScript<MerchantScript.State> {

    enum State { SETUP, TRADE, AWAY, SCAN, FINISH }

    /** Steps of a trip away while offers fill. */
    enum AwayPhase { LEAVING, AWAY, RETURNING }

    private static final int COINS = ItemID.COINS;
    private static final long MARKET_EVERY_MS = 20_000;
    private static final long REVIEW_EVERY_MS = 2 * 60_000;
    private static final long LOSS_COOLDOWN_MS = 2 * 60 * 60_000L;
    private static final int GE_RADIUS = 20;
    private static final int MAX_REBIDS = 2;
    private static final int MAX_MISSING_TRIES = 3;
    private static final double DIP_SHARE_OF_MAX_ITEM = 0.75;
    private static final int PROFILE_CANDIDATES = 40;
    private static final int PROFILES_PER_REFRESH = 8;

    @Inject
    private MerchantStats stats;
    @Inject
    private ConfigManager configManager;
    @Inject
    private Gson gson;

    private MerchantConfig config;
    private final MarketFeed feed = new MarketFeed();
    private ScheduledFuture<?> marketTask;

    private final List<Position> positions = new CopyOnWriteArrayList<>();
    private BuyLimits limits = new BuyLimits();
    private final Map<Integer, Long> cooldownUntil = new HashMap<>();
    private final AttemptGuard guard = new AttemptGuard(3, 4_000);
    private volatile boolean members = true;
    private boolean dirty;
    private boolean setupDone;
    private boolean reconciled;
    private String fatal;
    private long nextActionAt;
    private long idleSince;
    private boolean finishing;

    // Leaving while offers fill (see MerchantConfig.WaitMode).
    private static final int MAX_LEAVE_TRIES = 10;
    private static final long LOGIN_RETRY_MS = 20_000;
    private static final long SETTLE_AFTER_LOGIN_MS = 4_000;
    private long idleBeforeAwayMs;
    private boolean awayPlanned;
    private boolean returned;
    private volatile AwayPhase awayPhase = AwayPhase.LEAVING;
    private volatile MerchantConfig.WaitMode awayMode = MerchantConfig.WaitMode.STAY;
    private volatile long awayUntil;
    private long awayStartedAt;
    private int awayWorld;
    private int leaveTries;
    private long lastLoginAttemptAt;
    private long loggedBackInAt;
    // Set after a trip; the next bank visit puts away what the trip left in the inventory.
    private boolean tidyAfterTrip;

    /** The GE slots as read on the client thread. */
    private static final class Slot {
        final int itemId;
        final GrandExchangeOfferState state;
        final long price;
        final int total;
        final int filled;
        final long spent;

        Slot(GrandExchangeOffer o) {
            itemId = o == null ? 0 : o.getItemId();
            state = o == null ? GrandExchangeOfferState.EMPTY : o.getState();
            price = o == null ? 0 : o.getPrice();
            total = o == null ? 0 : o.getTotalQuantity();
            filled = o == null ? 0 : o.getQuantitySold();
            spent = o == null ? 0 : o.getSpent();
        }

        boolean empty() {
            return itemId == 0 || state == GrandExchangeOfferState.EMPTY;
        }

        boolean finished() {
            return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD
                    || state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL;
        }
    }

    /** Saved between sessions. */
    private static final class SavedState {
        List<Position> positions = new ArrayList<>();
        BuyLimits limits = new BuyLimits();
    }

    // --- State machine ---

    @Override
    protected State initialState() {
        return config.mode() == MerchantConfig.Mode.SCAN_ONLY ? State.SCAN : State.SETUP;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.SETUP)
                        .when(() -> fatal != null, "fatal != null")
                        .because("Setup failed")
                        .goTo(State.FINISH),
                Transition.<State>from(State.SETUP)
                        .when(() -> setupDone, "setupDone")
                        .because("At the GE with coins and prices")
                        .goTo(State.TRADE),
                Transition.<State>from(State.TRADE)
                        .when(() -> fatal != null, "fatal != null")
                        .because("Trading stopped")
                        .goTo(State.FINISH),
                Transition.<State>from(State.TRADE)
                        .when(() -> config.windDown() && positions.isEmpty(), "windDown && no positions")
                        .because("Wound down: everything sold")
                        .goTo(State.FINISH),
                Transition.<State>from(State.TRADE)
                        .when(() -> awayPlanned, "awayPlanned")
                        .because("Every offer is placed; leaving while they fill")
                        .goTo(State.AWAY),
                Transition.<State>from(State.AWAY)
                        .when(() -> returned, "returned")
                        .because("Back to check on the offers")
                        .goTo(State.SETUP)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        if (to == State.AWAY) {
            awayPlanned = false;
            returned = false;
            awayPhase = AwayPhase.LEAVING;
            awayMode = config.whileWaiting();
            leaveTries = 0;
            lastLoginAttemptAt = 0;
            loggedBackInAt = 0;
        }
        if (from == State.AWAY) {
            // Start over as on a fresh start: walk back, re-check the offers, top up coins.
            setupDone = false;
            reconciled = false;
            idleSince = 0;
            nextActionAt = 0;
            tidyAfterTrip = true;
            guard.reset();
        }
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case SETUP:
                setup();
                break;
            case TRADE:
                trade();
                break;
            case AWAY:
                away();
                break;
            case SCAN:
                Microbot.status = feed.isReady()
                        ? "Scan only: " + stats.opportunities().size() + " opportunities"
                        : "Loading prices";
                break;
            case FINISH:
                finish();
                break;
        }
    }

    // --- Setup ---

    private void setup() {
        if (!feed.isReady()) {
            Microbot.status = feed.getLastError() != null ? "Price API error: " + feed.getLastError() : "Loading prices";
            return;
        }
        if (!atGrandExchange()) {
            Microbot.status = "Walking to the Grand Exchange";
            if (!guard.attempt("walk to the Grand Exchange", AttemptGuard.playerSignature(), 20_000)) {
                fatal = guard.getFailure();
                StuckGuard.trip(fatal);
                return;
            }
            Rs2GrandExchange.walkToGrandExchange();
            return;
        }
        if (!reconciled) {
            reconcileOnStart();
            reconciled = true;
            return;
        }

        long wanted = config.capital() - capitalInUse();
        long carried = Rs2Inventory.itemQuantity(COINS);
        if (carried < wanted * 0.99 && !Rs2Bank.isOpen()) {
            if (Rs2GrandExchange.isOpen()) {
                Rs2GrandExchange.closeExchange();
                return;
            }
            Microbot.status = "Opening the bank for coins";
            if (!guard.attempt("open the bank", AttemptGuard.playerSignature())) {
                fatal = guard.getFailure();
                StuckGuard.trip(fatal);
                return;
            }
            Rs2Bank.openBank();
            return;
        }
        if (Rs2Bank.isOpen()) {
            if (tidyAfterTrip) {
                tidyAfterTrip = false;
                if (depositTripLeftovers()) return;
            }
            int inBank = Rs2Bank.count(COINS);
            long take = Math.min(inBank, wanted - carried);
            if (take > 0) {
                Microbot.status = String.format("Withdrawing %,d coins", take);
                Rs2Bank.withdrawX(COINS, (int) Math.min(Integer.MAX_VALUE, take));
                sleepUntil(() -> Rs2Inventory.itemQuantity(COINS) > carried, 3000);
            }
            Rs2Bank.closeBank();
            if (Rs2Inventory.itemQuantity(COINS) < wanted) {
                log.info("[Merchant] Trading with {} coins; the capital setting is {}", Rs2Inventory.itemQuantity(COINS), config.capital());
            }
        }
        if (Rs2Inventory.emptySlotCount() < 9) {
            Microbot.log("Shadowfax Merchant: free up some inventory space; bought items collect there (one slot each)");
        }
        guard.reset();
        setupDone = true;
    }

    /**
     * Saved positions whose offer slot is now empty finished while we were away (and someone collected them). The
     * inventory says how many items are left: more than we knew of means buys filled, fewer means sales did.
     */
    private void reconcileOnStart() {
        Slot[] slots = readSlots();
        for (Position p : positions) {
            if (!p.hasLiveOffer()) continue;
            Slot s = slots[p.slot];
            if (!s.empty() && s.itemId == p.itemId) continue;
            int inInventory = carried(p.itemId);
            if (p.phase == Phase.BUYING && inInventory > p.held()) {
                int extra = inInventory - p.held();
                p.bought += extra;
                p.spent += extra * p.offerPrice;
            } else if (p.phase == Phase.SELLING && inInventory < p.held()) {
                int gone = p.held() - inInventory;
                p.sold += gone;
                p.received += gone * p.offerPrice;
            }
            p.clearOffer();
            p.phase = p.held() > 0 ? Phase.SELLING : Phase.DONE;
            p.note = "reconciled after restart";
            dirty = true;
        }
        closeFinished();
    }

    // --- Trading ---

    private void trade() {
        Slot[] slots = readSlots();
        sync(slots);
        closeFinished();
        save();
        if (System.currentTimeMillis() < nextActionAt) return;

        if (collectFinished(slots)) return;
        if (listForSale(slots)) return;
        if (reviewOffers(slots)) return;
        if (!config.windDown() && rebidOrBuy(slots)) return;
        idle(slots);
    }

    /** Folds each live offer's fills into its position; an offer that has gone from its slot is over. */
    private void sync(Slot[] slots) {
        long now = System.currentTimeMillis();
        for (Position p : positions) {
            if (!p.hasLiveOffer()) continue;
            Slot s = slots[p.slot];
            if (!s.empty() && s.itemId == p.itemId) {
                if (p.phase == Phase.BUYING) {
                    int total = p.baseBought + s.filled;
                    int delta = total - p.bought;
                    if (delta > 0) {
                        limits.record(p.itemId, delta, now);
                        if (p.firstFilledAt == 0) p.firstFilledAt = now;
                        p.lastFillAt = now;
                        dirty = true;
                    }
                    p.bought = total;
                    p.spent = p.baseSpent + s.spent;
                } else if (p.phase == Phase.SELLING) {
                    int total = p.baseSold + s.filled;
                    if (total != p.sold) {
                        p.lastFillAt = now;
                        dirty = true;
                    }
                    p.sold = total;
                    p.received = p.baseReceived + s.spent;
                }
                continue;
            }
            // Collected (by us or by hand): the offer is over.
            p.clearOffer();
            if (p.phase == Phase.BUYING) {
                if (p.rebid && p.held() == 0) {
                    p.rebid = false;
                } else {
                    p.rebid = false;
                    p.phase = p.held() > 0 ? Phase.SELLING : Phase.DONE;
                    if (p.held() == 0 && p.note.isEmpty()) p.note = "buy never filled";
                }
            } else if (p.phase == Phase.SELLING && p.held() <= 0) {
                p.phase = Phase.DONE;
            }
            dirty = true;
        }
    }

    /** Logs and drops finished positions. */
    private void closeFinished() {
        for (Position p : positions) {
            if (p.phase != Phase.DONE || p.hasLiveOffer()) continue;
            p.closedAt = System.currentTimeMillis();
            positions.remove(p);
            if (p.bought > 0) {
                stats.recordClosed(p);
                journal(p);
                if (p.realisedProfit() < 0) cooldownUntil.put(p.itemId, p.closedAt + LOSS_COOLDOWN_MS);
                log.info("[Merchant] Closed {} {}: bought {} sold {} profit {} ({})", p.strategy, p.name, p.bought,
                        p.sold, p.realisedProfit(), p.note);
            }
            dirty = true;
        }
    }

    private boolean collectFinished(Slot[] slots) {
        boolean any = false;
        for (Position p : positions) {
            if (p.hasLiveOffer() && slots[p.slot].itemId == p.itemId && slots[p.slot].finished()) any = true;
        }
        if (!any) return false;
        if (Rs2Inventory.isFull()) {
            // Collecting with a full inventory makes the GE helper bank everything, coins included.
            fatal = "inventory is full; bought items have nowhere to go";
            return true;
        }
        if (!ensureGe()) return true;
        Microbot.status = "Collecting finished offers";
        Rs2GrandExchange.collectAllToInventory();
        sleepUntil(() -> {
            Slot[] now = readSlots();
            for (Position p : positions) {
                if (p.hasLiveOffer() && now[p.slot].itemId == p.itemId && now[p.slot].finished()) return false;
            }
            return true;
        }, 3000);
        acted();
        return true;
    }

    /** Puts bought items up for sale. */
    private boolean listForSale(Slot[] slots) {
        for (Position p : positions) {
            if (p.phase != Phase.SELLING || p.hasLiveOffer() || p.held() <= 0) continue;
            if (freeSlot(slots) < 0) return false;
            int have = carried(p.itemId);
            if (have <= 0) {
                if (++p.missingTries >= MAX_MISSING_TRIES) {
                    log.warn("[Merchant] {} x{} isn't in the inventory; forgetting the position", p.name, p.held());
                    Microbot.log("Shadowfax Merchant: " + p.name + " went missing from the inventory, stopped tracking it");
                    p.phase = Phase.DONE;
                    p.note = "items missing";
                    dirty = true;
                } else if (ensureGe()) {
                    Rs2GrandExchange.collectAllToInventory();
                    acted();
                }
                return true;
            }
            p.missingTries = 0;
            long price = sellPrice(p);
            int qty = Math.min(have, p.held());
            if (!ensureGe()) return true;
            Microbot.status = String.format("Selling %,d %s at %,d", qty, p.name, price);
            if (place(p, GrandExchangeAction.SELL, price, qty)) {
                p.note = p.note.startsWith("stop-loss") || p.note.startsWith("held") ? p.note : "";
            }
            acted();
            return true;
        }
        return false;
    }

    /**
     * Every couple of minutes per offer: buys that stopped filling are rebid or given up (keeping any partial fill
     * to sell), and sales that stall are cut toward the market, never under break-even until the hold time runs out.
     * A stop-loss or a buy whose margin vanished acts at once.
     */
    private boolean reviewOffers(Slot[] slots) {
        long now = System.currentTimeMillis();
        for (Position p : positions) {
            if (!p.hasLiveOffer() || now < p.reviewAt) continue;
            Slot s = slots[p.slot];
            if (s.empty() || s.itemId != p.itemId || s.finished()) continue;
            p.reviewAt = now + REVIEW_EVERY_MS;
            MarketScanner.Quote q = MarketScanner.quote(feed, p.itemId);

            if (p.phase == Phase.BUYING) {
                if (config.windDown()) return cancel(p, "winding down");
                long sinceFill = now - Math.max(p.offerPlacedAt, p.lastFillAt);
                boolean marginGone = q != null && GeTax.afterTax(p.itemId, q.ask) <= p.offerPrice;
                if (marginGone && p.strategy == Strategy.FLIP) return cancel(p, "margin gone");
                if (sinceFill < buyTimeoutMs(p)) continue;
                boolean partial = p.bought > p.baseBought;
                if (!partial && p.strategy == Strategy.FLIP && p.reprices < MAX_REBIDS && q != null
                        && q.bid > p.offerPrice && marginRatio(p.itemId, q) >= minRoi()) {
                    p.rebid = true;
                    p.reprices++;
                    return cancel(p, "rebidding at " + q.bid);
                }
                return cancel(p, partial ? "buy stalled, selling the part bought" : "buy never filled");
            }

            long target = sellPrice(p);
            boolean stale = now - Math.max(p.offerPlacedAt, p.lastFillAt) >= sellTimeoutMs(p);
            boolean emergency = p.note.startsWith("stop-loss");
            if (target < p.offerPrice && (stale || emergency)) {
                p.reprices++;
                return cancel(p, p.note.isEmpty() ? "ask cut to " + target : p.note);
            }
        }
        return false;
    }

    /** Places a new buy for a rebid position, else the best opportunity that fits a free slot and the coins. */
    private boolean rebidOrBuy(Slot[] slots) {
        if (freeSlot(slots) < 0) return false;
        long now = System.currentTimeMillis();
        for (Position p : positions) {
            if (p.phase != Phase.BUYING || p.hasLiveOffer()) continue;
            MarketScanner.Quote q = MarketScanner.quote(feed, p.itemId);
            int qty = Math.min(p.targetQty - p.bought, limitRemaining(p.itemId, now));
            qty = (int) Math.min(qty, coinsFree() / Math.max(1, q == null ? 1 : q.bid));
            if (q == null || qty <= 0 || marginRatio(p.itemId, q) < minRoi()) {
                p.phase = p.held() > 0 ? Phase.SELLING : Phase.DONE;
                p.note = "no longer worth buying";
                dirty = true;
                return false;
            }
            if (!ensureGe()) return true;
            Microbot.status = String.format("Rebidding %s at %,d", p.name, q.bid);
            place(p, GrandExchangeAction.BUY, q.bid, qty);
            acted();
            return true;
        }

        Opportunity pick = pickOpportunity(now);
        if (pick == null) return false;
        if (!ensureGe()) return true;
        int qty = (int) Math.min(Math.min(pick.quantity, coinsFree() / pick.buyPrice), limitRemaining(pick.itemId, now));
        Position p = Position.open(pick);
        p.targetQty = qty;
        positions.add(p);
        Microbot.status = String.format("Buying %,d %s at %,d (%s)", qty, pick.name, pick.buyPrice, pick.strategy);
        if (!place(p, GrandExchangeAction.BUY, pick.buyPrice, qty)) {
            positions.remove(p);
            cooldownUntil.put(pick.itemId, now + 10 * 60_000);
        }
        acted();
        return true;
    }

    private Opportunity pickOpportunity(long now) {
        Set<Integer> held = new HashSet<>();
        int dips = 0;
        for (Position p : positions) {
            held.add(p.itemId);
            if (p.strategy == Strategy.DIP) dips++;
        }
        long free = coinsFree();
        Set<String> excluded = excludedNames();
        for (Opportunity o : stats.opportunities()) {
            if (held.contains(o.itemId) || cooldownUntil.getOrDefault(o.itemId, 0L) > now) continue;
            if (excluded.contains(o.name.toLowerCase(Locale.ROOT))) continue;
            if (o.strategy == Strategy.DIP && dips >= config.maxDipSlots()) continue;
            if (!o.checked) continue;
            // Prices move; re-check against the freshest quote before spending.
            MarketScanner.Quote q = MarketScanner.quote(feed, o.itemId);
            if (q == null || q.bid > o.buyPrice * 1.01 + 1) continue;
            long qty = Math.min(o.quantity, Math.min(free / o.buyPrice, limitRemaining(o.itemId, now)));
            if (qty <= 0 || qty * o.marginEach < config.minProfit()) continue;
            return o;
        }
        return null;
    }

    private void idle(Slot[] slots) {
        long now = System.currentTimeMillis();
        if (idleSince == 0) {
            idleSince = now;
            // Varied each time, so leaving doesn't happen on a fixed beat.
            idleBeforeAwayMs = (long) (config.idleMinutesBeforeAway() * 60_000L
                    * ThreadLocalRandom.current().nextDouble(0.75, 1.5));
        }
        if (readyToLeave(slots, now)) {
            log.info("[Merchant] Nothing to do for {} min; going away ({})", (now - idleSince) / 60_000,
                    config.whileWaiting());
            awayPlanned = true;
            return;
        }
        int live = 0;
        for (Position p : positions) if (p.hasLiveOffer()) live++;
        Microbot.status = live == 0
                ? (config.windDown() ? "Winding down" : "Waiting for an opportunity")
                : "Watching " + live + (live == 1 ? " offer" : " offers");
        // Close the GE after a while with nothing to do, as a player looking away would.
        if (Rs2GrandExchange.isOpen() && now - idleSince > ThreadLocalRandom.current().nextLong(12_000, 40_000)) {
            Rs2GrandExchange.closeExchange();
        }
        nextActionAt = now + ThreadLocalRandom.current().nextLong(2_000, 5_000);
    }

    // --- Away while offers fill ---

    /**
     * Whether it's clearly a wait: nothing needed doing for a while, every bought item is listed, no rebid is pending
     * and none of our offers is waiting to be collected. Only then is it safe to leave the GE.
     */
    private boolean readyToLeave(Slot[] slots, long now) {
        if (config.whileWaiting() == MerchantConfig.WaitMode.STAY || config.windDown()) return false;
        if (now - idleSince < idleBeforeAwayMs) return false;
        for (Position p : positions) {
            if (!p.hasLiveOffer()) {
                if (p.held() > 0 || p.phase == Phase.BUYING) return false;
                continue;
            }
            if (slots[p.slot].finished()) return false;
        }
        return true;
    }

    private void away() {
        switch (awayPhase) {
            case LEAVING:
                leave();
                break;
            case AWAY:
                if (awayMode == MerchantConfig.WaitMode.GEM_CRAB && !Microbot.isPluginEnabled(GemCrabPlugin.class)
                        && System.currentTimeMillis() - awayStartedAt > 30_000) {
                    // The crab script stopped on its own (or was stopped): come back early.
                    log.info("[Merchant] Shadowfax Gem Crab isn't running any more; heading back early");
                    awayPhase = AwayPhase.RETURNING;
                } else if (System.currentTimeMillis() >= awayUntil) {
                    log.info("[Merchant] Time's up; heading back to check the offers");
                    awayPhase = AwayPhase.RETURNING;
                }
                break;
            case RETURNING:
                comeBack();
                break;
        }
    }

    private void leave() {
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return;
        }
        if (++leaveTries > MAX_LEAVE_TRIES) {
            log.warn("[Merchant] Couldn't get away after {} tries; staying at the GE", MAX_LEAVE_TRIES);
            returned = true;
            return;
        }
        if (awayMode == MerchantConfig.WaitMode.LOG_OUT) {
            awayWorld = Microbot.getClient().getWorld();
            Microbot.status = "Logging out while offers fill";
            if (Rs2Player.logoutWithoutAutoLogin() == Rs2Player.TerminalLogoutState.LOGGED_OUT) {
                startAwayTimer();
            }
            return;
        }

        // Gem crabs: bank the coins first (no reason to carry the capital around), then start the crab script.
        if (Rs2Inventory.itemQuantity(COINS) > 0) {
            if (!Rs2Bank.isOpen()) {
                Microbot.status = "Banking coins before leaving";
                Rs2Bank.openBank();
                sleepUntil(Rs2Bank::isOpen, 3000);
                return;
            }
            Rs2Bank.depositAll(COINS);
            sleepUntil(() -> Rs2Inventory.itemQuantity(COINS) == 0, 3000);
            return;
        }
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (Microbot.getPlugin(GemCrabPlugin.class) == null) {
            log.warn("[Merchant] Shadowfax Gem Crab isn't installed; staying at the GE");
            returned = true;
            return;
        }
        Microbot.status = "Off to kill Gemstone Crabs";
        Microbot.startPlugin(GemCrabPlugin.class);
        startAwayTimer();
    }

    private void startAwayTimer() {
        int lo = Math.min(config.awayMinMinutes(), config.awayMaxMinutes());
        int hi = Math.max(config.awayMinMinutes(), config.awayMaxMinutes());
        long minutes = ThreadLocalRandom.current().nextLong(lo * 60_000L, hi * 60_000L + 1);
        awayStartedAt = System.currentTimeMillis();
        awayUntil = awayStartedAt + minutes;
        awayPhase = AwayPhase.AWAY;
        log.info("[Merchant] Away ({}) for {} min", awayMode, minutes / 60_000);
    }

    private void comeBack() {
        long now = System.currentTimeMillis();
        if (awayMode == MerchantConfig.WaitMode.GEM_CRAB) {
            if (Microbot.isPluginEnabled(GemCrabPlugin.class)) {
                Microbot.stopPlugin(GemCrabPlugin.class);
                return;
            }
            returned = true;
            return;
        }

        if (Microbot.isLoggedIn()) {
            // Let the game finish loading before walking and opening things.
            if (loggedBackInAt == 0) loggedBackInAt = now;
            if (now - loggedBackInAt >= SETTLE_AFTER_LOGIN_MS) returned = true;
            return;
        }
        if (LoginManager.getActiveProfile() == null) {
            // Nothing to log in with: wait for the user to log in by hand.
            return;
        }
        if (now - lastLoginAttemptAt < LOGIN_RETRY_MS) return;
        lastLoginAttemptAt = now;
        log.info("[Merchant] Logging back in to world {}", awayWorld);
        if (awayWorld > 0) {
            LoginManager.login(awayWorld);
        } else {
            LoginManager.login();
        }
    }

    /**
     * Banks whatever a trip left in the inventory (gems, food, ...) so collected offers have room. Keeps coins, the
     * items we're trading and a pickaxe. True if anything was deposited.
     */
    private boolean depositTripLeftovers() {
        Set<Integer> keep = new HashSet<>();
        keep.add(COINS);
        for (Position p : positions) {
            keep.add(p.itemId);
            keep.add(ItemProcurer.notedId(p.itemId));
        }
        Predicate<Rs2ItemModel> leftover =
                item -> !keep.contains(item.getId())
                        && !(item.getName() != null && item.getName().toLowerCase(Locale.ROOT).endsWith("pickaxe"));
        if (Rs2Inventory.items(leftover).findAny().isEmpty()) return false;
        Microbot.status = "Banking what the trip left in the inventory";
        Rs2Bank.depositAll(leftover);
        sleepUntil(() -> Rs2Inventory.items(leftover).findAny().isEmpty(), 3000);
        return true;
    }

    /** What the overlay shows while away, since the crab script writes its own status. */
    String awayStatus() {
        if (getCurrentState() != State.AWAY) return null;
        long left = Math.max(0, awayUntil - System.currentTimeMillis());
        String where = awayMode == MerchantConfig.WaitMode.GEM_CRAB ? "Killing Gemstone Crabs" : "Logged out";
        switch (awayPhase) {
            case LEAVING:
                return awayMode == MerchantConfig.WaitMode.GEM_CRAB ? "Heading off to the crabs" : "Logging out";
            case AWAY:
                return String.format("%s; back in %d:%02d", where, left / 60_000, (left / 1000) % 60);
            default:
                return awayMode == MerchantConfig.WaitMode.LOG_OUT && LoginManager.getActiveProfile() == null
                        && !Microbot.isLoggedIn()
                        ? "Time's up: log in to continue (no AutoLogin profile)"
                        : "Heading back to the GE";
        }
    }

    // --- Pricing ---

    /**
     * Ask for held items: the market ask, never below break-even plus a coin, and for dips not below the planned
     * recovery price. Past the max hold time the floor goes; at twice the hold time, or when the stop-loss trips
     * (buyers under our cost by the stop-loss %), it sells into the current bids.
     */
    private long sellPrice(Position p) {
        MarketScanner.Quote q = MarketScanner.quote(feed, p.itemId);
        long cost = p.averageCost();
        long ask = q != null ? q.ask : p.plannedSell;
        long bid = q != null ? q.bid : Math.max(1, cost);
        long heldMs = System.currentTimeMillis() - (p.firstFilledAt > 0 ? p.firstFilledAt : p.openedAt);
        long maxHold = config.maxHoldHours() * 3_600_000L * (p.strategy == Strategy.DIP ? 4 : 1);

        if (q != null && cost > 0 && q.bid < cost * (1 - config.stopLossPercent() / 100.0)) {
            p.note = String.format("stop-loss: bids %,d vs cost %,d", q.bid, cost);
            return Math.max(1, bid - 1);
        }
        if (heldMs >= 2 * maxHold) {
            p.note = "held too long, selling at market";
            return Math.max(1, bid);
        }
        if (heldMs >= maxHold) {
            p.note = "held past max hold";
            return Math.max(1, ask);
        }
        long floor = p.breakEven() + 1;
        return p.strategy == Strategy.DIP ? Math.max(Math.max(ask, floor), p.plannedSell) : Math.max(ask, floor);
    }

    private long buyTimeoutMs(Position p) {
        return p.strategy == Strategy.DIP ? 45 * 60_000L : Math.max(10, config.targetMinutes() / 2) * 60_000L;
    }

    private long sellTimeoutMs(Position p) {
        return p.strategy == Strategy.DIP ? 3 * 60 * 60_000L : Math.max(15, config.targetMinutes()) * 60_000L;
    }

    private double minRoi() {
        return config.minMarginTenths() / 1000.0;
    }

    private static double marginRatio(int itemId, MarketScanner.Quote q) {
        return (GeTax.afterTax(itemId, q.ask) - q.bid) / (double) q.bid;
    }

    // --- GE actions ---

    private boolean place(Position p, GrandExchangeAction action, long price, int qty) {
        if (price <= 0 || price > Integer.MAX_VALUE || qty <= 0) return false;
        Set<Integer> boundSlots = new HashSet<>();
        for (Position other : positions) if (other.hasLiveOffer()) boundSlots.add(other.slot);

        boolean ok;
        if (action == GrandExchangeAction.BUY) {
            ok = Rs2GrandExchange.buyItem(p.name, (int) price, qty);
        } else {
            ok = Rs2GrandExchange.processOffer(GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.SELL)
                    .itemName(p.name)
                    .exact(true)
                    .quantity(qty)
                    .price((int) price)
                    .build());
        }
        if (!ok) {
            log.warn("[Merchant] Couldn't place {} offer for {} x{} at {}", action, p.name, qty, price);
            return false;
        }
        sleepUntil(() -> findSlot(readSlots(), p.itemId, price, boundSlots) >= 0, 3000);
        int slot = findSlot(readSlots(), p.itemId, price, boundSlots);
        if (slot < 0) {
            log.warn("[Merchant] Placed {} for {} but can't find its slot", action, p.name);
            return false;
        }
        p.slot = slot;
        p.offerPrice = price;
        p.offerQty = qty;
        p.offerPlacedAt = System.currentTimeMillis();
        p.reviewAt = p.offerPlacedAt + REVIEW_EVERY_MS;
        p.baseBought = p.bought;
        p.baseSpent = p.spent;
        p.baseSold = p.sold;
        p.baseReceived = p.received;
        dirty = true;
        log.info("[Merchant] {} {} x{} at {} in slot {}", action, p.name, qty, price, slot + 1);
        return true;
    }

    private boolean cancel(Position p, String why) {
        if (!ensureGe()) return true;
        p.note = why;
        Microbot.status = "Cancelling " + p.name + ": " + why;
        log.info("[Merchant] Cancelling {} offer for {}: {}", p.phase, p.name, why);
        Rs2GrandExchange.cancelSpecificOffers(List.of(GrandExchangeSlots.values()[p.slot]), false);
        acted();
        return true;
    }

    private static int findSlot(Slot[] slots, int itemId, long price, Set<Integer> taken) {
        for (int i = 0; i < slots.length; i++) {
            if (taken.contains(i) || slots[i].empty()) continue;
            if (slots[i].itemId == itemId && slots[i].price == price) return i;
        }
        return -1;
    }

    /** A slot that's empty and usable on this world (3 on free-to-play), or -1. */
    private int freeSlot(Slot[] slots) {
        int usable = members ? 8 : 3;
        for (int i = 0; i < Math.min(usable, slots.length); i++) {
            if (slots[i].empty()) return i;
        }
        return -1;
    }

    private Slot[] readSlots() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            GrandExchangeOffer[] offers = Microbot.getClient().getGrandExchangeOffers();
            Slot[] out = new Slot[8];
            for (int i = 0; i < 8; i++) out[i] = new Slot(offers != null && i < offers.length ? offers[i] : null);
            return out;
        }).orElseGet(() -> {
            Slot[] out = new Slot[8];
            for (int i = 0; i < 8; i++) out[i] = new Slot(null);
            return out;
        });
    }

    private boolean ensureGe() {
        idleSince = 0;
        if (Rs2GrandExchange.isOpen()) return true;
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return false;
        }
        if (!guard.attempt("open the Grand Exchange", AttemptGuard.playerSignature())) {
            fatal = guard.getFailure();
            StuckGuard.trip(fatal);
            return false;
        }
        Microbot.status = "Opening the Grand Exchange";
        if (!atGrandExchange()) {
            Rs2GrandExchange.walkToGrandExchange();
        } else {
            Rs2GrandExchange.openExchange();
        }
        return false;
    }

    /** Spaces actions out a little, like a person clicking through offers. */
    private void acted() {
        guard.reset();
        nextActionAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(1_200, 3_500);
    }

    // --- Capital ---

    private long capitalInUse() {
        long sum = 0;
        for (Position p : positions) sum += p.capitalInUse();
        return sum;
    }

    /** Coins we may still put into new buys: the capital left over, and no more than the coins carried. */
    private long coinsFree() {
        return Math.max(0, Math.min(Rs2Inventory.itemQuantity(COINS), config.capital() - capitalInUse()));
    }

    private int limitRemaining(int itemId, long now) {
        ItemMapping m = feed.getMapping().get(itemId);
        int limit = m != null && m.limit > 0 ? m.limit : 0;
        return limits.remaining(itemId, limit, now);
    }

    private static int carried(int itemId) {
        int count = Rs2Inventory.itemQuantity(itemId);
        int noted = ItemProcurer.notedId(itemId);
        if (noted > 0 && noted != itemId) count += Rs2Inventory.itemQuantity(noted);
        return count;
    }

    private static boolean atGrandExchange() {
        return Rs2Player.getWorldLocation() != null
                && Rs2Player.getWorldLocation().distanceTo(BankLocation.GRAND_EXCHANGE.getWorldPoint()) <= GE_RADIUS;
    }

    private Set<String> excludedNames() {
        Set<String> out = new HashSet<>();
        for (String s : config.exclude().split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // --- Market scanning (background) ---

    private void refreshMarket() {
        try {
            members = ItemProcurer.onMembersWorld() || !Microbot.isLoggedIn();
            if (!feed.refresh() && stats.scannedAt() > 0) return;
            if (!feed.isReady()) return;
            long now = System.currentTimeMillis();
            MarketScanner.Params p = new MarketScanner.Params();
            p.capital = config.capital();
            p.members = members;
            p.maxItemShare = config.maxItemPercent() / 100.0;
            p.dipShare = p.maxItemShare * DIP_SHARE_OF_MAX_ITEM;
            p.minRoi = minRoi();
            p.minFlipProfit = config.minProfit();
            p.targetMinutes = config.targetMinutes();
            p.dips = config.dips();
            p.minDipPct = config.minDipPercent() / 100.0;
            p.limitRemaining = id -> limitRemaining(id, now);
            List<Opportunity> found = MarketScanner.scan(feed, p);
            // Check the best unchecked flips against their last day, a few per refresh, then rank again.
            List<Integer> unchecked = new ArrayList<>();
            for (Opportunity o : found) {
                if (!o.checked && unchecked.size() < PROFILE_CANDIDATES) unchecked.add(o.itemId);
            }
            if (!unchecked.isEmpty()) {
                feed.refreshProfiles(unchecked, PROFILES_PER_REFRESH);
                found = MarketScanner.scan(feed, p);
            }
            stats.setScan(found, feed.getMapping().size());
            limits.prune(now);
        } catch (Throwable t) {
            log.warn("[Merchant] Market scan failed", t);
        }
    }

    // --- Persistence ---

    private void load() {
        positions.clear();
        limits = new BuyLimits();
        String json = config.savedState();
        if (json == null || json.isEmpty()) return;
        try {
            SavedState s = gson.fromJson(json, SavedState.class);
            if (s != null) {
                if (s.positions != null) positions.addAll(s.positions);
                if (s.limits != null && s.limits.windows != null) limits = s.limits;
            }
            if (!positions.isEmpty()) log.info("[Merchant] Resumed {} open positions", positions.size());
        } catch (Exception e) {
            log.warn("[Merchant] Couldn't read saved positions; starting fresh", e);
        }
    }

    private void save() {
        if (!dirty) return;
        dirty = false;
        SavedState s = new SavedState();
        s.positions = new ArrayList<>(positions);
        s.limits = limits;
        configManager.setConfiguration(MerchantConfig.GROUP, MerchantConfig.STATE_KEY, gson.toJson(s));
    }

    /** Appends a closed position to ~/.runelite/shadowfax-merchant/trades.csv for later analysis. */
    private void journal(Position p) {
        File dir = new File(RuneLite.RUNELITE_DIR, "shadowfax-merchant");
        File file = new File(dir, "trades.csv");
        try {
            boolean fresh = !file.exists();
            if (fresh && !dir.exists() && !dir.mkdirs()) return;
            try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                if (fresh) out.println("closed_at,item_id,item,strategy,bought,avg_buy,sold,avg_sell,tax,profit,hold_minutes,note");
                long avgSell = p.sold == 0 ? 0 : p.received / p.sold;
                long holdMin = (p.closedAt - (p.firstFilledAt > 0 ? p.firstFilledAt : p.openedAt)) / 60_000;
                out.printf(Locale.ROOT, "%d,%d,\"%s\",%s,%d,%d,%d,%d,%d,%d,%d,\"%s\"%n", p.closedAt, p.itemId,
                        p.name.replace("\"", "'"), p.strategy, p.bought, p.averageCost(), p.sold, avgSell, p.taxPaid(),
                        p.realisedProfit(), holdMin, p.note.replace("\"", "'"));
            }
        } catch (IOException e) {
            log.warn("[Merchant] Couldn't write the trade log", e);
        }
    }

    // --- Lifecycle ---

    private void finish() {
        if (finishing) return;
        finishing = true;
        if (fatal != null) {
            log.error("[Merchant] Stopping: {}", fatal);
            Microbot.log("Shadowfax Merchant stopped: " + fatal);
            Microbot.status = "Stopped: " + fatal;
        } else {
            Microbot.log(String.format("Shadowfax Merchant wound down: %,d gp profit this session", stats.realisedProfit()));
            Microbot.status = "Wound down";
        }
        dirty = true;
        save();
        Microbot.stopPlugin(MerchantPlugin.class);
    }

    // For the overlay.
    List<Position> positions() {
        return positions;
    }

    MarketFeed feed() {
        return feed;
    }

    public boolean run(MerchantConfig config) {
        this.config = config;
        stats.reset();
        load();
        setupDone = false;
        reconciled = false;
        fatal = null;
        finishing = false;
        nextActionAt = 0;
        idleSince = 0;
        awayPlanned = false;
        returned = false;
        tidyAfterTrip = false;
        cooldownUntil.clear();
        guard.reset();

        marketTask = scheduledExecutorService.scheduleWithFixedDelay(this::refreshMarket, 0, MARKET_EVERY_MS,
                TimeUnit.MILLISECONDS);
        StuckGuard.attach("Shadowfax Merchant", MerchantPlugin.class);
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                // Away and logged out is expected: that state only watches the clock and logs back in.
                if (!Microbot.isLoggedIn() && getCurrentState() != State.AWAY) return;
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error would cancel the scheduled task without a log line.
                log.error("[Merchant] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(MerchantPlugin.class);
        if (marketTask != null) marketTask.cancel(true);
        dirty = true;
        if (config != null) save();
        super.shutdown();
    }
}
