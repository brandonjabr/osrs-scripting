package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientInt;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lays the whole bank out tab by tab and row by row (see {@link BankPlan} and {@link BankItemClassifier}).
 * Placeholders are organized like the items they stand for.
 * <p>
 * OPEN_BANK → SCAN → COLLAPSE → BUILD → VERIFY → FINISH
 * <ul>
 *   <li><b>SCAN</b>: lists every bank slot (items, placeholders, fillers) and plans the finished layout before
 *       anything moves. The plan is written to the log row by row. If the bank already matches it, stops there.</li>
 *   <li><b>COLLAPSE</b>: empties every tab into the main tab with its own "Collapse tab" option, last tab first.</li>
 *   <li><b>BUILD</b>: drags each planned item, in order, onto its tab (the first one onto the "new tab" slot);
 *       dropping onto a tab adds to its end, so each tab is built in its final order.</li>
 *   <li><b>VERIFY</b>: rereads every tab and swaps anything out of place into position (swap mode).</li>
 * </ul>
 * If the bank gets closed it is reopened and the same phase carries on.
 */
@Slf4j
@Singleton
public class BankOrganizerScript extends StateMachineScript<BankOrganizerScript.State> {

    enum State { OPEN_BANK, SCAN, COLLAPSE, BUILD, VERIFY, FINISH }

    // In Rs2Bank.getTabs(), the main tab is dynamic child 10 and tab k is child 10 + k; the "new tab" slot follows
    // the last real tab.
    private static final int MAIN_TAB_INDEX = 10;
    // Item count of bank tab 1 (placeholders and fillers included); tabs 2-9 are the next varbits.
    private static final int FIRST_TAB_COUNT_VARBIT = VarbitID.BANK_TAB_1;
    private static final int MAX_TABS = 9;
    private static final int MAX_DRAG_ATTEMPTS = 3;
    private static final int MAX_SWAP_ATTEMPTS = 4;

    /** One bank slot as read from the container. Bank fillers are left where they are. */
    static final class Slot {
        final int index;
        final int itemId; // the real item id, also for placeholders
        final boolean placeholder;
        final boolean filler;

        Slot(int index, int itemId, boolean placeholder, boolean filler) {
            this.index = index;
            this.itemId = itemId;
            this.placeholder = placeholder;
            this.filler = filler;
        }

        int key() {
            return filler ? -1 : itemId;
        }
    }

    @Inject
    private BankOrganizerStats stats;

    private List<BankPlan.Listed> listing = new ArrayList<>();
    private BankPlan plan;
    private State resume = State.SCAN;

    // BUILD: a cursor over the plan; started[t] = slots placed in plan tab t (its real tab exists once > 0).
    private int cursorTab;
    private int cursorEntry;
    private int[] started = new int[0];
    private int dragAttempts;

    // VERIFY
    private int verifyTab;
    private final Map<String, Integer> swapAttempts = new HashMap<>();

    private boolean finishing;

    @Override
    protected State initialState() {
        return State.OPEN_BANK;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        List<Transition<State>> transitions = new ArrayList<>();
        for (State target : new State[]{State.SCAN, State.COLLAPSE, State.BUILD, State.VERIFY}) {
            transitions.add(Transition.<State>from(State.OPEN_BANK)
                    .when(() -> Rs2Bank.isOpen() && resume == target, "bank open && resume == " + target)
                    .because("Bank open")
                    .goTo(target));
            transitions.add(Transition.<State>from(target)
                    .when(() -> !Rs2Bank.isOpen(), "!bank open")
                    .because("Bank closed; reopening")
                    .goTo(State.OPEN_BANK));
        }
        transitions.add(Transition.<State>from(State.SCAN)
                .when(() -> resume == State.FINISH, "already organized")
                .because("The bank already matches the plan")
                .goTo(State.FINISH));
        transitions.add(Transition.<State>from(State.SCAN)
                .when(() -> resume == State.COLLAPSE, "planned")
                .because("Bank listed and planned")
                .goTo(State.COLLAPSE));
        transitions.add(Transition.<State>from(State.COLLAPSE)
                .when(() -> resume == State.BUILD, "realTabCount() == 0")
                .because("Every tab collapsed into the main tab")
                .goTo(State.BUILD));
        transitions.add(Transition.<State>from(State.BUILD)
                .when(() -> resume == State.VERIFY, "built")
                .because("Every planned slot placed")
                .goTo(State.VERIFY));
        transitions.add(Transition.<State>from(State.VERIFY)
                .when(() -> resume == State.FINISH, "verified")
                .because("Every tab matches the plan")
                .goTo(State.FINISH));
        return transitions;
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case OPEN_BANK:
                Microbot.status = "Opening the bank";
                Rs2Bank.walkToBankAndUseBank();
                break;
            case SCAN:
                scan();
                break;
            case COLLAPSE:
                collapseLastTab();
                break;
            case BUILD:
                buildNext();
                break;
            case VERIFY:
                verifyNext();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    // --- SCAN ---

    private void scan() {
        Microbot.status = "Listing every bank slot";
        List<Slot> bank = readBank();
        if (bank.isEmpty()) return; // container not loaded yet

        Map<Integer, BankPlan.Listed> byItem = new LinkedHashMap<>();
        int placeholders = 0;
        for (Slot slot : bank) {
            if (slot.filler) continue;
            if (slot.placeholder) placeholders++;
            if (byItem.containsKey(slot.itemId)) continue;
            BankItemClassifier.Placement placement = Microbot.getClientThread()
                    .runOnClientThreadOptional(() -> BankItemClassifier.classify(slot.itemId)).orElse(null);
            if (placement != null) byItem.put(slot.itemId, new BankPlan.Listed(placement, slot.placeholder));
        }
        listing = new ArrayList<>(byItem.values());
        log.info("[BankOrganizer] Listed {} slots: {} items, {} placeholders", bank.size(),
                listing.size() - placeholders, placeholders);
        plan = BankPlan.build(listing);
        plan.log();
        if (plan.tabs.size() > MAX_TABS) log.warn("[BankOrganizer] More tabs planned than the bank has");
        publishPlan();

        if (matchesPlan(bank)) {
            Microbot.status = "Bank is already organized";
            resume = State.FINISH;
            return;
        }
        started = new int[plan.tabs.size()];
        resume = State.COLLAPSE;
    }

    /** True if tabs 1..n hold exactly the planned items and nothing but bank fillers is left untabbed. */
    private boolean matchesPlan(List<Slot> bank) {
        if (realTabCount() != plan.tabs.size()) return false;
        int start = 0;
        for (int t = 0; t < plan.tabs.size(); t++) {
            List<BankPlan.Entry> entries = plan.tabs.get(t).entries;
            if (tabCount(t + 1) != entries.size()) return false;
            for (int i = 0; i < entries.size(); i++) {
                if (bank.get(start + i).key() != entries.get(i).itemId) return false;
            }
            start += entries.size();
        }
        for (int i = start; i < bank.size(); i++) {
            if (!bank.get(i).filler) return false;
        }
        return true;
    }

    // --- COLLAPSE ---

    /** Collapses the last real tab into the main tab through its own "Collapse tab" menu option. */
    private void collapseLastTab() {
        if (Rs2Dialogue.hasSelectAnOption()) {
            Rs2Dialogue.keyPressForDialogueOption("Yes", false);
            return;
        }
        int count = realTabCount();
        if (count == 0) {
            resume = State.BUILD;
            return;
        }
        Widget tab = tabWidget(MAIN_TAB_INDEX + count);
        if (tab == null) return;
        String[] actions = tab.getActions();
        int index = -1;
        for (int i = 0; actions != null && i < actions.length; i++) {
            if (actions[i] != null && actions[i].toLowerCase().contains("collapse")) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            log.warn("[BankOrganizer] Tab {} has no Collapse option", count);
            return;
        }
        Microbot.status = "Collapsing tab " + count;
        Microbot.doInvoke(new NewMenuEntry()
                        .option(actions[index])
                        .target("")
                        .identifier(index + 1)
                        .type(MenuAction.CC_OP)
                        .param0(tab.getIndex())
                        .param1(tab.getId()),
                tab.getBounds() != null ? tab.getBounds() : new Rectangle(1, 1));
        sleepUntil(() -> realTabCount() < count || Rs2Dialogue.hasSelectAnOption(), 3000);
    }

    // --- BUILD ---

    /** One drag per tick: the next planned item onto the end of its tab. */
    private void buildNext() {
        while (cursorTab < plan.tabs.size() && cursorEntry >= plan.tabs.get(cursorTab).entries.size()) {
            cursorTab++;
            cursorEntry = 0;
        }
        if (cursorTab >= plan.tabs.size()) {
            resume = State.VERIFY;
            return;
        }
        if (!Rs2Bank.isMainTabOpen()) {
            Rs2Bank.openMainTab();
            return;
        }

        BankPlan.TabPlan tabPlan = plan.tabs.get(cursorTab);
        BankPlan.Entry entry = tabPlan.entries.get(cursorEntry);
        stats.setCurrent(tabPlan.category);
        boolean tabExists = started[cursorTab] > 0;
        int tabNumber = realTabNumber(cursorTab);

        List<Slot> bank = readBank();
        int untabbed = tabbedTotal();
        Slot source = null;
        for (int i = untabbed; i < bank.size(); i++) {
            if (bank.get(i).key() == entry.itemId) {
                source = bank.get(i);
                break;
            }
        }
        if (source == null) {
            // A drag that landed after its timeout, or an item withdrawn since the listing.
            boolean landed = bank.stream().anyMatch(s -> s.index < untabbed && s.key() == entry.itemId);
            if (!landed) log.warn("[BankOrganizer] {} is no longer in the main tab; skipping it", entry.name);
            advance(tabPlan.category, landed, !landed);
            return;
        }

        if (!showSlot(source.index)) return;
        Rectangle from = slotBounds(source.index);
        Widget target = tabWidget(tabExists ? MAIN_TAB_INDEX + tabNumber : MAIN_TAB_INDEX + realTabCount() + 1);
        Rectangle to = target == null ? null
                : Microbot.getClientThread().runOnClientThreadOptional(target::getBounds).orElse(null);
        if (from == null || to == null) return;

        int before = tabExists ? tabCount(tabNumber) : 0;
        Microbot.status = (tabExists ? "Tab " + tabNumber + ": " : "Starting tab " + tabNumber + ": ") + entry.name;
        Microbot.drag(from, to);
        if (sleepUntil(() -> tabCount(tabNumber) > before, 3000)) {
            advance(tabPlan.category, true, false);
        } else if (++dragAttempts >= MAX_DRAG_ATTEMPTS) {
            log.warn("[BankOrganizer] Couldn't move {} into tab {}; leaving it", entry.name, tabNumber);
            advance(tabPlan.category, false, true);
        }
    }

    private void advance(BankCategory category, boolean placed, boolean skipped) {
        if (placed) {
            started[cursorTab]++;
            stats.addPlaced(category, true);
        } else if (skipped) {
            stats.addSkipped(category);
        }
        cursorEntry++;
        dragAttempts = 0;
    }

    /** The real tab number of a plan tab: plan tabs that got nothing placed don't exist in the bank. */
    private int realTabNumber(int planTab) {
        int n = 1;
        for (int t = 0; t < planTab; t++) {
            if (started[t] > 0) n++;
        }
        return n;
    }

    // --- VERIFY ---

    /** Puts the first out-of-place slot of the current tab into position with one swap. */
    private void verifyNext() {
        while (verifyTab < plan.tabs.size() && started[verifyTab] == 0) verifyTab++;
        if (verifyTab >= plan.tabs.size()) {
            resume = State.FINISH;
            return;
        }
        if (!Rs2Bank.isMainTabOpen()) {
            Rs2Bank.openMainTab();
            return;
        }
        if (Microbot.getVarbitValue(VarbitID.BANK_INSERTMODE) != 0) {
            Microbot.status = "Switching the bank to swap mode";
            Rs2Widget.clickWidget(InterfaceID.Bankmain.SWAP_INSERT);
            sleepUntil(() -> Microbot.getVarbitValue(VarbitID.BANK_INSERTMODE) == 0, 2000);
            return;
        }

        int tabNumber = realTabNumber(verifyTab);
        int start = 0;
        for (int t = 1; t < tabNumber; t++) start += tabCount(t);
        int length = tabCount(tabNumber);
        List<Slot> bank = readBank();
        if (start + length > bank.size()) return;
        List<Integer> actual = new ArrayList<>();
        for (int i = 0; i < length; i++) actual.add(bank.get(start + i).key());
        List<Integer> expected = expectedOrder(plan.tabs.get(verifyTab), actual);

        int i = 0;
        while (i < length && actual.get(i).equals(expected.get(i))) i++;
        if (i == length) {
            log.info("[BankOrganizer] Tab {} ({}) verified", tabNumber, plan.tabs.get(verifyTab).category.label);
            verifyTab++;
            return;
        }
        int want = expected.get(i);
        int j = -1;
        for (int k = i + 1; k < length; k++) {
            if (actual.get(k) == want) {
                j = k;
                break;
            }
        }
        String attemptKey = verifyTab + ":" + i;
        if (j < 0 || swapAttempts.merge(attemptKey, 1, Integer::sum) > MAX_SWAP_ATTEMPTS) {
            log.warn("[BankOrganizer] Couldn't put tab {} slot {} in order; leaving the rest of that tab", tabNumber, i + 1);
            verifyTab++;
            return;
        }

        int destination = start + i;
        int source = start + j;
        if (!showSlot(destination)) return;
        if (!isSlotVisible(source)) {
            // Too far apart to see both: move the item up to the first visible slot after the destination; the
            // slot it lands on is out of order anyway, so nothing already in place is disturbed.
            if (!showSlot(source)) return;
            int hop = -1;
            for (int k = i + 1; k < j; k++) {
                if (isSlotVisible(start + k)) {
                    hop = start + k;
                    break;
                }
            }
            if (hop < 0) return;
            destination = hop;
        }
        Rectangle from = slotBounds(source);
        Rectangle to = slotBounds(destination);
        if (from == null || to == null) return;
        Microbot.status = "Tab " + tabNumber + ": fixing the order";
        int landedAt = destination;
        Microbot.drag(from, to);
        sleepUntil(() -> {
            List<Slot> now = readBank();
            return landedAt < now.size() && now.get(landedAt).key() == want;
        }, 2000);
    }

    /** The plan's order for the slots actually in the tab; anything unplanned goes to the end as it is. */
    private static List<Integer> expectedOrder(BankPlan.TabPlan tab, List<Integer> actual) {
        Map<Integer, Integer> remaining = new HashMap<>();
        for (int key : actual) remaining.merge(key, 1, Integer::sum);
        List<Integer> expected = new ArrayList<>();
        for (BankPlan.Entry e : tab.entries) {
            Integer n = remaining.get(e.itemId);
            if (n != null && n > 0) {
                expected.add(e.itemId);
                remaining.put(e.itemId, n - 1);
            }
        }
        for (int key : actual) {
            Integer n = remaining.get(key);
            if (n != null && n > 0) {
                expected.add(key);
                remaining.put(key, n - 1);
            }
        }
        return expected;
    }

    // --- FINISH ---

    private void finish() {
        if (finishing) return;
        finishing = true;
        if (stats.moved() > 0) {
            Microbot.status = "Bank organized: " + stats.moved() + " items placed";
        }
        log.info("[BankOrganizer] Done: {} placed, {} skipped", stats.moved(), stats.skipped());
        Microbot.stopPlugin(BankOrganizerPlugin.class);
    }

    // --- Bank reading ---

    /** Every bank slot in order, placeholders and fillers included. */
    private static List<Slot> readBank() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            ItemContainer container = Microbot.getClient().getItemContainer(InventoryID.BANK);
            List<Slot> slots = new ArrayList<>();
            if (container == null) return slots;
            Item[] items = container.getItems();
            for (int i = 0; i < items.length; i++) {
                int id = items[i].getId();
                if (id <= 0) continue;
                ItemComposition def = Microbot.getClient().getItemDefinition(id);
                boolean placeholder = def.getPlaceholderTemplateId() != -1;
                int realId = placeholder && def.getPlaceholderId() > 0 ? def.getPlaceholderId() : id;
                slots.add(new Slot(i, realId, placeholder, id == ItemID.BANK_FILLER));
            }
            return slots;
        }).orElse(new ArrayList<>());
    }

    // --- Slots on screen ---

    /** Scrolls the bank so the slot is in view; true once it is. */
    private boolean showSlot(int slot) {
        if (isSlotVisible(slot)) return true;
        Microbot.getClientThread().invokeLater(() -> {
            Widget container = Microbot.getClient().getWidget(InterfaceID.Bankmain.ITEMS);
            Widget item = container == null ? null : container.getChild(slot);
            if (item == null) return;
            int view = container.getHeight();
            int max = Math.max(0, container.getScrollHeight() - view);
            int scroll = Math.max(0, Math.min(max, item.getRelativeY() - view / 3));
            Microbot.getClient().setVarcIntValue(VarClientInt.BANK_SCROLL, scroll);
            Microbot.getClient().runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Bankmain.SCROLLBAR,
                    InterfaceID.Bankmain.ITEMS, scroll);
            container.setScrollY(scroll);
        });
        return sleepUntil(() -> isSlotVisible(slot), 1200);
    }

    private static boolean isSlotVisible(int slot) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget container = Microbot.getClient().getWidget(InterfaceID.Bankmain.ITEMS);
            Widget item = container == null ? null : container.getChild(slot);
            if (item == null || item.isHidden()) return false;
            int top = item.getRelativeY() - container.getScrollY();
            return top >= 0 && top + item.getHeight() <= container.getHeight();
        }).orElse(false);
    }

    private static Rectangle slotBounds(int slot) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget container = Microbot.getClient().getWidget(InterfaceID.Bankmain.ITEMS);
            Widget item = container == null ? null : container.getChild(slot);
            return item == null ? null : item.getBounds();
        }).orElse(null);
    }

    // --- Tabs ---

    private static int tabCount(int tab) {
        return tab < 1 || tab > MAX_TABS ? 0 : Microbot.getVarbitValue(FIRST_TAB_COUNT_VARBIT + tab - 1);
    }

    /** Tabs that exist (they're always contiguous from tab 1). */
    private static int realTabCount() {
        int count = 0;
        for (int t = 1; t <= MAX_TABS && tabCount(t) > 0; t++) count++;
        return count;
    }

    /** Slots in tabs 1-9; the main tab's own items come after them. */
    private static int tabbedTotal() {
        int total = 0;
        for (int t = 1; t <= MAX_TABS; t++) total += tabCount(t);
        return total;
    }

    private static Widget tabWidget(int dynamicIndex) {
        List<Widget> widgets = Rs2Bank.getTabs();
        return dynamicIndex >= 0 && dynamicIndex < widgets.size() ? widgets.get(dynamicIndex) : null;
    }

    private void publishPlan() {
        Map<BankCategory, Integer> counts = new LinkedHashMap<>();
        for (BankPlan.TabPlan tab : plan.tabs) counts.put(tab.category, tab.entries.size());
        stats.setPlan(counts);
    }

    // --- Lifecycle ---

    public boolean run(BankOrganizerConfig config) {
        stats.reset();
        listing = new ArrayList<>();
        plan = null;
        resume = State.SCAN;
        cursorTab = 0;
        cursorEntry = 0;
        started = new int[0];
        dragAttempts = 0;
        verifyTab = 0;
        swapAttempts.clear();
        finishing = false;

        StuckGuard.attach("Shadowfax Bank Organizer", BankOrganizerPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error would cancel the scheduled task without a log line.
                log.error("[BankOrganizer] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(BankOrganizerPlugin.class);
        super.shutdown();
    }
}
