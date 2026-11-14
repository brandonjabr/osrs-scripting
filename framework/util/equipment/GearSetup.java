package net.runelite.client.plugins.microbot.util.equipment;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A remembered set of worn equipment (plus, for magic, the runes carried and the autocast spell), captured from what
 * the player is wearing and saved as one line of text so it can live in the config.
 * <p>
 * Text form: {@code W:slot,id,qty,name|...;I:id,qty,name|...;A:SPELL}. Names are only for display.
 */
public final class GearSetup {

    /** One item: worn in {@code slot} (equipment slot index), or carried when {@code slot} is -1. */
    public static final class Item {
        private final int slot;
        private final int id;
        private final int quantity;
        private final String name;

        public Item(int slot, int id, int quantity, String name) {
            this.slot = slot;
            this.id = id;
            this.quantity = Math.max(1, quantity);
            this.name = name == null ? "Item " + id : name;
        }

        public int slot() {
            return slot;
        }

        public int id() {
            return id;
        }

        public int quantity() {
            return quantity;
        }

        public String name() {
            return name;
        }
    }

    private final Map<Integer, Item> worn;
    private final List<Item> carried;
    private final String autocast;

    private GearSetup(Map<Integer, Item> worn, List<Item> carried, String autocast) {
        this.worn = Collections.unmodifiableMap(worn);
        this.carried = Collections.unmodifiableList(carried);
        this.autocast = autocast == null ? "" : autocast;
    }

    public static GearSetup empty() {
        return new GearSetup(new LinkedHashMap<>(), new ArrayList<>(), "");
    }

    /**
     * What the player has on right now. {@code carry} picks inventory items to remember too (runes and a rune
     * pouch for a magic setup); {@code withAutocast} records the autocast spell.
     */
    public static GearSetup captureWorn(Predicate<Rs2ItemModel> carry, boolean withAutocast) {
        Map<Integer, Item> worn = new LinkedHashMap<>();
        for (Rs2ItemModel item : Rs2Equipment.items()) {
            if (item == null || item.getId() <= 0) continue;
            worn.put(item.getSlot(), new Item(item.getSlot(), item.getId(), item.getQuantity(), item.getName()));
        }
        List<Item> carried = new ArrayList<>();
        if (carry != null) {
            Rs2Inventory.items(carry).forEach(item ->
                    carried.add(new Item(-1, item.getId(), item.getQuantity(), item.getName())));
        }
        String spell = "";
        if (withAutocast) {
            Rs2CombatSpells current = Rs2Magic.getCurrentAutoCastSpell();
            if (current != null) spell = current.name();
        }
        return new GearSetup(worn, carried, spell);
    }

    /** Runes and rune pouches: what a magic setup carries. */
    public static boolean isRuneSupply(Rs2ItemModel item) {
        String name = item.getName() == null ? "" : item.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(" rune") || name.contains("rune pouch");
    }

    public boolean isEmpty() {
        return worn.isEmpty();
    }

    public Map<Integer, Item> worn() {
        return worn;
    }

    public List<Item> carried() {
        return carried;
    }

    public Item inSlot(EquipmentInventorySlot slot) {
        return worn.get(slot.getSlotIdx());
    }

    /** The autocast spell to set, or null when none was recorded (or it no longer exists). */
    public Rs2CombatSpells autocastSpell() {
        if (autocast.isEmpty()) return null;
        try {
            return Rs2CombatSpells.valueOf(autocast);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True if the worn item ids match this setup's, slot for slot. */
    public boolean isWorn() {
        for (Item item : worn.values()) {
            if (!Rs2Equipment.isWearing(item.id())) return false;
        }
        return true;
    }

    /** Short one-line description for overlays and logs, e.g. "Rune scimitar, Rune full helm +6". */
    public String summary() {
        if (worn.isEmpty()) return "not set";
        List<String> names = new ArrayList<>();
        Item weapon = worn.get(EquipmentInventorySlot.WEAPON.getSlotIdx());
        if (weapon != null) names.add(weapon.name());
        for (Item item : worn.values()) {
            if (item != weapon && names.size() < 2) names.add(item.name());
        }
        int rest = worn.size() - names.size();
        return String.join(", ", names) + (rest > 0 ? " +" + rest : "");
    }

    /** Multi-line listing for the setup window. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values()) {
            Item item = worn.get(slot.getSlotIdx());
            if (item == null) continue;
            lines.add(pretty(slot.name()) + ": " + item.name() + (item.quantity() > 1 ? " x" + item.quantity() : ""));
        }
        for (Item item : carried) {
            lines.add("Carry: " + item.name() + (item.quantity() > 1 ? " x" + item.quantity() : ""));
        }
        Rs2CombatSpells spell = autocastSpell();
        if (spell != null) lines.add("Autocast: " + pretty(spell.name()));
        return lines;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder("W:");
        boolean first = true;
        for (Item item : worn.values()) {
            if (!first) sb.append('|');
            first = false;
            sb.append(item.slot()).append(',').append(item.id()).append(',').append(item.quantity()).append(',')
                    .append(clean(item.name()));
        }
        sb.append(";I:");
        first = true;
        for (Item item : carried) {
            if (!first) sb.append('|');
            first = false;
            sb.append(item.id()).append(',').append(item.quantity()).append(',').append(clean(item.name()));
        }
        sb.append(";A:").append(autocast);
        return sb.toString();
    }

    /** Reads {@link #serialize()} output back; anything unreadable gives an empty setup. */
    public static GearSetup parse(String text) {
        if (text == null || text.isBlank()) return empty();
        Map<Integer, Item> worn = new LinkedHashMap<>();
        List<Item> carried = new ArrayList<>();
        String autocast = "";
        try {
            for (String part : text.split(";")) {
                if (part.startsWith("W:")) {
                    for (String entry : part.substring(2).split("\\|")) {
                        String[] f = entry.split(",", 4);
                        if (f.length < 4) continue;
                        int slot = Integer.parseInt(f[0].trim());
                        worn.put(slot, new Item(slot, Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()), f[3]));
                    }
                } else if (part.startsWith("I:")) {
                    for (String entry : part.substring(2).split("\\|")) {
                        String[] f = entry.split(",", 3);
                        if (f.length < 3) continue;
                        carried.add(new Item(-1, Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()), f[2]));
                    }
                } else if (part.startsWith("A:")) {
                    autocast = part.substring(2).trim();
                }
            }
        } catch (NumberFormatException e) {
            return empty();
        }
        return new GearSetup(worn, carried, autocast);
    }

    private static String clean(String name) {
        return name == null ? "" : name.replaceAll("[,|;]", " ");
    }

    private static String pretty(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
