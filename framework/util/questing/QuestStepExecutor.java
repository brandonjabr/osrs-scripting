package net.runelite.client.plugins.microbot.util.questing;

import net.runelite.client.plugins.microbot.questhelper.QuestScript;

import java.util.concurrent.ScheduledFuture;

/**
 * Quest Helper's step executor ({@link QuestScript#applyStep}), usable without starting its own loop.
 * <p>
 * {@code QuestScript} checks its {@code mainScheduledFuture} for cancellation inside long step handlers (e.g. while
 * searching for a tile to stand on beside an object). That field is only set by {@code QuestScript.run()}, so a
 * script that calls {@code applyStep} directly must {@link #bindTo} its own loop, or those checks throw a
 * NullPointerException. Binding also means stopping the owning script cancels the executor's long walks.
 */
public class QuestStepExecutor extends QuestScript {

    /** Shares the owning script's scheduled loop for cancellation checks. Call right after scheduling it. */
    public void bindTo(ScheduledFuture<?> ownerLoop) {
        this.mainScheduledFuture = ownerLoop;
    }
}
