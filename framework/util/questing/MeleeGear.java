package net.runelite.client.plugins.microbot.util.questing;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemPrices;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a melee weapon and armour (helm, body, legs) for a fight quest from the standard metal tiers, by the
 * player's Attack and Defence levels: for each slot the best piece that is already owned (worn, carried or banked)
 * or affordable within the budget, falling back a tier at a time. Pieces are compared by their real equipment stats,
 * so anything better already worn in a slot (a members weapon, a reward item) is kept and that slot is left alone.
 * <p>
 * The shield slot is left out on purpose: quests choose it themselves (Dragon Slayer wears the anti-dragon shield).
 * Every planned piece is an {@link ItemNeed#isEquip() equip} need, so the questing script wears it after the bank.
 */
@Slf4j
public final class MeleeGear {

    // GE prices drift; this much over the guide price is what a quick buy usually costs.
    private static final double PRICE_MARGIN = 1.15;

    private static final class Piece {
        final int itemId;
        final int level;
        final boolean members;

        Piece(int itemId, int level, boolean members) {
            this.itemId = itemId;
            this.level = level;
            this.members = members;
        }
    }

    private enum Slot {
        WEAPON(EquipmentInventorySlot.WEAPON, Skill.ATTACK, new Piece[]{
                new Piece(ItemID.DRAGON_SCIMITAR, 60, true),
                new Piece(ItemID.RUNE_SCIMITAR, 40, false),
                new Piece(ItemID.ADAMANT_SCIMITAR, 30, false),
                new Piece(ItemID.MITHRIL_SCIMITAR, 20, false),
                new Piece(ItemID.BLACK_SCIMITAR, 10, false),
                new Piece(ItemID.STEEL_SCIMITAR, 5, false),
                new Piece(ItemID.IRON_SCIMITAR, 1, false),
                new Piece(ItemID.BRONZE_SCIMITAR, 1, false)}),
        // Rune platebody needs Dragon Slayer itself, so the rune tier is a chainbody.
        BODY(EquipmentInventorySlot.BODY, Skill.DEFENCE, new Piece[]{
                new Piece(ItemID.RUNE_CHAINBODY, 40, false),
                new Piece(ItemID.ADAMANT_PLATEBODY, 30, false),
                new Piece(ItemID.MITHRIL_PLATEBODY, 20, false),
                new Piece(ItemID.BLACK_PLATEBODY, 10, false),
                new Piece(ItemID.STEEL_PLATEBODY, 5, false),
                new Piece(ItemID.IRON_PLATEBODY, 1, false),
                new Piece(ItemID.BRONZE_PLATEBODY, 1, false)}),
        LEGS(EquipmentInventorySlot.LEGS, Skill.DEFENCE, new Piece[]{
                new Piece(ItemID.RUNE_PLATELEGS, 40, false),
                new Piece(ItemID.ADAMANT_PLATELEGS, 30, false),
                new Piece(ItemID.MITHRIL_PLATELEGS, 20, false),
                new Piece(ItemID.BLACK_PLATELEGS, 10, false),
                new Piece(ItemID.STEEL_PLATELEGS, 5, false),
                new Piece(ItemID.IRON_PLATELEGS, 1, false),
                new Piece(ItemID.BRONZE_PLATELEGS, 1, false)}),
        HEAD(EquipmentInventorySlot.HEAD, Skill.DEFENCE, new Piece[]{
                new Piece(ItemID.RUNE_FULL_HELM, 40, false),
                new Piece(ItemID.ADAMANT_FULL_HELM, 30, false),
                new Piece(ItemID.MITHRIL_FULL_HELM, 20, false),
                new Piece(ItemID.BLACK_FULL_HELM, 10, false),
                new Piece(ItemID.STEEL_FULL_HELM, 5, false),
                new Piece(ItemID.IRON_FULL_HELM, 1, false),
                new Piece(ItemID.BRONZE_FULL_HELM, 1, false)});

        final EquipmentInventorySlot equipmentSlot;
        final Skill skill;
        final Piece[] pieces;

        Slot(EquipmentInventorySlot equipmentSlot, Skill skill, Piece[] pieces) {
            this.equipmentSlot = equipmentSlot;
            this.skill = skill;
            this.pieces = pieces;
        }
    }

    private MeleeGear() {
    }

    /**
     * The pieces to wear, in buying priority (weapon, body, legs, helm), with the budget spent best-first: an owned
     * piece costs nothing, an unowned one is planned only if its price still fits. Slots where nothing beats what's
     * worn are left out.
     */
    public static List<ItemNeed> plan(long budgetGp) {
        boolean members = ItemProcurer.onMembersWorld();
        long left = budgetGp;
        List<ItemNeed> plan = new ArrayList<>();
        for (Slot slot : Slot.values()) {
            Rs2ItemModel worn = Rs2Equipment.get(slot.equipmentSlot);
            int wornScore = worn == null ? -1 : score(slot, worn.getId());
            int level = Rs2Player.getRealSkillLevel(slot.skill);
            for (Piece piece : slot.pieces) {
                if (piece.level > level || (piece.members && !members)) continue;
                if (score(slot, piece.itemId) <= wornScore) break; // what's worn is as good or better
                boolean owned = ItemProcurer.ownedCount(piece.itemId, true) > 0;
                long price = owned ? 0 : Math.round(ItemPrices.guidePrice(piece.itemId) * PRICE_MARGIN);
                if (!owned && (price <= 0 || price > left)) continue;
                left -= price;
                plan.add(new ItemNeed(piece.itemId, ItemProcurer.itemName(piece.itemId), 1, true));
                break;
            }
        }
        log.info("[Questing] Melee gear for Attack {} / Defence {} with {} gp: {}",
                Rs2Player.getRealSkillLevel(Skill.ATTACK), Rs2Player.getRealSkillLevel(Skill.DEFENCE), budgetGp,
                plan.isEmpty() ? "keeping what's worn" : plan);
        return plan;
    }

    /** True if every planned piece is worn. */
    public static boolean allWorn(List<ItemNeed> kit) {
        return missing(kit).isEmpty();
    }

    /** Planned gear in the kit that isn't worn yet. */
    public static List<ItemNeed> missing(List<ItemNeed> kit) {
        List<ItemNeed> missing = new ArrayList<>();
        for (ItemNeed need : kit) {
            if (need.isEquip() && isPlanned(need.getItemId()) && !Rs2Equipment.isWearing(need.getItemId())) {
                missing.add(need);
            }
        }
        return missing;
    }

    /** Whether the item is one of the pieces this planner chooses from. */
    public static boolean isPlanned(int itemId) {
        for (Slot slot : Slot.values()) {
            for (Piece piece : slot.pieces) {
                if (piece.itemId == itemId) return true;
            }
        }
        return false;
    }

    /**
     * How good an item is in the slot: best melee attack plus strength for weapons, the three melee defences for
     * armour. Items the client has no stats for score -1 (weapon) or 0 (armour), so a planned piece replaces them.
     */
    private static int score(Slot slot, int itemId) {
        ItemEquipmentStats stats = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            ItemStats itemStats = Microbot.getItemManager().getItemStats(itemId);
            return itemStats == null ? null : itemStats.getEquipment();
        }).orElse(null);
        if (stats == null) return slot == Slot.WEAPON ? -1 : 0;
        if (slot == Slot.WEAPON) {
            return Math.max(stats.getAstab(), Math.max(stats.getAslash(), stats.getAcrush())) + stats.getStr();
        }
        return stats.getDstab() + stats.getDslash() + stats.getDcrush();
    }
}
