package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session progress written by the script thread and read by the overlay on the client thread.
 */
@Singleton
public class QuestingStats {

    public enum Status {
        QUEUED("queued"),
        WAITING("waiting"),
        RUNNING("running"),
        DONE("done"),
        ALREADY_DONE("already done"),
        SKIPPED("skipped"),
        STUCK("stuck");

        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** One selected quest's progress. */
    public static final class Row {
        final String quest;
        volatile Status status = Status.QUEUED;
        volatile String detail = "";
        volatile Instant startedAt;
        volatile Duration took;

        Row(String quest) {
            this.quest = quest;
        }
    }

    private final List<Row> rows = new CopyOnWriteArrayList<>();
    private volatile Instant startedAt = Instant.now();
    private final AtomicLong gpSpent = new AtomicLong();
    private volatile int startQuestPoints = -1;
    private volatile String phase = "";
    private volatile String currentQuest = "";
    private volatile String currentStep = "";

    void reset(List<String> quests) {
        rows.clear();
        quests.forEach(q -> rows.add(new Row(q)));
        startedAt = Instant.now();
        gpSpent.set(0);
        startQuestPoints = -1;
        phase = "";
        currentQuest = "";
        currentStep = "";
    }

    void captureBaselineIfNeeded() {
        if (startQuestPoints < 0) startQuestPoints = questPoints();
    }

    void setStatus(String quest, Status status, String detail) {
        for (Row row : rows) {
            if (!row.quest.equals(quest)) continue;
            if (status == Status.RUNNING && row.startedAt == null) row.startedAt = Instant.now();
            if ((status == Status.DONE || status == Status.SKIPPED) && row.startedAt != null && row.took == null) {
                row.took = Duration.between(row.startedAt, Instant.now());
            }
            row.status = status;
            row.detail = detail == null ? "" : detail;
        }
    }

    Status statusOf(String quest) {
        return rows.stream().filter(r -> r.quest.equals(quest)).map(r -> r.status).findFirst().orElse(null);
    }

    void addGpSpent(long gp) {
        if (gp > 0) gpSpent.addAndGet(gp);
    }

    void setPhase(String phase) {
        this.phase = phase;
    }

    void setCurrent(String quest, String step) {
        this.currentQuest = quest == null ? "" : quest;
        this.currentStep = step == null ? "" : step;
    }

    List<Row> rows() {
        return rows;
    }

    long count(Status... statuses) {
        return rows.stream().filter(r -> List.of(statuses).contains(r.status)).count();
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    long gpSpent() {
        return gpSpent.get();
    }

    String phase() {
        return phase;
    }

    String currentQuest() {
        return currentQuest;
    }

    String currentStep() {
        return currentStep;
    }

    int questPointsGained() {
        return startQuestPoints < 0 ? 0 : Math.max(0, questPoints() - startQuestPoints);
    }

    static int questPoints() {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getVarpValue(VarPlayerID.QP))
                .orElse(0);
    }
}
