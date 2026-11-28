package com.midotheeng.relay;

import static org.junit.Assert.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.Random;
import org.junit.Test;

/** Checks the ntfy.sh behaviour Relay relies on: inline text, binary upload as attachment, reading back. */
public class CloudLiveTest {
    private static String req(String method, String url, byte[] body, String filename) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Relay-Android");
        if (filename != null) c.setRequestProperty("Filename", filename);
        if (body != null) { c.setDoOutput(true); c.getOutputStream().write(body); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = new String(in.readAllBytes(), "UTF-8");
        System.out.println(method + " " + url + " -> " + code + " " + s.substring(0, Math.min(400, s.length())));
        assertTrue("HTTP " + code, code >= 200 && code < 300);
        return s;
    }

    @Test
    public void ntfyRoundTrip() throws Exception {
        String topic = "relay_ci_" + Long.toHexString(new Random().nextLong());
        final StringBuilder live = new StringBuilder();
        // Like Relay: keep a live stream open, then publish to it.
        Thread t = new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://ntfy.sh/" + topic + "/json?since=10m").openConnection();
                c.setReadTimeout(25000);
                c.setRequestProperty("User-Agent", "Relay-Android");
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (live) { live.append(line).append("\n"); }
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(3000);

        byte[] small = new byte[2000];
        new Random().nextBytes(small);
        String b64 = Base64.getEncoder().encodeToString(small);
        req("POST", "https://ntfy.sh/" + topic, b64.getBytes("UTF-8"), null);

        byte[] big = new byte[200_000];
        new Random().nextBytes(big);
        req("PUT", "https://ntfy.sh/" + topic, big, "relay.bin");

        String lines = "";
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            synchronized (live) { lines = live.toString(); }
            if (lines.contains(b64) && lines.contains("\"url\":\"")) break;
        }
        System.out.println("live stream got: " + lines.substring(0, Math.min(300, lines.length())));
        assertTrue("inline message arrives live, unchanged", lines.contains(b64));
        int i = lines.indexOf("\"url\":\"");
        assertTrue("binary upload arrives live as an attachment", i > 0);
        String url = lines.substring(i + 7, lines.indexOf('"', i + 7));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        byte[] got = c.getInputStream().readAllBytes();
        assertArrayEquals("attachment downloads byte-for-byte", big, got);

        // Catch-up after being offline: a new connection with since=<earlier id> should replay.
        String replay = req("GET", "https://ntfy.sh/" + topic + "/json?poll=1&since=10m", null, null);
        System.out.println("replay contains message: " + replay.contains(b64));
        System.out.println("ntfy round trip OK: " + topic);
    }
}
