package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.microbot.Microbot;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides where one item goes: its {@link BankSection} (and so its tab), the group it belongs to inside that section
 * (an armour set, a potion's doses, a herb grimy and clean, all runes...), and its order inside the group.
 * <p>
 * Checks, first match wins:
 * <ol>
 *   <li>Coins and platinum tokens; runes and rune pouches; teleport jewellery and travel items (before armour, so a
 *       games necklace lands with the teleports); tablets and scrolls named "teleport".</li>
 *   <li>Runecrafting pieces (essence, talismans, tiaras) and skilling tools (axes, pickaxes, hammers...), before the
 *       wearable check because many of them can be worn or wielded.</li>
 *   <li>Food ("Eat"), then potions ("Drink" with a dose count).</li>
 *   <li>Anything wearable: by slot (ammo, jewellery, capes) and otherwise by its best attack style, split into armour
 *       and weapons, grouped by material ("Rune", "Green d'hide", "Mystic"...) and ordered tier by tier.</li>
 *   <li>Untradeable non-clue items are quest items; clues go to Misc.</li>
 *   <li>Skilling resources by name (ores, bars, logs, raw fish, herbs, gems, crafting supplies), the rest to Misc.</li>
 * </ol>
 * Must run on the client thread (it reads item definitions and stats).
 */
final class BankItemClassifier {

    /** Where an item goes. Groups sort by (groupRank, group); items in a group by (itemRank, name). */
    static final class Placement {
        final int itemId;
        final String name;
        final BankSection section;
        final String group;
        final int groupRank;
        final int itemRank;

        Placement(int itemId, String name, BankSection section, String group, int groupRank, int itemRank) {
            this.itemId = itemId;
            this.name = name;
            this.section = section;
            this.group = group;
            this.groupRank = groupRank;
            this.itemRank = itemRank;
        }
    }

    private static final int UNRANKED = 10_000;
    private static final Pattern COUNT_SUFFIX = Pattern.compile("^(.*?)\\s*\\((\\d+)\\)$");
    private static final Pattern PARENTHESES = Pattern.compile("\\s*\\([^)]*\\)");

    private static final List<String> RUNES = Arrays.asList(
            "air", "water", "earth", "fire", "mind", "body", "cosmic", "chaos", "nature", "law", "death", "astral",
            "blood", "soul", "wrath", "mist", "dust", "mud", "smoke", "steam", "lava", "aether", "sunfire");

    /** Teleport jewellery and travel items whose names don't say "teleport". */
    private static final List<String> TRAVEL_ITEMS = Arrays.asList(
            "games necklace", "ring of dueling", "amulet of glory", "skills necklace", "combat bracelet",
            "ring of wealth", "necklace of passage", "digsite pendant", "burning amulet", "slayer ring",
            "xeric's talisman", "ectophial", "enchanted lyre", "drakan's medallion", "royal seed pod",
            "camulet", "ring of returning", "ring of the elements", "pharaoh's sceptre", "chronicle",
            "kharedst's memoirs", "book of the dead", "ardougne cloak", "teleport crystal", "ring of shadows",
            "giantsoul amulet", "quetzal whistle", "construct. cape", "max cape");

    /** Gear materials in tier order; anything else sorts after these, alphabetically. */
    private static final List<String> TIERS = Arrays.asList(
            "bronze", "iron", "steel", "black", "white", "mithril", "adamant", "rune", "granite", "dragon",
            "leather", "hardleather", "hard leather", "studded", "snakeskin", "green d'hide", "blue d'hide",
            "red d'hide", "black d'hide", "oak", "willow", "maple", "yew", "magic",
            "wizard", "blue wizard", "black wizard", "xerician", "splitbark", "mystic", "infinity");

    /** Piece names stripped from the end of gear names to find the material. Longest first where they overlap. */
    private static final List<String> PIECES = Arrays.asList(
            "full helm", "med helm", "platebody", "chainbody", "platelegs", "plateskirt", "kiteshield", "sq shield",
            "defender", "2h sword", "longsword", "scimitar", "battleaxe", "warhammer", "halberd", "dagger", "sword",
            "mace", "spear", "hasta", "claws", "pickaxe", "boots", "gloves", "gauntlets", "vambraces", "vambs",
            "bracers", "chaps", "coif", "cowl", "robe top", "robe bottom", "robe", "skirt", "body", "top", "legs",
            "hood", "helm", "hat", "mask", "shield", "shortbow", "longbow", "crossbow", "comp bow", "bow",
            "battlestaff", "staff", "wand", "cape", "amulet", "necklace", "bracelet", "ring", "tiara", "bolts",
            "arrows", "arrow", "bolt", "darts", "dart", "knife", "knives", "javelin", "thrownaxe", "axe");

    private static final List<String> FOODS = Arrays.asList(
            "shrimps", "cooked chicken", "cooked meat", "anchovies", "sardine", "bread", "herring", "mackerel",
            "trout", "cod", "pike", "salmon", "tuna", "cake", "2/3 cake", "slice of cake", "meat pie", "pizza",
            "lobster", "bass", "swordfish", "potato with cheese", "monkfish", "cooked karambwan", "shark",
            "sea turtle", "manta ray", "anglerfish");

    private static final List<String> ORES = Arrays.asList(
            "copper ore", "tin ore", "iron ore", "silver ore", "coal", "gold ore", "mithril ore", "adamantite ore",
            "runite ore");
    private static final List<String> BARS = Arrays.asList(
            "bronze bar", "iron bar", "silver bar", "steel bar", "gold bar", "mithril bar", "adamantite bar",
            "runite bar");
    private static final List<String> LOGS = Arrays.asList(
            "logs", "oak logs", "willow logs", "teak logs", "maple logs", "mahogany logs", "yew logs", "magic logs",
            "redwood logs");
    private static final List<String> HERBS = Arrays.asList(
            "guam leaf", "marrentill", "tarromin", "harralander", "ranarr weed", "toadflax", "irit leaf", "avantoe",
            "kwuarm", "snapdragon", "cadantine", "lantadyme", "dwarf weed", "torstol");
    private static final List<String> SECONDARIES = Arrays.asList(
            "eye of newt", "unicorn horn dust", "limpwurt root", "red spiders' eggs", "chocolate dust",
            "white berries", "snape grass", "jangerberries", "wine of zamorak", "potato cactus", "dragon scale dust",
            "crushed nest", "toad's legs", "ground bat bones", "mort myre fungus", "zulrah's scales");
    private static final List<String> GEMS = Arrays.asList(
            "opal", "jade", "red topaz", "sapphire", "emerald", "ruby", "diamond", "dragonstone", "onyx", "zenyte");
    private static final List<String> CRAFTING = Arrays.asList(
            "cowhide", "leather", "hide", "thread", "wool", "flax", "bow string", "molten glass", "bucket of sand",
            "soda ash", "seaweed", "clay", "unstrung", "unpowered orb", "battlestaff");
    private static final List<String> TOOLS = Arrays.asList(
            "hammer", "chisel", "knife", "tinderbox", "needle", "saw", "glassblowing pipe", "shears", "spade",
            "rake", "seed dibber", "secateurs", "gardening trowel", "watering can", "small fishing net",
            "big fishing net", "fishing rod", "fly fishing rod", "lobster pot", "harpoon", "barbarian rod");

    private BankItemClassifier() {
    }

    /** Classifies an item by its real (non-placeholder) id. Client thread only. */
    static Placement classify(int itemId) {
        ItemComposition def = Microbot.getClient().getItemDefinition(itemId);
        String display = def == null || def.getName() == null ? "Item " + itemId : def.getName();
        String name = display.toLowerCase(Locale.ROOT);
        List<String> actions = def == null ? List.of() : lower(def.getInventoryActions());

        // --- Currency, runes, teleports ---
        if (itemId == ItemID.COINS || name.equals("platinum token")) {
            return place(itemId, display, BankSection.CURRENCY, "coins", 0, itemId == ItemID.COINS ? 0 : 1);
        }
        if (name.endsWith(" rune") || name.contains("rune pouch")) {
            int rank = name.contains("pouch") ? UNRANKED : rank(RUNES, name.substring(0, name.length() - 5));
            return place(itemId, display, BankSection.RUNES, "runes", 0, rank);
        }
        String travel = TRAVEL_ITEMS.stream().filter(name::contains).findFirst().orElse(null);
        if (travel != null) {
            return place(itemId, display, BankSection.TELEPORT_JEWELLERY, travel, rank(TRAVEL_ITEMS, travel),
                    -count(name));
        }
        if (name.contains("teleport")) {
            return place(itemId, display, BankSection.TELEPORT_ITEMS, "teleports", 0, UNRANKED);
        }

        // --- Runecrafting and tools, before the wearable check ---
        if (name.contains("essence") || name.endsWith(" talisman") || name.endsWith(" tiara")) {
            String kind = name.contains("essence") ? "essence" : name.endsWith("talisman") ? "talismans" : "tiaras";
            return place(itemId, display, BankSection.RUNECRAFTING, kind, kindRank(kind), runeRank(name));
        }
        if (name.endsWith(" pickaxe") || (name.endsWith(" axe") && !name.contains("battleaxe"))
                || name.endsWith(" felling axe")) {
            String kind = name.endsWith("pickaxe") ? "pickaxes" : "axes";
            return place(itemId, display, BankSection.TOOLS, kind, kind.equals("pickaxes") ? 1 : 2,
                    tierRank(material(name)));
        }
        if (TOOLS.contains(stripParentheses(name)) || name.endsWith(" mould")) {
            return place(itemId, display, BankSection.TOOLS, "tools", 0, rank(TOOLS, stripParentheses(name)));
        }

        // --- Food and potions ---
        if (actions.contains("eat")) {
            String base = stripCount(name);
            return place(itemId, display, BankSection.FOOD, base, rank(FOODS, base), -count(name));
        }
        Matcher dose = COUNT_SUFFIX.matcher(name);
        if (actions.contains("drink") && dose.matches()) {
            return place(itemId, display, BankSection.POTIONS, dose.group(1), UNRANKED,
                    -Integer.parseInt(dose.group(2)));
        }

        // --- Gear ---
        ItemStats stats = def == null ? null : Microbot.getItemManager().getItemStats(itemId);
        boolean wearable = (stats != null && stats.isEquipable())
                || actions.contains("wear") || actions.contains("wield") || actions.contains("equip");
        if (wearable) {
            return gear(itemId, display, name, stats == null ? null : stats.getEquipment());
        }

        // --- Quest items and clues ---
        boolean clue = name.contains("clue") || name.contains("casket") || name.contains("challenge scroll");
        if (def != null && !def.isTradeable() && !clue) {
            return place(itemId, display, BankSection.QUEST, name, UNRANKED, 0);
        }
        if (clue) {
            return place(itemId, display, BankSection.CLUES, "clues", 0, UNRANKED);
        }

        // --- Skilling resources ---
        if (ORES.contains(name)) return place(itemId, display, BankSection.ORES_AND_BARS, "ores", 0, rank(ORES, name));
        if (BARS.contains(name)) return place(itemId, display, BankSection.ORES_AND_BARS, "bars", 1, rank(BARS, name));
        if (name.endsWith("logs")) return place(itemId, display, BankSection.LOGS, "logs", 0, rank(LOGS, name));
        if (name.endsWith(" plank")) return place(itemId, display, BankSection.LOGS, "planks", 1, UNRANKED);
        if (name.startsWith("raw ")) {
            return place(itemId, display, BankSection.FISHING, "raw", 0, rank(FOODS, name.substring(4)));
        }
        if (name.contains("bait") || name.equals("feather") || name.equals("sandworms")) {
            return place(itemId, display, BankSection.FISHING, "bait", 1, UNRANKED);
        }
        String herb = name.startsWith("grimy ") ? name.substring(6) : name;
        if (HERBS.contains(herb)) {
            // Grimy then clean, herb by herb.
            return place(itemId, display, BankSection.HERBLORE, "herbs", 0,
                    rank(HERBS, herb) * 2 + (name.startsWith("grimy ") ? 0 : 1));
        }
        if (name.endsWith("(unf)")) return place(itemId, display, BankSection.HERBLORE, "unfinished", 1, UNRANKED);
        if (SECONDARIES.contains(name)) {
            return place(itemId, display, BankSection.HERBLORE, "secondaries", 2, rank(SECONDARIES, name));
        }
        if (name.startsWith("vial")) return place(itemId, display, BankSection.HERBLORE, "vials", 3, UNRANKED);
        if (name.endsWith(" seed") || name.endsWith(" seeds") || name.endsWith(" sapling")) {
            return place(itemId, display, BankSection.HERBLORE, "seeds", 4, UNRANKED);
        }
        String gem = name.startsWith("uncut ") ? name.substring(6) : name;
        if (GEMS.contains(gem)) {
            // Uncut then cut, gem by gem.
            return place(itemId, display, BankSection.GEMS, "gems", 0,
                    rank(GEMS, gem) * 2 + (name.startsWith("uncut ") ? 0 : 1));
        }
        if (CRAFTING.stream().anyMatch(name::contains)) {
            return place(itemId, display, BankSection.CRAFTING, "crafting", 0, UNRANKED);
        }
        if (def != null && def.isTradeable() && (name.contains("bones") || name.contains("ashes"))) {
            return place(itemId, display, BankSection.OTHER_RESOURCES, "bones", 0, UNRANKED);
        }
        return place(itemId, display, BankSection.OTHER, name, UNRANKED, 0);
    }

    /** Wearable items: by slot, then by best attack style, grouped by material in tier order. */
    private static Placement gear(int itemId, String display, String name, ItemEquipmentStats eq) {
        int slot = eq == null ? -1 : eq.getSlot();
        String material = material(name);
        int tier = tierRank(material);
        int pieceRank = pieceRank(slot);

        if (slot == EquipmentInventorySlot.AMMO.getSlotIdx()) {
            String kind = ammoKind(name);
            return place(itemId, display, BankSection.AMMO, kind, kindRank(kind), tier);
        }
        if (slot == EquipmentInventorySlot.AMULET.getSlotIdx() || slot == EquipmentInventorySlot.RING.getSlotIdx()) {
            return place(itemId, display, BankSection.JEWELLERY, material, tier, pieceRank);
        }
        if (slot == EquipmentInventorySlot.CAPE.getSlotIdx()) {
            return place(itemId, display, BankSection.CAPES, "capes", 0, UNRANKED);
        }

        boolean weapon = slot == EquipmentInventorySlot.WEAPON.getSlotIdx();
        if (eq == null) {
            return place(itemId, display, BankSection.OTHER_GEAR, material, tier, pieceRank);
        }
        int melee = Math.max(eq.getAstab(), Math.max(eq.getAslash(), eq.getAcrush()));
        int defence = eq.getDstab() + eq.getDslash() + eq.getDcrush() + eq.getDmagic() + eq.getDrange();
        BankSection section;
        if (eq.getAmagic() > 0 && eq.getAmagic() >= eq.getArange() && eq.getAmagic() >= melee) {
            section = weapon ? BankSection.MAGIC_WEAPONS : BankSection.MAGIC_ARMOUR;
        } else if (eq.getArange() > 0 && eq.getArange() >= melee) {
            section = weapon ? BankSection.RANGED_WEAPONS : BankSection.RANGED_ARMOUR;
        } else if (melee > 0 || defence > 0 || eq.getStr() > 0) {
            section = weapon ? BankSection.MELEE_WEAPONS : BankSection.MELEE_ARMOUR;
        } else {
            section = BankSection.OTHER_GEAR;
        }
        return place(itemId, display, section, material, tier, pieceRank);
    }

    // --- Name helpers ---

    /** "Rune full helm (g)" → "rune", "Green d'hide body" → "green d'hide", "Staff of air" → "staff". */
    static String material(String name) {
        String base = stripParentheses(name);
        for (String piece : PIECES) {
            if (base.equals(piece)) return piece;
            if (base.endsWith(" " + piece)) return base.substring(0, base.length() - piece.length() - 1).trim();
            if (base.startsWith(piece + " of ")) return piece;
        }
        return base;
    }

    private static String ammoKind(String name) {
        if (name.contains("arrow")) return "arrows";
        if (name.contains("bolt")) return "bolts";
        if (name.contains("dart")) return "darts";
        if (name.contains("knife") || name.contains("knives")) return "knives";
        if (name.contains("javelin")) return "javelins";
        return "other ammo";
    }

    private static int kindRank(String kind) {
        switch (kind) {
            case "essence": case "arrows": return 0;
            case "talismans": case "bolts": return 1;
            case "tiaras": case "darts": return 2;
            case "knives": return 3;
            case "javelins": return 4;
            default: return 5;
        }
    }

    /** Runecrafting pieces in rune order ("Air talisman", "Fire tiara"). */
    private static int runeRank(String name) {
        int space = name.indexOf(' ');
        return space < 0 ? UNRANKED : rank(RUNES, name.substring(0, space));
    }

    /** Armour reads top to bottom (helm, cape, neck, body, legs, shield, hands, feet, ring), weapons last. */
    private static int pieceRank(int slot) {
        if (slot == EquipmentInventorySlot.HEAD.getSlotIdx()) return 0;
        if (slot == EquipmentInventorySlot.CAPE.getSlotIdx()) return 1;
        if (slot == EquipmentInventorySlot.AMULET.getSlotIdx()) return 2;
        if (slot == EquipmentInventorySlot.BODY.getSlotIdx()) return 3;
        if (slot == EquipmentInventorySlot.LEGS.getSlotIdx()) return 4;
        if (slot == EquipmentInventorySlot.SHIELD.getSlotIdx()) return 5;
        if (slot == EquipmentInventorySlot.GLOVES.getSlotIdx()) return 6;
        if (slot == EquipmentInventorySlot.BOOTS.getSlotIdx()) return 7;
        if (slot == EquipmentInventorySlot.RING.getSlotIdx()) return 8;
        if (slot == EquipmentInventorySlot.WEAPON.getSlotIdx()) return 9;
        return 10;
    }

    private static int tierRank(String material) {
        return rank(TIERS, material);
    }

    private static int rank(List<String> order, String value) {
        int i = order.indexOf(value);
        return i < 0 ? UNRANKED : i;
    }

    /** "Games necklace(8)" → 8, no count → 0. */
    private static int count(String name) {
        Matcher m = COUNT_SUFFIX.matcher(name);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    private static String stripCount(String name) {
        Matcher m = COUNT_SUFFIX.matcher(name);
        return m.matches() ? m.group(1) : name;
    }

    private static String stripParentheses(String name) {
        return PARENTHESES.matcher(name).replaceAll("").trim();
    }

    private static List<String> lower(String[] actions) {
        if (actions == null) return List.of();
        String[] out = new String[actions.length];
        for (int i = 0; i < actions.length; i++) {
            out[i] = actions[i] == null ? "" : actions[i].toLowerCase(Locale.ROOT);
        }
        return Arrays.asList(out);
    }

    private static Placement place(int itemId, String display, BankSection section, String group, int groupRank,
                                   int itemRank) {
        return new Placement(itemId, display, section, group, groupRank, itemRank);
    }
}
