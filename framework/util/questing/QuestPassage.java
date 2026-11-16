package net.runelite.client.plugins.microbot.util.questing;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.reachable.Rs2Reachable;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * A quest-specific way through a spot the walker can't handle on its own: a locked door with another way round (the
 * Khazard tower's crumbled wall), a door opened with a key or item, a ladder, or a fixed path that has to be walked
 * tile by tile while keeping away from an NPC (the Witch's garden hedges).
 * <p>
 * A quest lists its passages on its {@link QuestDefinition}. Every tick, after the quest's step overrides and before
 * the generic executor, the runner takes the first passage that applies:
 * <ul>
 *   <li>its {@link Builder#when extra condition} holds (if given),</li>
 *   <li>the player is on its near side ({@link Builder#from}), and</li>
 *   <li>the step's target is on its far side ({@link Builder#toTarget}), or the step's text contains its
 *       {@link Builder#forStep fragment}.</li>
 * </ul>
 * It then walks its {@link Builder#path path} (if any) and uses its {@link Builder#object object} (if any). Passages
 * chain across ticks: once one is crossed the player is on the next one's near side.
 */
@Slf4j
public final class QuestPassage {

    private static final int HOP_TILES = 5;

    private final String name;
    private final Predicate<QuestContext> when;
    private final Predicate<WorldPoint> from;
    private final Predicate<WorldPoint> toTarget;
    private final String stepFragment;
    private final List<WorldPoint> path;
    private final int avoidNpcId;
    private final int avoidDistance;
    private final int objectId;
    private final String action;
    private final WorldPoint objectAt;
    private final int useItemId;

    private QuestPassage(Builder b) {
        this.name = b.name;
        this.when = b.when;
        this.from = b.from;
        this.toTarget = b.toTarget;
        this.stepFragment = b.stepFragment == null ? null : b.stepFragment.toLowerCase(Locale.ROOT);
        this.path = List.copyOf(b.path);
        this.avoidNpcId = b.avoidNpcId;
        this.avoidDistance = b.avoidDistance;
        this.objectId = b.objectId;
        this.action = b.action;
        this.objectAt = b.objectAt;
        this.useItemId = b.useItemId;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** A box of tiles on one plane, corners inclusive (either order). */
    public static Predicate<WorldPoint> area(int x1, int y1, int x2, int y2, int plane) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        return p -> p != null && p.getPlane() == plane
                && p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY;
    }

    public String name() {
        return name;
    }

    /** Whether this passage should take the tick for this player position and step target (null when the step has none). */
    boolean appliesTo(QuestContext ctx, WorldPoint player, WorldPoint target) {
        if (player == null || !from.test(player)) return false;
        if (when != null && !when.test(ctx)) return false;
        boolean byTarget = toTarget != null && target != null && toTarget.test(target);
        boolean byStep = stepFragment != null && ctx.stepText().toLowerCase(Locale.ROOT).contains(stepFragment);
        return byTarget || byStep;
    }

    /** One tick of crossing. @return true if it acted, false to let the generic executor have the tick */
    boolean run(QuestContext ctx) {
        if (Rs2Combat.inCombat()) return false; // fights are the runner's (and auto-retaliate's) business
        if (Rs2Dialogue.isInDialogue()) return ctx.continueDialogue();
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return true;

        if (!path.isEmpty() && !pathDone(here)) {
            return walkPath(here);
        }
        if (objectAt != null) {
            return useObject(ctx, here);
        }
        return false;
    }

    // --- Path ---

    private boolean pathDone(WorldPoint here) {
        WorldPoint last = path.get(path.size() - 1);
        return here.getPlane() == last.getPlane() && here.distanceTo(last) <= 1;
    }

    private boolean walkPath(WorldPoint here) {
        WorldPoint next = nextHop(here);
        if (avoidNpcId > 0) {
            Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withId(avoidNpcId).nearestOnClientThread();
            WorldPoint at = npc == null ? null : npc.getWorldLocation();
            if (at != null && at.getPlane() == here.getPlane()
                    && (at.distanceTo(next) <= avoidDistance || at.distanceTo(here) <= avoidDistance)) {
                Microbot.status = name + ": waiting for " + npc.getName() + " to move away";
                return true;
            }
        }
        Microbot.status = name;
        boolean direct = here.distanceTo(next) <= HOP_TILES + 1 && Rs2Reachable.isReachableFromPlayer(next);
        if (direct) {
            Rs2Walker.walkFastCanvas(next);
        } else {
            // A door (or anything else the walker opens) between here and the next tile.
            Rs2Walker.walkTo(next, 0);
        }
        sleepUntil(() -> {
            WorldPoint now = Rs2Player.getWorldLocation();
            return now != null && now.distanceTo(next) <= 1;
        }, 4_000);
        return true;
    }

    /**
     * The next tile to walk to: up to {@value #HOP_TILES} tiles along the path past the waypoint segment the player
     * is on, so the walk goes corner by corner and can stop between hops if an NPC comes close.
     */
    private WorldPoint nextHop(WorldPoint here) {
        List<WorldPoint> tiles = expand();
        int nearest = 0;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            WorldPoint t = tiles.get(i);
            int d = t.getPlane() == here.getPlane() ? t.distanceTo(here) : Integer.MAX_VALUE;
            if (d <= best) { // ties go to the later tile, so the walk moves forward
                best = d;
                nearest = i;
            }
        }
        if (best > 1) return tiles.get(nearest); // off the path: back onto it first
        int target = Math.min(tiles.size() - 1, nearest + HOP_TILES);
        // Stop at a corner, so a hop never cuts one.
        for (int i = nearest + 1; i < target; i++) {
            if (path.contains(tiles.get(i))) return tiles.get(i);
        }
        return tiles.get(target);
    }

    /** Every tile along the path's straight (or diagonal) legs, waypoints included. */
    private List<WorldPoint> expand() {
        List<WorldPoint> tiles = new ArrayList<>();
        tiles.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            WorldPoint a = path.get(i - 1);
            WorldPoint b = path.get(i);
            int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getY() - a.getY()));
            for (int s = 1; s <= steps; s++) {
                tiles.add(new WorldPoint(a.getX() + Integer.signum(b.getX() - a.getX()) * Math.min(s, Math.abs(b.getX() - a.getX())),
                        a.getY() + Integer.signum(b.getY() - a.getY()) * Math.min(s, Math.abs(b.getY() - a.getY())), b.getPlane()));
            }
        }
        return tiles;
    }

    // --- Object ---

    private boolean useObject(QuestContext ctx, WorldPoint here) {
        Rs2TileObjectModel object = findObject();
        if (object == null || here.getPlane() != objectAt.getPlane() || here.distanceTo(objectAt) > 10) {
            Microbot.status = name + ": walking there";
            ctx.walkTo(objectAt, 2);
            return true;
        }
        Microbot.status = name;
        boolean clicked = useItemId > 0 && Rs2Inventory.hasItem(useItemId)
                ? ctx.useItemOn(useItemId, object)
                : action != null ? object.click(action) : object.click();
        if (clicked) {
            log.info("[Questing] {}: {}", ctx.definition().getLabel(), name);
            sleepUntil(() -> {
                WorldPoint now = Rs2Player.getWorldLocation();
                return now == null || !from.test(now) || Rs2Dialogue.isInDialogue();
            }, 6_000);
        }
        return true;
    }

    private Rs2TileObjectModel findObject() {
        if (objectId > 0) {
            return Microbot.getRs2TileObjectCache().query().withId(objectId).nearestOnClientThread(objectAt, 3);
        }
        return Microbot.getRs2TileObjectCache().query().withAction(action).nearestOnClientThread(objectAt, 3);
    }

    public static final class Builder {
        private final String name;
        private Predicate<QuestContext> when;
        private Predicate<WorldPoint> from = p -> true;
        private Predicate<WorldPoint> toTarget;
        private String stepFragment;
        private final List<WorldPoint> path = new ArrayList<>();
        private int avoidNpcId = -1;
        private int avoidDistance;
        private int objectId = -1;
        private String action;
        private WorldPoint objectAt;
        private int useItemId = -1;

        private Builder(String name) {
            this.name = name;
        }

        /** Extra condition, e.g. an item carried or a varbit. */
        public Builder when(Predicate<QuestContext> condition) {
            this.when = condition;
            return this;
        }

        /** The near side: the passage only applies while the player's position matches. */
        public Builder from(Predicate<WorldPoint> playerSide) {
            this.from = playerSide;
            return this;
        }

        /** The far side: applies when the active step's target (its map point) matches. */
        public Builder toTarget(Predicate<WorldPoint> targetSide) {
            this.toTarget = targetSide;
            return this;
        }

        /** Also applies while the step's text contains this (case-insensitive), for steps with no map point. */
        public Builder forStep(String fragment) {
            this.stepFragment = fragment;
            return this;
        }

        /** Waypoints walked in order (straight or diagonal legs between them) before the object, if any. */
        public Builder path(WorldPoint... waypoints) {
            path.addAll(List.of(waypoints));
            return this;
        }

        /** While walking the path, wait whenever this NPC is within {@code distance} of the player or the next hop. */
        public Builder avoid(int npcId, int distance) {
            this.avoidNpcId = npcId;
            this.avoidDistance = distance;
            return this;
        }

        /** The object to use, by ID (or any object with {@code action} when {@code id} is -1) near {@code at}. */
        public Builder object(int id, String action, WorldPoint at) {
            this.objectId = id;
            this.action = action;
            this.objectAt = at;
            return this;
        }

        /** Use this item on the object (when carried) instead of clicking its action. */
        public Builder useItem(int itemId) {
            this.useItemId = itemId;
            return this;
        }

        public QuestPassage build() {
            if (toTarget == null && stepFragment == null) {
                throw new IllegalStateException(name + ": a passage needs toTarget or forStep");
            }
            if (path.isEmpty() && objectAt == null) {
                throw new IllegalStateException(name + ": a passage needs a path or an object");
            }
            return new QuestPassage(this);
        }
    }
}
