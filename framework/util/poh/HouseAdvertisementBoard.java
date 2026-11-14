package net.runelite.client.plugins.microbot.util.poh;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * The House Advertisement board next to POH portals: the way into other players' houses since the board update.
 * Right-click options are View (open the list), Visit-Last (re-enter the last house visited) and Add-House.
 * <p>
 * The list interface (group {@code POH_BOARD}) has one column component per field. Each row's cells are dynamic
 * children of those columns, lined up by their y position; when the owner is home, the location cell shows an arrow
 * that enters the house. Rows are read by grouping cells on y, so this doesn't depend on child indexes.
 */
public final class HouseAdvertisementBoard {

    private static final String BOARD_NAME = "House Advertisement";
    private static final int SEARCH_RADIUS = 20;
    // Cells in the same row sit within this many pixels of each other vertically.
    private static final int ROW_TOLERANCE = 6;

    /** One advertised house. */
    public static final class Listing {
        public final String owner;
        /** The altar column's text: the altar tier (7 = gilded) or a yes/no mark. */
        public final String altar;
        private final Widget enterButton;
        private final int y;

        Listing(String owner, String altar, Widget enterButton, int y) {
            this.owner = owner;
            this.altar = altar;
            this.enterButton = enterButton;
            this.y = y;
        }

        /** The owner is home and the row's enter arrow is showing. */
        public boolean canEnter() {
            return enterButton != null;
        }

        public boolean hasGildedAltar() {
            String a = altar == null ? "" : altar.trim().toLowerCase();
            return a.equals("7") || a.equals("y") || a.equals("yes") || a.contains("gilded");
        }

        @Override
        public String toString() {
            return owner + " (altar " + altar + (canEnter() ? ", home" : ", away") + ")";
        }
    }

    private HouseAdvertisementBoard() {
    }

    public static Rs2TileObjectModel find() {
        return Microbot.getRs2TileObjectCache().query()
                .withName(BOARD_NAME)
                .nearestOnClientThread(SEARCH_RADIUS);
    }

    public static boolean isOpen() {
        return Rs2Widget.isWidgetVisible(InterfaceID.PohBoard.CONTENT);
    }

    /** Opens the list. */
    public static boolean view() {
        Rs2TileObjectModel board = find();
        return board != null && board.click("View") && sleepUntil(HouseAdvertisementBoard::isOpen, 5000);
    }

    /** Re-enters the last house visited; check {@link PohPresence#isInHouse()} afterwards. */
    public static boolean visitLast() {
        Rs2TileObjectModel board = find();
        return board != null && board.click("Visit-Last");
    }

    public static void close() {
        if (isOpen()) Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
    }

    /** Listings top to bottom as currently shown. Empty if the board isn't open. */
    public static List<Listing> listings() {
        return Microbot.getClientThread().runOnClientThreadOptional(HouseAdvertisementBoard::readListings)
                .orElse(List.of());
    }

    /** Clicks the listing's enter arrow, if it is visible in the scroll area. */
    public static boolean enter(Listing listing) {
        if (listing == null || !listing.canEnter()) return false;
        Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Rectangle button = listing.enterButton.getBounds();
            Widget scroll = Microbot.getClient().getWidget(InterfaceID.PohBoard.SCROLLING);
            if (button == null || scroll == null || !scroll.getBounds().contains(button)) return null;
            return button;
        }).orElse(null);
        if (bounds == null) return false;
        Microbot.getMouse().click(bounds);
        return true;
    }

    // --- Reading the columns (client thread) ---

    private static List<Listing> readListings() {
        Map<Integer, String> owners = cellTexts(InterfaceID.PohBoard.NAME);
        Map<Integer, String> altars = cellTexts(InterfaceID.PohBoard.ALTAR);
        List<Widget> buttons = new ArrayList<>();
        buttons.addAll(clickableCells(InterfaceID.PohBoard.LOCATION));
        buttons.addAll(clickableCells(InterfaceID.PohBoard.BUTTON));

        List<Listing> listings = new ArrayList<>();
        for (Map.Entry<Integer, String> owner : owners.entrySet()) {
            int y = owner.getKey();
            String altar = nearestValue(altars, y);
            Widget button = buttons.stream()
                    .filter(b -> Math.abs(b.getRelativeY() - y) <= ROW_TOLERANCE)
                    .findFirst().orElse(null);
            listings.add(new Listing(owner.getValue(), altar == null ? "" : altar, button, y));
        }
        listings.sort(Comparator.comparingInt(l -> l.y));
        return listings;
    }

    /** Non-blank cell texts of a column, keyed by row y. */
    private static Map<Integer, String> cellTexts(int columnId) {
        Map<Integer, String> cells = new LinkedHashMap<>();
        for (Widget cell : cells(columnId)) {
            String text = Rs2UiHelper.stripColTags(cell.getText());
            if (text != null && !text.isBlank()) cells.put(cell.getRelativeY(), text.trim());
        }
        return cells;
    }

    /** Cells in a column that have a menu action (the enter arrows). */
    private static List<Widget> clickableCells(int columnId) {
        List<Widget> clickable = new ArrayList<>();
        for (Widget cell : cells(columnId)) {
            String[] actions = cell.getActions();
            if (actions == null || cell.isHidden()) continue;
            for (String action : actions) {
                if (action != null && !action.isBlank()) {
                    clickable.add(cell);
                    break;
                }
            }
        }
        return clickable;
    }

    private static List<Widget> cells(int columnId) {
        Widget column = Microbot.getClient().getWidget(columnId);
        if (column == null) return List.of();
        Widget[] children = column.getDynamicChildren();
        if (children == null) return List.of();
        List<Widget> cells = new ArrayList<>();
        for (Widget child : children) {
            if (child != null) cells.add(child);
        }
        return cells;
    }

    private static String nearestValue(Map<Integer, String> cells, int y) {
        for (Map.Entry<Integer, String> cell : cells.entrySet()) {
            if (Math.abs(cell.getKey() - y) <= ROW_TOLERANCE) return cell.getValue();
        }
        return null;
    }
}
