package net.runelite.client.plugins.microbot.util.shopping;

import net.runelite.client.plugins.microbot.Microbot;

/**
 * Safe access to RuneLite's cached GE guide prices.
 * <p>
 * {@code ItemManager.getItemPrice} reads the item's definition, which must happen on the client thread. Called from a
 * script thread it trips RuneLite's client-thread assertion; with assertions on (as {@code ./gradlew :client:run}
 * starts the client) that is an {@link AssertionError}, which a script loop's {@code catch (Exception)} misses, so the
 * loop dies without a trace. Use {@link #guidePrice(int)} instead from script code.
 */
public final class ItemPrices {

    private ItemPrices() {
    }

    /** The item's GE guide price, or 0 if unknown. Safe from any thread. */
    public static long guidePrice(int itemId) {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getItemManager().getItemPrice(itemId))
                .orElse(0L);
    }
}
