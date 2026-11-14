package net.runelite.client.plugins.microbot.shadowfaxgemcrab;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;

@ConfigGroup(GemCrabConfig.GROUP)
public interface GemCrabConfig extends Config {

    String GROUP = "shadowfaxgemcrab";

    enum StyleMode {
        BALANCED("Balanced"),
        ATTACK("Attack only"),
        STRENGTH("Strength only"),
        DEFENCE("Defence only"),
        KEEP("Don't change");

        private final String label;

        StyleMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum CombatType {
        AUTO("Match my weapon"),
        MELEE("Melee"),
        RANGED("Ranged"),
        MAGIC("Magic");

        private final String label;

        CombatType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum RangedStyleMode {
        RAPID("Rapid"),
        ACCURATE("Accurate"),
        LONGRANGE("Longrange (+Defence)"),
        KEEP("Don't change");

        private final String label;

        RangedStyleMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum RangedPotion {
        NONE("None", null),
        RANGING("Ranging potion", "Ranging potion"),
        DIVINE_RANGING("Divine ranging potion", "Divine ranging potion"),
        BASTION("Bastion potion", "Bastion potion"),
        DIVINE_BASTION("Divine bastion potion", "Divine bastion potion");

        private final String label;
        final String itemName;

        RangedPotion(String label, String itemName) {
            this.label = label;
            this.itemName = itemName;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum MagicPotion {
        NONE("None", null),
        MAGIC("Magic potion", "Magic potion"),
        DIVINE_MAGIC("Divine magic potion", "Divine magic potion"),
        BATTLEMAGE("Battlemage potion", "Battlemage potion"),
        DIVINE_BATTLEMAGE("Divine battlemage potion", "Divine battlemage potion");

        private final String label;
        final String itemName;

        MagicPotion(String label, String itemName) {
            this.label = label;
            this.itemName = itemName;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigSection(
            name = "Combat type",
            description = "Melee, ranged or magic, and the ranged style or autocast spell to use",
            position = 0
    )
    String combatSection = "combat";

    @ConfigSection(
            name = "Melee styles",
            description = "Melee only: which skills to train and how to share xp between them",
            position = 1
    )
    String styleSection = "styles";

    @ConfigSection(
            name = "Potions",
            description = "Boosting potions for the combat type in use, drunk while fighting",
            position = 2
    )
    String potionSection = "potions";

    @ConfigSection(
            name = "Food & banking",
            description = "Eating, and when to bank at Tal Teklan",
            position = 3
    )
    String foodSection = "food";

    @ConfigSection(
            name = "Shell",
            description = "Mining the shell the crab leaves behind",
            position = 4
    )
    String shellSection = "shell";

    @ConfigSection(
            name = "Stamina",
            description = "Stamina potions for the walk to and from the bank",
            position = 5
    )
    String staminaSection = "stamina";

    @ConfigSection(
            name = "General",
            description = "Antiban and on-screen overlay",
            position = 6
    )
    String generalSection = "general";

    // --- Combat type ---

    @ConfigItem(
            keyName = "combatType",
            name = "Combat type",
            description = "Match my weapon reads the wielded weapon (a bow or crossbow is ranged; a powered staff or a "
                    + "staff set to autocast is magic; anything else is melee). Picks the styles, potions and xp to track",
            position = 0,
            section = combatSection
    )
    default CombatType combatType() {
        return CombatType.AUTO;
    }

    @ConfigItem(
            keyName = "rangedStyle",
            name = "Ranged style",
            description = "Rapid gives the most Ranged xp; Longrange also trains Defence; Don't change leaves it alone",
            position = 1,
            section = combatSection
    )
    default RangedStyleMode rangedStyle() {
        return RangedStyleMode.RAPID;
    }

    @ConfigItem(
            keyName = "setAutocast",
            name = "Set autocast",
            description = "Magic with a normal staff: set the spell below as the autocast. Off leaves your autocast as "
                    + "it is (and powered staves like the trident don't need one)",
            position = 2,
            section = combatSection
    )
    default boolean setAutocast() {
        return false;
    }

    @ConfigItem(
            keyName = "autocastSpell",
            name = "Autocast spell",
            description = "Spell to autocast when Set autocast is on. You need the runes (or a staff that gives them)",
            position = 3,
            section = combatSection
    )
    default Rs2CombatSpells autocastSpell() {
        return Rs2CombatSpells.FIRE_STRIKE;
    }

    @ConfigItem(
            keyName = "defensiveCasting",
            name = "Defensive casting",
            description = "Autocast defensively, sharing xp between Magic and Defence",
            position = 4,
            section = combatSection
    )
    default boolean defensiveCasting() {
        return false;
    }

    // --- Attack styles ---

    @ConfigItem(
            keyName = "styleMode",
            name = "Style mode",
            description = "Balanced trains whichever ticked skill is lowest and switches once it's a few levels "
                    + "ahead. The others stick to one style; Don't change leaves your style alone",
            position = 0,
            section = styleSection
    )
    default StyleMode styleMode() {
        return StyleMode.BALANCED;
    }

    @ConfigItem(
            keyName = "trainAttack",
            name = "Balance: Attack",
            description = "Include Attack when balancing",
            position = 1,
            section = styleSection
    )
    default boolean trainAttack() {
        return true;
    }

    @ConfigItem(
            keyName = "trainStrength",
            name = "Balance: Strength",
            description = "Include Strength when balancing",
            position = 2,
            section = styleSection
    )
    default boolean trainStrength() {
        return true;
    }

    @ConfigItem(
            keyName = "trainDefence",
            name = "Balance: Defence",
            description = "Include Defence when balancing",
            position = 3,
            section = styleSection
    )
    default boolean trainDefence() {
        return true;
    }

    @Range(min = 1, max = 10)
    @ConfigItem(
            keyName = "balanceMargin",
            name = "Switch after (levels)",
            description = "Keep training a skill until it is this many levels above the lowest ticked one",
            position = 4,
            section = styleSection
    )
    default int balanceMargin() {
        return 2;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "attackTarget",
            name = "Attack target level",
            description = "Stop training Attack at this level",
            position = 5,
            section = styleSection
    )
    default int attackTarget() {
        return 99;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "strengthTarget",
            name = "Strength target level",
            description = "Stop training Strength at this level",
            position = 6,
            section = styleSection
    )
    default int strengthTarget() {
        return 99;
    }

    @Range(min = 1, max = 99)
    @ConfigItem(
            keyName = "defenceTarget",
            name = "Defence target level",
            description = "Stop training Defence at this level",
            position = 7,
            section = styleSection
    )
    default int defenceTarget() {
        return 99;
    }

    // --- Potions ---

    @ConfigItem(
            keyName = "useSuperStrength",
            name = "Melee: super strength",
            description = "Melee only: drink a dose of Super strength while fighting whenever the Strength boost runs low",
            position = 0,
            section = potionSection
    )
    default boolean useSuperStrength() {
        return true;
    }

    @ConfigItem(
            keyName = "useSuperAttack",
            name = "Melee: super attack",
            description = "Melee only: also drink Super attack the same way (for accuracy)",
            position = 1,
            section = potionSection
    )
    default boolean useSuperAttack() {
        return false;
    }

    @ConfigItem(
            keyName = "rangedPotion",
            name = "Ranged: potion",
            description = "Ranged only: potion to drink whenever the Ranged boost runs low",
            position = 2,
            section = potionSection
    )
    default RangedPotion rangedPotion() {
        return RangedPotion.RANGING;
    }

    @ConfigItem(
            keyName = "magicPotion",
            name = "Magic: potion",
            description = "Magic only: potion to drink whenever the Magic boost runs low",
            position = 3,
            section = potionSection
    )
    default MagicPotion magicPotion() {
        return MagicPotion.MAGIC;
    }

    @Range(min = 0, max = 20)
    @ConfigItem(
            keyName = "redrinkMinBoost",
            name = "Re-drink at boost (min)",
            description = "Drink again once the boost (boosted minus real level) is down to a level picked at "
                    + "random between this and the max. A new level is picked after every dose. Never above what "
                    + "one dose gives, so a small boost (a magic potion's +4) isn't drunk back to back",
            position = 4,
            section = potionSection
    )
    default int redrinkMinBoost() {
        return 1;
    }

    @Range(min = 0, max = 20)
    @ConfigItem(
            keyName = "redrinkMaxBoost",
            name = "Re-drink at boost (max)",
            description = "Upper end of the random re-drink level",
            position = 5,
            section = potionSection
    )
    default int redrinkMaxBoost() {
        return 6;
    }

    @Range(min = 0, max = 10)
    @ConfigItem(
            keyName = "potionCount",
            name = "Potions to carry",
            description = "How many of each potion in use to take from the bank on each trip",
            position = 6,
            section = potionSection
    )
    default int potionCount() {
        return 2;
    }

    // --- Food & banking ---

    @ConfigItem(
            keyName = "foodName",
            name = "Food",
            description = "Food to take from the bank. Leave empty to take whatever food the bank has",
            position = 0,
            section = foodSection
    )
    default String foodName() {
        return "";
    }

    @Range(min = 0, max = 20)
    @ConfigItem(
            keyName = "foodAmount",
            name = "Food to carry",
            description = "Food to take on each bank trip. The crab hits 1 at most, so a few go a long way",
            position = 1,
            section = foodSection
    )
    default int foodAmount() {
        return 4;
    }

    @Range(min = 10, max = 90)
    @ConfigItem(
            keyName = "eatAtPercent",
            name = "Eat at health %",
            description = "Eat when health drops to this",
            position = 2,
            section = foodSection
    )
    default int eatAtPercent() {
        return 50;
    }

    @Range(min = 5, max = 80)
    @ConfigItem(
            keyName = "bankAtPercent",
            name = "Bank at health %",
            description = "Out of food and below this: go to the Tal Teklan bank to restock",
            position = 3,
            section = foodSection
    )
    default int bankAtPercent() {
        return 30;
    }

    // --- Shell ---

    @ConfigItem(
            keyName = "mineShell",
            name = "Mine the shell",
            description = "After the crab burrows, mine its shell for three uncut gems before following it. Needs a "
                    + "pickaxe and a top-16 damage spot. Gems are banked when the inventory fills",
            position = 0,
            section = shellSection
    )
    default boolean mineShell() {
        return true;
    }

    // --- Stamina ---

    @ConfigItem(
            keyName = "useStaminas",
            name = "Use stamina potions",
            description = "Take stamina potions from the bank (if it has any) and drink one on the way to or from the "
                    + "bank when run energy is low",
            position = 0,
            section = staminaSection
    )
    default boolean useStaminas() {
        return true;
    }

    @Range(min = 1, max = 5)
    @ConfigItem(
            keyName = "staminaCount",
            name = "Stamina potions to carry",
            description = "How many stamina potions to keep in the inventory; topped up on each bank trip",
            position = 1,
            section = staminaSection
    )
    default int staminaCount() {
        return 1;
    }

    @Range(min = 5, max = 90)
    @ConfigItem(
            keyName = "staminaAtEnergy",
            name = "Drink at run energy %",
            description = "Drink a stamina potion while travelling when run energy drops to this and no stamina "
                    + "effect is active",
            position = 2,
            section = staminaSection
    )
    default int staminaAtEnergy() {
        return 30;
    }

    // --- General ---

    @ConfigItem(
            keyName = "antiban",
            name = "Antiban",
            description = "Short random pauses and micro-breaks between actions",
            position = 0,
            section = generalSection
    )
    default boolean antiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Draw the Shadowfax Gem Crab panel on screen",
            position = 1,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }
}
