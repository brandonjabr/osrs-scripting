package net.runelite.client.plugins.microbot.shadowfaxslayer;

import net.runelite.client.plugins.microbot.util.equipment.GearSetup;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * "Gear setups" window: one card per combat style showing what's saved, with Save (records what you're wearing
 * now) and Clear. Closing it, or pressing Start, lets the script carry on.
 * <p>
 * Swing only: build and show it on the event dispatch thread. Saving reads the equipment from the game, which is
 * cached, plus the autocast varbit for magic.
 */
final class GearSetupDialog extends JDialog {

    private static final Color BG = new Color(18, 20, 27);
    private static final Color CARD = new Color(28, 31, 40);
    private static final Color BORDER = new Color(255, 255, 255, 28);
    private static final Color TEXT = new Color(236, 238, 242);
    private static final Color MUTED = new Color(140, 146, 160);
    private static final Color ACCENT = new Color(225, 90, 90);
    private static final Color BUTTON = new Color(44, 48, 60);

    private static final Font UI = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font UI_BOLD = UI.deriveFont(Font.BOLD);
    private static final Font UI_SMALL = UI.deriveFont(11.5f);
    private static final Font TITLE = UI.deriveFont(Font.BOLD, 17f);

    private final GearStore store;
    private final Runnable onClosed;
    private final Map<CombatStyle, JTextArea> listings = new EnumMap<>(CombatStyle.class);
    private final JLabel status = new JLabel(" ");
    private boolean closed;

    GearSetupDialog(Window owner, GearStore store, Runnable onClosed) {
        super(owner, "Shadowfax Slayer – gear setups", ModalityType.MODELESS);
        this.store = store;
        this.onClosed = onClosed;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setAlwaysOnTop(owner == null);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(label("Gear setups", TITLE, TEXT));
        top.add(label("Put a setup on in game, then press Save for that style. The script swaps to it at the bank "
                + "for each task.", UI_SMALL, MUTED));
        top.add(label("Magic also remembers the runes you carry (and a rune pouch) and your autocast spell.",
                UI_SMALL, MUTED));
        root.add(top, BorderLayout.NORTH);

        JPanel cards = new JPanel();
        cards.setOpaque(false);
        cards.setLayout(new BoxLayout(cards, BoxLayout.X_AXIS));
        for (CombatStyle style : CombatStyle.values()) {
            if (style != CombatStyle.MELEE) cards.add(javax.swing.Box.createHorizontalStrut(10));
            cards.add(card(style));
        }
        root.add(cards, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        status.setFont(UI_SMALL);
        status.setForeground(MUTED);
        footer.add(status, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        JButton start = button("Start slayer", true);
        start.addActionListener(e -> close());
        right.add(start);
        footer.add(right, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
        setSize(760, 470);
        setMinimumSize(new Dimension(640, 380));
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        refresh();
    }

    private JPanel card(CombatStyle style) {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(CARD);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JLabel title = new JLabel(style.label());
        title.setFont(UI_BOLD.deriveFont(15f));
        title.setForeground(ACCENT);
        card.add(title, BorderLayout.NORTH);

        JTextArea listing = new JTextArea();
        listing.setEditable(false);
        listing.setFocusable(false);
        listing.setLineWrap(true);
        listing.setWrapStyleWord(true);
        listing.setFont(UI_SMALL);
        listing.setForeground(TEXT);
        listing.setBackground(CARD);
        listings.put(style, listing);
        card.add(listing, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        JButton save = button("Save worn gear", false);
        save.addActionListener(e -> save(style));
        JButton clear = button("Clear", false);
        clear.addActionListener(e -> {
            store.clear(style);
            status.setText(style.label() + " setup cleared");
            refresh();
        });
        buttons.add(save);
        buttons.add(clear);
        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private void save(CombatStyle style) {
        status.setText("Reading your equipment…");
        // The autocast check runs on the client thread; don't block the EDT on it.
        CompletableFuture.supplyAsync(() -> store.captureNow(style)).whenComplete((setup, error) ->
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (error != null || setup == null) {
                        status.setText("Couldn't read your equipment: are you logged in?");
                    } else if (setup.isEmpty()) {
                        status.setText("Nothing is worn: put the " + style.label().toLowerCase() + " gear on first");
                    } else {
                        status.setText(style.label() + " setup saved: " + setup.summary());
                    }
                    refresh();
                }));
    }

    private void refresh() {
        for (CombatStyle style : CombatStyle.values()) {
            GearSetup setup = store.get(style);
            List<String> lines = setup.describe();
            listings.get(style).setText(lines.isEmpty()
                    ? "Not saved.\n\nWear your " + style.label().toLowerCase() + " gear and press Save."
                    : String.join("\n", lines));
            listings.get(style).setCaretPosition(0);
        }
    }

    private void close() {
        if (closed) return;
        closed = true;
        dispose();
        onClosed.run();
    }

    private static JLabel label(String text, Font font, Color color) {
        JLabel l = new JLabel(text, SwingConstants.LEFT);
        l.setFont(font);
        l.setForeground(color);
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return l;
    }

    private static JButton button(String text, boolean primary) {
        JButton b = new JButton(text);
        b.setFont(primary ? UI_BOLD : UI_SMALL);
        b.setForeground(primary ? Color.WHITE : TEXT);
        b.setBackground(primary ? ACCENT : BUTTON);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
}
