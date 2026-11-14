package net.runelite.client.plugins.microbot.util.combat;

import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ParamID;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.Locale;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * What kind of attack the wielded weapon makes (melee, ranged or magic), and ranged style selection. Read from the
 * weapon's style structs, the same data the combat tab is built from; button {@code i} of the tab is style {@code i},
 * the index the {@code COM_MODE} varp holds.
 */
public final class WeaponStyles {

    public enum Kind {
        MELEE, RANGED, MAGIC
    }

    /** Ranged styles by their usual {@code COM_MODE} index. Longrange is looked up from the weapon when it can be. */
    public enum RangedStyle {
        ACCURATE(0), RAPID(1), LONGRANGE(3);

        final int defaultIndex;

        RangedStyle(int defaultIndex) {
            this.defaultIndex = defaultIndex;
        }
    }

    private static final int[] STYLE_BUTTONS = {
            InterfaceID.CombatInterface._0, InterfaceID.CombatInterface._1,
            InterfaceID.CombatInterface._2, InterfaceID.CombatInterface._3
    };

    private WeaponStyles() {
    }

    /** Lower-case attack style names of the wielded weapon's styles, by index; empty when unknown. */
    public static String[] styleNames() {
        return Microbot.getClientThread().runOnClientThreadOptional(WeaponStyles::readStyleNames)
                .orElse(new String[0]);
    }

    private static String[] readStyleNames() {
        Client client = Microbot.getClient();
        int weaponType = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
        int stylesEnum = client.getEnum(EnumID.WEAPON_STYLES).getIntValue(weaponType);
        if (stylesEnum == -1) return new String[0];
        EnumComposition styles = client.getEnum(stylesEnum);
        int[] structs = styles.getIntVals();
        String[] names = new String[structs.length];
        for (int i = 0; i < structs.length; i++) {
            StructComposition struct = client.getStructComposition(structs[i]);
            String name = struct == null ? null : struct.getStringValue(ParamID.ATTACK_STYLE_NAME);
            names[i] = name == null ? "" : name.toLowerCase(Locale.ROOT);
        }
        return names;
    }

    /**
     * Melee, ranged or magic for the wielded weapon and selected style. A weapon with ranging styles is ranged; a
     * casting style that's selected (autocast on a staff) or a weapon that only casts (powered staves) is magic;
     * anything else is melee.
     */
    public static Kind currentKind() {
        String[] names = styleNames();
        int index = currentIndex();
        boolean anyRanged = false;
        boolean allCasting = names.length > 0;
        for (String name : names) {
            if (name.equals("ranging") || name.equals("longrange")) anyRanged = true;
            if (!name.isEmpty() && !name.contains("casting")) allCasting = false;
        }
        if (anyRanged) return Kind.RANGED;
        boolean castingSelected = index >= 0 && index < names.length && names[index].contains("casting");
        if (castingSelected || allCasting || Microbot.getVarbitValue(VarbitID.AUTOCAST_SPELL) > 0) return Kind.MAGIC;
        return Kind.MELEE;
    }

    /** True when the wielded weapon has a casting style (a staff that can autocast, or a powered staff). */
    public static boolean canCast() {
        for (String name : styleNames()) {
            if (name.contains("casting")) return true;
        }
        return false;
    }

    /** Index of the selected style button. */
    public static int currentIndex() {
        return Microbot.getVarbitPlayerValue(VarPlayerID.COM_MODE);
    }

    /** The style button index for this ranged style on the wielded weapon. */
    public static int rangedIndex(RangedStyle style) {
        if (style == RangedStyle.LONGRANGE) {
            String[] names = styleNames();
            for (int i = names.length - 1; i >= 0; i--) {
                if (names[i].equals("longrange")) return i;
            }
        }
        return style.defaultIndex;
    }

    /** True when this ranged style is the selected one. */
    public static boolean isSelected(RangedStyle style) {
        return currentIndex() == rangedIndex(style);
    }

    /**
     * Selects style button {@code index}, opening the combat tab for the click and going back to the inventory
     * after. True once the game reports the new style.
     */
    public static boolean selectIndex(int index) {
        if (index < 0 || index >= STYLE_BUTTONS.length) return false;
        if (currentIndex() == index) return true;
        if (Rs2Tab.getCurrentTab() != InterfaceTab.COMBAT) {
            Rs2Tab.switchTo(InterfaceTab.COMBAT);
            sleepUntil(() -> Rs2Tab.getCurrentTab() == InterfaceTab.COMBAT, 1200);
        }
        boolean selected = Rs2Widget.clickWidget(STYLE_BUTTONS[index])
                && sleepUntil(() -> currentIndex() == index, 1800);
        Rs2Tab.switchTo(InterfaceTab.INVENTORY);
        return selected;
    }

    public static boolean select(RangedStyle style) {
        return selectIndex(rangedIndex(style));
    }
}
