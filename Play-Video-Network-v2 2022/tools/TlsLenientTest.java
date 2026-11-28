package com.example.mediahub;
import com.sun.net.httpserver.*; import javax.net.ssl.*; import java.io.*; import java.net.*; import java.security.*; import java.util.*;
public class TLS {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12"); ks.load(new FileInputStream("ks.p12"), "changeit".toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509"); kmf.init(ks, "changeit".toCharArray());
        SSLContext sc = SSLContext.getInstance("TLS"); sc.init(kmf.getKeyManagers(), null, null);
        HttpsServer srv = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setHttpsConfigurator(new HttpsConfigurator(sc));
        srv.createContext("/list.m3u", ex -> { byte[] b = "#EXTM3U\n#EXTINF:-1,Secure Chan\nhttp://s/1.ts\n".getBytes("UTF-8"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.start();
        String url = "https://localhost:" + srv.getAddress().getPort() + "/list.m3u";
        int fail = 0;
        M3uPlaylistParser.setLenientTls(false);
        try { M3uPlaylistParser.load(url); System.out.println("FAIL strict mode accepted a self-signed cert"); fail++; }
        catch (javax.net.ssl.SSLException e) { System.out.println("PASS strict mode rejects untrusted cert (" + e.getClass().getSimpleName() + ")"); }
        M3uPlaylistParser.setLenientTls(true);
        try { List<M3uPlaylistParser.ChannelItem> l = M3uPlaylistParser.load(url);
              boolean ok = l.size() == 1 && l.get(0).title.equals("Secure Chan");
              System.out.println((ok ? "PASS" : "FAIL") + " lenient mode downloads the playlist"); if (!ok) fail++; }
        catch (Exception e) { System.out.println("FAIL lenient mode: " + e); fail++; }
        M3uPlaylistParser.setLenientTls(false);
        srv.stop(0);
        System.out.println(fail == 0 ? "TLS TESTS PASSED" : fail + " FAILED");
    }
}
