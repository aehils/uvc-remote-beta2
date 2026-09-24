package com.expiation.reemanremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP client for the Reeman navigation computer (firmware RSNF1-v5.1.12_01).
 * Blocking calls: only ever call these from a background thread.
 *
 * A demo instance (see {@link #demo}) answers from an in-app {@link FakeRobot}
 * and never opens a socket.
 */
final class RobotApi {

    static final class Result {
        final boolean ok;
        final int httpCode;
        final String body;
        final String error;

        Result(boolean ok, int httpCode, String body, String error) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.body = body;
            this.error = error;
        }
    }

    private volatile String host;
    private final FakeRobot fake; // non-null = demo mode

    RobotApi(String host) {
        this(host, null);
    }

    private RobotApi(String host, FakeRobot fake) {
        this.host = host;
        this.fake = fake;
    }

    static RobotApi demo(FakeRobot fake) {
        return new RobotApi("demo", fake);
    }

    boolean isDemo() {
        return fake != null;
    }

    void setHost(String host) {
        this.host = host;
    }

    String getHost() {
        return host;
    }

    Result get(String path) {
        return request("GET", path, null, 1500, 2000);
    }

    Result post(String path, String json) {
        return request("POST", path, json, 2000, 4000);
    }

    private Result request(String method, String path, String json, int connectMs, int readMs) {
        if (fake != null) return simulate(method, path, json, connectMs);
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://" + host + path);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            if (json != null) {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = is == null ? "" : readAll(is).trim();
            return toResult(code, body);
        } catch (IOException e) {
            return new Result(false, -1, "", describe(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private Result simulate(String method, String path, String json, int connectMs) {
        FakeRobot.Reply r = fake.handle(method, path, json);
        try {
            // A dropped connection costs the same wait as the real timeout would.
            Thread.sleep(r == null ? connectMs : r.latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -1, "", "interrupted");
        }
        if (r == null) return new Result(false, -1, "", "timed out: robot not answering (demo: simulated Wi-Fi drop)");
        return toResult(r.code, r.body);
    }

    private static Result toResult(int code, String body) {
        boolean ok = code >= 200 && code < 300;
        return new Result(ok, code, body, ok ? null : "HTTP " + code + " " + body);
    }

    private static String readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("CLEARTEXT") || m.contains("network security policy")) {
            return "Android blocked plain http to this address. The app only allows 192.168.1.228 (see README).";
        }
        if (e instanceof SocketTimeoutException) return "timed out: robot not answering";
        if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            return "can't reach robot: is the phone on the robot's Wi-Fi?";
        }
        return m;
    }
}
