package com.example.dsbalance;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parsed usage data, built from the three endpoints the dashboard itself calls.
 *
 * Shapes were taken from real responses, not from documentation:
 *
 *   by_api_key/amount  data.biz_data.series[]  -> {model, buckets:[{time, usage:{...}}]}
 *   by_api_key/cost    data.biz_data.data[]    -> {currency, series:[...]}   <- one extra level
 *   get_user_summary   data.biz_data.total_costs[]
 *
 * The amount/cost asymmetry is real: amount nests series directly, cost wraps them
 * per currency. They cannot share one walker.
 */
final class UsageModel {

    static final class Day {
        final long time;
        double cost;
        long requests;
        long tokens;

        Day(long time) {
            this.time = time;
        }
    }

    static final class Model {
        final String name;
        double cost;
        long requests;
        long tokens;

        Model(String name) {
            this.name = name;
        }
    }

    final List<Day> days = new ArrayList<>();
    final List<Model> models = new ArrayList<>();
    double totalCost;
    long totalRequests;
    long totalTokens;
    /** Lifetime spend, from get_user_summary — not the 30-day window. */
    double accumulatedCost;
    String currency = "CNY";
    long fetchedAt;

    /** True when the responses parsed but carried nothing worth drawing. */
    boolean isEmpty() {
        return totalTokens == 0 && totalRequests == 0 && totalCost == 0;
    }

    static UsageModel parse(String amountJson, String costJson, String summaryJson)
            throws JSONException {
        UsageModel model = new UsageModel();
        Map<Long, Day> daysByTime = new TreeMap<Long, Day>();
        Map<String, Model> modelsByName = new LinkedHashMap<String, Model>();

        // ---- daily tokens -------------------------------------------------
        JSONObject amountBiz = bizData(amountJson);
        JSONArray amountSeries = amountBiz.optJSONArray("series");
        if (amountSeries == null) {
            throw new JSONException("by_api_key/amount 缺少 series");
        }
        for (int i = 0; i < amountSeries.length(); i++) {
            JSONObject series = amountSeries.optJSONObject(i);
            if (series == null) {
                continue;
            }
            Model model_entry = model(modelsByName, series.optString("model", "?"));
            JSONArray buckets = series.optJSONArray("buckets");
            for (int j = 0; buckets != null && j < buckets.length(); j++) {
                JSONObject bucket = buckets.optJSONObject(j);
                if (bucket == null) {
                    continue;
                }
                JSONObject usage = bucket.optJSONObject("usage");
                long requests = usage == null ? 0 : usage.optLong("REQUEST", 0);
                long tokens = tokenTotal(usage);
                Day day = day(daysByTime, bucket.optLong("time", 0L));
                day.requests += requests;
                day.tokens += tokens;
                model_entry.requests += requests;
                model_entry.tokens += tokens;
            }
        }

        // ---- daily cost ---------------------------------------------------
        JSONObject costBiz = bizData(costJson);
        JSONArray currencies = costBiz.optJSONArray("data");
        if (currencies == null || currencies.length() == 0) {
            throw new JSONException("by_api_key/cost 缺少 data");
        }
        JSONObject chosen = currencies.optJSONObject(0);
        for (int i = 0; i < currencies.length(); i++) {
            JSONObject entry = currencies.optJSONObject(i);
            if (entry != null && "CNY".equalsIgnoreCase(entry.optString("currency", ""))) {
                chosen = entry;
                break;
            }
        }
        if (chosen == null) {
            throw new JSONException("by_api_key/cost 没有可用币种");
        }
        model.currency = chosen.optString("currency", "CNY");
        JSONArray costSeries = chosen.optJSONArray("series");
        for (int i = 0; costSeries != null && i < costSeries.length(); i++) {
            JSONObject series = costSeries.optJSONObject(i);
            if (series == null) {
                continue;
            }
            Model model_entry = model(modelsByName, series.optString("model", "?"));
            JSONArray buckets = series.optJSONArray("buckets");
            for (int j = 0; buckets != null && j < buckets.length(); j++) {
                JSONObject bucket = buckets.optJSONObject(j);
                if (bucket == null) {
                    continue;
                }
                double cost = parseAmount(bucket.optString("cost", "0"));
                Day day = day(daysByTime, bucket.optLong("time", 0L));
                day.cost += cost;
                model_entry.cost += cost;
            }
        }

        // ---- lifetime spend -----------------------------------------------
        JSONObject summaryBiz = bizData(summaryJson);
        JSONArray totalCosts = summaryBiz.optJSONArray("total_costs");
        for (int i = 0; totalCosts != null && i < totalCosts.length(); i++) {
            JSONObject entry = totalCosts.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            if (model.accumulatedCost == 0 || "CNY".equalsIgnoreCase(entry.optString("currency", ""))) {
                model.accumulatedCost = parseAmount(entry.optString("amount", "0"));
            }
        }

        model.days.addAll(daysByTime.values());
        model.models.addAll(modelsByName.values());
        for (Day day : model.days) {
            model.totalCost += day.cost;
            model.totalRequests += day.requests;
            model.totalTokens += day.tokens;
        }
        model.fetchedAt = System.currentTimeMillis();
        return model;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Tokens = cache hit + cache miss + output. The dashboard's own tooltip adds up
     * exactly this way; PROMPT_TOKEN is always 0 and carries no information.
     */
    private static long tokenTotal(JSONObject usage) {
        if (usage == null) {
            return 0;
        }
        return usage.optLong("PROMPT_CACHE_HIT_TOKEN", 0)
                + usage.optLong("PROMPT_CACHE_MISS_TOKEN", 0)
                + usage.optLong("RESPONSE_TOKEN", 0);
    }

    /**
     * Reaches through {code, data:{biz_code, biz_data}}. The HTTP status is always
     * 200 here — the real outcome lives in biz_code, so a non-zero one has to be
     * treated as a failure rather than parsed as empty data.
     */
    private static JSONObject bizData(String body) throws JSONException {
        JSONObject data = new JSONObject(body).getJSONObject("data");
        int code = data.optInt("biz_code", -1);
        if (code != 0) {
            throw new JSONException("biz_code=" + code + " " + data.optString("biz_msg"));
        }
        return data.getJSONObject("biz_data");
    }

    private static Day day(Map<Long, Day> map, long time) {
        Day day = map.get(time);
        if (day == null) {
            day = new Day(time);
            map.put(time, day);
        }
        return day;
    }

    private static Model model(Map<String, Model> map, String name) {
        Model model = map.get(name);
        if (model == null) {
            model = new Model(name);
            map.put(name, model);
        }
        return model;
    }

    private static double parseAmount(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- formatting

    static String money(double value) {
        return String.format(Locale.US, "¥%.2f", value);
    }

    static String count(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** Compact form for tight rows, following the dashboard's own 76.5M style. */
    static String compact(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2fB", value / 1_000_000_000.0);
        }
        if (value >= 1_000_000L) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000L) {
            return String.format(Locale.US, "%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    static String dayLabel(long epochSeconds) {
        return new SimpleDateFormat("M/d", Locale.US).format(new Date(epochSeconds * 1000L));
    }
}
