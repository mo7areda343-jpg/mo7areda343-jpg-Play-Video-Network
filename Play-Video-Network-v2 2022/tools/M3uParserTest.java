package com.example.mediahub;
import java.io.*; import java.util.*;
public class T {
    static int fail = 0;
    static void eq(String name, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  got=" + got + "  want=" + want));
    }
    static List<M3uPlaylistParser.ChannelItem> p(String text, String base, String self) throws IOException {
        return M3uPlaylistParser.parseStream(new ByteArrayInputStream(text.getBytes("UTF-8")), base, self);
    }
    public static void main(String[] a) throws Exception {
        // isM3uPathOrUrl
        eq("direct mp4 url is NOT a playlist", M3uPlaylistParser.isM3uPathOrUrl("http://h/v.mp4"), false);
        eq("live .ts url is NOT a playlist", M3uPlaylistParser.isM3uPathOrUrl("http://h:8080/live/1.ts"), false);
        eq("m3u8 url", M3uPlaylistParser.isM3uPathOrUrl("http://h/a.m3u8"), true);
        eq("iptv get.php type=m3u_plus", M3uPlaylistParser.isM3uPathOrUrl("http://h/get.php?username=x&password=y&type=m3u_plus"), true);
        eq("local .M3U uppercase", M3uPlaylistParser.isM3uPathOrUrl("/mnt/usb/List.M3U"), true);
        eq("local mkv", M3uPlaylistParser.isM3uPathOrUrl("/mnt/usb/a.mkv"), false);
        eq("pls url", M3uPlaylistParser.isM3uPathOrUrl("https://h/radio.pls"), true);

        // IPTV titles with commas and BOM
        String iptv = "\uFEFF#EXTM3U\n#EXTINF:-1 tvg-id=\"x\" group-title=\"News, Sports\",Name, HD\nhttp://s/1.ts\n#EXTINF:-1,Plain\nhttp://s/2.ts\nhttp://s/3.ts\n";
        List<M3uPlaylistParser.ChannelItem> l = p(iptv, "http://h/p/list.m3u", "http://h/p/list.m3u");
        eq("iptv count", l.size(), 3);
        eq("title keeps comma", l.get(0).title, "Name, HD");
        eq("plain title", l.get(1).title, "Plain");
        eq("fallback title", l.get(2).title, "Channel 3");

        // relative resolution (http base)
        l = p("#EXTM3U\nchan1.m3u8\n/live/2\nhttp://other/x\nsub dir/a b.ts\n", "http://h/p/list.m3u", "http://h/p/list.m3u");
        eq("relative http", l.get(0).url, "http://h/p/chan1.m3u8");
        eq("host-absolute http", l.get(1).url, "http://h/live/2");
        eq("absolute kept", l.get(2).url, "http://other/x");
        eq("spaces encoded", l.get(3).url, "http://h/p/sub%20dir/a%20b.ts");

        // local relative + backslashes (Windows-made playlists)
        l = p("#EXTM3U\nMovies\\a.mp4\n/abs/b.mp4\n", "/mnt/usb/lists", "/mnt/usb/lists/x.m3u");
        eq("local relative", l.get(0).url, "/mnt/usb/lists/Movies/a.mp4");
        eq("local absolute", l.get(1).url, "/abs/b.mp4");

        // PLS (titles after files)
        l = p("[playlist]\nNumberOfEntries=2\nFile1=http://r/1\nTitle1=Radio One\nLength1=-1\nFile2=http://r/2\nTitle2=Radio Two\nVersion=2\n", "http://h/r.pls", "http://h/r.pls");
        eq("pls count", l.size(), 2);
        eq("pls title1", l.get(0).title, "Radio One");
        eq("pls url2", l.get(1).url, "http://r/2");

        // HLS media playlist => ONE item, the playlist itself
        String media = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:10.0,\nseg5.ts\n#EXTINF:10.0,\nseg6.ts\n";
        l = p(media, "http://h/live", "http://h/live/stream.m3u8");
        eq("hls media -> single", l.size(), 1);
        eq("hls media url is the playlist", l.get(0).url, "http://h/live/stream.m3u8");

        // HLS master => ONE variant, 720p preferred, resolved
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160\nuhd/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\nhd/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\nsd/index.m3u8\n";
        l = p(master, "http://h/m/master.m3u8", "http://h/m/master.m3u8");
        eq("master -> single", l.size(), 1);
        eq("master picks 720p", l.get(0).url, "http://h/m/hd/index.m3u8");

        // HTML error page must not become a playlist
        boolean threw = false;
        try { p("<html><body>403</body></html>", "http://h/x.m3u", "http://h/x.m3u"); } catch (IOException e) { threw = true; }
        eq("html page rejected", threw, true);

        // cap
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 6000; i++) sb.append("#EXTINF:-1,C").append(i).append("\nhttp://s/").append(i).append("\n");
        eq("cap 5000", p(sb.toString(), "http://h/b.m3u", "http://h/b.m3u").size(), 5000);

        System.out.println(fail == 0 ? "ALL TESTS PASSED" : (fail + " TEST(S) FAILED"));
    }
}
