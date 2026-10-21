package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Current conditions plus today / tomorrow / the day after, from Open-Meteo: no key, no Play services,
 * plain HTTPS. The last answer is cached in SharedPreferences, so Home draws it at once and keeps showing
 * it while the network (or the LAN's proxy) is down.
 */
final class Weather {

    interface Sink {
        void onWeather(Data data);
    }

    /** Three days of forecast, index 0 being today. */
    static final class Data {
        final String city;
        final int temp;
        final int code;
        /** 1 while the sun is up, 0 after dark: the clear and partly cloudy icons follow it. */
        final int day;
        final long at;
        final int[] max = new int[DAYS];
        final int[] min = new int[DAYS];
        final int[] dcode = new int[DAYS];
        final String[] dates = new String[DAYS];

        Data(String city, int temp, int code, int day, long at) {
            this.city = city;
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.at = at;
        }
    }

    static final int DAYS = 3;
    /** How many forecast columns the launcher shows: tomorrow and the day after, today is the big block. */
    static final int SHOWN = 3;

    private static final String PREFS = "weather";
    private static final long TTL_MS = 20 * 60 * 1000L;
    /** Where Home reads the city from: adb shell settings put global tvhome_city 杭州 */
    private static final String CITY_KEY = "tvhome_city";
    private static final String FALLBACK_CITY = "上海";
    private static final double FALLBACK_LAT = 31.22222;
    private static final double FALLBACK_LON = 121.45806;

    private static volatile boolean running;
    private static SSLSocketFactory tls;

    private Weather() {
    }

    /** Draws the cached reading straight away, then refreshes it when it is older than TTL_MS. */
    static void load(Context ctx, Sink sink) {
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (tls == null) tls = tlsFactory(ctx.getApplicationContext());
        Data cached = read(prefs);
        if (cached != null) sink.onWeather(cached);
        if (cached != null && System.currentTimeMillis() - cached.at < TTL_MS) return;
        refresh(ctx, sink);
    }

    static void refresh(final Context ctx, final Sink sink) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Data data = null;
                try {
                    data = fetch(app);
                } catch (Throwable t) {
                    // Offline or no route: the cached reading stays on screen.
                    Log.w("Weather", "fetch failed: " + t);
                }
                running = false;
                if (data == null) return;
                write(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), data);
                final Data out = data;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        sink.onWeather(out);
                    }
                });
            }
        }, "weather").start();
    }

    /** WMO weather code to the short Chinese label the panel shows. */
    static String describe(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: return "晴间多云";
            case 2: return "多云";
            case 3: return "阴";
            case 45:
            case 48: return "雾";
            case 51:
            case 53:
            case 55: return "毛毛雨";
            case 56:
            case 57: return "冻雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66:
            case 67: return "冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "雪粒";
            case 80:
            case 81: return "阵雨";
            case 82: return "暴雨";
            case 85:
            case 86: return "阵雪";
            case 95: return "雷阵雨";
            case 96:
            case 99: return "雷暴冰雹";
            default: return "未知";
        }
    }

    /** The drawable that stands for a WMO weather code. */
    static int icon(int code) {
        switch (code) {
            case 0: return R.drawable.ic_wx_sun;
            case 1:
            case 2: return R.drawable.ic_wx_partly;
            case 3: return R.drawable.ic_wx_cloud;
            case 45:
            case 48: return R.drawable.ic_wx_fog;
            case 51:
            case 53:
            case 55:
            case 56:
            case 57: return R.drawable.ic_wx_drizzle;
            case 61:
            case 63:
            case 65:
            case 66:
            case 67:
            case 80:
            case 81:
            case 82: return R.drawable.ic_wx_rain;
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86: return R.drawable.ic_wx_snow;
            case 95:
            case 96:
            case 99: return R.drawable.ic_wx_thunder;
            default: return R.drawable.ic_wx_cloud;
        }
    }

    /**
     * The same code, but after dark a clear sky is a moon and a mostly clear one is a moon behind a
     * cloud. Everything else — rain, snow, fog, thunder — looks the same day or night.
     */
    static int icon(int code, boolean night) {
        if (night) {
            if (code == 0) return R.drawable.ic_wx_clear_night;
            if (code == 1 || code == 2) return R.drawable.ic_wx_partly_night;
        }
        return icon(code);
    }

    /** 今天 / 明天 / 后天, for the three forecast columns. */
    static String dayName(int index) {
        switch (index) {
            case 0: return "今天";
            case 1: return "明天";
            case 2: return "后天";
            default: return "";
        }
    }

    static String cityName(Context ctx) {
        try {
            String city = Settings.Global.getString(ctx.getContentResolver(), CITY_KEY);
            if (city != null && city.trim().length() > 0) return city.trim();
        } catch (Throwable ignored) {
            // Older builds lack the key; the fallback city covers it.
        }
        return FALLBACK_CITY;
    }

    private static Data fetch(Context ctx) throws Exception {
        String city = cityName(ctx);
        double lat = FALLBACK_LAT;
        double lon = FALLBACK_LON;
        String label = city;
        String geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name="
                + URLEncoder.encode(city, "UTF-8"));
        if (geo != null) {
            JSONObject root = new JSONObject(geo);
            JSONArray results = root.optJSONArray("results");
            if (results != null && results.length() > 0) {
                JSONObject first = results.getJSONObject(0);
                lat = first.optDouble("latitude", lat);
                lon = first.optDouble("longitude", lon);
                String name = first.optString("name", city);
                if (name != null && name.length() > 0) label = name;
            }
        }
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=" + DAYS;
        String body = get(url);
        if (body == null) return null;
        JSONObject root = new JSONObject(body);
        JSONObject current = root.optJSONObject("current");
        if (current == null) return null;
        Data data = new Data(label,
                (int) Math.round(current.optDouble("temperature_2m", 0)),
                current.optInt("weather_code", -1),
                current.optInt("is_day", 1),
                System.currentTimeMillis());
        JSONObject daily = root.optJSONObject("daily");
        JSONArray codes = daily == null ? null : daily.optJSONArray("weather_code");
        JSONArray maxs = daily == null ? null : daily.optJSONArray("temperature_2m_max");
        JSONArray mins = daily == null ? null : daily.optJSONArray("temperature_2m_min");
        JSONArray dates = daily == null ? null : daily.optJSONArray("time");
        for (int i = 0; i < DAYS; i++) {
            data.max[i] = maxs == null ? data.temp : (int) Math.round(maxs.optDouble(i, data.temp));
            data.min[i] = mins == null ? data.temp : (int) Math.round(mins.optDouble(i, data.temp));
            data.dcode[i] = codes == null ? data.code : codes.optInt(i, data.code);
            data.dates[i] = dates == null ? "" : dates.optString(i, "");
        }
        return data;
    }

    private static String get(String url) {
        String body = request(url);
        // Some boxes' firmware ships a TLS stack the API's certificate chain does not suit.
        if (body == null && url.startsWith("https://")) body = request("http://" + url.substring(8));
        return body;
    }

    private static String request(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            if (conn instanceof HttpsURLConnection && tls != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(tls);
            }
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "TvHome");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            Log.w("Weather", "request failed: " + url + " " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Data read(SharedPreferences prefs) {
        long at = prefs.getLong("at", 0);
        if (at == 0) return null;
        Data data = new Data(prefs.getString("city", ""), prefs.getInt("temp", 0), prefs.getInt("code", -1),
                prefs.getInt("day", 1), at);
        String days = prefs.getString("days", "");
        if (days == null || days.length() == 0) return null;
        String[] rows = days.split(";");
        for (int i = 0; i < DAYS && i < rows.length; i++) {
            String[] parts = rows[i].split(",");
            if (parts.length < 4) continue;
            data.dates[i] = parts[0];
            data.dcode[i] = parseInt(parts[1], data.code);
            data.max[i] = parseInt(parts[2], data.temp);
            data.min[i] = parseInt(parts[3], data.temp);
        }
        return data;
    }

    private static void write(SharedPreferences prefs, Data data) {
        StringBuilder days = new StringBuilder();
        for (int i = 0; i < DAYS; i++) {
            if (i > 0) days.append(';');
            days.append(data.dates[i]).append(',').append(data.dcode[i]).append(',')
                    .append(data.max[i]).append(',').append(data.min[i]);
        }
        prefs.edit()
                .putString("city", data.city)
                .putInt("temp", data.temp)
                .putInt("code", data.code)
                .putInt("day", data.day)
                .putString("days", days.toString())
                .putLong("at", data.at)
                .apply();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /**
     * Android 6's trust store predates Let's Encrypt, and every free weather API sits behind it. Accept
     * either the system CAs or the bundled ISRG root, so the fetch works on this box and stays verified.
     */
    private static SSLSocketFactory tlsFactory(Context ctx) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            InputStream in = ctx.getResources().openRawResource(R.raw.isrg_root_x1);
            Certificate root = cf.generateCertificate(in);
            in.close();
            extra.setCertificateEntry("isrg-root-x1", root);
            TrustManagerFactory extraTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            extraTmf.init(extra);
            final X509TrustManager extraTm = (X509TrustManager) extraTmf.getTrustManagers()[0];

            TrustManagerFactory sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sysTmf.init((KeyStore) null);
            final X509TrustManager sysTm = (X509TrustManager) sysTmf.getTrustManagers()[0];

            X509TrustManager both = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    sysTm.checkClientTrusted(chain, auth);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        sysTm.checkServerTrusted(chain, auth);
                    } catch (CertificateException ignored) {
                        extraTm.checkServerTrusted(chain, auth);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return sysTm.getAcceptedIssuers();
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{both}, null);
            return context.getSocketFactory();
        } catch (Throwable t) {
            Log.w("Weather", "tls setup failed: " + t);
            return null;
        }
    }
}
