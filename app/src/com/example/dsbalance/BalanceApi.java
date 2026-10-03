package com.example.dsbalance;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Calls the one documented public endpoint that an API key can authenticate:
 * GET https://api.deepseek.com/user/balance
 *
 * Usage statistics are NOT available here — those live behind the web dashboard's
 * session token, which an API key cannot use.
 *
 * Every failure mode is turned into a {@link Result} rather than an exception, so
 * callers never have to guess what went wrong.
 */
final class BalanceApi {

    static final int OK = 0;
    static final int ERR_NETWORK = 1;
    static final int ERR_TIMEOUT = 2;
    static final int ERR_UNAUTHORIZED = 3;
    static final int ERR_SERVER = 4;
    static final int ERR_PARSE = 5;
    static final int ERR_NO_BALANCE = 6;

    private static final String ENDPOINT = "https://api.deepseek.com/user/balance";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    private BalanceApi() {
    }

    static final class Result {
        final int code;
        final int httpStatus;
        final String currency;
        final String total;
        final String granted;
        final String toppedUp;
        final boolean available;
        final long fetchedAt;

        Result(int code, int httpStatus, String currency, String total,
               String granted, String toppedUp, boolean available, long fetchedAt) {
            this.code = code;
            this.httpStatus = httpStatus;
            this.currency = currency;
            this.total = total;
            this.granted = granted;
            this.toppedUp = toppedUp;
            this.available = available;
            this.fetchedAt = fetchedAt;
        }

        boolean isOk() {
            return code == OK;
        }

        static Result error(int code, int httpStatus) {
            return new Result(code, httpStatus, null, null, null, null, false, 0L);
        }
    }

    /** Blocking. Must be called off the main thread. */
    static Result fetch(String apiKey) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                    || status == HttpURLConnection.HTTP_FORBIDDEN) {
                return Result.error(ERR_UNAUTHORIZED, status);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                return Result.error(ERR_SERVER, status);
            }
            return parse(read(conn.getInputStream()));
        } catch (SocketTimeoutException e) {
            return Result.error(ERR_TIMEOUT, 0);
        } catch (IOException e) {
            return Result.error(ERR_NETWORK, 0);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    /**
     * Balance values arrive as strings, e.g. "15.33", and are kept that way so the
     * number shown matches the dashboard exactly instead of drifting through a
     * float conversion. Missing fields fall back to "0" rather than throwing.
     */
    private static Result parse(String body) {
        try {
            JSONObject root = new JSONObject(body);
            boolean available = root.optBoolean("is_available", false);
            JSONArray infos = root.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) {
                return Result.error(ERR_NO_BALANCE, 200);
            }

            // Prefer CNY; otherwise take whatever the first entry is.
            JSONObject chosen = null;
            for (int i = 0; i < infos.length(); i++) {
                JSONObject entry = infos.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                if (chosen == null) {
                    chosen = entry;
                }
                if ("CNY".equalsIgnoreCase(entry.optString("currency", ""))) {
                    chosen = entry;
                    break;
                }
            }
            if (chosen == null) {
                return Result.error(ERR_NO_BALANCE, 200);
            }

            return new Result(
                    OK,
                    200,
                    chosen.optString("currency", ""),
                    chosen.optString("total_balance", "0"),
                    chosen.optString("granted_balance", "0"),
                    chosen.optString("topped_up_balance", "0"),
                    available,
                    System.currentTimeMillis());
        } catch (JSONException e) {
            return Result.error(ERR_PARSE, 200);
        }
    }
}
