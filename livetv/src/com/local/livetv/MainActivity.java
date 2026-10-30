package com.local.livetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视直播 2.0
 * 频道表来自 NAS 上的 iPTV 服务（TXT 订阅），本地缓存兜底，失效自动切下一个源。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "livetv";
    private static final String KEY_SUB = "sub_url";
    private static final String KEY_GROUPS = "groups";
    private static final String DEFAULT_SUB = "http://192.168.123.6:1905/txt";
    private static final String DEFAULT_GROUPS =
            "央视,卫视,体育,香港,台湾,澳门,国际,影视,少儿,教育,文旅,百视通,宠物";

    private static final int WATCHDOG = 1;
    private static final long PLAY_DEBOUNCE = 700;
    private static final int START_TIMEOUT = 16000;
    private static final int STALL_TIMEOUT = 16000;

    private VideoView video;
    private ListView listView;
    private TextView header;
    private TextView overlay;
    private ChannelAdapter adapter;

    private final List<Playlist.Channel> channels = new ArrayList<Playlist.Channel>();

    private int curIndex = -1;
    private int curUrl = 0;
    private boolean prepared;
    private int lastPos = -1;
    private int stalledMs;
    private int startWaitMs;
    private String playingName = "";

    private String subUrl;
    private String groups;

    private final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != WATCHDOG) return;
            if (curIndex < 0) return;
            if (!prepared) {
                startWaitMs += 3000;
                if (startWaitMs >= START_TIMEOUT) {
                    switchSource("起播超时");
                } else {
                    sendEmptyMessageDelayed(WATCHDOG, 3000);
                }
                return;
            }
            int pos = 0;
            try {
                pos = video.getCurrentPosition();
            } catch (Exception ignored) {
            }
            if (pos == lastPos) {
                stalledMs += 3000;
                if (stalledMs >= STALL_TIMEOUT) {
                    switchSource("卡流");
                } else {
                    sendEmptyMessageDelayed(WATCHDOG, 3000);
                }
            } else {
                lastPos = pos;
                stalledMs = 0;
                sendEmptyMessageDelayed(WATCHDOG, 3000);
            }
        }
    };

    private final java.util.Set<String> lowResMoved = new java.util.HashSet<String>();
    private final java.util.Set<String> favs = new java.util.HashSet<String>();
    private volatile Epg epg;
    private final Runnable refreshEpgRows = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) adapter.notifyDataSetChanged();
            handler.postDelayed(this, 300000);
        }
    };


    /** \u5148\u7528\u672c\u5730\u7f13\u5b58\uff0c\u518d\u540e\u53f0\u62c9\u6700\u65b0\u7684 /playback.xml\uff08XMLTV\uff09\u3002 */
    private void loadEpg() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.io.File cache = new java.io.File(getFilesDir(), "epg.xml");
                if (cache.exists()) {
                    try {
                        java.io.InputStream in = new java.io.FileInputStream(cache);
                        epg = Epg.parse(in);
                        in.close();
                        runOnUiThread(refreshEpgRows);
                    } catch (Exception ignored) {
                    }
                }
                String base = subUrl.substring(0, subUrl.lastIndexOf('/'));
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(base + "/playback.xml").openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    if (conn.getResponseCode() != 200) return;
                    java.io.File tmp = new java.io.File(getFilesDir(), "epg.xml.tmp");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                    java.io.InputStream in = conn.getInputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    if (tmp.length() > 1024) {
                        cache.delete();
                        tmp.renameTo(cache);
                        java.io.InputStream in2 = new java.io.FileInputStream(cache);
                        epg = Epg.parse(in2);
                        in2.close();
                        runOnUiThread(refreshEpgRows);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "epg").start();
    }

    private void loadFavs() {
        favs.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("favs", "");
        for (String n : raw.split("\n")) {
            if (n.trim().length() > 0) favs.add(n.trim());
        }
    }

    private void saveFavs() {
        StringBuilder sb = new StringBuilder();
        for (String n : favs) sb.append(n).append('\n');
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("favs", sb.toString()).apply();
    }

    /** 收藏的台排最前，其余保持原顺序 */
    private java.util.List<Playlist.Channel> withFavs(java.util.List<Playlist.Channel> list) {
        if (favs.isEmpty()) return list;
        java.util.List<Playlist.Channel> out = new java.util.ArrayList<Playlist.Channel>();
        for (Playlist.Channel c : list) if (favs.contains(c.name)) out.add(c);
        for (Playlist.Channel c : list) if (!favs.contains(c.name)) out.add(c);
        return out;
    }

    private void toggleFav() {
        if (curIndex < 0 || curIndex >= channels.size()) return;
        String name = channels.get(curIndex).name;
        boolean on;
        if (favs.contains(name)) {
            favs.remove(name);
            on = false;
        } else {
            favs.add(name);
            on = true;
        }
        saveFavs();
        toast((on ? "\u5df2\u6536\u85cf\uff1a" : "\u5df2\u53d6\u6d88\u6536\u85cf\uff1a") + name);
        applyChannels(withFavs(new java.util.ArrayList<Playlist.Channel>(channels)), "\u6536\u85cf");
    }

    /** url -> 宽度，记住上次探到的分辨率，下次同一个源就不用先起播再跳了 */
    private final java.util.Map<String, Integer> resCache = new java.util.HashMap<String, Integer>();

    private void loadResCache() {
        java.io.File f = new java.io.File(getFilesDir(), "res_cache.txt");
        String text = readFile(f);
        if (text == null) return;
        for (String line : text.split("\r?\n")) {
            int i = line.lastIndexOf('\t');
            if (i <= 0) continue;
            try {
                resCache.put(line.substring(0, i), Integer.parseInt(line.substring(i + 1).trim()));
            } catch (Exception ignored) {
            }
        }
    }

    private void saveResCache() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : resCache.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        writeFile(new java.io.File(getFilesDir(), "res_cache.txt"), sb.toString());
    }

    private TextView watermark;
    private final Runnable hideWatermark = new Runnable() {
        @Override
        public void run() {
            if (watermark != null) watermark.setVisibility(View.GONE);
        }
    };

    private final StringBuilder numBuf = new StringBuilder();
    private final Runnable numCommit = new Runnable() {
        @Override
        public void run() {
            if (numBuf.length() == 0) return;
            int n;
            try {
                n = Integer.parseInt(numBuf.toString());
            } catch (Exception e) {
                n = 0;
            }
            numBuf.setLength(0);
            hideOverlay();
            if (n >= 1 && n <= channels.size()) {
                playChannel(n - 1);
            } else {
                toast("没有第 " + n + " 台");
            }
        }
    };


    private LinearLayout leftPanel;
    private boolean listVisible = true;

    /** 播放/按键 6 秒后把左侧玻璃列表滑出去，按方向键再滑回来。 */
    private final Runnable hideList = new Runnable() {
        @Override
        public void run() {
            if (leftPanel == null || !listVisible) return;
            listVisible = false;
            android.util.Log.i("livetv", "hide list w=" + leftPanel.getWidth());
            leftPanel.animate().translationX(-dp(300)).alpha(0f)
                    .setDuration(280).start();
        }
    };

    private final Runnable playSelected = new Runnable() {
        @Override
        public void run() {
            int pos = listView.getSelectedItemPosition();
            if (pos >= 0) playChannel(pos);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        loadResCache();
        loadFavs();
        loadEpg();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        subUrl = sp.getString(KEY_SUB, DEFAULT_SUB);
        groups = sp.getString(KEY_GROUPS, DEFAULT_GROUPS);

        buildUi();

        // 先用缓存/内置表把界面撑起来，再后台刷新订阅
        String cached = readFile(cacheFile());
        if (cached == null || cached.trim().length() == 0) cached = readAsset("channels.txt");
        if (cached != null) {
            applyChannels(Playlist.filter(Playlist.parse(cached), groups), "缓存");
            String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_channel", "");
            int startIdx = 0;
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).name.equals(last)) {
                    startIdx = i;
                    break;
                }
            }
            playChannel(startIdx);
        }
        refresh(false);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private File cacheFile() {
        return new File(getFilesDir(), "playlist.txt");
    }

    // ---------------- UI ----------------

    private void buildUi() {
        // 视频整屏（16:9 正好铺满 1920x1080），左侧频道列表做成半透明玻璃盖在上面，
        // 不再把画面挤到右边——那样会留上下黑边。
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(Color.parseColor("#B3000000"));
        leftPanel = left;

        header = new TextView(this);
        header.setTextColor(Color.parseColor("#E6FFFFFF"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.setText("电视直播 · 正在加载…");
        left.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listView = new ListView(this);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setSelector(android.R.color.transparent);
        adapter = new ChannelAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                playChannel(position);
            }
        });
        left.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.MATCH_PARENT);
        llp.gravity = Gravity.START;
        root.addView(left, llp);

        overlay = new TextView(this);
        overlay.setTextColor(Color.parseColor("#EEEEEE"));
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.parseColor("#66000000"));
        overlay.setPadding(dp(16), dp(10), dp(16), dp(10));
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.gravity = Gravity.BOTTOM | Gravity.END;
        olp.setMargins(0, 0, dp(16), dp(16));
        root.addView(overlay, olp);

        watermark = new TextView(this);
        watermark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        watermark.setTextColor(Color.WHITE);
        watermark.setBackgroundColor(Color.parseColor("#99000000"));
        watermark.setPadding(dp(14), dp(7), dp(14), dp(7));
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.TOP | Gravity.END;
        wlp.setMargins(0, dp(18), dp(18), 0);
        root.addView(watermark, wlp);
        watermark.setVisibility(View.GONE);

        setContentView(root);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                // 同台有更高分辨率的源时先跳过 720p 以下的（咪咕 H.264 只有 540p）
                int vw = mp.getVideoWidth();
                int vh = mp.getVideoHeight();
                if (vw > 0 && curIndex >= 0 && curIndex < channels.size()) {
                    String here = channels.get(curIndex).urls.get(curUrl);
                    if (!Integer.valueOf(vw).equals(resCache.get(here))) {
                        resCache.put(here, vw);
                        saveResCache();
                    }
                }
                if (vw > 0 && vw < 1280 && curIndex >= 0 && curIndex < channels.size()) {
                    final Playlist.Channel ch = channels.get(curIndex);
                    final String curUrlStr = ch.urls.get(curUrl);
                    if (ch.urls.size() > 1 && !lowResMoved.contains(curUrlStr)) {
                        // 低清源先挪到队尾，让高清源先试；全部失败时它还在最后兜底
                        lowResMoved.add(curUrlStr);
                        android.util.Log.i("livetv", "move low-res to back " + vw + "x" + vh);
                        ch.urls.remove(curUrl);
                        ch.urls.add(curUrlStr);
                        if (curUrl >= ch.urls.size()) curUrl = 0;
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startCurrent();
                            }
                        }, 80);
                        return;
                    }
                }
                prepared = true;
                stalledMs = 0;
                lastPos = -1;
                video.start();
                hideOverlay();
                updateHeader();
                if (watermark != null) {
                    String tag = (curIndex >= 0 && curIndex < channels.size()
                            ? channels.get(curIndex).name : "") + "  " + vw + "×" + vh;
                    watermark.setText(tag);
                    watermark.setVisibility(View.VISIBLE);
                    handler.removeCallbacks(hideWatermark);
                    handler.postDelayed(hideWatermark, 3000);
                }
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                switchSource("播放错误 " + what);
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                switchSource("流结束");
            }
        });

        listView.requestFocus();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private class ChannelAdapter extends BaseAdapter {
        private int selected = -1;

        void setSelected(int i) {
            selected = i;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return channels.size();
        }

        @Override
        public Object getItem(int position) {
            return channels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView name;
            TextView group;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(7), dp(8), dp(7));

                name = new TextView(MainActivity.this);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                group = new TextView(MainActivity.this);
                group.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                group.setPadding(dp(6), 0, 0, 0);
                group.setSingleLine(true);
                group.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(group, new LinearLayout.LayoutParams(
                        dp(170), ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setTag(new View[]{name, group});
            } else {
                row = (LinearLayout) convertView;
                View[] tags = (View[]) row.getTag();
                name = (TextView) tags[0];
                group = (TextView) tags[1];
            }
            Playlist.Channel c = channels.get(position);
            name.setText(c.name);
            String right = null;
            Epg e = epg;
            if (e != null) {
                try {
                    right = e.current(c.name, System.currentTimeMillis());
                } catch (Exception ignored) {
                }
            }
            if (right == null || right.length() == 0) {
                right = c.group == null ? "" : c.group;
            }
            group.setText(right);
            if (position == selected) {
                row.setBackgroundColor(Color.parseColor("#CC1E6FD9"));
                name.setTextColor(Color.WHITE);
                group.setTextColor(Color.parseColor("#DCE8FF"));
            } else {
                row.setBackgroundColor(Color.TRANSPARENT);
                name.setTextColor(Color.parseColor("#EEEEEE"));
                group.setTextColor(Color.parseColor("#777777"));
            }
            return row;
        }
    }

    // ---------------- 遥控器 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (!isListVisible()) { showListTemporarily(); return true; }
                showListTemporarily();
                move(-(e.getRepeatCount() >= 2 ? 5 : 1));
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!isListVisible()) { showListTemporarily(); return true; }
                showListTemporarily();
                move(e.getRepeatCount() >= 2 ? 5 : 1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (e.getRepeatCount() >= 1) {
                    toggleFav();
                    return true;
                }
                if (isListVisible()) {
                    int pos = listView.getSelectedItemPosition();
                    if (pos >= 0 && pos != curIndex) {
                        playChannel(pos);
                    } else {
                        hideListNow();
                    }
                } else {
                    showListTemporarily();
                }
                return true;
            }
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                showListTemporarily();
                numBuf.append((char) ('0' + (code - KeyEvent.KEYCODE_0)));
                if (numBuf.length() > 3) numBuf.deleteCharAt(0);
                handler.removeCallbacks(numCommit);
                handler.postDelayed(numCommit, 1200);
                showOverlay("跳转 " + numBuf + " 台");
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                jumpGroup(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                jumpGroup(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                showSettings();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean isListVisible() {
        return leftPanel != null && leftPanel.getTranslationX() > -dp(150);
    }

    private void showListTemporarily() {
        if (leftPanel == null) return;
        if (!listVisible) {
            listVisible = true;
            leftPanel.animate().translationX(0f).alpha(1f).setDuration(180).start();
        }
        handler.removeCallbacks(hideList);
        handler.postDelayed(hideList, 6000);
    }

    private void hideListNow() {
        handler.removeCallbacks(hideList);
        hideList.run();
    }

    /** 左右键在分组之间跳：央视 -> 卫视 -> 香港 ... 循环。 */
    private void jumpGroup(int dir) {
        if (channels.isEmpty()) return;
        java.util.List<String> groups = new java.util.ArrayList<String>();
        for (Playlist.Channel c : channels) {
            String g = c.group == null ? "" : c.group;
            if (!groups.contains(g)) groups.add(g);
        }
        if (groups.size() < 2) return;
        String curGroup = curIndex >= 0 && curIndex < channels.size()
                ? (channels.get(curIndex).group == null ? "" : channels.get(curIndex).group)
                : groups.get(0);
        int gi = groups.indexOf(curGroup);
        if (gi < 0) gi = 0;
        int next = (gi + dir + groups.size()) % groups.size();
        String want = groups.get(next);
        for (int i = 0; i < channels.size(); i++) {
            String g = channels.get(i).group == null ? "" : channels.get(i).group;
            if (g.equals(want)) {
                playChannel(i);
                toast(want);
                return;
            }
        }
    }

    private void move(int delta) {
        if (channels.isEmpty()) return;
        int pos = listView.getSelectedItemPosition();
        if (pos < 0) pos = 0;
        int next = pos + delta;
        if (next < 0) next = 0;
        if (next >= channels.size()) next = channels.size() - 1;
        adapter.setSelected(next);
        listView.setSelection(next);
        handler.removeCallbacks(playSelected);
        handler.postDelayed(playSelected, PLAY_DEBOUNCE);
    }

    // ---------------- 播放 ----------------

    private void playChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        lowResMoved.clear();
        showListTemporarily();
        curIndex = index;
        curUrl = 0;
        playingName = channels.get(index).name;
        adapter.setSelected(index);
        listView.setSelection(index);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_channel", channels.get(index).name).apply();
        startCurrent();
    }

    private void startCurrent() {
        if (curIndex < 0 || curIndex >= channels.size()) return;
        Playlist.Channel c = channels.get(curIndex);
        while (curUrl < c.urls.size()) {
            String u = c.urls.get(curUrl);
            if (!Playlist.looksPlayable(u)) {
                curUrl++;
                continue;
            }
            Integer known = resCache.get(u);
            if (known != null && known > 0 && known < 1280 && curUrl + 1 < c.urls.size()) {
                android.util.Log.i("livetv", "skip cached low-res " + known + " " + u);
                curUrl++;
                continue;
            }
            break;
        }
        if (curUrl >= c.urls.size()) {
            showOverlay(c.name + "\n没有可播放的源");
            return;
        }
        prepared = false;
        stalledMs = 0;
        startWaitMs = 0;
        lastPos = -1;
        String url = c.urls.get(curUrl);
        showOverlay("正在连接 " + c.name);
        try {
            video.stopPlayback();
            video.setVideoURI(Uri.parse(url));
            video.start();
        } catch (Exception ex) {
            switchSource("地址无效");
            return;
        }
        handler.removeMessages(WATCHDOG);
        handler.sendEmptyMessageDelayed(WATCHDOG, 3000);
        updateHeader();
    }

    private void switchSource(String reason) {
        handler.removeMessages(WATCHDOG);
        if (curIndex < 0 || curIndex >= channels.size()) return;
        Playlist.Channel c = channels.get(curIndex);
        if (curUrl + 1 < c.urls.size()) {
            curUrl++;
            startCurrent();
        } else {
            showOverlay(c.name + "\n全部源都失败（" + reason + "）\n可等下次更新或换个台");
            prepared = false;
            updateHeader();
        }
    }

    private void updateHeader() {
        String state;
        if (curIndex >= 0 && curIndex < channels.size()) {
            Playlist.Channel c = channels.get(curIndex);
            int total = Math.max(1, c.urls.size());
            int shown = Math.min(curUrl + 1, total);
            if (prepared) {
                state = "播放中 · 源" + shown + "/" + total;
            } else {
                state = "连接中 · 源" + shown + "/" + total;
            }
        } else {
            state = "未播放";
        }
        header.setText(playingName + "\n共 " + channels.size() + " 台 · " + state);
    }

    private void showOverlay(String text) {
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    // ---------------- 频道表 ----------------

    private void applyChannels(final List<Playlist.Channel> list, final String source) {
        final List<Playlist.Channel> ordered = withFavs(list);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (ordered.isEmpty()) {
                    showOverlay("没有可用频道\n请检查订阅地址：" + subUrl);
                    return;
                }
                String keep = playingName;
                channels.clear();
                channels.addAll(ordered);
                int idx = 0;
                if (keep != null && keep.length() > 0) {
                    for (int i = 0; i < channels.size(); i++) {
                        if (channels.get(i).name.equals(keep)) {
                            idx = i;
                            break;
                        }
                    }
                }
                adapter.setSelected(idx);
                listView.setSelection(idx);
                boolean same = curIndex >= 0 && curIndex < channels.size()
                        && channels.get(curIndex).name.equals(keep);
                curIndex = idx;
                // 订阅刷新后源的数量可能变少，curUrl 越界时要从头重播
                if (!same || !prepared || curUrl >= channels.get(idx).urls.size()) {
                    curUrl = 0;
                    startCurrent();
                }
                updateHeader();
                android.util.Log.i("livetv", "频道表已载入(" + source + ")，共 " + channels.size() + " 台");
            }
        });
    }

    private void refresh(final boolean manual) {
        if (manual) showOverlay("正在更新频道表…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String text = Playlist.get(subUrl, 8000, 20000);
                    final List<Playlist.Channel> all = Playlist.parse(text);
                    if (all.size() < 5) throw new Exception("频道数异常: " + all.size());
                    writeFile(cacheFile(), text);
                    final List<Playlist.Channel> shown = Playlist.filter(all, groups);
                    if (shown.isEmpty()) throw new Exception("过滤后无频道，请检查分组设置");
                    applyChannels(shown, "在线");
                    if (manual) toast("已更新，共 " + shown.size() + " 台");
                } catch (final Exception ex) {
                    android.util.Log.w("livetv", "订阅更新失败: " + ex.getMessage());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (channels.isEmpty()) {
                                showOverlay("订阅更新失败：" + ex.getMessage()
                                        + "\n正在使用内置频道表");
                                String fb = readAsset("channels.txt");
                                if (fb != null) {
                                    applyChannels(Playlist.filter(Playlist.parse(fb), groups), "内置");
                                }
                            } else if (manual) {
                                toast("更新失败：" + ex.getMessage() + "（继续用缓存）");
                            }
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------- 设置 ----------------

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView t1 = new TextView(this);
        t1.setText("订阅地址（NAS 上的 iPTV 服务）");
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t1.setTextColor(Color.parseColor("#666666"));
        box.addView(t1);

        final EditText sub = new EditText(this);
        sub.setText(subUrl);
        sub.setSingleLine(true);
        sub.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(sub);

        TextView t2 = new TextView(this);
        t2.setText("显示的分组（逗号分隔，需与订阅里的分组名一致）");
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t2.setTextColor(Color.parseColor("#666666"));
        t2.setPadding(0, dp(10), 0, 0);
        box.addView(t2);

        final EditText grp = new EditText(this);
        grp.setText(groups);
        grp.setSingleLine(true);
        grp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(grp);

        TextView t3 = new TextView(this);
        t3.setText("当前 " + channels.size() + " 台 · 版本 2.0 · 菜单键打开本页，返回键退出");
        t3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t3.setTextColor(Color.parseColor("#888888"));
        t3.setPadding(0, dp(10), 0, 0);
        box.addView(t3);

        new AlertDialog.Builder(this)
                .setTitle("电视直播设置")
                .setView(box)
                .setPositiveButton("保存并刷新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newSub = sub.getText().toString().trim();
                        String newGrp = grp.getText().toString().trim();
                        if (newSub.length() == 0) newSub = DEFAULT_SUB;
                        if (!newSub.equals(subUrl) || !newGrp.equals(groups)) {
                            subUrl = newSub;
                            groups = newGrp;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putString(KEY_SUB, subUrl)
                                    .putString(KEY_GROUPS, groups)
                                    .apply();
                            refresh(true);
                        }
                    }
                })
                .setNeutralButton("重新拉取", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        refresh(true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(MainActivity.this, text,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------- 文件 ----------------

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void writeFile(File f, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
