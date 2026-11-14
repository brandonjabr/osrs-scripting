package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The finished layout: for each tab, every item in order. Built from the full listing of the bank before anything is
 * moved. Within a tab, sections come in constant order; inside a section, groups (an armour set, a weapon tier, all
 * runes, one potion's doses...) are kept together and sorted tier by tier.
 */
@Slf4j
final class BankPlan {

    static final int ROW = 8;

    /** One planned slot: a real item, or its placeholder. */
    static final class Entry {
        final int itemId; // the real item id
        final String name;
        final BankSection section;
        final boolean placeholder;

        Entry(int itemId, String name, BankSection section, boolean placeholder) {
            this.itemId = itemId;
            this.name = name;
            this.section = section;
            this.placeholder = placeholder;
        }
    }

    static final class TabPlan {
        final BankCategory category;
        final List<Entry> entries;

        TabPlan(BankCategory category, List<Entry> entries) {
            this.category = category;
            this.entries = entries;
        }
    }

    /** One bank item from the listing, with where it goes. */
    static final class Listed {
        final BankItemClassifier.Placement placement;
        final boolean placeholder;

        Listed(BankItemClassifier.Placement placement, boolean placeholder) {
            this.placement = placement;
            this.placeholder = placeholder;
        }
    }

    private static final class Group {
        final String key;
        final int rank;
        final List<Listed> items = new ArrayList<>();

        Group(String key, int rank) {
            this.key = key;
            this.rank = rank;
        }
    }

    final List<TabPlan> tabs;

    private BankPlan(List<TabPlan> tabs) {
        this.tabs = tabs;
    }

    /** Tabs → sections → groups → items, all sorted. */
    static BankPlan build(List<Listed> listing) {
        Map<BankSection, Map<String, Group>> bySection = new EnumMap<>(BankSection.class);
        for (Listed listed : listing) {
            BankItemClassifier.Placement p = listed.placement;
            bySection.computeIfAbsent(p.section, s -> new LinkedHashMap<>())
                    .computeIfAbsent(p.group, g -> new Group(g, p.groupRank))
                    .items.add(listed);
        }
        Map<BankCategory, List<Entry>> byTab = new EnumMap<>(BankCategory.class);
        for (Map.Entry<BankSection, Map<String, Group>> e : bySection.entrySet()) {
            List<Group> groups = new ArrayList<>(e.getValue().values());
            groups.sort(Comparator.<Group>comparingInt(g -> g.rank).thenComparing(g -> g.key));
            List<Entry> seq = byTab.computeIfAbsent(e.getKey().category, c -> new ArrayList<>());
            for (Group g : groups) {
                g.items.sort(Comparator.<Listed>comparingInt(l -> l.placement.itemRank)
                        .thenComparing(l -> l.placement.name.toLowerCase())
                        .thenComparingInt(l -> l.placement.itemId));
                for (Listed l : g.items) {
                    seq.add(new Entry(l.placement.itemId, l.placement.name, e.getKey(), l.placeholder));
                }
            }
        }
        List<TabPlan> tabs = new ArrayList<>();
        byTab.forEach((category, entries) -> tabs.add(new TabPlan(category, entries)));
        return new BankPlan(tabs);
    }

    int totalSlots() {
        return tabs.stream().mapToInt(t -> t.entries.size()).sum();
    }

    /** The plan row by row, so the log shows exactly what the bank will look like. */
    void log() {
        log.info("[BankOrganizer] Plan: {} tabs, {} items", tabs.size(), totalSlots());
        for (int t = 0; t < tabs.size(); t++) {
            TabPlan tab = tabs.get(t);
            log.info("[BankOrganizer] Tab {} — {} ({} items)", t + 1, tab.category.label, tab.entries.size());
            for (int start = 0; start < tab.entries.size(); start += ROW) {
                List<Entry> row = tab.entries.subList(start, Math.min(start + ROW, tab.entries.size()));
                TreeSet<String> sections = new TreeSet<>();
                StringBuilder sb = new StringBuilder();
                for (Entry e : row) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(e.name).append(e.placeholder ? " (placeholder)" : "");
                    sections.add(e.section.label);
                }
                log.info("[BankOrganizer]   row {} [{}]: {}", start / ROW + 1, String.join(", ", sections), sb);
            }
        }
    }
}
