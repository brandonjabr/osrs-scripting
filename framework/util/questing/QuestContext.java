package net.runelite.client.plugins.microbot.util.questing;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.QuestScript;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * What a {@link StepOverride} works with: the quest and its active step, a per-quest scratchpad for override state,
 * and short verbs for the usual quest actions. Every verb does one thing and returns quickly, so overrides read as
 * "if not there, walk; else if X, do Y" and the runner keeps its tick rhythm.
 */
public class QuestContext {

    private final QuestDefinition definition;
    private final QuestScript executor;
    private final Map<String, Object> scratch = new HashMap<>();
    private QuestStep activeStep;
    private List<ItemNeed> kit = List.of();
    private ItemNeed food;
    private List<ItemNeed> requested;
    private boolean requestMakesRoom;

    QuestContext(QuestDefinition definition, QuestScript executor) {
        this.definition = definition;
        this.executor = executor;
    }

    void setKit(List<ItemNeed> kit, ItemNeed food) {
        this.kit = kit == null ? List.of() : kit;
        this.food = food;
    }

    /** The items prepared for this quest, including planned gear (marked {@link ItemNeed#isEquip()}). */
    public List<ItemNeed> kit() {
        return kit;
    }

    /** The food brought for this quest at the kit's amount, or null when the quest brings none. */
    public ItemNeed food() {
        return food;
    }

    /**
     * Asks for a bank trip (buying what the bank lacks) before going on. With {@code makeRoom} everything else in
     * the inventory is banked first and only these items are taken out, e.g. a full load of food for a boss.
     */
    public void requestItems(List<ItemNeed> items, boolean makeRoom) {
        if (items == null || items.isEmpty()) return;
        this.requested = List.copyOf(items);
        this.requestMakesRoom = makeRoom;
    }

    /** The pending request, cleared; null when there is none. */
    List<ItemNeed> takeRequestedItems() {
        List<ItemNeed> items = requested;
        requested = null;
        return items;
    }

    boolean requestMakesRoom() {
        return requestMakesRoom;
    }

    void setActiveStep(QuestStep step) {
        this.activeStep = step;
    }

    // --- Quest state ---

    public QuestDefinition definition() {
        return definition;
    }

    public QuestHelper helper() {
        return QuestHelperBridge.selectedHelper();
    }

    public QuestStep activeStep() {
        return activeStep;
    }

    /** All lines of the active step's text joined; empty when there is no step. */
    public String stepText() {
        if (activeStep == null) return "";
        List<String> text = activeStep.getText();
        return text == null ? "" : String.join(" ", text);
    }

    /** Hands the active step to Quest Helper's generic executor. */
    public boolean applyGenericStep() {
        return activeStep != null && executor.applyStep(activeStep);
    }

    // --- Scratchpad for override state (cleared when the quest changes) ---

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T fallback) {
        return (T) scratch.getOrDefault(key, fallback);
    }

    public void set(String key, Object value) {
        scratch.put(key, value);
    }

    // --- Movement ---

    /** True once within {@code distance} tiles; otherwise takes a walker step towards the point. */
    public boolean walkTo(WorldPoint point, int distance) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here != null && here.getPlane() == point.getPlane() && here.distanceTo(point) <= distance) {
            return true;
        }
        Microbot.status = "Walking";
        Rs2Walker.walkTo(point, distance);
        return false;
    }

    // --- NPCs ---

    public Rs2NpcModel npc(int npcId) {
        return Microbot.getRs2NpcCache().query().withId(npcId).nearestReachableOnClientThread();
    }

    public Rs2NpcModel npc(String name) {
        return Microbot.getRs2NpcCache().query().withName(name).nearestReachableOnClientThread();
    }

    public boolean talkTo(Rs2NpcModel npc) {
        if (npc == null || Rs2Dialogue.isInDialogue()) return false;
        return npc.click("Talk-to") && sleepUntil(Rs2Dialogue::isInDialogue, 5000);
    }

    /** Attacks the NPC unless we're already fighting; eating is handled by the runner. */
    public boolean attack(Rs2NpcModel npc) {
        if (npc == null) return false;
        if (Rs2Combat.inCombat()) return true;
        return npc.click("Attack") && sleepUntil(Rs2Player::isInteracting, 3000);
    }

    // --- Objects & items ---

    public Rs2TileObjectModel object(int objectId) {
        return Microbot.getRs2TileObjectCache().query().withId(objectId).nearestOnClientThread();
    }

    public List<Rs2TileObjectModel> objects(int objectId) {
        return Microbot.getRs2TileObjectCache().query().withId(objectId).toListOnClientThread();
    }

    /** Uses an inventory item on a game object (Quest Helper's "use X on Y" steps). */
    public boolean useItemOn(int itemId, Rs2TileObjectModel object) {
        if (object == null || !Rs2Inventory.hasItem(itemId)) return false;
        if (!Rs2Inventory.use(itemId)) return false;
        if (!sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1500)) return false;
        return object.click();
    }

    public boolean has(int itemId) {
        return Rs2Inventory.hasItem(itemId) || Rs2Equipment.isWearing(itemId);
    }

    public int count(int itemId) {
        return Rs2Inventory.itemQuantity(itemId);
    }

    public boolean equip(int itemId) {
        if (Rs2Equipment.isWearing(itemId)) return true;
        return Rs2Inventory.hasItem(itemId) && Rs2Inventory.equip(itemId)
                && sleepUntil(() -> Rs2Equipment.isWearing(itemId), 2000);
    }

    public boolean unequip(int itemId) {
        if (!Rs2Equipment.isWearing(itemId)) return true;
        return Rs2Equipment.unEquip(itemId) && sleepUntil(() -> Rs2Inventory.hasItem(itemId), 2000);
    }

    /** Clicks through "click here to continue" dialogue; true if there was some. */
    public boolean continueDialogue() {
        if (!Rs2Dialogue.hasContinue()) return false;
        Rs2Dialogue.clickContinue();
        return true;
    }
}
