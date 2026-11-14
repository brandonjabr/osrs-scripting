package net.runelite.client.plugins.microbot.util.questing;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Quest-specific handling for a step the generic executor can't do well (fights with special items, puzzles,
 * multi-object interactions). The runner asks each of a quest's overrides, in order, before the generic executor;
 * the first whose {@link #appliesTo} matches gets the tick.
 */
public final class StepOverride {

    /** The override's work for one tick. */
    @FunctionalInterface
    public interface Action {
        /** @return true if it acted this tick, so the generic executor stays out of the way */
        boolean run(QuestContext ctx);
    }

    private final String name;
    private final Predicate<QuestContext> condition;
    private final Action action;

    public StepOverride(String name, Predicate<QuestContext> condition, Action action) {
        this.name = name;
        this.condition = condition;
        this.action = action;
    }

    /** Applies while the active step's text contains {@code fragment} (case-insensitive). */
    public static StepOverride onStepText(String name, String fragment, Action action) {
        String needle = fragment.toLowerCase(Locale.ROOT);
        return new StepOverride(name, ctx -> ctx.stepText().toLowerCase(Locale.ROOT).contains(needle), action);
    }

    public String name() {
        return name;
    }

    public boolean appliesTo(QuestContext ctx) {
        return condition.test(ctx);
    }

    public boolean run(QuestContext ctx) {
        return action.run(ctx);
    }
}
