package com.local.livetv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "LIVETV";

    static class Ch {
        String name;
        final List<String> urls = new ArrayList<String>();
    }

    private final List<Ch> chans = new ArrayList<Ch>();
    private VideoView vv;
    private ListView list;
    private TextView title;
    private TextView status;
    private ChAdapter adapter;
    private final Handler h = new Handler();
    private int curCh = -1;
    private int curUrl = 0;
    private int startIndex = 0;
    private int playToken = 0;
    private boolean listVisible = true;
    private Runnable hideTask;
    private Runnable stallTask;
    private static final int STALL_MS = 12000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loadChannels();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        vv = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.gravity = Gravity.CENTER;
        root.addView(vv, vlp);

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        title.setPadding(dp(28), dp(18), dp(28), dp(18));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP | Gravity.LEFT;
        root.addView(title, tlp);

        status = new TextView(this);
        status.setTextColor(0xFFB0BEC5);
        status.setTextSize(16);
        status.setPadding(dp(28), dp(8), dp(28), dp(8));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        root.addView(status, slp);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setBackgroundColor(0xCC000000);
        list.setVerticalScrollBarEnabled(false);
        adapter = new ChAdapter();
        list.setAdapter(adapter);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT);
        llp.gravity = Gravity.LEFT;
        root.addView(list, llp);

        setContentView(root);

        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                adapter.notifyDataSetChanged();
                schedule(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                play(position);
            }
        });

        SharedPreferences sp = getSharedPreferences("livetv", MODE_PRIVATE);
        startIndex = sp.getInt("last", 0);
        if (startIndex < 0 || startIndex >= chans.size()) startIndex = 0;
        list.setSelection(startIndex);
        list.requestFocus();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (curCh < 0) play(startIndex);
            }
        }, 400);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void loadChannels() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("channels.txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 2) continue;
                Ch c = new Ch();
                c.name = parts[0];
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].startsWith("http")) c.urls.add(parts[i]);
                }
                if (!c.urls.isEmpty()) chans.add(c);
            }
            r.close();
        } catch (Exception e) {
            Log.e(TAG, "load channels failed", e);
        }
        Log.i(TAG, "channels = " + chans.size());
    }

    private void schedule(final int position) {
        if (hideTask != null) h.removeCallbacks(hideTask);
        hideTask = new Runnable() {
            @Override
            public void run() {
                if (position == curCh && vv.isPlaying()) return;
                play(position);
            }
        };
        h.postDelayed(hideTask, 700);
    }

    private void play(int position) {
        if (position < 0 || position >= chans.size()) return;
        curCh = position;
        curUrl = 0;
        getSharedPreferences("livetv", MODE_PRIVATE).edit().putInt("last", position).commit();
        playCurrent();
    }

    private void playCurrent() {
        if (curCh < 0 || curCh >= chans.size()) return;
        final Ch c = chans.get(curCh);
        if (curUrl >= c.urls.size()) {
            status.setText("无法播放：" + c.name);
            status.setVisibility(View.VISIBLE);
            Log.e(TAG, "all urls failed: " + c.name);
            setListVisible(true);
            return;
        }
        final String url = c.urls.get(curUrl);
        final int token = ++playToken;
        title.setText((curCh + 1) + ". " + c.name);
        status.setText("正在打开 " + (curUrl + 1) + "/" + c.urls.size() + " …");
        status.setVisibility(View.VISIBLE);
        Log.i(TAG, "play " + c.name + " -> " + url);
        try {
            vv.stopPlayback();
            vv.setVideoURI(Uri.parse(url));
        } catch (Exception e) {
            Log.e(TAG, "setVideoURI failed", e);
            curUrl++;
            playCurrent();
            return;
        }
        vv.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(android.media.MediaPlayer mp) {
                if (token != playToken) return;
                Log.i(TAG, "prepared " + c.name);
                cancelStall();
                status.setVisibility(View.GONE);
                vv.start();
                autoHide();
            }
        });
        vv.setOnInfoListener(new android.media.MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(android.media.MediaPlayer mp, int what, int extra) {
                if (token != playToken) return false;
                if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    Log.w(TAG, "buffering " + c.name + " url=" + url);
                    status.setText("缓冲中 " + c.name + " …");
                    status.setVisibility(View.VISIBLE);
                    cancelStall();
                    stallTask = new Runnable() {
                        @Override
                        public void run() {
                            if (token != playToken) return;
                            Log.w(TAG, "stall, switch source " + c.name);
                            curUrl++;
                            playCurrent();
                        }
                    };
                    h.postDelayed(stallTask, STALL_MS);
                } else if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    Log.i(TAG, "buffering end " + c.name);
                    cancelStall();
                    status.setVisibility(View.GONE);
                }
                return false;
            }
        });
        vv.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                if (token != playToken) return true;
                Log.e(TAG, "error what=" + what + " extra=" + extra + " url=" + url);
                cancelStall();
                curUrl++;
                playCurrent();
                return true;
            }
        });
    }

    private void cancelStall() {
        if (stallTask != null) {
            h.removeCallbacks(stallTask);
            stallTask = null;
        }
    }

    private void autoHide() {
        if (!listVisible) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (vv.isPlaying()) setListVisible(false);
            }
        }, 6000);
    }

    private void setListVisible(boolean v) {
        listVisible = v;
        list.setVisibility(v ? View.VISIBLE : View.GONE);
        if (v) {
            list.requestFocus();
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_MENU) {
                setListVisible(!listVisible);
                return true;
            }
            if (!listVisible && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_CENTER
                    || k == KeyEvent.KEYCODE_ENTER)) {
                setListVisible(true);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            vv.pause();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        try {
            vv.stopPlayback();
        } catch (Exception ignored) {
        }
    }

    class ChAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return chans.size();
        }

        @Override
        public Object getItem(int i) {
            return chans.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View reuse, ViewGroup parent) {
            TextView tv = (TextView) reuse;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setLayoutParams(new ListView.LayoutParams(ListView.LayoutParams.MATCH_PARENT, dp(52)));
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setPadding(dp(18), 0, dp(12), 0);
                tv.setTextSize(18);
                tv.setSingleLine(true);
            }
            Ch c = chans.get(i);
            tv.setText((i + 1) + ".  " + c.name);
            boolean focused = list != null && list.isFocused() && list.getSelectedItemPosition() == i;
            boolean current = (i == curCh);
            if (focused) {
                tv.setBackgroundColor(0xFF1565C0);
                tv.setTextColor(Color.WHITE);
            } else if (current) {
                tv.setBackgroundColor(0x33FFFFFF);
                tv.setTextColor(0xFFFFD54F);
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT);
                tv.setTextColor(0xFFE0E0E0);
            }
            return tv;
        }
    }
}
