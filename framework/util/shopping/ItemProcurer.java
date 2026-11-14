package net.runelite.client.plugins.microbot.util.shopping;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.models.WikiPrice;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Makes sure the bank (plus whatever is already carried or worn) holds a list of {@link ItemNeed}s, buying any
 * shortfall on the Grand Exchange. Bought items are collected to the bank, so callers withdraw their own kit
 * afterwards.
 * <p>
 * Call {@link #tick()} once per script loop until it returns {@link Status#DONE} or {@link Status#FAILED}; each call
 * does one short step (walk, open, place offers, collect), so the caller keeps control between ticks.
 * <ul>
 *   <li>Only this procurer's own offers are collected or cancelled; other offers in the GE are left alone.</li>
 *   <li>Offers start at the wiki instant-buy price plus a markup and climb each time one sits unfilled.</li>
 *   <li>The whole shortfall is priced before anything is bought, so a budget or coin problem fails up front.</li>
 * </ul>
 */
@Slf4j
public class ItemProcurer {

    public enum Status { WORKING, DONE, FAILED }

    private enum Phase { CHECK, BUY, WAIT }

    private static final int STEP_MARKUP_PERCENT = 25;
    private static final int MAX_ATTEMPTS = 5;
    private static final long OFFER_WAIT_MS = 40_000;
    // The GE bank only counts as "the GE bank" within this many tiles of the GE.
    private static final int GE_RADIUS = 20;

    private final List<ItemNeed> needs;
    private final long budgetGp;
    private final int baseMarkupPercent;

    private Phase phase = Phase.CHECK;
    private final Map<Integer, Integer> attempts = new HashMap<>();
    // What still has to be bought, refreshed at every bank check: item id -> quantity.
    private Map<Integer, Integer> toBuy = new LinkedHashMap<>();
    // Our offers currently in the GE.
    private final Set<Integer> placed = new LinkedHashSet<>();
    // Reference price per item, looked up once: low-volume items (burnt meat) have no wiki price, and the
    // fallback lookup is several slow web requests that would otherwise repeat on every check.
    private final Map<Integer, Integer> referencePrices = new HashMap<>();
    private long waitStartedAt;
    private long coinsAtStart = -1;
    /** Most coins taken out of the bank for buying; anything costing more fails up front with the shortfall. */
    public static final long MAX_CARRIED_COINS = 100_000;

    @Getter
    private long gpSpent;
    @Getter
    private String status = "";
    @Getter
    private String failureReason;

    /**
     * @param needs             items (and quantities) the bank should end up holding
     * @param budgetGp          most gp this procurer may spend in total
     * @param baseMarkupPercent markup over the wiki instant-buy price for the first offer of each item
     */
    public ItemProcurer(List<ItemNeed> needs, long budgetGp, int baseMarkupPercent) {
        this.needs = ItemNeed.merge(needs);
        this.budgetGp = budgetGp;
        this.baseMarkupPercent = Math.max(0, baseMarkupPercent);
    }

    /** Offers are placed and being waited on; nothing needs doing until they fill or time out. */
    public boolean isWaitingForOffers() {
        return phase == Phase.WAIT;
    }

    public Status tick() {
        switch (phase) {
            case CHECK:
                return check();
            case BUY:
                return buy();
            case WAIT:
                return waitForOffers();
            default:
                return Status.WORKING;
        }
    }

    // --- Phases ---

    /** At the GE bank: work out the shortfall, take coins, and price everything before buying. */
    private Status check() {
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return Status.WORKING;
        }
        if (Rs2Bank.isOpen() && !atGrandExchange()) {
            // Some other bank (e.g. Lumbridge) is open; the shopping happens at the GE.
            Rs2Bank.closeBank();
            return Status.WORKING;
        }
        if (!Rs2Bank.isOpen()) {
            status = "Walking to the Grand Exchange bank";
            Rs2Bank.walkToBankAndUseBank(BankLocation.GRAND_EXCHANGE);
            return Status.WORKING;
        }

        long coinsNow = Rs2Inventory.itemQuantity(ItemID.COINS) + (long) Rs2Bank.count(ItemID.COINS);
        if (coinsAtStart < 0) coinsAtStart = coinsNow;
        gpSpent = Math.max(0, coinsAtStart - coinsNow);

        toBuy = shortfall();
        if (toBuy.isEmpty()) {
            status = "All items in stock";
            Rs2Bank.closeBank();
            return Status.DONE;
        }

        // Carry up to MAX_CARRIED_COINS for the offers, never the whole bank.
        int carried = Rs2Inventory.itemQuantity(ItemID.COINS);
        int take = (int) Math.min(MAX_CARRIED_COINS - carried, Rs2Bank.count(ItemID.COINS));
        if (take > 0) {
            Rs2Bank.withdrawX(ItemID.COINS, take);
            sleepUntil(() -> Rs2Inventory.itemQuantity(ItemID.COINS) > carried, 3000);
        }

        long cost = 0;
        for (Map.Entry<Integer, Integer> e : toBuy.entrySet()) {
            int id = e.getKey();
            String name = itemName(id);
            if (!isTradeable(id)) {
                return fail(name + " can't be bought on the Grand Exchange");
            }
            if (isMembersItem(id) && !onMembersWorld()) {
                return fail(name + " is members-only and can't be bought from a free-to-play world");
            }
            if (attempts.getOrDefault(id, 0) >= MAX_ATTEMPTS) {
                return fail("Offers for " + name + " never filled, even after raising the price " + MAX_ATTEMPTS + " times");
            }
            int price = offerPrice(id);
            if (price <= 0) {
                return fail("No Grand Exchange price for " + name);
            }
            cost += (long) price * e.getValue();
        }

        long coins = Rs2Inventory.itemQuantity(ItemID.COINS);
        if (cost > budgetGp - gpSpent) {
            return fail(String.format("Shopping needs about %,d gp but only %,d gp of the budget is left", cost, budgetGp - gpSpent));
        }
        if (cost > coins) {
            return fail(String.format("Shopping needs about %,d gp but you only have %,d gp", cost, coins));
        }

        log.info("[ItemProcurer] Buying {} item types (~{} gp): {}", toBuy.size(), cost, describe(toBuy));
        Rs2Bank.closeBank();
        phase = Phase.BUY;
        return Status.WORKING;
    }

    /** Places offers for whatever still needs buying, as far as the free GE slots allow. */
    private Status buy() {
        if (!openExchange()) return Status.WORKING;

        int free = Rs2GrandExchange.getAvailableSlotsCount();
        if (free <= 0 && placed.isEmpty()) {
            return fail("No free Grand Exchange slots; collect or cancel your other offers");
        }
        for (Map.Entry<Integer, Integer> e : toBuy.entrySet()) {
            if (free <= 0) break;
            int id = e.getKey();
            if (placed.contains(id)) continue;
            String name = itemName(id);
            int price = offerPrice(id);
            status = String.format("Buying %s x%d at %,d gp", name, e.getValue(), price);
            if (Rs2GrandExchange.buyItem(name, price, e.getValue())) {
                placed.add(id);
                free--;
            } else {
                log.warn("[ItemProcurer] Couldn't place an offer for {}", name);
                attempts.merge(id, 1, Integer::sum);
            }
        }
        waitStartedAt = System.currentTimeMillis();
        phase = Phase.WAIT;
        return Status.WORKING;
    }

    /** Collects our filled offers; reprices any that sit too long. */
    private Status waitForOffers() {
        if (!openExchange()) return Status.WORKING;

        Map<Integer, GrandExchangeOfferState> states = ourOfferStates();
        boolean anyBought = states.values().stream().anyMatch(s -> s == GrandExchangeOfferState.BOUGHT);
        boolean anyBuying = states.values().stream().anyMatch(s -> s == GrandExchangeOfferState.BUYING);

        if (anyBought) {
            status = "Collecting bought items";
            Rs2GrandExchange.collectAllToBank();
            sleepUntil(() -> ourOfferStates().values().stream().noneMatch(s -> s == GrandExchangeOfferState.BOUGHT), 3000);
            placed.removeIf(id -> !ourOfferStates().containsKey(id));
            return Status.WORKING;
        }

        if (!anyBuying) {
            // Everything placed has been collected (or vanished): recount at the bank.
            placed.clear();
            phase = Phase.CHECK;
            return Status.WORKING;
        }

        if (System.currentTimeMillis() - waitStartedAt > OFFER_WAIT_MS) {
            for (Map.Entry<Integer, GrandExchangeOfferState> e : states.entrySet()) {
                if (e.getValue() != GrandExchangeOfferState.BUYING) continue;
                String name = itemName(e.getKey());
                status = "Repricing " + name;
                if (Rs2GrandExchange.abortOffer(name, true)) {
                    attempts.merge(e.getKey(), 1, Integer::sum);
                }
            }
            placed.clear();
            phase = Phase.CHECK;
            return Status.WORKING;
        }

        status = "Waiting for offers to fill";
        return Status.WORKING;
    }

    // --- Counting ---

    /** Item id -> quantity still missing once bank, inventory (noted or not) and worn items are counted. */
    private Map<Integer, Integer> shortfall() {
        Map<Integer, Integer> missing = new LinkedHashMap<>();
        for (ItemNeed need : needs) {
            int have = ownedCount(need.getItemId(), true);
            if (have < need.getQuantity()) {
                missing.put(need.getItemId(), need.getQuantity() - have);
            }
        }
        return missing;
    }

    /** How many of an item the player holds: inventory (incl. noted), equipment and, optionally, the bank. */
    public static int ownedCount(int itemId, boolean includeBank) {
        int count = Rs2Inventory.itemQuantity(itemId);
        int noted = notedId(itemId);
        if (noted > 0) count += Rs2Inventory.itemQuantity(noted);
        if (Rs2Equipment.isWearing(itemId)) count += 1;
        if (includeBank) count += Rs2Bank.count(itemId);
        return count;
    }

    private Map<Integer, GrandExchangeOfferState> ourOfferStates() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Map<Integer, GrandExchangeOfferState> states = new HashMap<>();
            GrandExchangeOffer[] offers = Microbot.getClient().getGrandExchangeOffers();
            if (offers == null) return states;
            for (GrandExchangeOffer offer : offers) {
                if (offer != null && placed.contains(offer.getItemId())
                        && offer.getState() != GrandExchangeOfferState.EMPTY) {
                    states.put(offer.getItemId(), offer.getState());
                }
            }
            return states;
        }).orElse(new HashMap<>());
    }

    // --- Prices & item info ---

    /** Wiki instant-buy price (falling back to the GE guide price) plus this item's current markup. */
    private int offerPrice(int itemId) {
        int reference = referencePrices.computeIfAbsent(itemId, ItemProcurer::lookUpReferencePrice);
        if (reference <= 0) return -1;
        int markup = baseMarkupPercent + STEP_MARKUP_PERCENT * attempts.getOrDefault(itemId, 0);
        // Cheap items need a flat bump too, or +20% of 3 gp rounds to nothing.
        return (int) Math.min(Integer.MAX_VALUE, Math.max(reference + 2L, (long) Math.ceil(reference * (100 + markup) / 100.0)));
    }

    private static int lookUpReferencePrice(int itemId) {
        Microbot.status = "Looking up the price of " + itemName(itemId);
        WikiPrice wiki = Rs2GrandExchange.getRealTimePrices(itemId);
        if (wiki != null && wiki.buyPrice > 0 && wiki.buyPrice < Integer.MAX_VALUE) {
            return wiki.buyPrice;
        }
        long guide = ItemPrices.guidePrice(itemId);
        return guide > 0 && guide < Integer.MAX_VALUE ? (int) guide : -1;
    }

    private static boolean atGrandExchange() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.distanceTo(BankLocation.GRAND_EXCHANGE.getWorldPoint()) <= GE_RADIUS;
    }

    /** Unnoted copies carried in the inventory plus worn ones: what a quest NPC or step can actually use. */
    public static int carriedUsable(int itemId) {
        return Rs2Inventory.itemQuantity(itemId) + (Rs2Equipment.isWearing(itemId) ? 1 : 0);
    }

    private static ItemComposition definition(int itemId) {
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
                Microbot.getClient().getItemDefinition(itemId)).orElse(null);
    }

    public static String itemName(int itemId) {
        ItemComposition def = definition(itemId);
        return def != null ? def.getName() : "item " + itemId;
    }

    /** Whether the item stacks in one inventory slot (coins, runes, noted items). */
    public static boolean isStackable(int itemId) {
        ItemComposition def = definition(itemId);
        return def != null && def.isStackable();
    }

    public static boolean isTradeable(int itemId) {
        ItemComposition def = definition(itemId);
        return def != null && def.isTradeable();
    }

    private static boolean isMembersItem(int itemId) {
        ItemComposition def = definition(itemId);
        return def != null && def.isMembers();
    }

    public static boolean onMembersWorld() {
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
                Microbot.getClient().getWorldType().contains(WorldType.MEMBERS)).orElse(false);
    }

    public static int notedId(int itemId) {
        ItemComposition def = definition(itemId);
        if (def == null || def.getNote() == 799) return -1;
        return def.getLinkedNoteId() > 0 ? def.getLinkedNoteId() : -1;
    }

    // --- Helpers ---

    private boolean openExchange() {
        if (Rs2GrandExchange.isOpen()) return true;
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return false;
        }
        status = "Opening the Grand Exchange";
        if (!Rs2GrandExchange.openExchange()) {
            Rs2GrandExchange.walkToGrandExchange();
        }
        return false;
    }

    private Status fail(String reason) {
        failureReason = reason;
        status = reason;
        log.warn("[ItemProcurer] {}", reason);
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
        return Status.FAILED;
    }

    private static String describe(Map<Integer, Integer> items) {
        List<String> parts = new ArrayList<>();
        items.forEach((id, qty) -> parts.add(itemName(id) + " x" + qty));
        return String.join(", ", parts);
    }
}
