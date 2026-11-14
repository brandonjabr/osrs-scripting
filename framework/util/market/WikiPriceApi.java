package net.runelite.client.plugins.microbot.util.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The OSRS Wiki real-time prices API (prices.runescape.wiki, v2). Since 30 September 2026 its data comes from Jagex
 * and covers every client, not just RuneLite users, so volumes are true market totals.
 * <ul>
 *   <li>{@code /mapping}: every tradeable item with its buy limit, members flag and alch values.</li>
 *   <li>{@code /latest}: the last instant-buy ("high") and instant-sell ("low") price per item, with times.</li>
 *   <li>{@code /5m}, {@code /1h}, {@code /24h}: average high/low prices and how many traded at each.</li>
 *   <li>{@code /timeseries}: one item's history; 24h → 5-minute steps, 7d → hourly, 30d → 6-hourly, 6m → daily.</li>
 * </ul>
 * The wiki asks for a descriptive User-Agent (generic Java ones are blocked) and bulk requests rather than one per
 * item; responses are cached for about a minute on their side, so polling faster than that gains nothing.
 * <p>
 * All calls block; run them off the client thread.
 */
@Slf4j
public final class WikiPriceApi {

    private static final String BASE = "https://prices.runescape.wiki/api/v2/osrs/";
    private static final String USER_AGENT = "Shadowfax Merchant - Microbot hobby flipping scanner";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** One item from {@code /mapping}. {@code limit} is 0 when the wiki doesn't know it. */
    public static final class ItemMapping {
        public final int id;
        public final String name;
        public final boolean members;
        public final int limit;
        public final long value;
        public final long highAlch;

        ItemMapping(int id, String name, boolean members, int limit, long value, long highAlch) {
            this.id = id;
            this.name = name;
            this.members = members;
            this.limit = limit;
            this.value = value;
            this.highAlch = highAlch;
        }
    }

    /** {@code /latest}: last instant-buy (high) and instant-sell (low) prices; 0 / 0 time when never seen. */
    public static final class Latest {
        public final long high;
        public final long highTime;
        public final long low;
        public final long lowTime;

        Latest(long high, long highTime, long low, long lowTime) {
            this.high = high;
            this.highTime = highTime;
            this.low = low;
            this.lowTime = lowTime;
        }
    }

    /** One window of averages ({@code /5m}, {@code /1h}, {@code /24h} or a timeseries point). 0 = no trades. */
    public static final class Average {
        public final long timestamp;
        public final double avgHigh;
        public final long highVolume;
        public final double avgLow;
        public final long lowVolume;

        Average(long timestamp, double avgHigh, long highVolume, double avgLow, long lowVolume) {
            this.timestamp = timestamp;
            this.avgHigh = avgHigh;
            this.highVolume = highVolume;
            this.avgLow = avgLow;
            this.lowVolume = lowVolume;
        }

        /** Midpoint of the average high and low, or whichever side traded. */
        public double mid() {
            if (avgHigh > 0 && avgLow > 0) return (avgHigh + avgLow) / 2;
            return Math.max(avgHigh, avgLow);
        }

        public long volume() {
            return highVolume + lowVolume;
        }
    }

    private WikiPriceApi() {
    }

    public static Map<Integer, ItemMapping> mapping() throws IOException {
        JsonArray arr = get("mapping").getAsJsonArray();
        Map<Integer, ItemMapping> out = new HashMap<>(arr.size() * 2);
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            int id = o.get("id").getAsInt();
            out.put(id, new ItemMapping(id, str(o, "name"), bool(o, "members"), (int) num(o, "limit"),
                    num(o, "value"), num(o, "highalch")));
        }
        return out;
    }

    public static Map<Integer, Latest> latest() throws IOException {
        JsonObject data = get("latest").getAsJsonObject().getAsJsonObject("data");
        Map<Integer, Latest> out = new HashMap<>(data.size() * 2);
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            out.put(Integer.parseInt(e.getKey()),
                    new Latest(num(o, "high"), num(o, "highTime"), num(o, "low"), num(o, "lowTime")));
        }
        return out;
    }

    /** {@code window} is "5m", "1h" or "24h". */
    public static Map<Integer, Average> averages(String window) throws IOException {
        return parseAverages(get(window).getAsJsonObject());
    }

    /**
     * A past window: {@code timestamp} (epoch seconds) is the window's start and must be aligned to it, e.g. a UTC
     * midnight for "24h". Past windows never change, so callers can cache them for good.
     */
    public static Map<Integer, Average> averagesAt(String window, long timestamp) throws IOException {
        return parseAverages(get(window + "?timestamp=" + timestamp).getAsJsonObject());
    }

    private static Map<Integer, Average> parseAverages(JsonObject root) {
        long ts = (long) num(root, "timestamp");
        JsonObject data = root.getAsJsonObject("data");
        Map<Integer, Average> out = new HashMap<>(data.size() * 2);
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            out.put(Integer.parseInt(e.getKey()), average(ts, e.getValue().getAsJsonObject()));
        }
        return out;
    }

    /** {@code lookback} is "24h", "7d", "30d", "6m" or "1y"; oldest point first. */
    public static List<Average> timeseries(int itemId, String lookback) throws IOException {
        JsonElement data = get("timeseries?id=" + itemId + "&lookback=" + lookback).getAsJsonObject().get("data");
        if (data == null || !data.isJsonArray()) return Collections.emptyList();
        List<Average> out = new ArrayList<>();
        for (JsonElement e : data.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            out.add(average((long) num(o, "timestamp"), o));
        }
        return out;
    }

    // --- Plumbing ---

    private static Average average(long ts, JsonObject o) {
        return new Average(ts, dbl(o, "avgHighPrice"), num(o, "highPriceVolume"), dbl(o, "avgLowPrice"), num(o, "lowPriceVolume"));
    }

    private static JsonElement get(String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("prices API " + path + " returned HTTP " + response.statusCode());
            }
            return new JsonParser().parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + path, e);
        }
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static double dbl(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
