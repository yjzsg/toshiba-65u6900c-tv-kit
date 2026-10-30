package com.local.livetv;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 解析 IPTV 服务端的 XMLTV（/playback.xml）。它的 channel id 就是频道名，直接对得上。 */
public class Epg {

    public static class Prog {
        public final long start;
        public final long stop;
        public final String title;

        Prog(long start, long stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }

    private final Map<String, List<Prog>> byChannel = new HashMap<String, List<Prog>>();

    public static Epg parse(InputStream in) throws Exception {
        Epg epg = new Epg();
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(in, "UTF-8");

        String channel = null;
        long start = 0;
        long stop = 0;
        String title = null;

        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("programme".equals(name)) {
                    channel = parser.getAttributeValue(null, "channel");
                    start = parseTime(parser.getAttributeValue(null, "start"));
                    stop = parseTime(parser.getAttributeValue(null, "stop"));
                    title = null;
                } else if ("title".equals(name) && channel != null) {
                    title = parser.nextText();
                }
            } else if (event == XmlPullParser.END_TAG && "programme".equals(parser.getName())) {
                if (channel != null && title != null && stop > start) {
                    List<Prog> list = epg.byChannel.get(channel);
                    if (list == null) {
                        list = new ArrayList<Prog>();
                        epg.byChannel.put(channel, list);
                    }
                    list.add(new Prog(start, stop, title));
                }
                channel = null;
            }
        }
        return epg;
    }

    private static long parseTime(String raw) {
        if (raw == null || raw.length() < 14) return 0;
        try {
            return new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(raw).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 当前正在播的节目名，没有就返回 null。 */
    public String current(String channel, long now) {
        List<Prog> list = byChannel.get(channel);
        if (list == null) return null;
        for (Prog p : list) {
            if (p.start <= now && now < p.stop) return p.title;
        }
        return null;
    }

    public int size() {
        return byChannel.size();
    }
}
