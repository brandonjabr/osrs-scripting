package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.questing.QuestPassage;
import net.runelite.client.plugins.microbot.util.reachable.Rs2Reachable;

import java.util.function.Predicate;

import static net.runelite.client.plugins.microbot.util.questing.QuestPassage.area;

/**
 * Tree Gnome Village: the first orb is in a chest at the top of the Khazard tower on the battlefield. The tower's
 * door is locked; the way in and out is the crumbled wall the ballista knocked down (object 2185 at 2509,3253,
 * "Climb-over"). The walker doesn't know about the wall and keeps trying the locked door at (2502,3250), which the
 * game refuses every time, so the quest stalled on "Climb the ladder". These passages climb over the wall to get in
 * (the Khazard commander who attacks inside is fought by auto-retaliate), and on the way out back down the ladder
 * and over the wall again.
 */
final class TreeGnomeVillageSupport {

    private static final int CRUMBLED_WALL = 2185;
    private static final WorldPoint WALL = new WorldPoint(2509, 3253, 0);
    private static final WorldPoint LADDER = new WorldPoint(2503, 3252, 0);
    private static final WorldPoint LADDER_TOP = new WorldPoint(2503, 3252, 1);
    private static final WorldPoint TOWER = new WorldPoint(2504, 3255, 0);

    /** Upstairs (Quest Helper's zone, which matches the collision map's floor). */
    private static final Predicate<WorldPoint> UPSTAIRS = area(2500, 3251, 2506, 3259, 1);
    /** A step target in the tower: the ground floor (generously; the wall is at x 2509) or upstairs. */
    private static final Predicate<WorldPoint> TOWER_TARGET = area(2499, 3250, 2508, 3259, 0).or(UPSTAIRS);

    static final QuestPassage CLIMB_IN = QuestPassage.named("climbing over the crumbled wall into the Khazard tower")
            .from(p -> p.getPlane() == 0 && p.distanceTo(TOWER) <= 25 && !insideTower())
            .toTarget(TOWER_TARGET)
            .object(CRUMBLED_WALL, "Climb-over", WALL)
            .build();

    static final QuestPassage LADDER_DOWN = QuestPassage.named("climbing down the Khazard tower")
            .from(UPSTAIRS)
            .toTarget(UPSTAIRS.negate())
            .object(-1, "Climb-down", LADDER_TOP)
            .build();

    static final QuestPassage CLIMB_OUT = QuestPassage.named("climbing out of the Khazard tower over the crumbled wall")
            .from(p -> p.getPlane() == 0 && p.distanceTo(TOWER) <= 12 && insideTower())
            .toTarget(TOWER_TARGET.negate())
            .object(CRUMBLED_WALL, "Climb-over", WALL)
            .build();

    private TreeGnomeVillageSupport() {
    }

    /**
     * Inside the tower's ground floor: a tile beside the ladder can be walked to. From outside, the locked door
     * keeps all of them out of reach.
     */
    private static boolean insideTower() {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (Rs2Reachable.isReachableFromPlayer(new WorldPoint(LADDER.getX() + d[0], LADDER.getY() + d[1], 0))) {
                return true;
            }
        }
        return false;
    }
}
