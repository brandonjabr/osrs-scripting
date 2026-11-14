package net.runelite.client.plugins.microbot.util.widget;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Accepts the game's "are you sure?" warning screens (the CWS warning interfaces): the Mort Myre swamp gate, the
 * Wilderness ditch and levers, dangerous dungeon entrances and the like. All of them share a layout: a "don't
 * warn me again" toggle and up to three buttons. This ticks the toggle (so the warning stops appearing) and clicks
 * the button that goes ahead.
 * <p>
 * The variants are read from the client's own {@code InterfaceID.CwsWarning*} definitions, so every one of them is
 * covered (the walker used to handle two), including ones added later.
 */
@Slf4j
public final class Rs2Warnings {

    // The toggle's "not ticked" sprite.
    private static final int UNTICKED_SPRITE = 941;
    private static final Pattern GO_AHEAD = Pattern.compile(
            "\\b(yes|proceed|enter|continue|go|climb|jump|cross|open|i understand|accept|ok)\\b");
    private static final Pattern STAY = Pattern.compile("\\b(no|cancel|stay|don't|do not|back|leave|close)\\b");

    /** One warning variant: its buttons and toggle as packed component ids (-1 when absent). */
    private static final class Variant {
        final String name;
        final int[] buttons;
        final int toggle;

        Variant(String name, int[] buttons, int toggle) {
            this.name = name;
            this.buttons = buttons;
            this.toggle = toggle;
        }
    }

    private static final List<Variant> VARIANTS = loadVariants();

    private Rs2Warnings() {
    }

    private static List<Variant> loadVariants() {
        List<Variant> variants = new ArrayList<>();
        for (Class<?> cls : InterfaceID.class.getDeclaredClasses()) {
            if (!cls.getSimpleName().startsWith("CwsWarning")) continue;
            List<Integer> buttons = new ArrayList<>();
            for (String button : new String[]{"WARN1", "WARN2", "WARN3"}) {
                int id = component(cls, button);
                if (id != -1) buttons.add(id);
            }
            int toggle = component(cls, "CWS_TOGGLE_BUTTON");
            if (buttons.isEmpty()) continue;
            variants.add(new Variant(cls.getSimpleName(), buttons.stream().mapToInt(Integer::intValue).toArray(), toggle));
        }
        log.debug("[Warnings] {} warning screen variants known", variants.size());
        return Collections.unmodifiableList(variants);
    }

    private static int component(Class<?> cls, String field) {
        try {
            Field f = cls.getField(field);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** True while any warning screen is open. */
    public static boolean isOpen() {
        return open() != null;
    }

    /** The open variant, found in one client-thread pass (cheap enough for the walker's 100 ms door polls). */
    private static Variant open() {
        if (Microbot.getClient() == null) return null;
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            for (Variant v : VARIANTS) {
                Widget w = Microbot.getClient().getWidget(v.buttons[0]);
                if (w != null && !w.isHidden()) return v;
            }
            return null;
        }).orElse(null);
    }

    /**
     * Accepts an open warning screen: ticks "don't warn again" if it has one, then clicks the go-ahead button
     * (picked by its text, else the first). True if one was open and it closed.
     */
    public static boolean accept() {
        Variant v = open();
        if (v == null) return false;
        log.info("[Warnings] Accepting the {} warning screen", v.name);
        Microbot.status = "Accepting a warning";

        if (v.toggle != -1 && Rs2Widget.isWidgetVisible(v.toggle) && unticked(v.toggle)) {
            Rs2Widget.clickWidget(v.toggle);
            sleepUntil(() -> !unticked(v.toggle), 1_800);
        }
        int button = goAheadButton(v);
        Rs2Widget.clickWidget(button);
        boolean closed = sleepUntil(() -> open() != v, 3_000);
        if (!closed) log.warn("[Warnings] The {} warning didn't close after accepting it", v.name);
        return closed;
    }

    private static boolean unticked(int toggle) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget w = Microbot.getClient().getWidget(toggle);
            return w != null && w.getSpriteId() == UNTICKED_SPRITE;
        }).orElse(false);
    }

    /** The button whose text reads as going ahead; the first button when none say. */
    private static int goAheadButton(Variant v) {
        for (int button : v.buttons) {
            String text = text(button);
            if (text.isEmpty() || STAY.matcher(text).find()) continue;
            if (GO_AHEAD.matcher(text).find()) return button;
        }
        return v.buttons[0];
    }

    /** All text on a button and its children, lower case. */
    private static String text(int component) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget w = Microbot.getClient().getWidget(component);
            if (w == null || w.isHidden()) return "";
            StringBuilder sb = new StringBuilder();
            append(sb, w);
            for (Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()}) {
                if (kids == null) continue;
                for (Widget kid : kids) append(sb, kid);
            }
            return sb.toString().toLowerCase(Locale.ROOT).trim();
        }).orElse("");
    }

    private static void append(StringBuilder sb, Widget w) {
        if (w == null) return;
        String t = Rs2UiHelper.stripColTags(w.getText());
        if (t != null && !t.isEmpty()) sb.append(t).append(' ');
        String name = Rs2UiHelper.stripColTags(w.getName());
        if (name != null && !name.isEmpty()) sb.append(name).append(' ');
        String[] actions = w.getActions();
        if (actions != null) {
            for (String a : actions) if (a != null && !a.isEmpty()) sb.append(a).append(' ');
        }
    }
}
