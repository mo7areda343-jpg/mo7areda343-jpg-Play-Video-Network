package com.example.mediahub;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Lightweight M3U / M3U8 parser tuned for Android 4.3 (API 18) low-RAM devices.
 * Supports local files (internal/USB) and HTTP/HTTPS playlists (with TLSv1.2 enabled).
 */
public class M3uPlaylistParser {

    public static final int MAX_ENTRIES = 5000;

    public static class ChannelItem {
        public final String title;
        public final String url;

        public ChannelItem(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    public static boolean isM3uPathOrUrl(String target) {
        if (target == null) return false;
        String clean = target.trim().toLowerCase(Locale.US);
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            // Only playlist-looking links are downloaded and parsed; a direct .mp4 / live .ts link
            // must go straight to libVLC. IPTV providers often use get.php?...type=m3u_plus.
            return clean.contains(".m3u") || clean.contains(".pls")
                    || clean.contains("type=m3u") || clean.contains("output=m3u");
        }
        int q = clean.indexOf('?');
        if (q != -1) clean = clean.substring(0, q);
        int hash = clean.indexOf('#');
        if (hash != -1) clean = clean.substring(0, hash);
        return clean.endsWith(".m3u") || clean.endsWith(".m3u8") || clean.endsWith(".pls");
    }

    public static List<ChannelItem> load(String source) throws IOException {
        if (source == null || source.trim().isEmpty()) {
            throw new IOException("Empty playlist source");
        }
        String src = source.trim();
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return loadFromUrl(src, 0);
        }
        if (src.startsWith("file://")) {
            src = src.substring("file://".length());
        }
        return loadFromFile(new File(src));
    }

    private static List<ChannelItem> loadFromUrl(String urlStr, int redirects) throws IOException {
        if (redirects > 5) {
            throw new IOException("Too many redirects");
        }
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                enableTls12((HttpsURLConnection) conn);
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "VLC/3.0.18 LibVLC/3.0.18");
            conn.connect();

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_SEE_OTHER) {
                String next = conn.getHeaderField("Location");
                if (next != null && !next.isEmpty()) {
                    return loadFromUrl(new URL(url, next).toString(), redirects + 1);
                }
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            in = conn.getInputStream();
            return parseStream(in, urlStr, urlStr);
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static List<ChannelItem> loadFromFile(File file) throws IOException {
        if (!file.exists() || !file.isFile()) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            String baseDir = file.getParentFile() != null ? file.getParentFile().getAbsolutePath() : null;
            return parseStream(in, baseDir, file.getAbsolutePath());
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static List<ChannelItem> parseStream(InputStream in, String baseSource) throws IOException {
        return parseStream(in, baseSource, null);
    }

    public static List<ChannelItem> parseStream(InputStream in, String baseSource, String selfSource) throws IOException {
        List<ChannelItem> list = new ArrayList<ChannelItem>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 4096);

        String line;
        String pendingTitle = null;
        boolean isMasterHlsVariant = false;
        boolean isHlsMedia = false;
        boolean sawFirstLine = false;
        int index = 1;

        while ((line = reader.readLine()) != null) {
            if (list.size() >= MAX_ENTRIES) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("\uFEFF")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (!sawFirstLine) {
                sawFirstLine = true;
                if (trimmed.startsWith("<")) {
                    throw new IOException("Not a playlist (HTML page)");   // caller falls back to direct play
                }
                if (trimmed.equalsIgnoreCase("[playlist]")) {
                    return parsePls(reader, baseSource);
                }
            }

            if (trimmed.startsWith("#EXTINF")) {
                int lastQuote = trimmed.lastIndexOf('"');
                int comma = trimmed.indexOf(',', lastQuote >= 0 ? lastQuote : 0);
                if (comma != -1 && comma + 1 < trimmed.length()) {
                    pendingTitle = trimmed.substring(comma + 1).trim();
                }
            } else if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                isMasterHlsVariant = true;
                String res = extractAttribute(trimmed, "RESOLUTION=");
                pendingTitle = res != null ? ("HLS " + res) : ("HLS Stream " + index);
            } else if (trimmed.startsWith("#EXT-X-TARGETDURATION") || trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                isHlsMedia = true;   // one HLS stream (segments), not a list of channels
                break;
            } else if (trimmed.startsWith("#")) {
                // ignore other tags (#EXTM3U, #EXTVLCOPT, #EXT-X-TARGETDURATION, etc.)
            } else {
                String resolved = resolveUri(trimmed, baseSource);
                String title = (pendingTitle != null && !pendingTitle.isEmpty())
                        ? pendingTitle
                        : ("Channel " + index);
                list.add(new ChannelItem(title, resolved));
                index++;
                pendingTitle = null;
            }
        }

        if (isHlsMedia && selfSource != null) {
            List<ChannelItem> one = new ArrayList<ChannelItem>(1);
            one.add(new ChannelItem("HLS stream", selfSource));
            return one;
        }

        // If this was an HLS variant master playlist, prefer 720p/1080p over 4K for Cortex-A9
        if (isMasterHlsVariant && list.size() > 1) {
            ChannelItem best = selectOptimalHlsVariant(list);
            List<ChannelItem> single = new ArrayList<ChannelItem>(1);
            single.add(best);
            return single;
        }

        return list;
    }

    private static ChannelItem selectOptimalHlsVariant(List<ChannelItem> variants) {
        for (ChannelItem item : variants) {
            if (item.title.contains("1280x720") || item.title.contains("x720")) {
                return item;
            }
        }
        for (ChannelItem item : variants) {
            if (item.title.contains("1920x1080") || item.title.contains("x1080")) {
                return item;
            }
        }
        return variants.get(0);
    }

    /** PLS: File1=..., Title1=... (titles may come after the files, so read everything first). */
    private static List<ChannelItem> parsePls(BufferedReader reader, String baseSource) throws IOException {
        java.util.TreeMap<Integer, String> files = new java.util.TreeMap<Integer, String>();
        java.util.HashMap<Integer, String> titles = new java.util.HashMap<Integer, String>();
        String line;
        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).toLowerCase(Locale.US);
            String v = t.substring(eq + 1).trim();
            try {
                if (k.startsWith("file")) files.put(Integer.valueOf(k.substring(4)), v);
                else if (k.startsWith("title")) titles.put(Integer.valueOf(k.substring(5)), v);
            } catch (NumberFormatException ignored) {
            }
        }
        List<ChannelItem> list = new ArrayList<ChannelItem>();
        for (java.util.Map.Entry<Integer, String> e : files.entrySet()) {
            if (list.size() >= MAX_ENTRIES) break;
            String title = titles.get(e.getKey());
            if (title == null || title.length() == 0) title = "Channel " + e.getKey();
            list.add(new ChannelItem(title, resolveUri(e.getValue(), baseSource)));
        }
        return list;
    }

    private static String extractAttribute(String line, String key) {
        int idx = line.indexOf(key);
        if (idx == -1) return null;
        int start = idx + key.length();
        int end = line.indexOf(',', start);
        return (end == -1 ? line.substring(start) : line.substring(start, end)).trim();
    }

    private static String resolveUri(String entry, String baseSource) {
        entry = entry.replace('\\', '/');
        if (entry.contains("://")) return entry;
        if (baseSource == null) return entry;
        if (baseSource.startsWith("http://") || baseSource.startsWith("https://")) {
            try {
                return new URL(new URL(baseSource), entry.replace(" ", "%20")).toString();
            } catch (Exception e) {
                return entry;
            }
        }
        if (entry.startsWith("/")) return entry;
        return new File(baseSource, entry).getAbsolutePath();
    }

    /**
     * Optional, OFF by default: accept any HTTPS certificate when downloading a playlist.
     * Needed on old Android (4.3) whose root-certificate store lacks modern CAs; less secure.
     */
    private static volatile boolean lenientTls = false;

    public static void setLenientTls(boolean value) {
        lenientTls = value;
    }

    /** Extra trusted roots (device store + bundled CAs); see TrustBundle. */
    private static volatile X509TrustManager extraTrust;

    public static void setExtraTrust(X509TrustManager tm) {
        extraTrust = tm;
    }

    public static boolean hasExtraTrust() {
        return extraTrust != null;
    }

    private static void enableTls12(HttpsURLConnection conn) {
        try {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            if (lenientTls) {
                sc.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, null);
                conn.setHostnameVerifier(new HostnameVerifier() {
                    @Override public boolean verify(String hostname, SSLSession session) { return true; }
                });
            } else {
                X509TrustManager extra = extraTrust;
                sc.init(null, extra != null ? new TrustManager[]{extra} : null, null);
            }
            conn.setSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
        } catch (Exception ignored) {
        }
    }

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.1", "TLSv1.2"});
            }
            return s;
        }
    }
}
