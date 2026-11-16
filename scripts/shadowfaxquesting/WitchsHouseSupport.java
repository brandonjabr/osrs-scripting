package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.QuestPassage;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;

import java.util.function.Predicate;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.questing.QuestPassage.area;

/**
 * Witch's House: the way into the garden and round it.
 * <ul>
 *   <li><b>The mouse:</b> Quest Helper's step says to use the cheese on the mouse hole, then the magnet on the mouse
 *   that comes out, without highlighting either item, so the generic executor only clicks the hole. This does both
 *   halves; the mouse with the magnet is what lets the garden door open.</li>
 *   <li><b>The garden:</b> hedges leave one route, the perimeter: down the west path, east along the south path, up
 *   the east side, then back west along the north path to the fountain (traced on the walker's collision map; the
 *   grass in the middle can't be crossed). The witch, Nora T. Hagg, patrols it and getting seen means starting over
 *   from the mouse, so the walk goes a few tiles at a time and waits while she's close.</li>
 *   <li><b>The shed:</b> its door takes the key from the fountain.</li>
 * </ul>
 */
final class WitchsHouseSupport {

    private static final Predicate<WorldPoint> HOUSE = area(2901, 3466, 2907, 3476, 0);
    private static final Predicate<WorldPoint> FOUNTAIN_AREA = area(2908, 3467, 2912, 3475, 0);
    private static final Predicate<WorldPoint> GARDEN = area(2900, 3459, 2933, 3465, 0)
            .or(area(2908, 3466, 2933, 3467, 0)).or(FOUNTAIN_AREA);
    private static final Predicate<WorldPoint> SHED = area(2934, 3459, 2937, 3467, 0);
    private static final Predicate<WorldPoint> OUTSIDE = HOUSE.or(GARDEN).or(SHED).negate();

    private static final WorldPoint MOUSE_ROOM = new WorldPoint(2903, 3467, 0);
    private static final WorldPoint SHED_DOOR = new WorldPoint(2934, 3463, 0);
    private static final WorldPoint BY_SHED_DOOR = new WorldPoint(2933, 3463, 0);
    private static final int WITCH_DISTANCE = 4;

    static final StepOverride MOUSE = new StepOverride("cheese for the mouse, then the magnet on it",
            ctx -> ctx.stepText().contains("cheese on the mouse hole") && Rs2Inventory.hasItem(ItemID.MAGNET),
            WitchsHouseSupport::mouse);

    /** House → fountain, round the perimeter. */
    static final QuestPassage TO_FOUNTAIN = QuestPassage.named("sneaking round the garden to the fountain")
            .from(HOUSE.or(GARDEN).and(FOUNTAIN_AREA.negate()))
            .toTarget(FOUNTAIN_AREA)
            .path(MOUSE_ROOM, new WorldPoint(2903, 3466, 0), new WorldPoint(2901, 3466, 0),
                    new WorldPoint(2901, 3460, 0), new WorldPoint(2933, 3460, 0), new WorldPoint(2933, 3466, 0),
                    new WorldPoint(2912, 3466, 0), new WorldPoint(2912, 3467, 0), new WorldPoint(2911, 3467, 0),
                    new WorldPoint(2911, 3470, 0))
            .avoid(NpcID.NORA_T_HAGG, WITCH_DISTANCE)
            .build();

    /** At the shed: the key from the fountain opens it (and the door opens without it afterwards). */
    static final QuestPassage SHED_DOOR_PASSAGE = QuestPassage.named("opening the shed with the key")
            .from(p -> p != null && p.getPlane() == 0 && p.distanceTo(BY_SHED_DOOR) <= 1)
            .toTarget(SHED)
            .object(ObjectID.WITCHSHEDDOOR, "Open", SHED_DOOR)
            .useItem(ItemID.WITCHES_SHEDKEY)
            .build();

    /** Fountain → shed door, back along the north path and down the east side. */
    static final QuestPassage FOUNTAIN_TO_SHED = QuestPassage.named("sneaking from the fountain to the shed")
            .from(FOUNTAIN_AREA)
            .toTarget(SHED)
            .path(new WorldPoint(2911, 3470, 0), new WorldPoint(2911, 3467, 0), new WorldPoint(2912, 3467, 0),
                    new WorldPoint(2912, 3466, 0), new WorldPoint(2933, 3466, 0), BY_SHED_DOOR)
            .avoid(NpcID.NORA_T_HAGG, WITCH_DISTANCE)
            .build();

    /** House (or the south side of the garden) → shed door, round the west and south paths. */
    static final QuestPassage HOUSE_TO_SHED = QuestPassage.named("sneaking round the garden to the shed")
            .from(HOUSE.or(GARDEN).and(FOUNTAIN_AREA.negate()).and(p -> p.distanceTo(BY_SHED_DOOR) > 1))
            .toTarget(SHED)
            .path(MOUSE_ROOM, new WorldPoint(2903, 3466, 0), new WorldPoint(2901, 3466, 0),
                    new WorldPoint(2901, 3460, 0), new WorldPoint(2933, 3460, 0), BY_SHED_DOOR)
            .avoid(NpcID.NORA_T_HAGG, WITCH_DISTANCE)
            .build();

    /** Shed → house with the ball, the same way back; from the house the walker leaves by the front door. */
    static final QuestPassage OUT_WITH_THE_BALL = QuestPassage.named("sneaking back through the garden with the ball")
            .from(SHED.or(GARDEN))
            .toTarget(OUTSIDE)
            .path(BY_SHED_DOOR, new WorldPoint(2933, 3460, 0), new WorldPoint(2901, 3460, 0),
                    new WorldPoint(2901, 3466, 0), new WorldPoint(2903, 3466, 0), MOUSE_ROOM)
            .avoid(NpcID.NORA_T_HAGG, WITCH_DISTANCE)
            .build();

    private WitchsHouseSupport() {
    }

    private static boolean mouse(QuestContext ctx) {
        if (ctx.continueDialogue()) return true;
        Rs2NpcModel mouse = Microbot.getRs2NpcCache().query().withId(NpcID.WITCHRAT).nearestReachableOnClientThread();
        if (mouse != null) {
            Microbot.status = "Using the magnet on the mouse";
            if (Rs2Inventory.use(ItemID.MAGNET)
                    && sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1_500)
                    && mouse.click()) {
                sleepUntil(() -> !Rs2Inventory.hasItem(ItemID.MAGNET), 4_000);
            }
            return true;
        }
        if (!Rs2Inventory.hasItem(ItemID.CHEESE)) return false; // the runner fetches more cheese
        Rs2TileObjectModel hole = Microbot.getRs2TileObjectCache().query().withId(ObjectID.WITCHMOUSEHOLE)
                .nearestOnClientThread(new WorldPoint(2903, 3466, 0), 4);
        if (hole == null) {
            Microbot.status = "Walking to the mouse hole";
            ctx.walkTo(MOUSE_ROOM, 1);
            return true;
        }
        Microbot.status = "Using the cheese on the mouse hole";
        if (ctx.useItemOn(ItemID.CHEESE, hole)) {
            sleepUntil(() -> Microbot.getRs2NpcCache().query().withId(NpcID.WITCHRAT).nearestOnClientThread() != null,
                    5_000);
        }
        return true;
    }
}
