package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Session results, written by the script thread and read by the overlay on the client thread.
 */
@Singleton
public class MerchantStats {

    private static final int RECENT = 4;

    private volatile Instant startedAt = Instant.now();
    private volatile long realisedProfit;
    private volatile long taxPaid;
    private volatile int closed;
    private volatile int wins;
    private volatile long volumeTraded;
    private final List<Position> recent = new ArrayList<>();
    private volatile List<Opportunity> opportunities = Collections.emptyList();
    private volatile long scannedAt;
    private volatile int scannedItems;

    void reset() {
        startedAt = Instant.now();
        realisedProfit = 0;
        taxPaid = 0;
        closed = 0;
        wins = 0;
        volumeTraded = 0;
        synchronized (recent) {
            recent.clear();
        }
        opportunities = Collections.emptyList();
        scannedAt = 0;
    }

    void recordClosed(Position p) {
        long profit = p.realisedProfit();
        realisedProfit += profit;
        taxPaid += p.taxPaid();
        volumeTraded += p.spent + p.received;
        if (p.sold > 0) {
            closed++;
            if (profit > 0) wins++;
        }
        synchronized (recent) {
            recent.add(0, p);
            while (recent.size() > RECENT) recent.remove(recent.size() - 1);
        }
    }

    void setScan(List<Opportunity> opportunities, int items) {
        this.opportunities = Collections.unmodifiableList(new ArrayList<>(opportunities));
        this.scannedItems = items;
        this.scannedAt = System.currentTimeMillis();
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    long realisedProfit() {
        return realisedProfit;
    }

    long taxPaid() {
        return taxPaid;
    }

    int closed() {
        return closed;
    }

    int wins() {
        return wins;
    }

    long volumeTraded() {
        return volumeTraded;
    }

    List<Position> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    List<Opportunity> opportunities() {
        return opportunities;
    }

    long scannedAt() {
        return scannedAt;
    }

    int scannedItems() {
        return scannedItems;
    }

    long perHour(long amount) {
        long ms = runtime().toMillis();
        return ms < 60_000 ? 0 : amount * 3_600_000L / ms;
    }
}
