package com.local.livetv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 频道表解析与拉取。
 *
 * 支持两种格式：
 *  1) IPTV 服务端 TXT 订阅： 分组,#genre#  然后  频道名,url
 *  2) 旧版内置表：           频道名|url1|url2
 */
public class Playlist {

    public static class Channel {
        public String name;
        public String group;
        public final List<String> urls = new ArrayList<String>();

        Channel(String name, String group) {
            this.name = name;
            this.group = group;
        }
    }

    /** 解析频道表文本。同名频道合并为多源，保留首次出现的分组。 */
    public static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<Channel>();
        if (text == null) return out;
        LinkedHashMap<String, Channel> byName = new LinkedHashMap<String, Channel>();
        String group = "";
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            if (line.endsWith(",#genre#")) {
                group = line.substring(0, line.length() - 8).trim();
                continue;
            }
            String name;
            List<String> urls = new ArrayList<String>();
            if (line.indexOf('|') > 0) {
                String[] parts = line.split("\\|");
                name = parts[0].trim();
                for (int i = 1; i < parts.length; i++) {
                    String u = parts[i].trim();
                    if (u.length() > 0) urls.add(u);
                }
            } else {
                int i = line.indexOf(',');
                if (i <= 0) continue;
                name = line.substring(0, i).trim();
                String u = line.substring(i + 1).trim();
                if (u.length() > 0) urls.add(u);
            }
            if (name.length() == 0 || urls.isEmpty()) continue;
            // 咪咕把 CCTV5 / CCTV5+ / CCTV16 放在「体育」分组，看电视时希望在央视列表里连着排
            String grp = group;
            if (grp != null && grp.contains("体育") && name.startsWith("CCTV")) grp = "央视";
            Channel c = byName.get(name);
            if (c == null) {
                c = new Channel(name, grp);
                byName.put(name, c);
                out.add(c);
            }
            for (String u : urls) {
                if (!c.urls.contains(u)) c.urls.add(u);
            }
        }
        return out;
    }

    /** 老电视播不了的明显类型先剔掉（FLV/MP4/RTMP），其余交给播放器重试机制。 */
    public static boolean looksPlayable(String url) {
        String u = url.toLowerCase();
        if (u.startsWith("rtmp") || u.startsWith("rtsp")) return false;
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        if (path.endsWith(".flv") || path.endsWith(".mp4") || path.endsWith(".mp3")
                || path.endsWith(".m4a") || path.endsWith(".jpg") || path.endsWith(".png")) return false;
        return true;
    }

    /** 按分组白名单过滤并重排；白名单为空表示全部保留。 */
    public static List<Channel> filter(List<Channel> all, String allowedGroups) {
        if (allowedGroups == null || allowedGroups.trim().length() == 0) {
            List<Channel> out = new ArrayList<Channel>();
            for (Channel c : all) {
                if (hasPlayable(c)) out.add(c);
            }
            return out;
        }
        final String[] order = allowedGroups.split("[,，]");
        List<Channel> out = new ArrayList<Channel>();
        for (String g : order) {
            String want = g.trim();
            if (want.length() == 0) continue;
            for (Channel c : all) {
                if (want.equals(c.group) && hasPlayable(c) && !out.contains(c)) out.add(c);
            }
        }
        // 同组内 CCTV 按台号排，CCTV5 才不会跑到 CCTV17 后面
        java.util.Collections.sort(out, new java.util.Comparator<Channel>() {
            @Override
            public int compare(Channel a, Channel b) {
                int ga = groupOrder(order, a.group);
                int gb = groupOrder(order, b.group);
                if (ga != gb) return ga - gb;
                int na = cctvNumber(a.name);
                int nb = cctvNumber(b.name);
                if (na >= 0 && nb >= 0) return na - nb;
                if (na >= 0) return -1;
                if (nb >= 0) return 1;
                return 0;
            }
        });
        return out;
    }

    private static int groupOrder(String[] order, String group) {
        for (int i = 0; i < order.length; i++) {
            if (order[i].trim().equals(group)) return i;
        }
        return order.length;
    }

    /** CCTV-1 / CCTV1综合 / CCTV5+体育赛事 -> 1、10、55；非 CCTV 返回 -1。 */
    private static int cctvNumber(String name) {
        if (name == null || !name.startsWith("CCTV")) return -1;
        int i = 4;
        if (i < name.length() && (name.charAt(i) == '-' || name.charAt(i) == ' ')) i++;
        int start = i;
        while (i < name.length() && Character.isDigit(name.charAt(i))) i++;
        if (i == start) return -1;
        int num = Integer.parseInt(name.substring(start, i));
        if (i < name.length() && name.charAt(i) == '+') return num * 10 + 5;
        return num * 10;
    }

    private static boolean hasPlayable(Channel c) {
        for (String u : c.urls) {
            if (looksPlayable(u)) return true;
        }
        return false;
    }

    /** 简单 HTTP GET，返回 UTF-8 文本；失败抛异常。 */
    public static String get(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "livetv/2.0 (Android)");
            conn.setRequestProperty("Accept", "*/*");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
