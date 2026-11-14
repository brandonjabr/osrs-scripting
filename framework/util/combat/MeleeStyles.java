package net.runelite.client.plugins.microbot.util.combat;

import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ParamID;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.List;
import java.util.function.ToIntFunction;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Melee attack styles of the wielded weapon, mapped to the skill each one trains: Accurate → Attack, Aggressive →
 * Strength, Defensive → Defence. Controlled (shared xp) and non-melee styles map to nothing. Button {@code i} of the
 * combat tab is style {@code i}, the same index the {@code COM_MODE} varp holds.
 */
public final class MeleeStyles {

    private static final int[] STYLE_BUTTONS = {
            InterfaceID.CombatInterface._0, InterfaceID.CombatInterface._1,
            InterfaceID.CombatInterface._2, InterfaceID.CombatInterface._3
    };

    private MeleeStyles() {
    }

    /** The skill each of the four style buttons trains, null where it trains none on its own (or isn't melee). */
    public static Skill[] styleSkills() {
        return Microbot.getClientThread().runOnClientThreadOptional(MeleeStyles::readStyleSkills)
                .orElse(new Skill[STYLE_BUTTONS.length]);
    }

    private static Skill[] readStyleSkills() {
        Skill[] skills = new Skill[STYLE_BUTTONS.length];
        Client client = Microbot.getClient();
        int weaponType = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
        int stylesEnum = client.getEnum(EnumID.WEAPON_STYLES).getIntValue(weaponType);
        if (stylesEnum == -1) return skills;
        EnumComposition styles = client.getEnum(stylesEnum);
        int[] structs = styles.getIntVals();
        for (int i = 0; i < structs.length && i < skills.length; i++) {
            StructComposition struct = client.getStructComposition(structs[i]);
            String name = struct == null ? null : struct.getStringValue(ParamID.ATTACK_STYLE_NAME);
            if (name == null) continue;
            switch (name.toLowerCase()) {
                case "accurate":
                    skills[i] = Skill.ATTACK;
                    break;
                case "aggressive":
                    skills[i] = Skill.STRENGTH;
                    break;
                case "defensive":
                    skills[i] = Skill.DEFENCE;
                    break;
                case "ranging":
                case "longrange":
                case "casting":
                    // A ranged or magic weapon: none of its buttons is a melee style.
                    return new Skill[STYLE_BUTTONS.length];
                default:
                    break;
            }
        }
        return skills;
    }

    /** Index of the selected style button. */
    public static int currentIndex() {
        return Microbot.getVarbitPlayerValue(VarPlayerID.COM_MODE);
    }

    /** The skill the selected style trains on its own, or null (Controlled, ranged, magic). */
    public static Skill currentSkill() {
        Skill[] skills = styleSkills();
        int index = currentIndex();
        return index >= 0 && index < skills.length ? skills[index] : null;
    }

    /**
     * Selects the style that trains {@code skill}, opening the combat tab for the click and going back to the
     * inventory after. True once the game reports the new style; false if the weapon has none for that skill.
     */
    public static boolean select(Skill skill) {
        Skill[] skills = styleSkills();
        int index = -1;
        for (int i = 0; i < skills.length; i++) {
            if (skills[i] == skill) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;
        if (currentIndex() == index) return true;

        if (Rs2Tab.getCurrentTab() != InterfaceTab.COMBAT) {
            Rs2Tab.switchTo(InterfaceTab.COMBAT);
            sleepUntil(() -> Rs2Tab.getCurrentTab() == InterfaceTab.COMBAT, 1200);
        }
        final int wanted = index;
        boolean selected = Rs2Widget.clickWidget(STYLE_BUTTONS[index])
                && sleepUntil(() -> currentIndex() == wanted, 1800);
        Rs2Tab.switchTo(InterfaceTab.INVENTORY);
        return selected;
    }

    /**
     * Which of {@code skills} to train next so their levels stay even. Skills at or above their target level are
     * left out. The current skill is kept until it is {@code margin} levels ahead of the lowest one, so the style
     * doesn't flip back and forth on every level. Ties go to the earliest skill in the list.
     *
     * @return the skill to train, or null when every skill has reached its target
     */
    public static Skill balancedChoice(List<Skill> skills, Skill current, int margin,
                                       ToIntFunction<Skill> level, ToIntFunction<Skill> target) {
        Skill lowest = null;
        for (Skill s : skills) {
            if (level.applyAsInt(s) >= target.applyAsInt(s)) continue;
            if (lowest == null || level.applyAsInt(s) < level.applyAsInt(lowest)) lowest = s;
        }
        if (lowest == null) return null;
        if (current != null && skills.contains(current) && level.applyAsInt(current) < target.applyAsInt(current)
                && level.applyAsInt(current) < level.applyAsInt(lowest) + Math.max(1, margin)) {
            return current;
        }
        return lowest;
    }
}
