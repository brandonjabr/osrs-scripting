package net.runelite.client.plugins.microbot.util.misc;

import lombok.Getter;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.Objects;

/**
 * Stops a script from repeating an action that isn't working. Call {@link #attempt} right before each try, with a
 * signature of the game state the action should change: the same action with an unchanged signature counts as
 * another failed try, and once {@code maxAttempts} tries have left everything as it was, {@code attempt} returns
 * false and {@link #getFailure()} says what failed.
 * <p>
 * Any change in the action or the signature means the last try did something, and the count starts again. Calls
 * closer together than the gap belong to the same try still in flight (a walk being re-issued each tick, say).
 */
public final class AttemptGuard {

    private final int maxAttempts;
    private final long defaultGapMs;

    private String action;
    private String signature;
    private int count;
    private long lastAt;
    @Getter
    private String failure;

    public AttemptGuard(int maxAttempts, long defaultGapMs) {
        this.maxAttempts = maxAttempts;
        this.defaultGapMs = defaultGapMs;
    }

    public boolean attempt(String action, String signature) {
        return attempt(action, signature, defaultGapMs);
    }

    /** @return false once {@code action} has been tried {@code maxAttempts} times without the signature changing */
    public boolean attempt(String action, String signature, long gapMs) {
        long now = System.currentTimeMillis();
        if (!action.equals(this.action) || !Objects.equals(signature, this.signature)) {
            this.action = action;
            this.signature = signature;
            count = 1;
            lastAt = now;
            return true;
        }
        if (now - lastAt < gapMs) return true;
        lastAt = now;
        if (++count > maxAttempts) {
            failure = action + " (tried " + maxAttempts + " times and nothing changed)";
            return false;
        }
        return true;
    }

    /** Forgets the current action, e.g. after it is confirmed to have worked. */
    public void reset() {
        action = null;
        signature = null;
        count = 0;
    }

    /**
     * What a typical action changes: position, inventory contents, an open bank or GE, and the dialogue text. Two
     * equal signatures mean nothing visible happened between them.
     */
    public static String playerSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(Rs2Player.getWorldLocation()).append('|');
        Rs2Inventory.items().forEach(i -> sb.append(i.getId()).append('x').append(i.getQuantity()).append(','));
        sb.append('|').append(Rs2Bank.isOpen()).append(Rs2GrandExchange.isOpen());
        if (Rs2Dialogue.isInDialogue()) sb.append('|').append(Rs2Dialogue.getDialogueText());
        return sb.toString();
    }
}
