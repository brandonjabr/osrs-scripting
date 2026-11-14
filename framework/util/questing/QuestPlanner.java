package net.runelite.client.plugins.microbot.util.questing;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import net.runelite.client.plugins.microbot.questhelper.requirements.Requirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.microbot.util.shopping.ItemPrices;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns a {@link QuestDefinition} plus Quest Helper's data into concrete plans: the kit to carry, the coins it
 * needs, and any requirements the account doesn't meet yet.
 */
public final class QuestPlanner {

    private QuestPlanner() {
    }

    /**
     * Items to carry for the quest: Quest Helper's required items (minus skipped ones and coins), the definition's
     * extras, and food when the quest has fights. Where Quest Helper accepts several items (any pickaxe, any hammer),
     * one the player already owns is preferred, then the cheapest on the GE. A quest that
     * {@linkplain QuestDefinition#isGatherInGame() gathers in-game} gets only the extras and food.
     */
    public static List<ItemNeed> kit(QuestDefinition def, ItemNeed food) {
        return kit(def, food, 0);
    }

    /**
     * As {@link #kit(QuestDefinition, ItemNeed)}, plus the definition's planned items (gear, potions), which may spend
     * up to {@code budgetGp} on what has to be bought.
     */
    public static List<ItemNeed> kit(QuestDefinition def, ItemNeed food, long budgetGp) {
        List<ItemNeed> needs = new ArrayList<>();
        for (ItemRequirement req : def.isGatherInGame() ? List.<ItemRequirement>of() : itemRequirements(def)) {
            List<Integer> ids = validIds(req);
            if (ids.isEmpty() || ids.contains(ItemID.COINS)) continue;
            if (ids.stream().anyMatch(def.getSkippedItems()::contains)) continue;
            int id = chooseId(ids);
            needs.add(new ItemNeed(id, ItemProcurer.itemName(id), Math.max(1, req.getQuantity()), req.mustBeEquipped()));
        }
        needs.addAll(def.getExtraItems());
        for (java.util.function.LongFunction<List<ItemNeed>> planner : def.getKitPlanners()) {
            needs.addAll(planner.apply(Math.max(0, budgetGp)));
        }
        if (def.isBringFood() && food != null) {
            needs.add(food.getQuantity() < def.getMinFood() ? food.withQuantity(def.getMinFood()) : food);
        }
        return ItemNeed.merge(needs);
    }

    /** The part of the kit that can be bought on the GE. */
    public static List<ItemNeed> buyable(List<ItemNeed> kit) {
        // Coins count as tradeable but can't be bought.
        return kit.stream().filter(n -> n.getItemId() != ItemID.COINS && ItemProcurer.isTradeable(n.getItemId()))
                .collect(Collectors.toList());
    }

    /** Coins the quest itself asks for (boat fares, beer money). */
    public static int coinsNeeded(QuestDefinition def) {
        int coins = def.getExtraCoins();
        for (ItemRequirement req : itemRequirements(def)) {
            if (validIds(req).contains(ItemID.COINS)) coins += Math.max(0, req.getQuantity());
        }
        return coins;
    }

    /**
     * Why the quest can't be started right now (unmet skill, quest or quest point requirements, combat gate, members
     * world), or an empty list when it can.
     */
    public static List<String> unmetRequirements(QuestDefinition def) {
        List<String> unmet = new ArrayList<>();
        if (def.isMembers() && !ItemProcurer.onMembersWorld()) {
            unmet.add("needs a members world");
        }
        if (def.getMinCombatLevel() > 0 && Rs2Player.getCombatLevel() < def.getMinCombatLevel()) {
            unmet.add("combat level " + def.getMinCombatLevel() + "+ (you're " + Rs2Player.getCombatLevel() + ")");
        }
        QuestHelper helper = QuestHelperBridge.initializedHelper(def.getQuest());
        List<Requirement> general = Microbot.getClientThread()
                .runOnClientThreadOptional(helper::getGeneralRequirements).orElse(null);
        if (general != null) {
            for (Requirement req : general) {
                if (req == null) continue;
                boolean met = Microbot.getClientThread()
                        .runOnClientThreadOptional(() -> req.check(Microbot.getClient())).orElse(false);
                if (!met) unmet.add(req.getDisplayText());
            }
        }
        return unmet;
    }

    // --- Helpers ---

    private static List<ItemRequirement> itemRequirements(QuestDefinition def) {
        QuestHelper helper = QuestHelperBridge.initializedHelper(def.getQuest());
        List<ItemRequirement> reqs = Microbot.getClientThread()
                .runOnClientThreadOptional(helper::getItemRequirements).orElse(null);
        if (reqs == null) return List.of();
        return reqs.stream().filter(r -> r != null && r.isActualItem()).collect(Collectors.toList());
    }

    private static List<Integer> validIds(ItemRequirement req) {
        List<Integer> ids = req.getAllIds();
        if (ids == null) return List.of();
        return ids.stream().filter(id -> id != null && id > 0).collect(Collectors.toList());
    }

    private static int chooseId(List<Integer> ids) {
        for (int id : ids) {
            if (ItemProcurer.ownedCount(id, true) > 0) return id;
        }
        int best = ids.get(0);
        long bestPrice = Long.MAX_VALUE;
        for (int id : ids) {
            if (!ItemProcurer.isTradeable(id)) continue;
            // The client's cached GE price, not the wiki API: collections like "any pickaxe" hold ~30 ids.
            long price = ItemPrices.guidePrice(id);
            if (price > 0 && price < bestPrice) {
                bestPrice = price;
                best = id;
            }
        }
        return best;
    }
}
