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
        byte[] small = new byte[2000];
        new Random().nextBytes(small);
        String b64 = Base64.getEncoder().encodeToString(small);
        req("POST", "https://ntfy.sh/" + topic, b64.getBytes("UTF-8"), null);

        byte[] big = new byte[200_000];
        new Random().nextBytes(big);
        req("PUT", "https://ntfy.sh/" + topic, big, "relay.bin");

        String lines = req("GET", "https://ntfy.sh/" + topic + "/json?poll=1&since=all", null, null);
        assertTrue("inline message comes back unchanged", lines.contains(b64));
        int i = lines.indexOf("\"url\":\"");
        assertTrue("binary upload becomes an attachment", i > 0);
        String url = lines.substring(i + 7, lines.indexOf('"', i + 7));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        byte[] got = c.getInputStream().readAllBytes();
        assertArrayEquals("attachment downloads byte-for-byte", big, got);
        System.out.println("ntfy round trip OK: " + topic);
    }
}
