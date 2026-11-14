package net.runelite.client.plugins.microbot.util.woodcutting;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Where to chop each tree: groups of trees close to a bank, from the OSRS Wiki's Woodcutting training guides.
 * Listed best first per tree; members spots are skipped on free-to-play worlds. The centre and radius only need to
 * cover the group: trees are found by name around the centre, so a spot doesn't depend on exact tree tiles.
 */
public enum TreeSpot {
    // Regular trees: the wiki names no spot, so busy-free groups next to a bank.
    VARROCK_WEST_TREES(TreeType.TREE, "Outside Varrock's west gate", new WorldPoint(3162, 3414, 0), 14,
            BankLocation.VARROCK_WEST, false),
    LUMBRIDGE_TREES(TreeType.TREE, "West of Lumbridge Castle", new WorldPoint(3164, 3224, 0), 14,
            BankLocation.DRAYNOR_VILLAGE, false),

    SEERS_OAKS(TreeType.OAK, "South of Seers' Village bank", new WorldPoint(2724, 3481, 0), 10,
            BankLocation.CAMELOT, true),
    VARROCK_WEST_OAKS(TreeType.OAK, "Outside Varrock's west gate", new WorldPoint(3164, 3416, 0), 14,
            BankLocation.VARROCK_WEST, false),

    SEERS_WILLOWS(TreeType.WILLOW, "North-west of Seers' Village bank", new WorldPoint(2711, 3510, 0), 10,
            BankLocation.CAMELOT, true),
    DRAYNOR_WILLOWS(TreeType.WILLOW, "South of Draynor Village bank", new WorldPoint(3087, 3235, 0), 10,
            BankLocation.DRAYNOR_VILLAGE, false),

    CASTLE_WARS_TEAK(TreeType.TEAK, "South-west of Castle Wars", new WorldPoint(2335, 3048, 0), 12,
            BankLocation.CASTLE_WARS, true),

    SEERS_MAPLES(TreeType.MAPLE, "Around Seers' Village bank", new WorldPoint(2726, 3492, 0), 16,
            BankLocation.CAMELOT, true),

    GNOME_YEWS(TreeType.YEW, "Tree Gnome Stronghold, north of Nieve", new WorldPoint(2433, 3434, 0), 12,
            BankLocation.TREE_GNOME_STRONGHOLD_NIEVE, true),
    EDGEVILLE_YEWS(TreeType.YEW, "South of Edgeville bank", new WorldPoint(3087, 3474, 0), 10,
            BankLocation.EDGEVILLE, false),
    VARROCK_PALACE_YEWS(TreeType.YEW, "Behind Varrock Palace", new WorldPoint(3208, 3502, 0), 12,
            BankLocation.GRAND_EXCHANGE, false),

    SEERS_MAGICS(TreeType.MAGIC, "South of the Seers' yews, by the Sorcerer's Tower", new WorldPoint(2697, 3424, 0), 12,
            BankLocation.CAMELOT, true),
    GNOME_MAGICS(TreeType.MAGIC, "West of the Tree Gnome Stronghold bank", new WorldPoint(2432, 3420, 0), 12,
            BankLocation.TREE_GNOME_STRONGHOLD_NIEVE, true);

    private final TreeType tree;
    private final String label;
    private final WorldPoint centre;
    private final int radius;
    private final BankLocation bank;
    private final boolean members;

    TreeSpot(TreeType tree, String label, WorldPoint centre, int radius, BankLocation bank, boolean members) {
        this.tree = tree;
        this.label = label;
        this.centre = centre;
        this.radius = radius;
        this.bank = bank;
        this.members = members;
    }

    public TreeType tree() {
        return tree;
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

    public BankLocation bank() {
        return bank;
    }

    public boolean members() {
        return members;
    }

    public boolean available() {
        return !members || ItemProcurer.onMembersWorld();
    }

    /** The tree's spots usable on this world, best first. */
    public static List<TreeSpot> forTree(TreeType tree) {
        List<TreeSpot> spots = new ArrayList<>();
        for (TreeSpot spot : values()) {
            if (spot.tree == tree && spot.available()) spots.add(spot);
        }
        return spots;
    }

    public static boolean hasSpot(TreeType tree) {
        return !forTree(tree).isEmpty();
    }
}
