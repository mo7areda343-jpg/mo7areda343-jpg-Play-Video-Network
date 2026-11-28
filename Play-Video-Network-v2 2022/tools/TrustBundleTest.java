package com.example.mediahub;
import com.sun.net.httpserver.*; import javax.net.ssl.*; import java.io.*; import java.net.*; import java.security.*; import java.security.cert.*; import java.util.*;
public class TB {
    static int fail = 0;
    static void check(String n, boolean ok) { if (!ok) fail++; System.out.println((ok ? "PASS " : "FAIL ") + n); }
    public static void main(String[] a) throws Exception {
        // 1) the real bundle parses and contains modern roots
        List<X509Certificate> real = TrustBundle.parse(new FileInputStream("real.pem"));
        boolean isrg = false; for (X509Certificate c : real) if (c.getSubjectX500Principal().getName().contains("ISRG Root X1")) isrg = true;
        check("bundle parsed: " + real.size() + " certificates (>100)", real.size() > 100);
        check("bundle contains ISRG Root X1 (Let's Encrypt)", isrg);

        // 2) a corrupt block is skipped, valid ones kept
        String ca = new String(java.nio.file.Files.readAllBytes(new File("ca.pem").toPath()), "UTF-8");
        String bad = "-----BEGIN CERTIFICATE-----\nAAAA not a certificate\n-----END CERTIFICATE-----\n";
        List<X509Certificate> mixed = TrustBundle.parse(new ByteArrayInputStream((ca + bad + ca).getBytes("UTF-8")));
        check("corrupt block skipped (2 valid kept, got " + mixed.size() + ")", mixed.size() == 2);
        check("empty input gives empty list", TrustBundle.parse(new ByteArrayInputStream(new byte[0])).isEmpty());

        // 3) https server whose certificate is signed by a private CA the device does not know
        KeyStore ks = KeyStore.getInstance("PKCS12"); ks.load(new FileInputStream("srv.p12"), "changeit".toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509"); kmf.init(ks, "changeit".toCharArray());
        SSLContext sc = SSLContext.getInstance("TLS"); sc.init(kmf.getKeyManagers(), null, null);
        HttpsServer srv = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setHttpsConfigurator(new HttpsConfigurator(sc));
        srv.createContext("/l.m3u", ex -> { byte[] b = "#EXTM3U\n#EXTINF:-1,Trusted Chan\nhttp://s/1.ts\n".getBytes("UTF-8"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.start();
        String url = "https://localhost:" + srv.getAddress().getPort() + "/l.m3u";

        M3uPlaylistParser.setLenientTls(false);
        try { M3uPlaylistParser.load(url); check("without the bundled CA the unknown CA is rejected", false); }
        catch (SSLException e) { check("without the bundled CA the unknown CA is rejected", true); }

        M3uPlaylistParser.setExtraTrust(TrustBundle.composite(TrustBundle.parse(new ByteArrayInputStream(ca.getBytes("UTF-8")))));
        List<M3uPlaylistParser.ChannelItem> l = M3uPlaylistParser.load(url);
        check("with the CA in the bundle the playlist downloads", l.size() == 1 && l.get(0).title.equals("Trusted Chan"));

        // 4) a bundle that does NOT contain that CA still rejects it (no blanket trust)
        M3uPlaylistParser.setExtraTrust(TrustBundle.composite(real));
        try { M3uPlaylistParser.load(url); check("real Mozilla bundle does not trust the private CA", false); }
        catch (SSLException e) { check("real Mozilla bundle does not trust the private CA", true); }

        // 5) system-trusted behaviour is kept: composite still exposes the JVM's own issuers
        X509TrustManager tm = TrustBundle.composite(real);
        check("accepted issuers = system + bundled (" + tm.getAcceptedIssuers().length + ")", tm.getAcceptedIssuers().length > real.size());
        srv.stop(0);
        System.out.println(fail == 0 ? "TRUST TESTS PASSED" : fail + " FAILED");
    }
}
