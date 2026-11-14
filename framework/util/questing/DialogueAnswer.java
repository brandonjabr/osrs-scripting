package net.runelite.client.plugins.microbot.util.questing;

import java.util.List;

/**
 * A quest-specific answer for an option menu Quest Helper can't answer for the active step, e.g. Demon Slayer's
 * incantation, whose words Quest Helper keeps on a parent step. The runner asks a quest's answers, in order, before
 * Quest Helper's expected answer and its own fallback.
 */
@FunctionalInterface
public interface DialogueAnswer {
    /**
     * @param options the menu's options as shown, colour tags stripped
     * @return the option to pick (matched case-insensitively), or null to leave this menu to the runner
     */
    String choose(QuestContext ctx, List<String> options);
}
