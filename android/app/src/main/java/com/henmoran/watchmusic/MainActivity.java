package com.henmoran.watchmusic;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

/**
 * 极简 Wear OS 外壳：整屏只放一个 WebView。
 *
 * 用 WebView 而不是原生重写播放器的理由：
 *  - 手表 CPU/RAM 都紧张，网页方案只加载一份 Chromium 内核，业务全在服务端；
 *  - 改界面不用重新发版，改服务器上的文件即可。
 *
 * 不依赖 AppCompat / Compose，APK 只有一百多 KB。
 */
public class MainActivity extends Activity {

    private WebView web;
    private AudioManager audioManager;
    private SharedPreferences prefs;
    private boolean addressDialogShown = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 听歌时不熄屏，否则手表抬腕超时会直接断声
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        prefs = getSharedPreferences("wm", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        setContentView(web);

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        // 允许脚本直接起播，省掉每次点歌还要再点一次播放键
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setCacheMode(WebSettings.LOAD_DEFAULT);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportZoom(false);
        st.setDisplayZoomControls(false);
        // 去掉 wv 标记，部分 CDN 会因此拒绝请求
        st.setUserAgentString(st.getUserAgentString().replace("; wv", ""));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 让系统在内存吃紧时优先回收本进程的渲染层
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        }

        // 音量桥：网页里优先调系统媒体音量，比 audio.volume 更符合手表习惯
        web.addJavascriptInterface(new AudioBridge(), "AndroidAudio");

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 主文档打不开多半是服务器 IP 变了，弹窗让用户改，省得重新打包
                if (request.isForMainFrame() && !addressDialogShown) {
                    showServerDialog();
                }
            }
        });

        web.loadUrl(serverUrl());
    }

    private String serverUrl() {
        return prefs.getString("server", BuildConfig.SERVER_URL);
    }

    private void showServerDialog() {
        if (addressDialogShown) return;
        addressDialogShown = true;

        final EditText et = new EditText(this);
        et.setText(serverUrl());
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("服务器地址")
                .setMessage("填电脑上跑 server.js 的地址")
                .setView(et)
                .setPositiveButton("连接", (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (!u.isEmpty() && !u.startsWith("http")) u = "http://" + u;
                    prefs.edit().putString("server", u).apply();
                    addressDialogShown = false;
                    web.loadUrl(u);
                })
                .setNegativeButton("取消", (d, w) -> addressDialogShown = false)
                .show();
    }

    /** 网页通过 window.AndroidAudio 调用 */
    public class AudioBridge {
        @JavascriptInterface
        public void setVolume(int percent) {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int idx = Math.round(max * Math.max(0, Math.min(100, percent)) / 100f);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
        }

        @JavascriptInterface
        public int getVolume() {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return 100;
            return Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        }
    }

    /** 系统手势返回：先交给网页关掉覆盖层，关不掉才退出 */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__wmBack && window.__wmBack()) ? 1 : 0", value -> {
            if (value == null || !value.trim().equals("1")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不在 onPause 暂停音频：抬腕抬落不应该中断播放
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        web.loadUrl("about:blank");
        web.destroy();
        super.onDestroy();
    }
}
