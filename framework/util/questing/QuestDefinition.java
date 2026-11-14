package net.runelite.client.plugins.microbot.util.questing;

import net.runelite.client.plugins.microbot.questhelper.questinfo.QuestHelperQuest;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * Everything a questing script needs to know about one quest beyond Quest Helper's own data: extra items worth
 * buying (e.g. balls of wool instead of shearing sheep), helper items to leave out, whether to bring food, a
 * combat level gate, and step overrides. Build with {@link #builder(QuestHelperQuest)}.
 */
public final class QuestDefinition {

    private final QuestHelperQuest quest;
    private final String label;
    private final boolean members;
    private final int minCombatLevel;
    private final boolean bringFood;
    private final List<ItemNeed> extraItems;
    private final Set<Integer> skippedItems;
    private final Map<Integer, ItemNeed> substitutes;
    private final List<StepOverride> overrides;
    private final List<DialogueAnswer> answers;
    private final String notes;
    private final boolean gatherInGame;
    private final int extraCoins;
    private final int minFood;
    private final List<LongFunction<List<ItemNeed>>> kitPlanners;

    private QuestDefinition(Builder b) {
        this.quest = b.quest;
        this.label = b.label != null ? b.label : b.quest.getName();
        this.members = b.members;
        this.minCombatLevel = b.minCombatLevel;
        this.bringFood = b.bringFood;
        this.extraItems = Collections.unmodifiableList(new ArrayList<>(b.extraItems));
        this.skippedItems = Collections.unmodifiableSet(new HashSet<>(b.skippedItems));
        this.substitutes = Collections.unmodifiableMap(new HashMap<>(b.substitutes));
        this.overrides = Collections.unmodifiableList(new ArrayList<>(b.overrides));
        this.answers = Collections.unmodifiableList(new ArrayList<>(b.answers));
        this.notes = b.notes;
        this.gatherInGame = b.gatherInGame;
        this.extraCoins = b.extraCoins;
        this.minFood = b.minFood;
        this.kitPlanners = Collections.unmodifiableList(new ArrayList<>(b.kitPlanners));
    }

    public static Builder builder(QuestHelperQuest quest) {
        return new Builder(quest);
    }

    public QuestHelperQuest getQuest() {
        return quest;
    }

    public String getLabel() {
        return label;
    }

    public boolean isMembers() {
        return members;
    }

    public int getMinCombatLevel() {
        return minCombatLevel;
    }

    public boolean isBringFood() {
        return bringFood;
    }

    public List<ItemNeed> getExtraItems() {
        return extraItems;
    }

    public Set<Integer> getSkippedItems() {
        return skippedItems;
    }

    /** Quest Helper item ID to what's carried instead of it (and turned into it during the quest). */
    public Map<Integer, ItemNeed> getSubstitutes() {
        return substitutes;
    }

    public List<StepOverride> getOverrides() {
        return overrides;
    }

    public List<DialogueAnswer> getAnswers() {
        return answers;
    }

    /** Coins the quest pays that Quest Helper doesn't list as an item (e.g. Wormbrain's 10,000). */
    public int getExtraCoins() {
        return extraCoins;
    }

    /** Least food to carry for this quest's fights, whatever the configured amount (0 = just the configured). */
    public int getMinFood() {
        return minFood;
    }

    /**
     * Kit items worked out when the quest is picked (gear by the player's levels, potions by world type), each given
     * the gp it may spend on buying them.
     */
    public List<LongFunction<List<ItemNeed>>> getKitPlanners() {
        return kitPlanners;
    }

    /** Quest items are collected by playing the quest; nothing is fetched from the bank or bought. */
    public boolean isGatherInGame() {
        return gatherInGame;
    }

    public String getNotes() {
        return notes;
    }

    public static final class Builder {
        private final QuestHelperQuest quest;
        private String label;
        private boolean members;
        private int minCombatLevel;
        private boolean bringFood;
        private final List<ItemNeed> extraItems = new ArrayList<>();
        private final Set<Integer> skippedItems = new HashSet<>();
        private final Map<Integer, ItemNeed> substitutes = new HashMap<>();
        private final List<StepOverride> overrides = new ArrayList<>();
        private final List<DialogueAnswer> answers = new ArrayList<>();
        private String notes = "";
        private boolean gatherInGame;
        private int extraCoins;
        private int minFood;
        private final List<LongFunction<List<ItemNeed>>> kitPlanners = new ArrayList<>();

        private Builder(QuestHelperQuest quest) {
            this.quest = quest;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder members() {
            this.members = true;
            return this;
        }

        /** Skip the quest below this combat level, and bring food for its fights. */
        public Builder fight(int minCombatLevel) {
            this.minCombatLevel = minCombatLevel;
            this.bringFood = true;
            return this;
        }

        public Builder bringFood() {
            this.bringFood = true;
            return this;
        }

        /** Buy and carry an item Quest Helper doesn't list (or more of it). */
        public Builder extra(ItemNeed need) {
            extraItems.add(need);
            return this;
        }

        /** Leave one of Quest Helper's listed items out of the kit, e.g. shears when balls of wool are bought. */
        public Builder skip(int itemId) {
            skippedItems.add(itemId);
            return this;
        }

        /**
         * Carry {@code replacement} instead of a listed item, e.g. cooked meat for burnt meat (burnt during the quest).
         * A step listing the original then counts as covered while either is carried, and fetches the replacement.
         */
        public Builder substitute(int listedItemId, ItemNeed replacement) {
            skippedItems.add(listedItemId);
            extraItems.add(replacement);
            substitutes.put(listedItemId, replacement);
            return this;
        }

        public Builder override(StepOverride override) {
            overrides.add(override);
            return this;
        }

        /** Carry this many more coins than Quest Helper lists. */
        public Builder coins(int amount) {
            this.extraCoins += amount;
            return this;
        }

        /** Bring at least this much food, more than the configured amount if that's lower. Implies food. */
        public Builder minFood(int amount) {
            this.minFood = amount;
            this.bringFood = true;
            return this;
        }

        /** Add kit items planned when the quest is picked; the function gets the gp left for buying them. */
        public Builder kitPlanner(LongFunction<List<ItemNeed>> planner) {
            kitPlanners.add(planner);
            return this;
        }

        /** Answer option menus Quest Helper can't (see {@link DialogueAnswer}). */
        public Builder answer(DialogueAnswer answer) {
            answers.add(answer);
            return this;
        }

        /** Collect quest items in-game: the kit holds only extras and food, so steps never send it to the bank or GE. */
        public Builder gatherInGame() {
            this.gatherInGame = true;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public QuestDefinition build() {
            return new QuestDefinition(this);
        }
    }
}
