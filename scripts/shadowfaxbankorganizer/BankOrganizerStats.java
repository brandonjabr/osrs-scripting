package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Progress written by the script thread and read by the overlay. */
@Singleton
public class BankOrganizerStats {

    private volatile Instant startedAt = Instant.now();
    private volatile int totalItems;
    private final AtomicInteger moved = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final Map<BankCategory, Integer> planned = new ConcurrentHashMap<>();
    private final Map<BankCategory, AtomicInteger> placed = new ConcurrentHashMap<>();
    private volatile BankCategory current;

    void reset() {
        startedAt = Instant.now();
        totalItems = 0;
        moved.set(0);
        skipped.set(0);
        planned.clear();
        placed.clear();
        current = null;
    }

    /** Planned items per tab. */
    void setPlan(Map<BankCategory, Integer> counts) {
        placed.clear();
        planned.clear();
        planned.putAll(counts);
        totalItems = counts.values().stream().mapToInt(Integer::intValue).sum();
        counts.keySet().forEach(c -> placed.put(c, new AtomicInteger()));
    }

    void addPlaced(BankCategory category, boolean dragged) {
        placed.computeIfAbsent(category, c -> new AtomicInteger()).incrementAndGet();
        if (dragged) moved.incrementAndGet();
    }

    void addSkipped(BankCategory category) {
        skipped.incrementAndGet();
        placed.computeIfAbsent(category, c -> new AtomicInteger()).incrementAndGet();
    }

    void setCurrent(BankCategory category) {
        current = category;
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int totalItems() {
        return totalItems;
    }

    int done() {
        return placed.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    int moved() {
        return moved.get();
    }

    int skipped() {
        return skipped.get();
    }

    BankCategory current() {
        return current;
    }

    /** Planned item count per category, in tab order (only categories with items). */
    Map<BankCategory, Integer> planned() {
        Map<BankCategory, Integer> ordered = new EnumMap<>(BankCategory.class);
        ordered.putAll(planned);
        return ordered;
    }

    int placed(BankCategory category) {
        AtomicInteger n = placed.get(category);
        return n == null ? 0 : n.get();
    }
}
