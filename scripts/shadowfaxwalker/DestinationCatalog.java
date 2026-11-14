package net.runelite.client.plugins.microbot.shadowfaxwalker;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shadowfaxwalker.Destination.Category;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every place the picker offers, and the search behind its autocomplete.
 * <p>
 * Town and teleport-landing tiles come from Microbot's own walker data (teleportation_spells.tsv); banks are read
 * straight from {@link BankLocation}, so they stay in step with the rest of the client. The rest are well-known spots
 * checked against the OSRS wiki map.
 */
public final class DestinationCatalog {

    private static final List<Destination> ALL = build();

    // "3164 3485", "3164,3485,0", "(3164, 3485, 1)"
    private static final Pattern COORDS = Pattern.compile("^\\(?\\s*(\\d{3,5})\\s*[ ,]\\s*(\\d{3,5})(?:\\s*[ ,]\\s*([0-3]))?\\s*\\)?$");

    private DestinationCatalog() {
    }

    public static List<Destination> all() {
        return ALL;
    }

    /** A catalog place by name, or a "Tile x, y[, plane]" name saved from typed coordinates. */
    public static Optional<Destination> byName(String name) {
        if (name.startsWith("Tile ")) return parseCoordinates(name.substring(5));
        return ALL.stream().filter(d -> d.getName().equalsIgnoreCase(name)).findFirst();
    }

    /** Typed coordinates as a destination, e.g. "3200 3200" or "3200, 3200, 1". */
    public static Optional<Destination> parseCoordinates(String query) {
        Matcher m = COORDS.matcher(query.trim());
        if (!m.matches()) return Optional.empty();
        int x = Integer.parseInt(m.group(1));
        int y = Integer.parseInt(m.group(2));
        int plane = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return Optional.of(new Destination("Tile " + x + ", " + y + (plane > 0 ? ", " + plane : ""),
                Category.COORDINATES, new WorldPoint(x, y, plane)));
    }

    /**
     * Ranks destinations against the query: an exact name first, then names starting with it, then names with a word
     * starting with each typed word, then plain substring matches in the name or aliases, and last a loose
     * in-order letter match ("gex" → "Grand Exchange"). Shorter names win ties.
     */
    public static List<Destination> search(String query, Category filter) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Destination> pool = new ArrayList<>();
        for (Destination d : ALL) {
            if (filter == null || d.getCategory() == filter) pool.add(d);
        }
        if (q.isEmpty()) {
            pool.sort(Comparator.comparing((Destination d) -> d.getCategory().ordinal()).thenComparing(Destination::getName));
            return pool;
        }

        List<Scored> scored = new ArrayList<>();
        for (Destination d : pool) {
            int score = score(d, q);
            if (score > 0) scored.add(new Scored(d, score));
        }
        scored.sort(Comparator.comparingInt((Scored s) -> -s.score)
                .thenComparingInt(s -> s.destination.getName().length())
                .thenComparing(s -> s.destination.getName()));
        List<Destination> out = new ArrayList<>(scored.size());
        for (Scored s : scored) out.add(s.destination);
        return out;
    }

    private static int score(Destination d, String q) {
        String name = d.getName().toLowerCase(Locale.ROOT);
        String all = d.getSearchText();
        if (name.equals(q)) return 1000;
        for (String alias : d.getAliases()) {
            if (alias.equalsIgnoreCase(q)) return 950;
        }
        if (name.startsWith(q)) return 800;

        String[] words = q.split("\\s+");
        boolean allWordStarts = true;
        boolean allContained = true;
        for (String w : words) {
            if (!startsAWord(all, w)) allWordStarts = false;
            if (!all.contains(w)) allContained = false;
        }
        if (allWordStarts) return 600 + (startsAWord(name, words[0]) ? 50 : 0);
        if (allContained) return 300;
        if (isSubsequence(q.replace(" ", ""), name)) return 100;
        return 0;
    }

    private static boolean startsAWord(String text, String word) {
        int i = text.indexOf(word);
        while (i >= 0) {
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) return true;
            i = text.indexOf(word, i + 1);
        }
        return false;
    }

    private static boolean isSubsequence(String needle, String hay) {
        if (needle.length() < 2) return false;
        int j = 0;
        for (int i = 0; i < hay.length() && j < needle.length(); i++) {
            if (hay.charAt(i) == needle.charAt(j)) j++;
        }
        return j == needle.length();
    }

    /**
     * Which characters of {@code name} the query matched, for highlighting in the list: whole typed words where they
     * appear, otherwise the loose in-order letters.
     */
    public static boolean[] matchedChars(String name, String query) {
        boolean[] hit = new boolean[name.length()];
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return hit;
        String lower = name.toLowerCase(Locale.ROOT);
        boolean any = false;
        for (String w : q.split("\\s+")) {
            int i = indexOfWordStart(lower, w);
            if (i < 0) i = lower.indexOf(w);
            if (i < 0) continue;
            for (int k = i; k < i + w.length(); k++) hit[k] = true;
            any = true;
        }
        if (any) return hit;
        String needle = q.replace(" ", "");
        int j = 0;
        for (int i = 0; i < lower.length() && j < needle.length(); i++) {
            if (lower.charAt(i) == needle.charAt(j)) {
                hit[i] = true;
                j++;
            }
        }
        return j == needle.length() ? hit : new boolean[name.length()];
    }

    private static int indexOfWordStart(String text, String word) {
        int i = text.indexOf(word);
        while (i >= 0) {
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) return i;
            i = text.indexOf(word, i + 1);
        }
        return -1;
    }

    private static final class Scored {
        final Destination destination;
        final int score;

        Scored(Destination destination, int score) {
            this.destination = destination;
            this.score = score;
        }
    }

    // --- The list ---

    private static List<Destination> build() {
        List<Destination> l = new ArrayList<>();

        // Cities & towns
        add(l, "Lumbridge", Category.CITY, 3222, 3218, 0, "lumby", "home", "spawn");
        add(l, "Varrock", Category.CITY, 3213, 3424, 0, "varrock square", "fountain");
        add(l, "Grand Exchange", Category.CITY, 3164, 3485, 0, "ge", "g.e.", "trade", "market");
        add(l, "Falador", Category.CITY, 2965, 3378, 0, "fally", "fountain");
        add(l, "Port Sarim", Category.CITY, 3029, 3217, 0, "docks", "sarim", "karamja boat");
        add(l, "Rimmington", Category.CITY, 2957, 3214, 0, "rimmy", "house portal");
        add(l, "Draynor Village", Category.CITY, 3093, 3248, 0, "draynor");
        add(l, "Edgeville", Category.CITY, 3087, 3496, 0, "edge", "edgy");
        add(l, "Barbarian Village", Category.CITY, 3082, 3420, 0, "barb village");
        add(l, "Al Kharid", Category.CITY, 3293, 3174, 0, "alkharid", "desert", "palace");
        add(l, "Camelot", Category.CITY, 2757, 3478, 0, "cammy", "castle");
        add(l, "Seers' Village", Category.CITY, 2725, 3485, 0, "seers", "seers village");
        add(l, "Catherby", Category.CITY, 2808, 3440, 0, "cath");
        add(l, "East Ardougne", Category.CITY, 2661, 3305, 0, "ardy", "ardougne", "market");
        add(l, "West Ardougne", Category.CITY, 2530, 3305, 0, "ardy", "plague city");
        add(l, "Yanille", Category.CITY, 2606, 3093, 0, "nmz");
        add(l, "Taverley", Category.CITY, 2895, 3442, 0, "tav", "taverly");
        add(l, "Burthorpe", Category.CITY, 2899, 3546, 0, "burthorpe", "games room");
        add(l, "Canifis", Category.CITY, 3494, 3483, 0, "werewolf", "morytania");
        add(l, "Port Phasmatys", Category.CITY, 3687, 3468, 0, "phasmatys", "ectofuntus", "morytania");
        add(l, "Brimhaven", Category.CITY, 2760, 3178, 0, "karamja");
        add(l, "Musa Point", Category.CITY, 2918, 3176, 0, "karamja", "bananas");
        add(l, "Shilo Village", Category.CITY, 2852, 2954, 0, "shilo", "karamja");
        add(l, "Tai Bwo Wannai", Category.CITY, 2789, 3065, 0, "tai bwo", "karamja");
        add(l, "Rellekka", Category.CITY, 2660, 3657, 0, "fremennik");
        add(l, "Port Khazard", Category.CITY, 2664, 3161, 0, "khazard");
        add(l, "Tree Gnome Stronghold", Category.CITY, 2465, 3495, 0, "gnome", "grand tree");
        add(l, "Lletya", Category.CITY, 2353, 3163, 0, "elves", "tirannwn");
        add(l, "Shantay Pass", Category.CITY, 3304, 3116, 0, "shantay", "desert");
        add(l, "Pollnivneach", Category.CITY, 3359, 2972, 0, "polly", "desert");
        add(l, "Nardah", Category.CITY, 3428, 2892, 0, "desert");
        add(l, "Burgh de Rott", Category.CITY, 3495, 3212, 0, "burgh", "morytania");
        add(l, "Keldagrim", Category.CITY, 2837, 10207, 0, "dwarves", "kelda");
        add(l, "Dorgesh-Kaan", Category.CITY, 2702, 5350, 0, "dorgesh", "cave goblins");
        add(l, "Zanaris", Category.CITY, 2383, 4458, 0, "fairy", "lost city");
        add(l, "Lunar Isle", Category.CITY, 2099, 3919, 0, "moonclan", "lunar");
        add(l, "Neitiznot", Category.CITY, 2337, 3807, 0, "fremennik isles");
        add(l, "Jatizso", Category.CITY, 2416, 3801, 0, "fremennik isles");
        add(l, "Fossil Island", Category.CITY, 3739, 3804, 0, "museum camp");
        add(l, "Corsair Cove", Category.CITY, 2570, 2864, 0, "corsair");
        add(l, "Ferox Enclave", Category.CITY, 3130, 3631, 0, "ferox", "pool", "clan wars");
        add(l, "Prifddinas", Category.CITY, 3257, 6106, 0, "prif", "elf city");
        add(l, "Kourend Castle", Category.CITY, 1641, 3673, 0, "kourend", "zeah");
        add(l, "Hosidius", Category.CITY, 1749, 3599, 0, "zeah", "kourend");
        add(l, "Arceuus", Category.CITY, 1700, 3882, 0, "zeah", "kourend", "library");
        add(l, "Lovakengj", Category.CITY, 1526, 3739, 0, "zeah", "kourend", "dwarves");
        add(l, "Piscarilius", Category.CITY, 1803, 3790, 0, "zeah", "kourend", "port piscarilius");
        add(l, "Shayzien", Category.CITY, 1488, 3592, 0, "zeah", "kourend");
        add(l, "Civitas illa Fortis", Category.CITY, 1680, 3134, 0, "civitas", "varlamore");

        // Banks: every bank the walker already knows about
        for (BankLocation bank : BankLocation.values()) {
            String name = pretty(bank.name());
            if (!name.toLowerCase(Locale.ROOT).contains("bank") && !name.toLowerCase(Locale.ROOT).contains("chest")) {
                name += " Bank";
            }
            l.add(new Destination(name, Category.BANK, bank.getWorldPoint(), "bank"));
        }

        // Skilling spots
        add(l, "Varrock East Mine", Category.SKILLING, 3286, 3365, 0, "mining", "tin", "copper", "iron", "se varrock mine");
        add(l, "Varrock West Mine", Category.SKILLING, 3180, 3371, 0, "mining", "clay", "silver", "sw varrock mine");
        add(l, "Al Kharid Mine", Category.SKILLING, 3300, 3300, 0, "mining", "iron", "scorpion");
        add(l, "Motherlode Mine", Category.SKILLING, 3760, 5666, 0, "mlm", "mining", "pay-dirt");
        add(l, "Lumbridge Swamp Mine", Category.SKILLING, 3228, 3148, 0, "mining", "coal", "mithril");
        add(l, "Barbarian Village Fishing", Category.SKILLING, 3104, 3424, 0, "fishing", "trout", "salmon", "fly fishing");
        add(l, "Lumbridge Swamp Fishing", Category.SKILLING, 3241, 3148, 0, "fishing", "shrimp", "anchovies");
        add(l, "Draynor Fishing", Category.SKILLING, 3086, 3228, 0, "fishing", "shrimp", "net");
        add(l, "Catherby Fishing", Category.SKILLING, 2837, 3432, 0, "fishing", "lobster", "swordfish", "shark");
        add(l, "Karamja Fishing", Category.SKILLING, 2925, 3178, 0, "fishing", "lobster", "musa point");
        add(l, "Draynor Willows", Category.SKILLING, 3087, 3235, 0, "woodcutting", "willow trees");
        add(l, "Lumbridge Trees", Category.SKILLING, 3184, 3240, 0, "woodcutting", "oak", "logs");
        add(l, "Seers' Maples", Category.SKILLING, 2728, 3501, 0, "woodcutting", "maple trees", "seers");
        add(l, "Edgeville Yews", Category.SKILLING, 3087, 3475, 0, "woodcutting", "yew trees");
        add(l, "Lumbridge Furnace", Category.SKILLING, 3226, 3254, 0, "smelting", "smithing");
        add(l, "Al Kharid Furnace", Category.SKILLING, 3275, 3186, 0, "smelting", "smithing");
        add(l, "Edgeville Furnace", Category.SKILLING, 3109, 3499, 0, "smelting", "smithing");
        add(l, "Varrock Anvils", Category.SKILLING, 3188, 3426, 0, "smithing", "anvil");
        add(l, "Lumbridge Spinning Wheel", Category.SKILLING, 3209, 3213, 1, "crafting", "spinning", "flax", "wool");
        add(l, "Seers' Flax Field", Category.SKILLING, 2742, 3446, 0, "flax", "crafting");
        add(l, "Lumbridge Cows", Category.SKILLING, 3258, 3276, 0, "cows", "cowhide", "combat");
        add(l, "Lumbridge Chickens", Category.SKILLING, 3235, 3295, 0, "chickens", "feathers", "combat");
        add(l, "Lumbridge Goblins", Category.SKILLING, 3250, 3238, 0, "goblins", "combat");
        add(l, "Lumbridge Sheep", Category.SKILLING, 3201, 3268, 0, "sheep", "wool", "shears");
        add(l, "Blast Furnace", Category.SKILLING, 1948, 4957, 0, "bf", "smithing", "keldagrim");
        add(l, "Tithe Farm", Category.SKILLING, 1795, 3501, 0, "farming", "hosidius");
        add(l, "Wintertodt", Category.SKILLING, 1630, 3958, 0, "firemaking", "wt", "todt");

        // Agility courses (start obstacle)
        add(l, "Gnome Stronghold Agility", Category.AGILITY, 2474, 3438, 0, "agility", "course", "gnome course");
        add(l, "Draynor Rooftop", Category.AGILITY, 3103, 3279, 0, "agility", "rooftop", "course");
        add(l, "Al Kharid Rooftop", Category.AGILITY, 3273, 3195, 0, "agility", "rooftop", "course");
        add(l, "Varrock Rooftop", Category.AGILITY, 3221, 3414, 0, "agility", "rooftop", "course");
        add(l, "Canifis Rooftop", Category.AGILITY, 3507, 3488, 0, "agility", "rooftop", "course");
        add(l, "Falador Rooftop", Category.AGILITY, 3036, 3341, 0, "agility", "rooftop", "course");
        add(l, "Seers' Rooftop", Category.AGILITY, 2729, 3488, 0, "agility", "rooftop", "course");
        add(l, "Barbarian Outpost Agility", Category.AGILITY, 2552, 3561, 0, "agility", "course");

        // Runecrafting altars (outside the mysterious ruins)
        add(l, "Air Altar", Category.RUNECRAFT, 2983, 3288, 0, "runecrafting", "rc", "air runes");
        add(l, "Mind Altar", Category.RUNECRAFT, 2980, 3510, 0, "runecrafting", "rc", "mind runes");
        add(l, "Water Altar", Category.RUNECRAFT, 3182, 3162, 0, "runecrafting", "rc", "water runes");
        add(l, "Earth Altar", Category.RUNECRAFT, 3304, 3472, 0, "runecrafting", "rc", "earth runes");
        add(l, "Fire Altar", Category.RUNECRAFT, 3311, 3252, 0, "runecrafting", "rc", "fire runes");
        add(l, "Body Altar", Category.RUNECRAFT, 3055, 3443, 0, "runecrafting", "rc", "body runes");
        add(l, "Guardians of the Rift", Category.RUNECRAFT, 3615, 9471, 0, "gotr", "runecrafting", "rc", "temple of the eye");

        // Guilds
        add(l, "Champions' Guild", Category.GUILD, 3191, 3363, 0, "champions guild");
        add(l, "Cooks' Guild", Category.GUILD, 3143, 3443, 0, "cooks guild", "cooking");
        add(l, "Crafting Guild", Category.GUILD, 2933, 3285, 0, "crafting");
        add(l, "Mining Guild", Category.GUILD, 3046, 9756, 0, "mining", "falador");
        add(l, "Fishing Guild", Category.GUILD, 2611, 3393, 0, "fishing");
        add(l, "Ranging Guild", Category.GUILD, 2655, 3439, 0, "ranged", "range guild");
        add(l, "Warriors' Guild", Category.GUILD, 2878, 3546, 0, "warriors guild", "defenders");
        add(l, "Wizards' Tower", Category.GUILD, 3109, 3167, 0, "wizards tower", "sedridor");
        add(l, "Woodcutting Guild", Category.GUILD, 1591, 3479, 0, "woodcutting", "redwood");
        add(l, "Farming Guild", Category.GUILD, 1249, 3727, 0, "farming");
        add(l, "Hunter Guild", Category.GUILD, 1557, 3048, 0, "hunter", "hunters guild", "varlamore");

        // Minigames
        add(l, "Castle Wars", Category.MINIGAME, 2442, 3089, 0, "cw", "castlewars");
        add(l, "Pest Control", Category.MINIGAME, 2659, 2660, 0, "pc", "void knights outpost");
        add(l, "Barbarian Assault", Category.MINIGAME, 2531, 3577, 0, "ba");
        add(l, "Nightmare Zone", Category.MINIGAME, 2609, 3115, 0, "nmz", "yanille");
        add(l, "Mage Training Arena", Category.MINIGAME, 3363, 3318, 0, "mta");
        add(l, "Barrows", Category.MINIGAME, 3565, 3315, 0, "brothers", "morytania");
        add(l, "Giants' Foundry", Category.MINIGAME, 3361, 3150, 0, "foundry", "smithing");
        add(l, "Emir's Arena", Category.MINIGAME, 3381, 3268, 0, "duel arena", "pvp arena");
        add(l, "TzHaar City", Category.MINIGAME, 2446, 5178, 0, "fight caves", "jad", "inferno", "mor ul rek");
        add(l, "Soul Wars", Category.MINIGAME, 2212, 2859, 0, "isle of souls");

        // Landmarks & quest spots
        add(l, "Lumbridge Castle Kitchen", Category.LANDMARK, 3208, 3214, 0, "cook", "cooks assistant");
        add(l, "Draynor Manor", Category.LANDMARK, 3108, 3351, 0, "ernest", "vampyre");
        add(l, "Varrock Palace", Category.LANDMARK, 3212, 3473, 0, "king roald", "castle");
        add(l, "Aubury's Rune Shop", Category.LANDMARK, 3253, 3401, 0, "rune shop", "essence mine");
        add(l, "Fred the Farmer", Category.LANDMARK, 3189, 3273, 0, "sheep shearer");
        add(l, "Falador Park", Category.LANDMARK, 2995, 3376, 0, "fally park");
        add(l, "White Knights' Castle", Category.LANDMARK, 2963, 3341, 0, "falador castle", "sir amik");
        add(l, "Betty's Magic Emporium", Category.LANDMARK, 3013, 3258, 0, "betty", "port sarim", "rune shop");
        add(l, "Thurgo", Category.LANDMARK, 3000, 3145, 0, "knights sword", "redberry pie");
        add(l, "Wise Old Man", Category.LANDMARK, 3088, 3253, 0, "draynor");
        add(l, "Stronghold of Security", Category.LANDMARK, 3081, 3421, 0, "sos", "flying boots");
        add(l, "Edgeville Dungeon", Category.LANDMARK, 3097, 3468, 0, "trapdoor", "hill giants");
        add(l, "Varrock Sewers", Category.LANDMARK, 3237, 3458, 0, "manhole", "moss giants");
        add(l, "Taverley Dungeon", Category.LANDMARK, 2884, 3397, 0, "blue dragons", "chaos druids");
        add(l, "Lumbridge Swamp Caves", Category.LANDMARK, 3169, 3172, 0, "swamp cave", "tears of guthix");
        add(l, "Grand Tree", Category.LANDMARK, 2465, 3495, 0, "gnome", "king narnode");
        add(l, "Fairy Ring (Zanaris)", Category.LANDMARK, 2412, 4434, 0, "fairy ring");

        // Slayer masters
        add(l, "Turael", Category.SLAYER, 2931, 3536, 0, "slayer master", "burthorpe");
        add(l, "Spria", Category.SLAYER, 3092, 3267, 0, "slayer master", "draynor");
        add(l, "Mazchna", Category.SLAYER, 3510, 3507, 0, "slayer master", "canifis");
        add(l, "Vannaka", Category.SLAYER, 3146, 9913, 0, "slayer master", "edgeville dungeon");
        add(l, "Nieve", Category.SLAYER, 2432, 3423, 0, "steve", "slayer master", "gnome stronghold");
        add(l, "Duradel", Category.SLAYER, 2869, 2982, 1, "slayer master", "shilo");

        return Collections.unmodifiableList(l);
    }

    private static void add(List<Destination> l, String name, Category category, int x, int y, int plane, String... aliases) {
        l.add(new Destination(name, category, new WorldPoint(x, y, plane), aliases));
    }

    /** "VARROCK_WEST" → "Varrock West" */
    private static String pretty(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
