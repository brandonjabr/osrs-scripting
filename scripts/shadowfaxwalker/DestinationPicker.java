package net.runelite.client.plugins.microbot.shadowfaxwalker;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shadowfaxwalker.Destination.Category;
import net.runelite.client.ui.FontManager;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The "Where to?" window: a dark, rounded command-palette style dialog with a search field, category chips and a
 * ranked list that updates as you type. Arrow keys move, Enter walks, Tab cycles the categories and Esc closes.
 * <p>
 * Swing only: build and show it on the event dispatch thread. It answers exactly once, through {@code onPick} or
 * {@code onCancel}.
 */
public final class DestinationPicker extends JDialog {

    private static final int WINDOW_W = 620;
    private static final int WINDOW_H = 560;
    private static final int RADIUS = 18;
    private static final int ROW_H = 50;

    private static final Color CARD_BG = new Color(18, 20, 27);
    private static final Color CARD_BORDER = new Color(255, 255, 255, 28);
    private static final Color FIELD_BG = new Color(255, 255, 255, 12);
    private static final Color FIELD_FOCUS = new Color(240, 205, 110, 150);
    private static final Color ROW_SELECTED = new Color(240, 205, 110, 26);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(140, 146, 160);
    private static final Color FAINT = new Color(255, 255, 255, 18);
    private static final Color ACCENT = new Color(240, 205, 110);
    private static final Color ACCENT_2 = new Color(230, 150, 70);

    private static final Font UI = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font UI_BOLD = UI.deriveFont(Font.BOLD);
    private static final Font UI_SMALL = UI.deriveFont(11f);
    private static final Font UI_SMALL_BOLD = UI_SMALL.deriveFont(Font.BOLD);
    private static final Font SEARCH = UI.deriveFont(17f);

    private final WorldPoint player;
    private final List<String> recent;
    private final Consumer<Destination> onPick;
    private final Runnable onCancel;

    private final JTextField search = new JTextField();
    private final ChipBar chips = new ChipBar();
    private final DefaultListModel<Destination> model = new DefaultListModel<>();
    private final JList<Destination> list = new ResultList(model);
    private final Footer footer = new Footer();

    private boolean answered;
    private Point dragFrom;

    /**
     * @param player  where the player stands now, for distances and "nearby" ordering; null when unknown
     * @param recent  names of recently walked-to places, newest first; shown at the top before typing
     */
    public DestinationPicker(Window owner, WorldPoint player, List<String> recent,
                             Consumer<Destination> onPick, Runnable onCancel) {
        super(owner, "Shadowfax Walker", ModalityType.MODELESS);
        this.player = player;
        this.recent = recent;
        this.onPick = onPick;
        this.onCancel = onCancel;

        setUndecorated(true);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setAlwaysOnTop(owner == null);
        boolean translucent = isTranslucencySupported();
        if (translucent) setBackground(new Color(0, 0, 0, 0));

        JPanel root = new CardPanel(translucent);
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));
        root.add(buildTop(), BorderLayout.NORTH);
        root.add(buildList(), BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        if (translucent) {
            setShape(new RoundRectangle2D.Double(0, 0, WINDOW_W, WINDOW_H, RADIUS * 2, RADIUS * 2));
        }

        setSize(WINDOW_W, WINDOW_H);
        setLocationRelativeTo(owner);
        bindKeys();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                search.requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });
        refresh();
    }

    // --- Layout ---

    private JComponent buildTop() {
        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);

        JComponent header = new JComponent() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = smooth(g0);
                g.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
                g.setPaint(new GradientPaint(0, 0, ACCENT, 180, 0, ACCENT_2));
                g.drawString("Shadowfax Walker", 0, 18);
                g.setFont(UI);
                g.setColor(MUTED);
                g.drawString("Where would you like to go?", 0, 38);

                if (player != null) {
                    String here = "You  " + player.getX() + ", " + player.getY() + (player.getPlane() > 0 ? ", " + player.getPlane() : "");
                    g.setFont(UI_SMALL);
                    FontMetrics fm = g.getFontMetrics();
                    int w = fm.stringWidth(here) + 22;
                    int x = getWidth() - w;
                    g.setColor(FAINT);
                    g.fill(new RoundRectangle2D.Float(x, 4, w, 20, 20, 20));
                    g.setColor(new Color(120, 200, 110));
                    g.fill(new Ellipse2D.Float(x + 8, 11, 6, 6));
                    g.setColor(MUTED);
                    g.drawString(here, x + 17, 18);
                }
                g.dispose();
            }
        };
        header.setPreferredSize(new Dimension(0, 44));
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragFrom = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = getLocation();
                setLocation(p.x + e.getX() - dragFrom.x, p.y + e.getY() - dragFrom.y);
            }
        };
        header.addMouseListener(drag);
        header.addMouseMotionListener(drag);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        search.setFont(SEARCH);
        search.setForeground(TEXT);
        search.setCaretColor(ACCENT);
        search.setSelectionColor(new Color(240, 205, 110, 90));
        search.setSelectedTextColor(TEXT);
        search.setOpaque(false);
        search.setBorder(BorderFactory.createEmptyBorder());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });

        JPanel field = new SearchField();
        field.setLayout(new BorderLayout());
        field.setBorder(BorderFactory.createEmptyBorder(11, 42, 11, 14));
        field.add(search, BorderLayout.CENTER);
        // The focus ring belongs to the box around the text field, which doesn't repaint on its own.
        search.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                field.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                field.repaint();
            }
        });

        JPanel middle = new JPanel(new BorderLayout(0, 10));
        middle.setOpaque(false);
        middle.add(field, BorderLayout.NORTH);
        middle.add(chips, BorderLayout.CENTER);

        top.add(header, BorderLayout.NORTH);
        top.add(middle, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        return top;
    }

    private JComponent buildList() {
        list.setOpaque(false);
        list.setBackground(new Color(0, 0, 0, 0));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(ROW_H);
        list.setCellRenderer(new Row());
        list.setFocusable(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) list.setSelectedIndex(i);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    choose();
                }
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        list.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JScrollPane scroll = new JScrollPane(list);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(ROW_H / 2);
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getVerticalScrollBar().setUI(new SlimScrollBar());
        return scroll;
    }

    private void bindKeys() {
        bind("ESCAPE", "cancel", this::cancel);
        bind("ENTER", "choose", this::choose);
        bind("DOWN", "down", () -> move(1));
        bind("UP", "up", () -> move(-1));
        bind("PAGE_DOWN", "pageDown", () -> move(6));
        bind("PAGE_UP", "pageUp", () -> move(-6));
        bind("TAB", "nextChip", () -> chips.cycle(1));
        bind("shift TAB", "prevChip", () -> chips.cycle(-1));
        search.setFocusTraversalKeysEnabled(false);
    }

    private void bind(String key, String name, Runnable action) {
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        search.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, search.getActionMap().get(name));
    }

    // --- Behaviour ---

    private void refresh() {
        String query = search.getText();
        Category filter = chips.selected();
        List<Destination> results = new ArrayList<>();

        Optional<Destination> coords = DestinationCatalog.parseCoordinates(query);
        coords.ifPresent(results::add);

        if (query.trim().isEmpty()) {
            results.addAll(nearbyFirst(filter));
        } else {
            results.addAll(DestinationCatalog.search(query, filter));
        }

        model.clear();
        for (Destination d : results) model.addElement(d);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        // Back to the top for every new search; again after layout, which can land after a big change in rows.
        list.scrollRectToVisible(new Rectangle(0, 0, 1, 1));
        javax.swing.SwingUtilities.invokeLater(() -> list.scrollRectToVisible(new Rectangle(0, 0, 1, 1)));
        footer.repaint();
        list.repaint();
    }

    /** Before typing: recent places, then everything else nearest first. */
    private List<Destination> nearbyFirst(Category filter) {
        Set<Destination> ordered = new LinkedHashSet<>();
        for (String name : recent) {
            DestinationCatalog.byName(name)
                    .filter(d -> filter == null || d.getCategory() == filter)
                    .ifPresent(ordered::add);
        }
        List<Destination> rest = DestinationCatalog.search("", filter);
        if (player != null) rest.sort(Comparator.comparingInt(this::distance));
        ordered.addAll(rest);
        return new ArrayList<>(ordered);
    }

    private void move(int delta) {
        if (model.isEmpty()) return;
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void choose() {
        Destination d = list.getSelectedValue();
        if (d == null || answered) return;
        answered = true;
        dispose();
        onPick.accept(d);
    }

    private void cancel() {
        if (answered) return;
        answered = true;
        dispose();
        onCancel.run();
    }

    /** Closes the window without answering, e.g. when the plugin is turned off while it is open. */
    public void close() {
        answered = true;
        dispose();
    }

    private int distance(Destination d) {
        if (player == null) return Integer.MAX_VALUE;
        return distanceBetween(player, d.getPoint());
    }

    /** Straight-line tiles, ignoring the plane (ladders are short). */
    static int distanceBetween(WorldPoint a, WorldPoint b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    static String formatDistance(int tiles) {
        if (tiles == Integer.MAX_VALUE) return "";
        if (tiles <= 4) return "you're here";
        if (tiles >= 1000) return String.format("%.1fk tiles", tiles / 1000.0);
        return tiles + " tiles";
    }

    private static boolean isTranslucencySupported() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT);
        } catch (Exception e) {
            return false;
        }
    }

    private static Graphics2D smooth(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    // --- Painted pieces ---

    /** The window itself: rounded dark card with a thin border and the Shadowfax gold accent bar. */
    private static final class CardPanel extends JPanel {
        private final boolean translucent;

        CardPanel(boolean translucent) {
            this.translucent = translucent;
            setOpaque(!translucent);
            setBackground(CARD_BG);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = getWidth();
            int h = getHeight();
            RoundRectangle2D card = translucent
                    ? new RoundRectangle2D.Float(0, 0, w - 1, h - 1, RADIUS * 2, RADIUS * 2)
                    : new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 0, 0);
            g.setColor(CARD_BG);
            g.fill(card);
            g.setPaint(new GradientPaint(0, 0, new Color(240, 205, 110, 18), 0, 140, new Color(240, 205, 110, 0)));
            g.fill(card);
            g.setColor(CARD_BORDER);
            g.setStroke(new BasicStroke(1f));
            g.draw(card);
            g.setPaint(new GradientPaint(RADIUS, 0, ACCENT, w - RADIUS, 0, ACCENT_2));
            g.fill(new RoundRectangle2D.Float(RADIUS, 0, w - 2 * RADIUS, 3, 3, 3));
            g.dispose();
        }
    }

    /** Rounded search box with a magnifier and placeholder; the border glows gold while focused. */
    private final class SearchField extends JPanel {
        SearchField() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = getWidth();
            int h = getHeight();
            RoundRectangle2D box = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14);
            g.setColor(FIELD_BG);
            g.fill(box);
            g.setStroke(new BasicStroke(1.4f));
            g.setColor(search.isFocusOwner() ? FIELD_FOCUS : CARD_BORDER);
            g.draw(box);

            g.setColor(search.isFocusOwner() ? ACCENT : MUTED);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cy = h / 2;
            g.draw(new Ellipse2D.Float(15, cy - 8, 12, 12));
            g.draw(new Line2D.Float(25, cy + 2, 29, cy + 6));

            if (search.getText().isEmpty()) {
                g.setFont(SEARCH);
                g.setColor(MUTED);
                FontMetrics fm = g.getFontMetrics();
                g.drawString("Search places, or type coordinates like 3200 3200", 42, cy + fm.getAscent() / 2 - 2);
            }
            g.dispose();
        }
    }

    /** Category filter pills that wrap onto as many rows as they need. Click or Tab to switch. */
    private final class ChipBar extends JComponent {
        private static final int CHIP_H = 24;
        private static final int GAP = 6;
        private final List<Category> categories = new ArrayList<>();
        private final List<Rectangle> bounds = new ArrayList<>();
        private int selected = 0; // 0 = All
        private int hover = -1;

        ChipBar() {
            categories.add(null);
            for (Category c : Category.values()) {
                if (c != Category.COORDINATES) categories.add(c);
            }
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int i = hit(e.getPoint());
                    if (i >= 0) select(i);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = hit(e.getPoint());
                    if (i != hover) {
                        hover = i;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        Category selected() {
            return categories.get(selected);
        }

        void cycle(int delta) {
            select((selected + delta + categories.size()) % categories.size());
        }

        private void select(int i) {
            selected = i;
            repaint();
            refresh();
        }

        private int hit(Point p) {
            for (int i = 0; i < bounds.size(); i++) {
                if (bounds.get(i).contains(p)) return i;
            }
            return -1;
        }

        private String label(int i) {
            Category c = categories.get(i);
            return c == null ? "All" : c.getLabel();
        }

        private int layoutChips(int width) {
            bounds.clear();
            FontMetrics fm = getFontMetrics(UI_SMALL_BOLD);
            int x = 0;
            int y = 0;
            for (int i = 0; i < categories.size(); i++) {
                int w = fm.stringWidth(label(i)) + (categories.get(i) == null ? 22 : 32);
                if (x > 0 && x + w > width) {
                    x = 0;
                    y += CHIP_H + GAP;
                }
                bounds.add(new Rectangle(x, y, w, CHIP_H));
                x += w + GAP;
            }
            return y + CHIP_H;
        }

        @Override
        public Dimension getPreferredSize() {
            // The window has a fixed width (minus the 20px side padding); measuring getWidth() here can catch a
            // half-laid-out 1px width and wrap every chip onto its own row.
            int width = WINDOW_W - 40;
            return new Dimension(width, layoutChips(width));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            layoutChips(getWidth());
            g.setFont(UI_SMALL_BOLD);
            FontMetrics fm = g.getFontMetrics();
            for (int i = 0; i < bounds.size(); i++) {
                Rectangle r = bounds.get(i);
                Category c = categories.get(i);
                Color color = c == null ? ACCENT : c.getColor();
                boolean on = i == selected;
                RoundRectangle2D pill = new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, CHIP_H, CHIP_H);
                g.setColor(on ? alpha(color, 55) : (i == hover ? alpha(Color.WHITE, 22) : FAINT));
                g.fill(pill);
                g.setColor(on ? alpha(color, 200) : CARD_BORDER);
                g.draw(pill);
                int tx = r.x + 11;
                if (c != null) {
                    g.setColor(color);
                    g.fill(new Ellipse2D.Float(r.x + 10, r.y + r.height / 2f - 3, 6, 6));
                    tx = r.x + 21;
                }
                g.setColor(on ? TEXT : MUTED);
                g.drawString(label(i), tx, r.y + (r.height + fm.getAscent()) / 2 - 2);
            }
            g.dispose();
        }
    }

    /** The results list; paints a friendly hint instead of a blank area when nothing matches. */
    private final class ResultList extends JList<Destination> {
        ResultList(DefaultListModel<Destination> model) {
            super(model);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (!model.isEmpty()) return;
            Graphics2D g = smooth(g0);
            int cx = getParent() != null ? getParent().getWidth() / 2 : getWidth() / 2;
            g.setFont(UI_BOLD.deriveFont(15f));
            g.setColor(TEXT);
            String title = "No places match “" + search.getText().trim() + "”";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(title, cx - fm.stringWidth(title) / 2, 70);
            g.setFont(UI);
            g.setColor(MUTED);
            fm = g.getFontMetrics();
            String hint = chips.selected() != null
                    ? "Try All categories (Tab), a town name, or coordinates like 3200 3200"
                    : "Try a town name like Varrock, or coordinates like 3200 3200";
            g.drawString(hint, cx - fm.stringWidth(hint) / 2, 94);
            g.dispose();
        }
    }

    /** One result: category badge, highlighted name, details line and the distance from the player. */
    private final class Row extends JComponent implements ListCellRenderer<Destination> {
        private Destination value;
        private boolean selected;
        private boolean isRecent;

        @Override
        public Component getListCellRendererComponent(JList<? extends Destination> list, Destination value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.value = value;
            this.selected = isSelected;
            this.isRecent = search.getText().trim().isEmpty() && recent.contains(value.getName());
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (value == null) return;
            Graphics2D g = smooth(g0);
            int w = getWidth();
            int h = getHeight();
            Color color = value.getCategory().getColor();

            if (selected) {
                g.setColor(ROW_SELECTED);
                g.fill(new RoundRectangle2D.Float(0, 2, w - 4, h - 4, 12, 12));
                g.setColor(ACCENT);
                g.fill(new RoundRectangle2D.Float(0, 12, 3, h - 24, 3, 3));
            }

            // Category badge: tinted rounded square with the category's initial.
            int bx = 12;
            int by = (h - 30) / 2;
            g.setColor(alpha(color, 40));
            g.fill(new RoundRectangle2D.Float(bx, by, 30, 30, 9, 9));
            g.setColor(alpha(color, 120));
            g.draw(new RoundRectangle2D.Float(bx + 0.5f, by + 0.5f, 29, 29, 9, 9));
            g.setFont(UI_BOLD.deriveFont(13f));
            g.setColor(color);
            String initial = value.getCategory() == Category.COORDINATES ? "#" : value.getCategory().getLabel().substring(0, 1);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(initial, bx + (30 - fm.stringWidth(initial)) / 2, by + 20);

            // Right side: distance and, on the selected row, an Enter key cap.
            int right = w - 14;
            g.setFont(UI_SMALL);
            String dist = formatDistance(distance(value));
            if (selected) {
                right = keyCap(g, "↵ Walk", right, h / 2 - 10) - 10;
            }
            if (isRecent) {
                right = tag(g, "RECENT", right, h / 2 - 9, ACCENT) - 8;
            }
            g.setFont(UI_SMALL);
            g.setColor(MUTED);
            fm = g.getFontMetrics();
            if (!dist.isEmpty()) {
                g.drawString(dist, right - fm.stringWidth(dist), h / 2 + 4);
                right -= fm.stringWidth(dist) + 12;
            }

            // Name with the matched letters in gold, then the details line.
            int tx = bx + 42;
            drawHighlighted(g, value.getName(), search.getText(), tx, h / 2 - 2, right - tx);
            g.setFont(UI_SMALL);
            g.setColor(MUTED);
            String details = value.getCategory().getLabel() + "  ·  " + value.coordinates();
            String aliases = value.getCategory() == Category.BANK ? "" : value.aliasText();
            if (!aliases.isEmpty()) details += "  ·  " + aliases;
            g.drawString(ellipsize(g.getFontMetrics(), details, right - tx), tx, h / 2 + 14);
            g.dispose();
        }

        private void drawHighlighted(Graphics2D g, String name, String query, int x, int y, int maxW) {
            boolean[] hit = DestinationCatalog.matchedChars(name, query);
            FontMetrics plain = g.getFontMetrics(UI_BOLD.deriveFont(14f));
            String shown = ellipsize(plain, name, maxW);
            for (int i = 0; i < shown.length(); i++) {
                boolean on = i < hit.length && hit[i] && i < name.length();
                g.setFont(UI_BOLD.deriveFont(14f));
                g.setColor(on ? ACCENT : TEXT);
                String ch = String.valueOf(shown.charAt(i));
                g.drawString(ch, x, y);
                if (on) {
                    g.fillRect(x, y + 2, plain.stringWidth(ch), 1);
                }
                x += plain.stringWidth(ch);
            }
        }

        private int keyCap(Graphics2D g, String text, int right, int y) {
            g.setFont(UI_SMALL_BOLD);
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + 14;
            int x = right - w;
            g.setPaint(new GradientPaint(x, 0, ACCENT, x + w, 0, ACCENT_2));
            g.fill(new RoundRectangle2D.Float(x, y, w, 20, 8, 8));
            g.setColor(CARD_BG);
            g.drawString(text, x + 7, y + 14);
            return x;
        }

        private int tag(Graphics2D g, String text, int right, int y, Color color) {
            g.setFont(UI_SMALL_BOLD.deriveFont(9f));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + 12;
            int x = right - w;
            g.setColor(alpha(color, 35));
            g.fill(new RoundRectangle2D.Float(x, y, w, 18, 18, 18));
            g.setColor(color);
            g.drawString(text, x + 6, y + 12);
            return x;
        }
    }

    /** Key hints on the left, result count on the right. */
    private final class Footer extends JComponent {
        Footer() {
            setPreferredSize(new Dimension(0, 34));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            g.setColor(CARD_BORDER);
            g.drawLine(0, 6, getWidth(), 6);
            int y = 16;
            int x = 0;
            x = hint(g, x, y, "↑↓", "Navigate");
            x = hint(g, x, y, "↵", "Walk");
            x = hint(g, x, y, "Tab", "Category");
            hint(g, x, y, "Esc", "Close");

            g.setFont(UI_SMALL);
            g.setColor(MUTED);
            String count = model.size() == 1 ? "1 place" : model.size() + " places";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(count, getWidth() - fm.stringWidth(count), y + 13);
            g.dispose();
        }

        private int hint(Graphics2D g, int x, int y, String key, String label) {
            g.setFont(UI_SMALL_BOLD);
            FontMetrics fm = g.getFontMetrics();
            int w = Math.max(20, fm.stringWidth(key) + 10);
            g.setColor(FAINT);
            g.fill(new RoundRectangle2D.Float(x, y, w, 18, 6, 6));
            g.setColor(CARD_BORDER);
            g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, 17, 6, 6));
            g.setColor(TEXT);
            g.drawString(key, x + (w - fm.stringWidth(key)) / 2, y + 13);
            g.setFont(UI_SMALL);
            g.setColor(MUTED);
            g.drawString(label, x + w + 6, y + 13);
            return x + w + 6 + g.getFontMetrics().stringWidth(label) + 16;
        }
    }

    /** Thin rounded scrollbar thumb, no arrows or track. */
    private static final class SlimScrollBar extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(255, 255, 255, 40);
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected javax.swing.JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected javax.swing.JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private static javax.swing.JButton zeroButton() {
            javax.swing.JButton b = new javax.swing.JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
        }

        @Override
        protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g = smooth(g0);
            g.setColor(isDragging ? new Color(240, 205, 110, 140) : thumbColor);
            g.fill(new RoundRectangle2D.Float(r.x + 1, r.y + 2, r.width - 2, r.height - 4, r.width - 2, r.width - 2));
            g.dispose();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return new Dimension(8, 0);
        }
    }

    private static String ellipsize(FontMetrics fm, String s, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (fm.stringWidth(s) <= maxWidth) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > maxWidth) end--;
        return s.substring(0, end) + dots;
    }

    /** Names in {@code recent} that still exist, without duplicates; used to clean the saved list. */
    static List<String> cleanRecent(List<String> names, int max) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (out.size() >= max) break;
            if (n.isEmpty() || !seen.add(n)) continue;
            if (DestinationCatalog.byName(n).isPresent() || n.startsWith("Tile ")) out.add(n);
        }
        return out;
    }
}
