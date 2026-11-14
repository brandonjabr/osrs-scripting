package net.runelite.client.plugins.microbot.shadowfaxslayer;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.slayer.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How to do each slayer task Turael, Vannaka and Nieve hand out: where to go (spots picked for short travel and
 * few requirements, with spawn centres taken from Microbot's NPC location data), what to attack, the preferred
 * combat style, and what to bring (protective gear from the slayer shop, the finishing item, light, rope,
 * waterskins).
 * <p>
 * Tasks missing from this list (dragons, Wilderness-only tasks, ones behind awkward quests or special mechanics)
 * are skipped through Turael, or the script stops, depending on the config.
 */
public enum TaskGuide {

    // ---------------- Turael ----------------
    BIRDS(def(Task.BIRDS).npcs("Chicken", "Rooster")
            .spot("Lumbridge chickens", 3178, 3294, 0, 12, false)
            .spot("Falador farm chickens", 3022, 3288, 0, 10, false)),
    BATS(def(Task.BATS).npcs("Bat", "Giant bat")
            .spot("Silvarea bats", 3365, 3487, 0, 20, true)
            .spot("Taverley Dungeon giant bats", 2915, 9832, 0, 12, true)),
    BEARS(def(Task.BEARS).npcs("Grizzly bear", "Black bear", "Bear cub", "Grizzly bear cub")
            .spot("Ardougne grizzly bears", 2703, 3336, 0, 14, true)
            .spot("Mind Altar black bears", 2971, 3480, 0, 22, false)),
    CAVE_BUGS(def(Task.CAVE_BUGS).npcs("Cave bug").light().swampRope()
            .spot("Lumbridge Swamp Caves", 3184, 9568, 0, 20, true)),
    CAVE_CRAWLERS(def(Task.CAVE_CRAWLERS).npcs("Cave crawler").antipoison()
            .spot("Fremennik Slayer Dungeon", 2787, 9996, 0, 10, true)),
    CAVE_SLIMES(def(Task.CAVE_SLIMES).npcs("Cave slime").light().swampRope().antipoison()
            .spot("Lumbridge Swamp Caves", 3187, 9563, 0, 20, true)),
    COWS(def(Task.COWS).npcs("Cow", "Cow calf")
            .spot("Lumbridge east cows", 3254, 3278, 0, 14, false)
            .spot("Champions' Guild cows", 3185, 3316, 0, 16, false)),
    CRAWLING_HANDS(def(Task.CRAWLING_HANDS).npcs("Crawling Hand")
            .spot("Slayer Tower ground floor", 3421, 3562, 0, 14, true)),
    BANSHEES(def(Task.BANSHEES).npcs("Banshee").wear(ShopItem.EARMUFFS)
            .spot("Slayer Tower ground floor", 3438, 3550, 0, 12, true)),
    DOGS(def(Task.DOGS).npcs("Guard dog", "Jackal", "Wild dog")
            .spot("McGrubor's Wood guard dogs", 2650, 3490, 0, 20, true)),
    DWARVES(def(Task.DWARVES).npcs("Dwarf")
            .spot("Ice Mountain dwarves", 3016, 3449, 0, 16, false)
            .spot("Dwarven Mine", 3019, 9825, 0, 20, false)),
    GHOSTS(def(Task.GHOSTS).npcs("Ghost")
            .spot("Taverley Dungeon ghosts", 2913, 9837, 0, 16, true)),
    GOBLINS(def(Task.GOBLINS).npcs("Goblin")
            .spot("Lumbridge goblins", 3250, 3235, 0, 14, false)
            .spot("Port Sarim goblins", 3000, 3207, 0, 10, false)),
    ICEFIENDS(def(Task.ICEFIENDS).npcs("Icefiend")
            .spot("Top of Ice Mountain", 3003, 3479, 0, 12, false)),
    KALPHITE(def(Task.KALPHITE).npcs("Kalphite Worker", "Kalphite Soldier").supply("Rope", ItemID.ROPE, 1, true)
            .spot("Kalphite Cave", 3299, 9511, 0, 18, true)
            .spot("Kalphite Lair", 3184, 9486, 0, 16, true)),
    LIZARDS(def(Task.LIZARDS).npcs("Desert Lizard", "Lizard", "Small Lizard").finisher(ShopItem.ICE_COOLER).desert()
            .spot("Desert lizards south of Shantay", 3413, 3048, 0, 20, true)),
    MINOTAURS(def(Task.MINOTAURS).npcs("Minotaur")
            .spot("Stronghold of Security, level 1", 1888, 5213, 0, 16, false)),
    MONKEYS(def(Task.MONKEYS).npcs("Monkey")
            .spot("Tai Bwo Wannai monkeys", 2902, 3079, 0, 12, true)
            .spot("Ardougne Zoo monkeys", 2602, 3278, 0, 6, true)),
    RATS(def(Task.RATS).npcs("Giant rat", "Rat")
            .spot("Lumbridge Swamp giant rats", 3197, 3180, 0, 20, false)
            .spot("Varrock Sewers rats", 3244, 9871, 0, 18, false)),
    SCORPIONS(def(Task.SCORPIONS).npcs("Scorpion")
            .spot("Al Kharid mine", 3299, 3299, 0, 14, false)),
    SKELETONS(def(Task.SKELETONS).npcs("Skeleton")
            .spot("Edgeville Dungeon skeletons", 3122, 9895, 0, 16, false)),
    SPIDERS(def(Task.SPIDERS).npcs("Giant spider", "Spider")
            .spot("Lumbridge spiders", 3243, 3242, 0, 12, false)),
    WOLVES(def(Task.WOLVES).npcs("White wolf", "Wolf")
            .spot("White Wolf Mountain", 2853, 3482, 0, 20, true)),
    ZOMBIES(def(Task.ZOMBIES).npcs("Zombie")
            .spot("Edgeville Dungeon zombies", 3146, 9894, 0, 12, false)),

    // ---------------- Vannaka and Nieve ----------------
    ABERRANT_SPECTRES(def(Task.ABERRANT_SPECTRES).npcs("Aberrant spectre").wear(ShopItem.NOSE_PEG)
            .spot("Stronghold Slayer Cave", 2455, 9782, 0, 14, true)
            .spot("Slayer Tower first floor", 3427, 3545, 1, 14, true)),
    ABYSSAL_DEMONS(def(Task.ABYSSAL_DEMONS).npcs("Abyssal demon")
            .spot("Slayer Tower top floor", 3417, 3569, 2, 10, true)),
    ANKOU(def(Task.ANKOU).npcs("Ankou")
            .spot("Stronghold Slayer Cave", 2475, 9802, 0, 10, true)),
    BASILISKS(def(Task.BASILISKS).npcs("Basilisk").wear(ShopItem.MIRROR_SHIELD)
            .spot("Fremennik Slayer Dungeon", 2742, 10009, 0, 8, true)),
    BLOODVELD(def(Task.BLOODVELD).npcs("Bloodveld")
            .spot("Slayer Tower first floor", 3418, 3566, 1, 12, true)
            .spot("Stronghold Slayer Cave", 2461, 9824, 0, 20, true)),
    COCKATRICE(def(Task.COCKATRICE).npcs("Cockatrice").wear(ShopItem.MIRROR_SHIELD)
            .spot("Fremennik Slayer Dungeon", 2792, 10034, 0, 8, true)),
    CRABS(def(Task.CRABS).npcs("Rock Crab", "Sand Crab", "Swamp Crab")
            .spot("Rellekka rock crabs", 2689, 3722, 0, 20, true)),
    CROCODILES(def(Task.CROCODILES).npcs("Crocodile").desert()
            .spot("River Elid crocodiles", 3268, 2852, 0, 20, true)),
    DAGANNOTH(def(Task.DAGANNOTH).npcs("Dagannoth")
            .spot("Lighthouse basement", 2522, 10023, 0, 14, true)),
    DUST_DEVILS(def(Task.DUST_DEVILS).npcs("Dust devil").wear(ShopItem.FACEMASK)
            .spot("Smoke Dungeon", 3217, 9375, 0, 20, true)),
    FIRE_GIANTS(def(Task.FIRE_GIANTS).npcs("Fire giant")
            .spot("Stronghold Slayer Cave", 2401, 9776, 0, 12, true)
            .spot("Waterfall Dungeon", 2573, 9891, 0, 12, true)),
    GARGOYLES(def(Task.GARGOYLES).npcs("Gargoyle").finisher(ShopItem.ROCK_HAMMER)
            .spot("Slayer Tower top floor", 3440, 3542, 2, 10, true)),
    GHOULS(def(Task.GHOULS).npcs("Ghoul")
            .spot("West of Canifis", 3416, 3514, 0, 10, true)),
    GREATER_DEMONS(def(Task.GREATER_DEMONS).npcs("Greater demon")
            .spot("Chasm of Fire", 1376, 9988, 0, 16, true)),
    BLACK_DEMONS(def(Task.BLACK_DEMONS).npcs("Black demon")
            .spot("Chasm of Fire", 1374, 9886, 0, 16, true)),
    HELLHOUNDS(def(Task.HELLHOUNDS).npcs("Hellhound")
            .spot("Stronghold Slayer Cave", 2422, 9784, 0, 14, true)),
    HILL_GIANTS(def(Task.HILL_GIANTS).npcs("Hill Giant")
            .spot("Edgeville Dungeon hill giants", 3111, 9838, 0, 12, false)),
    HOBGOBLINS(def(Task.HOBGOBLINS).npcs("Hobgoblin")
            .spot("Hobgoblin Peninsula", 2911, 3282, 0, 14, false)),
    ICE_GIANTS(def(Task.ICE_GIANTS).npcs("Ice giant")
            .spot("White Wolf Mountain ice giants", 2814, 3509, 0, 12, true)),
    ICE_WARRIORS(def(Task.ICE_WARRIORS).npcs("Ice warrior")
            .spot("Asgarnian Ice Dungeon", 3054, 9580, 0, 12, false)),
    INFERNAL_MAGES(def(Task.INFERNAL_MAGES).npcs("Infernal Mage")
            .spot("Slayer Tower first floor", 3440, 3566, 1, 10, true)),
    JELLIES(def(Task.JELLIES).npcs("Jelly")
            .spot("Fremennik Slayer Dungeon", 2704, 10027, 0, 8, true)),
    KURASK(def(Task.KURASK).npcs("Kurask").leafBladed()
            .spot("Fremennik Slayer Dungeon", 2706, 9968, 0, 12, true)),
    LESSER_DEMONS(def(Task.LESSER_DEMONS).npcs("Lesser demon")
            .spot("Karamja Volcano", 2838, 9582, 0, 18, false)),
    MOSS_GIANTS(def(Task.MOSS_GIANTS).npcs("Moss giant")
            .spot("Varrock Sewers moss giants", 3160, 9893, 0, 12, false)
            .spot("Brimhaven Dungeon", 2655, 9547, 0, 20, true)),
    NECHRYAEL(def(Task.NECHRYAEL).npcs("Nechryael")
            .spot("Slayer Tower top floor", 3442, 3569, 2, 10, true)),
    OGRES(def(Task.OGRES).npcs("Ogre")
            .spot("Combat Training Camp", 2527, 3374, 0, 8, true)),
    PYREFIENDS(def(Task.PYREFIENDS).npcs("Pyrefiend")
            .spot("Fremennik Slayer Dungeon", 2761, 10004, 0, 8, true)),
    TROLLS(def(Task.TROLLS).npcs("Mountain troll", "Troll")
            .spot("Death Plateau", 2867, 3591, 0, 12, true)),
    TUROTH(def(Task.TUROTH).npcs("Turoth").leafBladed()
            .spot("Fremennik Slayer Dungeon", 2723, 10005, 0, 10, true)),
    VAMPYRES(def(Task.VAMPYRES).npcs("Feral Vampyre", "Vampyre Juvinate")
            .spot("Haunted Woods", 3612, 3475, 0, 24, true));

    /** One place a task can be done. */
    public static final class Spot {
        private final String label;
        private final WorldPoint centre;
        private final int radius;
        private final boolean members;

        Spot(String label, WorldPoint centre, int radius, boolean members) {
            this.label = label;
            this.centre = centre;
            this.radius = radius;
            this.members = members;
        }

        public String label() {
            return label;
        }

        public WorldPoint centre() {
            return centre;
        }

        public int radius() {
            return radius;
        }

        public boolean members() {
            return members;
        }
    }

    /** Something to take from the bank for the task. Optional ones are taken if banked and skipped if not. */
    public static final class Supply {
        private final String name;
        private final int itemId;
        private final int quantity;
        private final boolean optional;

        Supply(String name, int itemId, int quantity, boolean optional) {
            this.name = name;
            this.itemId = itemId;
            this.quantity = quantity;
            this.optional = optional;
        }

        public String name() {
            return name;
        }

        public int itemId() {
            return itemId;
        }

        public int quantity() {
            return quantity;
        }

        public boolean optional() {
            return optional;
        }
    }

    private final Task task;
    private final List<String> npcNames;
    private final List<Spot> spots;
    private final List<ShopItem> wear;
    private final ShopItem finisher;
    private final boolean leafBladed;
    private final boolean light;
    private final boolean antipoison;
    private final boolean swampRope;
    private final List<Supply> supplies;

    TaskGuide(Def def) {
        this.task = def.task;
        this.npcNames = Collections.unmodifiableList(def.npcs);
        this.spots = Collections.unmodifiableList(def.spots);
        this.wear = Collections.unmodifiableList(def.wear);
        this.finisher = def.finisher;
        this.leafBladed = def.leafBladed;
        this.light = def.light;
        this.antipoison = def.antipoison;
        this.swampRope = def.swampRope;
        this.supplies = Collections.unmodifiableList(def.supplies);
    }

    public Task task() {
        return task;
    }

    public String displayName() {
        return task.getName();
    }

    public List<String> npcNames() {
        return npcNames;
    }

    /** Whether an NPC with this name counts for the task. */
    public boolean isTarget(String npcName) {
        if (npcName == null) return false;
        for (String n : npcNames) {
            if (n.equalsIgnoreCase(npcName)) return true;
        }
        return false;
    }

    /** Spots usable on this world (members spots only on members worlds), best first. */
    public List<Spot> spots() {
        boolean members = ItemProcurer.onMembersWorld();
        return spots.stream().filter(s -> members || !s.members()).collect(Collectors.toList());
    }

    /** Protective gear worn for the whole task. */
    public List<ShopItem> wear() {
        return wear;
    }

    /** The item used on the monster to finish it (rock hammer, ice cooler), or null. */
    public ShopItem finisher() {
        return finisher;
    }

    /** Needs a leaf-bladed weapon (turoth, kurask). */
    public boolean leafBladed() {
        return leafBladed;
    }

    /** Needs a lit light source (the Lumbridge Swamp Caves are pitch black). */
    public boolean light() {
        return light;
    }

    public boolean antipoison() {
        return antipoison;
    }

    /** In the Lumbridge Swamp Caves: the entrance hole needs a rope tied to it once per account. */
    public boolean swampRope() {
        return swampRope;
    }

    public List<Supply> supplies() {
        return supplies;
    }

    /** The guide for a task name as the game reports it ("Birds", "Hill giants", …), or null if unsupported. */
    public static TaskGuide forTaskName(String name) {
        if (name == null) return null;
        Task task = Task.getTask(name.trim().toLowerCase(Locale.ROOT));
        for (TaskGuide g : values()) {
            if (task != null && g.task == task) return g;
        }
        // The game's name and RuneLite's can differ in the plural ("Kalphite" / "Kalphites").
        String wanted = normalise(name);
        for (TaskGuide g : values()) {
            if (normalise(g.task.getName()).equals(wanted)) return g;
        }
        return null;
    }

    private static String normalise(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("ves")) return s.substring(0, s.length() - 3) + "f";
        if (s.endsWith("es") && !s.endsWith("ees")) s = s.substring(0, s.length() - 1);
        return s.endsWith("s") ? s.substring(0, s.length() - 1) : s;
    }

    // --- Builder ---

    private static Def def(Task task) {
        return new Def(task);
    }

    private static final class Def {
        private final Task task;
        private final List<String> npcs = new ArrayList<>();
        private final List<Spot> spots = new ArrayList<>();
        private final List<ShopItem> wear = new ArrayList<>();
        private final List<Supply> supplies = new ArrayList<>();
        private ShopItem finisher;
        private boolean leafBladed;
        private boolean light;
        private boolean antipoison;
        private boolean swampRope;

        private Def(Task task) {
            this.task = task;
        }

        Def npcs(String... names) {
            npcs.addAll(Arrays.asList(names));
            return this;
        }

        Def spot(String label, int x, int y, int plane, int radius, boolean members) {
            spots.add(new Spot(label, new WorldPoint(x, y, plane), radius, members));
            return this;
        }

        Def wear(ShopItem item) {
            wear.add(item);
            return this;
        }

        Def finisher(ShopItem item) {
            finisher = item;
            return this;
        }

        Def leafBladed() {
            leafBladed = true;
            return this;
        }

        Def light() {
            light = true;
            return this;
        }

        Def antipoison() {
            antipoison = true;
            return this;
        }

        Def swampRope() {
            swampRope = true;
            return this;
        }

        /** The Kharidian Desert: waterskins against the heat, taken when banked. */
        Def desert() {
            return supply("Waterskin(4)", ItemID.WATER_SKIN4, 4, true);
        }

        Def supply(String name, int itemId, int quantity, boolean optional) {
            supplies.add(new Supply(name, itemId, quantity, optional));
            return this;
        }
    }
}
