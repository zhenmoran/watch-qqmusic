package com.henmoran.watchmusic;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 原生界面主控。
 *
 * 和 WebView 版的区别：这里没有任何网页内核，视图全部是 Android View
 * （列表用 ListView 复用、歌词/进度条/图标是自绘 Canvas），音频用 MediaPlayer。
 * 代价是代码量，收益是内存、滑动跟手度和冷启动。
 */
public class MainActivity extends Activity implements Player.Listener {

    static final int BG = 0xFF000000, CARD = 0xFF17171A, CARD2 = 0xFF212126,
            TEXT = 0xFFF5F5F7, DIM = 0xFF8B8B93, DIM2 = 0xFF5C5C64, ACCENT = 0xFF31E5A6;

    private FrameLayout root;
    private LinearLayout body, tabBar, miniBar;

    private View pgDiscover, pgSearch, pgMine;
    private LinearLayout hotGrid, recentBox;
    private TextView recentTitle;

    private EditText searchInput;
    private ListView searchList;
    private TextView searchHint;
    private LinearLayout hotWords;

    private View pgList;
    private TextView listTitle, listMeta;
    private ListView listView;

    private View pgPlayer, pgLyric;
    private CoverView cover;
    private LyricView lyricView;
    private TextView pSong, pSinger, tCur, tDur, lyricTitle;
    private BarView progress;
    private IconView playIcon, miniIcon, favIcon;
    private View volPanel;
    private BarView volPanelBar;
    private IconView volPanelIcon;
    private TextView volPanelLabel;
    private android.widget.ScrollView discoverScroll, mineScroll, loginScroll;
    private LinearLayout minePlaylists;
    private TextView mineLoginLabel, mineDownloadsLabel;

    private IconView downloadIcon;
    private View pgDownloads;
    private ListView downloadList;
    private DownloadAdapter downloadAdapter;
    private TextView downloadMeta;
    private final java.util.Set<String> cloudFavMids = new java.util.HashSet<>();
    private final java.util.Set<String> downloading = new java.util.HashSet<>();
    private long lastCloudFavFetch = 0;
    private ImageView miniCover;
    private TextView miniName;

    private View pgLogin;
    private ImageView qrImage;
    private TextView qrTip, qqTab, wxTab;

    private boolean wechat = false, lyricMode = false, loggedIn = false;
    private String nick = "";
    private final Deque<View> stack = new ArrayDeque<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable qrPoll;

    private final List<Qq.Song> fav = new ArrayList<>();
    private final List<Qq.Song> recent = new ArrayList<>();
    private List<Qq.Song> listData = new ArrayList<>();
    private String listCtx = "";
    private SongAdapter listAdapter, searchAdapter;

    private SharedPreferences sp;
    private AudioManager am;

    private float downX, downY;
    private long downAt;

    /* =============================== 生命周期 =============================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        installCrashLog();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        sp = getSharedPreferences("wm", MODE_PRIVATE);
        Api.init(this);
        LocalStore.init(this);
        Player.localLocator = LocalStore::pathOf;

        loadLocal();
        buildUi();
        askNotificationPermission();

        Player.get().addListener(this);
        android.content.Intent svc = new android.content.Intent(this, PlayService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        Qq.Song last = readSong(sp.getString("last", null));
        if (last != null) {
            Player.get().restore(last);
            showMini(last);
        }
        renderRecent();
        refreshSession();
        loadHot();

    }

    @Override
    protected void onDestroy() {
        Player.get().removeListener(this);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Android 13+ 需要运行时授予通知权限，否则媒体通知和控制中心不显示 */
    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }

    /** 把未捕获异常写到应用私有目录，手表上用文件管理器或 adb 就能取到 */
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler fallback = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) {
                    if (fallback != null) fallback.uncaughtException(t, e);
                    return;
                }
                java.io.File f = new java.io.File(dir, "crash.txt");
                java.io.PrintWriter pw = new java.io.PrintWriter(
                        new java.io.FileWriter(f, true));
                pw.println("==== " + new java.util.Date() + " ====");
                e.printStackTrace(pw);
                pw.close();
            } catch (Throwable ignored) {
            }
            if (fallback != null) fallback.uncaughtException(t, e);
        });
    }

    /* =============================== 工具 =============================== */

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView tv(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    private IconView ic(int type, int sizeDp, int color) {
        IconView v = new IconView(this);
        v.setType(type);
        v.setColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** 带 44dp 触摸区的图标按钮 */
    private FrameLayout iconButton(int type, int sizeDp, int color, View.OnClickListener l) {
        FrameLayout box = new FrameLayout(this);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        IconView v = new IconView(this);
        v.setType(type);
        v.setColor(color);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.gravity = Gravity.CENTER;
        box.addView(v, lp);
        if (l != null) box.setOnClickListener(l);
        return box;
    }

    private <T> void async(final Callable<T> task, final Cb<T> cb) {
        new Thread(() -> {
            try {
                final T v = task.call();
                ui.post(() -> cb.ok(v));
            } catch (Exception e) {
                final String m = (e.getMessage() == null || e.getMessage().isEmpty())
                        ? "网络异常" : e.getMessage();
                ui.post(() -> cb.fail(m));
            }
        }).start();
    }

    private interface Cb<T> {
        void ok(T v);

        void fail(String msg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* =============================== 界面骨架 =============================== */

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        FrameLayout content = new FrameLayout(this);
        body.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pgDiscover = buildDiscover();
        pgSearch = buildSearch();
        pgMine = buildMine();
        content.addView(pgDiscover);
        content.addView(pgSearch);
        content.addView(pgMine);

        tabBar = buildTabBar();
        body.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        root.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        miniBar = buildMini();
        miniBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        mlp.gravity = Gravity.BOTTOM;
        mlp.leftMargin = dp(7);
        mlp.rightMargin = dp(7);
        mlp.bottomMargin = dp(39);
        root.addView(miniBar, mlp);

        // 覆盖层：列表 / 播放器 / 登录
        pgList = buildList();
        pgPlayer = buildPlayer();
        pgLogin = buildLogin();
        pgLyric = buildLyricPage();
        pgDownloads = buildDownloads();
        for (View v : new View[]{pgList, pgPlayer, pgLogin, pgLyric, pgDownloads}) {
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        setContentView(root);
        showTab(0);
    }

    private LinearLayout buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(BG);

        int[] icons = {IconView.HOME, IconView.SEARCH, IconView.USER};
        String[] labels = {"发现", "搜索", "我的"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            // 图标固定 18dp，靠父级居中
            IconView icon = new IconView(this);
            icon.setType(icons[i]);
            icon.setColor(DIM2);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(icon, ilp);

            // 文字撑满整列再自居中，三个字/两个字都会和图标同一条轴线
            TextView label = tv(labels[i], 9, DIM2, false);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(1);
            item.addView(label, tlp);

            item.setTag(new View[]{icon, label});
            item.setOnClickListener(v -> showTab(idx));
            bar.addView(item);
        }
        return bar;
    }

    private void showTab(int idx) {
        closeAll();
        pgDiscover.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pgSearch.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pgMine.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);

        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View item = tabBar.getChildAt(i);
            int color = i == idx ? ACCENT : DIM2;
            Object tag = item.getTag();
            if (tag instanceof View[]) {
                View[] pair = (View[]) tag;
                ((IconView) pair[0]).setColor(color);
                ((TextView) pair[1]).setTextColor(color);
            }
        }
        if (idx == 2) refreshSession();
        if (idx == 1) {
            hotWords.setVisibility(View.VISIBLE);
            searchHint.setVisibility(View.GONE);
        }
    }

    private LinearLayout buildMini() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(CARD2, 10));
        bar.setPadding(dp(6), 0, dp(6), 0);

        miniCover = new ImageView(this);
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(24), dp(24));
        bar.addView(miniCover, clp);

        miniName = tv("", 10, TEXT, false);
        miniName.setSingleLine(true);
        miniName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(8);
        bar.addView(miniName, nlp);

        FrameLayout playBtn = new FrameLayout(this);
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        miniIcon = ic(IconView.PLAY, 16, TEXT);
        ((LinearLayout.LayoutParams) miniIcon.getLayoutParams()).gravity = Gravity.CENTER;
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(16), dp(16));
        ilp.gravity = Gravity.CENTER;
        playBtn.addView(miniIcon, ilp);
        playBtn.setOnClickListener(v -> Player.get().toggle());
        bar.addView(playBtn);

        bar.setOnClickListener(v -> openOverlay(pgPlayer));
        return bar;
    }

    private void showMini(Qq.Song s) {
        if (s == null) return;
        miniBar.setVisibility(View.VISIBLE);
        miniName.setText(s.name);
        ImageLoader.load(s.pic, miniCover);
    }

    /* =============================== 页面：发现 =============================== */

    private View buildDiscover() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(10), dp(10), dp(10), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv("发现", 15, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(iconButton(IconView.SEARCH, 18, TEXT, v -> showTab(1)));
        page.addView(head);

        discoverScroll = new android.widget.ScrollView(this);
        discoverScroll.setVerticalScrollBarEnabled(false);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        discoverScroll.addView(inner);
        page.addView(discoverScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hotLabel = tv("热门歌单", 10, DIM, false);
        hotLabel.setPadding(0, dp(8), 0, dp(6));
        inner.addView(hotLabel);

        hotGrid = new LinearLayout(this);
        hotGrid.setOrientation(LinearLayout.VERTICAL);
        inner.addView(hotGrid);

        recentTitle = tv("最近播放", 10, DIM, false);
        recentTitle.setPadding(0, dp(12), 0, dp(4));
        recentTitle.setVisibility(View.GONE);
        inner.addView(recentTitle);

        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        inner.addView(recentBox);

        return page;
    }

    private void loadHot() {
        async(() -> Api.q.hot(), new Cb<List<Qq.Playlist>>() {
            @Override
            public void ok(List<Qq.Playlist> list) {
                hotGrid.removeAllViews();
                LinearLayout row = null;
                for (int i = 0; i < list.size(); i++) {
                    if (i % 3 == 0) {
                        row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        hotGrid.addView(row);
                    }
                    final Qq.Playlist p = list.get(i);
                    row.addView(playlistCell(p));
                }
            }

            @Override
            public void fail(String msg) {
                TextView t = tv("歌单加载失败：" + msg, 11, DIM2, false);
                t.setPadding(0, dp(10), 0, 0);
                hotGrid.removeAllViews();
                hotGrid.addView(t);
            }
        });
    }

    private View playlistCell(final Qq.Playlist p) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        lp.bottomMargin = dp(10);
        cell.setLayoutParams(lp);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(round(CARD2, 8));
        img.setClipToOutline(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        cell.addView(img, ilp);
        ImageLoader.load(p.pic, img);

        TextView name = tv(p.name, 8, DIM, false);
        name.setMaxLines(2);
        name.setPadding(0, dp(4), 0, 0);
        cell.addView(name);

        cell.setOnClickListener(v -> openPlaylist(p));
        return cell;
    }

    private void renderRecent() {
        recentBox.removeAllViews();
        recentTitle.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < Math.min(3, recent.size()); i++) {
            recentBox.addView(songRow(recent.get(i), i, recent, "最近播放"));
        }
    }

    /* =============================== 页面：搜索 =============================== */

    private View buildSearch() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(10), dp(12), 0);

        searchInput = new EditText(this);
        searchInput.setHint("搜索歌曲");
        searchInput.setHintTextColor(DIM2);
        searchInput.setTextColor(TEXT);
        searchInput.setTextSize(11);
        searchInput.setSingleLine(true);
        searchInput.setBackground(round(CARD, 16));
        searchInput.setPadding(dp(10), dp(5), dp(10), dp(5));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            doSearch(searchInput.getText().toString());
            searchInput.clearFocus();
            return true;
        });

        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBar.addView(iconButton(IconView.SEARCH, 15, ACCENT,
                v -> {
                    doSearch(searchInput.getText().toString());
                    searchInput.clearFocus();
                }));
        page.addView(searchBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hotWords = new LinearLayout(this);
        hotWords.setOrientation(LinearLayout.HORIZONTAL);
        hotWords.setPadding(0, dp(8), 0, 0);
        String[] words = {"周杰伦", "林俊杰", "薛之谦", "纯音乐"};
        for (String w : words) {
            TextView chip = tv(w, 10, DIM, false);
            chip.setBackground(round(CARD, 12));
            chip.setPadding(dp(9), dp(4), dp(9), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                searchInput.setText(w);
                doSearch(w);
            });
            hotWords.addView(chip);
        }
        page.addView(hotWords);

        searchHint = tv("", 10, DIM2, false);
        searchHint.setPadding(0, dp(8), 0, dp(4));
        searchHint.setVisibility(View.GONE);
        page.addView(searchHint);

        searchList = new ListView(this);
        searchList.setDivider(null);
        searchList.setVerticalScrollBarEnabled(false);
        searchList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        searchAdapter = new SongAdapter();
        searchList.setAdapter(searchAdapter);
        page.addView(searchList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return page;
    }

    private void doSearch(String kw) {
        kw = kw == null ? "" : kw.trim();
        if (kw.isEmpty()) return;
        hotWords.setVisibility(View.GONE);
        searchHint.setVisibility(View.VISIBLE);
        searchHint.setText("搜索中…");

        final String key = kw;
        async(() -> Api.q.search(key), new Cb<List<Qq.Song>>() {
            @Override
            public void ok(List<Qq.Song> list) {
                listData = list;
                listCtx = "搜索：" + key;
                searchAdapter.setData(list, "搜索：" + key);
                searchHint.setText(list.isEmpty() ? "没有找到相关歌曲" : "找到 " + list.size() + " 首");
            }

            @Override
            public void fail(String msg) {
                searchHint.setText(msg);
                searchAdapter.setData(new ArrayList<>(), "搜索");
            }
        });
    }

    /* =============================== 页面：我的 =============================== */

    private View buildMine() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(10), dp(10), dp(10), 0);

        TextView title = tv("我的", 15, TEXT, true);
        // 诊断信息不再显示入口，长按标题仍可调出，方便日后排查
        title.setOnLongClickListener(v -> {
            showDiagnose();
            return true;
        });
        page.addView(title);

        mineScroll = new android.widget.ScrollView(this);
        mineScroll.setVerticalScrollBarEnabled(false);
        mineScroll.setFillViewport(true);
        page.addView(mineScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), 0, dp(10));

        // 入口：我喜欢 / 最近播放 / 离线缓存
        rows.addView(menuRow(IconView.HEART, "我喜欢", v -> openFav()));

        rows.addView(menuRow(IconView.CLOCK, "最近播放", v -> {
            if (recent.isEmpty()) {
                toast("还没有播放记录");
                return;
            }
            openList("最近播放", new ArrayList<>(recent));
        }));

        LinearLayout dlRow = (LinearLayout) menuRow(IconView.DOWNLOAD, "离线缓存",
                v -> openDownloads());
        mineDownloadsLabel = (TextView) dlRow.getChildAt(1);
        rows.addView(dlRow);

        // 我的歌单放在三个入口之后，随歌单数量自然撑高
        TextView plLabel = tv("我的歌单", 10, DIM, false);
        plLabel.setPadding(dp(2), dp(12), 0, dp(5));
        rows.addView(plLabel);

        minePlaylists = new LinearLayout(this);
        minePlaylists.setOrientation(LinearLayout.VERTICAL);
        rows.addView(minePlaylists);

        // 底部：登录信息
        View spacer = new View(this);
        rows.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));

        LinearLayout loginRow = (LinearLayout) menuRow(IconView.USER, "扫码登录",
                v -> openLogin());
        mineLoginLabel = (TextView) loginRow.getChildAt(1);
        rows.addView(loginRow);

        mineScroll.addView(rows);
        return page;
    }


    private View menuRow(int icon, String label, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(CARD, 10));
        row.setPadding(dp(9), dp(8), dp(9), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        row.addView(ic(icon, 15, DIM));
        TextView t = tv(label, 11, TEXT, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(9);
        row.addView(t, tlp);
        row.setOnClickListener(l);
        return row;
    }

    /* =============================== 页面：列表 =============================== */

    private View buildList() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(0, dp(8), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), 0, dp(12), 0);

        head.addView(iconButton(IconView.BACK, 18, TEXT, v -> back()));
        listTitle = tv("", 13, TEXT, false);
        listTitle.setSingleLine(true);
        listTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(listTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        listMeta = tv("", 9, DIM2, false);
        head.addView(listMeta);
        page.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setVerticalScrollBarEnabled(false);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0));
        listView.setPadding(dp(10), dp(4), dp(10), dp(10));
        listView.setClipToPadding(false);
        listAdapter = new SongAdapter();
        listView.setAdapter(listAdapter);
        page.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return page;
    }

    private void openPlaylist(final Qq.Playlist p) {
        openOverlay(pgList);
        listTitle.setText(p.name);
        listMeta.setText("");
        listAdapter.setData(new ArrayList<>(), p.name);

        async(() -> Api.q.playlist(p.id), new Cb<List<Qq.Song>>() {
            @Override
            public void ok(List<Qq.Song> list) {
                listMeta.setText(list.size() + " 首");
                openList(p.name, list);
            }

            @Override
            public void fail(String msg) {
                listMeta.setText(msg);
            }
        });
    }

    private void openList(String title, List<Qq.Song> songs) {
        listTitle.setText(title);
        listMeta.setText(songs.size() + " 首");
        listData = songs;
        listCtx = title;
        listAdapter.setData(songs, title);
        listView.setSelection(0);
        openOverlay(pgList);
    }

    /* =============================== 页面：播放器 =============================== */

    private View buildPlayer() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(12));
        page.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 封面与歌词共用这一块，尺寸由剩余空间决定（198dp 屏上约 68dp 高）
        FrameLayout stage = new FrameLayout(this);
        col.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cover = new CoverView(this);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(56), dp(56));
        clp.gravity = Gravity.CENTER;
        stage.addView(cover, clp);

        // 小表盘（如 320px≈160dp）剩余空间不足，按实际高度收封面，避免顶掉下面的控件
        stage.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> {
            int avail = bo - t;
            int size = Math.min(dp(56), Math.max(dp(26), avail - dp(4)));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cover.getLayoutParams();
            if (lp.width != size) {
                lp.width = size;
                lp.height = size;
                cover.setLayoutParams(lp);
            }
        });

        // 返回/歌词按钮悬浮在封面之上，不单独占一行
        FrameLayout backBtn = iconButton(IconView.BACK, 16, TEXT, v -> back());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(32), dp(32));
        blp.gravity = Gravity.TOP | Gravity.LEFT;
        stage.addView(backBtn, blp);

        FrameLayout dlBtn = iconButton(IconView.DOWNLOAD, 16, TEXT, v -> downloadCurrent());
        downloadIcon = (IconView) ((ViewGroup) dlBtn).getChildAt(0);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(32), dp(32));
        llp.gravity = Gravity.TOP | Gravity.RIGHT;
        stage.addView(dlBtn, llp);

        pSong = tv("未在播放", 12, TEXT, true);
        pSong.setSingleLine(true);
        pSong.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pSong.setGravity(Gravity.CENTER);
        col.addView(pSong, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));

        pSinger = tv("", 9, DIM, false);
        pSinger.setSingleLine(true);
        pSinger.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pSinger.setGravity(Gravity.CENTER);
        col.addView(pSinger, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));

        progress = new BarView(this);
        progress.setListener((p, fromUser) -> {
            int dur = Player.get().durationMs();
            if (dur > 0) {
                tCur.setText(fmt((int) (dur * p)));
                if (fromUser) Player.get().seekTo((int) (dur * p));
            }
        });
        col.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        tCur = tv("0:00", 8, DIM2, false);
        tDur = tv("0:00", 8, DIM2, false);
        times.addView(tCur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tDur.setGravity(Gravity.END);
        times.addView(tDur);
        col.addView(times, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(11)));

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);

        ctrl.addView(iconButton(IconView.PREV, 17, TEXT, v -> Player.get().prev()));

        FrameLayout playBtn = new FrameLayout(this);
        playBtn.setBackground(round(ACCENT, 20));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(40), dp(40));
        plp.leftMargin = dp(14);
        plp.rightMargin = dp(14);
        playBtn.setLayoutParams(plp);
        playIcon = ic(IconView.PLAY, 18, 0xFF04231A);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(18), dp(18));
        ilp.gravity = Gravity.CENTER;
        playBtn.addView(playIcon, ilp);
        playBtn.setOnClickListener(v -> Player.get().toggle());
        ctrl.addView(playBtn);

        ctrl.addView(iconButton(IconView.NEXT, 17, TEXT, v -> Player.get().next()));
        col.addView(ctrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        // 底栏：收藏 / 音量按钮（点开浮层调） / 歌词（进独立页）
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER);

        LinearLayout favBtn = new LinearLayout(this);
        favBtn.setOrientation(LinearLayout.HORIZONTAL);
        favBtn.setGravity(Gravity.CENTER_VERTICAL);
        favIcon = ic(IconView.HEART, 14, DIM);
        favBtn.addView(favIcon);
        TextView favLabel = tv("收藏", 8, DIM, false);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = dp(3);
        favBtn.addView(favLabel, flp);
        favBtn.setTag(favLabel);
        favBtn.setOnClickListener(v -> toggleFav());
        foot.addView(favBtn);

        FrameLayout volBtn = iconButton(IconView.VOL, 16, DIM, v -> showVolPanel());
        LinearLayout.LayoutParams vblp = new LinearLayout.LayoutParams(dp(34), dp(34));
        vblp.leftMargin = dp(16);
        vblp.rightMargin = dp(16);
        volBtn.setLayoutParams(vblp);
        foot.addView(volBtn);

        LinearLayout lyFoot = new LinearLayout(this);
        lyFoot.setOrientation(LinearLayout.HORIZONTAL);
        lyFoot.setGravity(Gravity.CENTER_VERTICAL);
        lyFoot.addView(ic(IconView.LYRIC, 14, DIM));
        TextView lyLabel = tv("歌词", 8, DIM, false);
        LinearLayout.LayoutParams llp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp2.leftMargin = dp(3);
        lyFoot.addView(lyLabel, llp2);
        lyFoot.setOnClickListener(v -> openLyric());
        foot.addView(lyFoot);

        col.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

        page.addView(buildVolPanel(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return page;
    }

    /** 点音量按钮弹出的浮层：一个粗条，拖完点空白处收起 */
    private View buildVolPanel() {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(0xD9000000);
        panel.setVisibility(View.GONE);
        panel.setOnClickListener(v -> hideVolPanel());
        volPanel = panel;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(round(CARD, 12));
        // 吃掉点击，避免点条身时把浮层关掉
        box.setClickable(true);

        volPanelIcon = ic(IconView.VOL, 22, ACCENT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(volPanelIcon, ilp);

        volPanelLabel = tv("60%", 13, TEXT, true);
        volPanelLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(volPanelLabel, tlp);

        volPanelBar = new BarView(this);
        volPanelBar.setThickness(9);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(126),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        volPanelBar.setLayoutParams(blp);
        volPanelBar.setListener((p, fromUser) -> {
            if (!fromUser) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * p), 0);
            paintVol(p);
        });
        box.addView(volPanelBar);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        panel.addView(box, boxLp);

        return panel;
    }

    private void showVolPanel() {
        if (volPanel == null) return;
        volPanel.setVisibility(View.VISIBLE);
        volPanel.bringToFront();
        syncVol();
    }

    private void hideVolPanel() {
        if (volPanel != null) volPanel.setVisibility(View.GONE);
    }

    private void paintVol(float p) {
        if (volPanelBar != null) volPanelBar.reset(p);
        if (volPanelLabel != null) volPanelLabel.setText(Math.round(p * 100) + "%");
        if (volPanelIcon != null) volPanelIcon.setType(p <= 0 ? IconView.MUTE : IconView.VOL);
    }

    /** 独立的歌词页 */
    private View buildLyricPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(12), dp(8), dp(12), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconButton(IconView.BACK, 17, TEXT, v -> back()));

        lyricTitle = tv("歌词", 9, DIM2, false);
        lyricTitle.setGravity(Gravity.CENTER);
        lyricTitle.setSingleLine(true);
        lyricTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(lyricTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧占位，让标题真正居中
        View spacer = new View(this);
        head.addView(spacer, new LinearLayout.LayoutParams(dp(32), dp(1)));

        page.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        lyricView = new LyricView(this);
        lyricView.setOnLineClick(ms -> {
            Player.get().seekTo((int) ms);
            lyricView.setTime(ms);
        });
        page.addView(lyricView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return page;
    }

    private void openLyric() {
        if (pgLyric == null) return;
        openOverlay(pgLyric);
        if (lyricView != null) lyricView.setTime(Player.get().positionMs());
    }

    private String fmt(int ms) {
        if (ms < 0) ms = 0;
        return (ms / 60000) + ":" + String.format("%02d", (ms / 1000) % 60);
    }

    /* =============================== 页面：登录 =============================== */

    private View buildLogin() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(14), dp(6), dp(14), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconButton(IconView.BACK, 18, TEXT, v -> back()));
        TextView title = tv("扫码登录", 13, TEXT, false);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(iconButton(IconView.REFRESH, 18, TEXT, v -> refreshQr()));
        page.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        loginScroll = new android.widget.ScrollView(this);
        loginScroll.setVerticalScrollBarEnabled(false);
        loginScroll.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        loginScroll.addView(box);
        page.addView(loginScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(0, dp(2), 0, dp(2));

        qqTab = tabButton("QQ 扫码", true);
        wxTab = tabButton("微信扫码", false);
        tabs.addView(qqTab);
        tabs.addView(wxTab);
        box.addView(tabs);

        qqTab.setOnClickListener(v -> {
            if (wechat) {
                wechat = false;
                styleTabs();
                refreshQr();
            }
        });
        wxTab.setOnClickListener(v -> {
            if (!wechat) {
                wechat = true;
                styleTabs();
                refreshQr();
            }
        });

        FrameLayout qrWrap = new FrameLayout(this);
        qrWrap.setBackground(round(0xFFFFFFFF, 10));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(108), dp(108));
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        qlp.topMargin = dp(2);
        qrWrap.setLayoutParams(qlp);
        qrImage = new ImageView(this);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(98), dp(98));
        ilp.gravity = Gravity.CENTER;
        qrWrap.addView(qrImage, ilp);
        box.addView(qrWrap);

        qrTip = tv("正在获取二维码…", 10, DIM, false);
        qrTip.setGravity(Gravity.CENTER);
        qrTip.setPadding(0, dp(6), 0, dp(4));
        box.addView(qrTip);

        styleTabs();
        return page;
    }

    private TextView tabButton(String text, boolean on) {
        TextView t = tv(text, 10, on ? TEXT : DIM, false);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private void styleTabs() {
        qqTab.setTextColor(wechat ? DIM : TEXT);
        wxTab.setTextColor(wechat ? TEXT : DIM);
        qqTab.setBackground(round(wechat ? CARD : CARD2, 14));
        wxTab.setBackground(round(wechat ? CARD2 : CARD, 14));
    }

    /** 已登录时把云端「我喜欢」拉下来，与本地收藏合并去重 */
    private void openFav() {
        if (!Api.loggedIn()) {
            if (fav.isEmpty()) {
                toast("还没有收藏，登录后可同步云端");
                return;
            }
            openList("我喜欢", new ArrayList<>(fav));
            return;
        }

        if (!fav.isEmpty()) openList("我喜欢", new ArrayList<>(fav));
        async(() -> Api.q.favSongs(), new Cb<List<Qq.Song>>() {
            @Override
            public void ok(List<Qq.Song> cloud) {
                List<Qq.Song> merged = new ArrayList<>(cloud);
                for (Qq.Song s : fav) {
                    boolean dup = false;
                    for (Qq.Song c : merged) {
                        if (c.mid.equals(s.mid)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) merged.add(s);
                }
                if (merged.isEmpty()) {
                    toast("云端和本地都没有收藏");
                    return;
                }
                openList("我喜欢", merged);
                listMeta.setText(merged.size() + " 首（含云端）");
            }

            @Override
            public void fail(String msg) {
                if (fav.isEmpty()) toast("拉取云端收藏失败：" + msg);
            }
        });
    }

    private long lastPlaylistFetch = 0;

    /** 「我的」页里的歌单列表，登录后自动拉取 */
    private void loadMyPlaylists() {
        if (minePlaylists == null) return;
        if (!Api.loggedIn()) {
            minePlaylists.removeAllViews();
            TextView t = tv("登录后显示", 9, DIM2, false);
            t.setPadding(0, dp(2), 0, 0);
            minePlaylists.addView(t);
            return;
        }
        // 切换 tab 会频繁触发，60 秒内不重复请求
        if (System.currentTimeMillis() - lastPlaylistFetch < 60000) return;
        lastPlaylistFetch = System.currentTimeMillis();

        async(() -> Api.q.myPlaylists(), new Cb<List<Qq.Playlist>>() {
            @Override
            public void ok(List<Qq.Playlist> list) {
                minePlaylists.removeAllViews();
                if (list.isEmpty()) {
                    TextView t = tv("没有找到歌单", 9, DIM2, false);
                    t.setPadding(0, dp(2), 0, 0);
                    minePlaylists.addView(t);
                    return;
                }
                for (Qq.Playlist p : list) minePlaylists.addView(playlistRow(p));
            }

            @Override
            public void fail(String msg) {
                lastPlaylistFetch = 0;
                minePlaylists.removeAllViews();
                TextView t = tv("拉取失败：" + msg, 9, DIM2, false);
                t.setPadding(0, dp(2), 0, 0);
                minePlaylists.addView(t);
            }
        });
    }

    private View playlistRow(final Qq.Playlist p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), dp(5), dp(7), dp(5));
        row.setBackground(round(CARD, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(round(CARD2, 5));
        row.addView(img, new LinearLayout.LayoutParams(dp(26), dp(26)));
        ImageLoader.load(p.pic, img);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        row.addView(txt, tlp);

        TextView name = tv(p.name, 10, TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub = tv(p.count > 0 ? p.count + " 首" : "歌单", 8, DIM2, false);
        txt.addView(name);
        txt.addView(sub);

        row.setOnClickListener(v -> openPlaylist(p));
        return row;
    }

    private View buildDownloads() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(0, dp(8), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), 0, dp(10), 0);
        head.addView(iconButton(IconView.BACK, 17, TEXT, v -> back()));

        TextView title = tv("离线缓存", 12, TEXT, false);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        downloadMeta = tv("", 8, DIM2, false);
        head.addView(downloadMeta);
        page.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        downloadList = new ListView(this);
        downloadList.setDivider(null);
        downloadList.setVerticalScrollBarEnabled(false);
        downloadList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        downloadList.setPadding(dp(10), dp(4), dp(10), dp(10));
        downloadList.setClipToPadding(false);
        downloadAdapter = new DownloadAdapter();
        downloadList.setAdapter(downloadAdapter);
        page.addView(downloadList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return page;
    }

    private void openDownloads() {
        openOverlay(pgDownloads);
        refreshDownloads();
    }

    private void refreshDownloads() {
        List<LocalStore.Item> list = LocalStore.all();
        if (downloadAdapter != null) downloadAdapter.setData(list);
        if (downloadMeta != null) {
            downloadMeta.setText(list.isEmpty() ? ""
                    : list.size() + " 首 · " + LocalStore.sizeText(LocalStore.totalSize()));
        }
        updateDownloadLabel();
    }

    private class DownloadAdapter extends BaseAdapter {
        private List<LocalStore.Item> data = new ArrayList<>();

        void setData(List<LocalStore.Item> d) {
            data = d == null ? new ArrayList<>() : d;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int i) {
            return data.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(final int i, View convert, ViewGroup parent) {
            final LocalStore.Item it = data.get(i);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(3), dp(4), dp(3));
            row.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

            ImageView img = new ImageView(MainActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackground(round(CARD2, 5));
            row.addView(img, new LinearLayout.LayoutParams(dp(24), dp(24)));
            ImageLoader.load(it.song.pic, img);

            LinearLayout txt = new LinearLayout(MainActivity.this);
            txt.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = dp(8);
            row.addView(txt, tlp);

            TextView name = tv(it.song.name, 10, TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sub = tv(it.song.singer + " · " + LocalStore.sizeText(it.size), 8, DIM2, false);
            sub.setSingleLine(true);
            txt.addView(name);
            txt.addView(sub);

            FrameLayout del = new FrameLayout(MainActivity.this);
            IconView trash = ic(IconView.TRASH, 13, DIM2);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(13), dp(13));
            ilp.gravity = Gravity.CENTER;
            del.addView(trash, ilp);
            del.setOnClickListener(v -> {
                LocalStore.removeAt(indexOfItem(it));
                refreshDownloads();
                updateDownloadUi();
                toast("已删除");
            });
            row.addView(del, new LinearLayout.LayoutParams(dp(28), dp(28)));

            row.setOnClickListener(v -> {
                List<Qq.Song> songs = new ArrayList<>();
                int idx = 0;
                for (int k = 0; k < data.size(); k++) {
                    songs.add(data.get(k).song);
                    if (data.get(k) == it) idx = k;
                }
                Player.get().play(songs, idx, "离线缓存");
                openOverlay(pgPlayer);
            });
            return row;
        }
    }

    /** 按引用找到条目当前下标（列表可能已被删改） */
    private int indexOfItem(LocalStore.Item target) {
        List<LocalStore.Item> now = LocalStore.all();
        for (int i = 0; i < now.size(); i++) {
            if (now.get(i).song.mid.equals(target.song.mid)) return i;
        }
        return -1;
    }

    private void showDiagnose() {
        final String text = "版本: v" + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")\n" + Api.q.diagnose();
        new android.app.AlertDialog.Builder(this)
                .setTitle("诊断信息")
                .setMessage(text)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("diag", text));
                    toast("已复制");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openLogin() {
        if (loggedIn) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(nick.isEmpty() ? "已登录" : nick)
                    .setMessage("要退出当前账号吗？")
                    .setPositiveButton("退出登录", (d, w) -> {
                        Api.logout();
                        loggedIn = false;
                        nick = "";
                        lastPlaylistFetch = 0;
                        cloudFavMids.clear();
                        lastCloudFavFetch = 0;
                        if (minePlaylists != null) minePlaylists.removeAllViews();
                        toast("已退出");
                        refreshSession();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        openOverlay(pgLogin);
        refreshQr();
    }

    private void refreshQr() {
        if (qrPoll != null) ui.removeCallbacks(qrPoll);
        qrTip.setText("正在获取二维码…");
        qrImage.setImageDrawable(null);
        final boolean wx = wechat;

        async(() -> wx ? Api.q.startWxLogin() : Api.q.startQqLogin(), new Cb<Qq.Qr>() {
            @Override
            public void ok(Qq.Qr qr) {
                try {
                    byte[] bytes = Base64.decode(qr.image, Base64.DEFAULT);
                    Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bm == null) {
                        qrTip.setText("二维码解析失败，点右上角刷新");
                        return;
                    }
                    qrImage.setImageBitmap(bm);
                    qrTip.setText(qr.tip);
                    schedulePoll(wx);
                } catch (Exception e) {
                    qrTip.setText("二维码解析失败");
                }
            }

            @Override
            public void fail(String msg) {
                qrTip.setText(msg);
            }
        });
    }

    private void schedulePoll(final boolean wx) {
        qrPoll = () -> {
            if (pgLogin.getVisibility() != View.VISIBLE) return;
            async(() -> wx ? Api.q.pollWxLogin() : Api.q.pollQqLogin(), new Cb<String>() {
                @Override
                public void ok(String state) {
                    if ("waiting".equals(state)) {
                        qrTip.setText(wx ? "打开微信扫一扫" : "打开手机 QQ 扫一扫");
                        schedulePoll(wx);
                    } else if ("scanned".equals(state)) {
                        qrTip.setText("已扫码，请在手机上确认");
                        schedulePoll(wx);
                    } else if ("expired".equals(state) || "refused".equals(state)) {
                        qrTip.setText("二维码已失效，点右上角刷新");
                    } else if ("ok".equals(state)) {
                        Api.saveCookies();
                        qrTip.setText("登录成功");
                        refreshSession();
                        ui.postDelayed(() -> {
                            back();
                            showTab(2);
                        }, 700);
                    } else {
                        schedulePoll(wx);
                    }
                }

                @Override
                public void fail(String msg) {
                    qrTip.setText(msg);
                    schedulePoll(wx);
                }
            });
        };
        ui.postDelayed(qrPoll, wx ? 600 : 1000);
    }

    /* =============================== 会话 =============================== */

    private void refreshSession() {
        async(() -> Api.q.userInfo(), new Cb<Qq.User>() {
            @Override
            public void ok(Qq.User s) {
                loggedIn = Api.q.isLoggedIn();
                nick = s.nick;
                // 直接引用，别再按子 View 索引推断——布局一改就会崩
                if (mineLoginLabel != null) {
                    mineLoginLabel.setText(Api.loggedIn()
                            ? ("已登录：" + s.nick + (s.vip ? "  VIP" : ""))
                            : "扫码登录");
                }
                loadMyPlaylists();
                loadCloudFav();
                updateDownloadLabel();
            }

            @Override
            public void fail(String msg) {
            }
        });
    }

    /* =============================== 列表适配器 =============================== */

    private LinearLayout songRow(Qq.Song s, final int pos, final List<Qq.Song> data, final String ctx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(3), dp(6), dp(3));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(round(CARD2, 6));
        row.addView(img, new LinearLayout.LayoutParams(dp(24), dp(24)));
        ImageLoader.load(s.pic, img);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(9);
        row.addView(txt, tlp);

        TextView name = tv(s.name, 11, TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub = tv(s.singer, 8, DIM2, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(name);
        txt.addView(sub);

        row.setOnClickListener(v -> {
            Player.get().play(data, pos, ctx);
            openOverlay(pgPlayer);
        });
        return row;
    }

    private class SongAdapter extends BaseAdapter {
        private List<Qq.Song> data = new ArrayList<>();
        private String ctx = "";

        void setData(List<Qq.Song> d, String context) {
            data = d == null ? new ArrayList<>() : d;
            if (context != null) ctx = context;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int i) {
            return data.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = songRow(data.get(i), i, data, ctx);
                convert.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
            } else {
                // 复用行：只更新内容与点击上下文
                LinearLayout row = (LinearLayout) convert;
                final Qq.Song s = data.get(i);
                ImageView img = (ImageView) row.getChildAt(0);
                ImageLoader.load(s.pic, img);
                LinearLayout txt = (LinearLayout) row.getChildAt(1);
                ((TextView) txt.getChildAt(0)).setText(s.name);
                ((TextView) txt.getChildAt(1)).setText(s.singer);
                final int pos = i;
                final List<Qq.Song> src = data;
                row.setOnClickListener(v -> {
                    Player.get().play(src, pos, ctx);
                    openOverlay(pgPlayer);
                });
            }
            return convert;
        }
    }

    /* =============================== 导航 =============================== */

    private void openOverlay(View v) {
        v.setVisibility(View.VISIBLE);
        v.bringToFront();
        if (!stack.contains(v)) stack.push(v);
        miniBar.setVisibility(View.GONE);
    }

    private void back() {
        if (volPanel != null && volPanel.getVisibility() == View.VISIBLE) {
            hideVolPanel();
            return;
        }
        if (!stack.isEmpty()) {
            View v = stack.pop();
            v.setVisibility(View.GONE);
        }
        if (stack.isEmpty()) {
            if (Player.get().current() != null) miniBar.setVisibility(View.VISIBLE);
            miniBar.bringToFront();
        }
    }

    private void closeAll() {
        stack.clear();
        pgList.setVisibility(View.GONE);
        pgPlayer.setVisibility(View.GONE);
        pgLogin.setVisibility(View.GONE);
        if (pgLyric != null) pgLyric.setVisibility(View.GONE);
        if (pgDownloads != null) pgDownloads.setVisibility(View.GONE);
        miniBar.setVisibility(Player.get().current() != null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (!stack.isEmpty()) {
            back();
        } else {
            finishAndRemoveTask();
        }
    }

    /* =============================== 表冠 =============================== */

    /**
     * 旋转表冠 → ACTION_SCROLL。分发给当前页面的滚动容器。
     * 一段歌词/列表项的高度约 24~36dp，这里每格给 42dp，转起来比较跟手。
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL) {
            float d = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (Math.abs(d) > 0.0001f && handleCrown(d)) return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // 部分机型/旧固件把表冠映射成方向键
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && !(getCurrentFocus() instanceof EditText)) {
            int k = e.getKeyCode();
            if (k == android.view.KeyEvent.KEYCODE_DPAD_UP && handleCrown(-1f)) return true;
            if (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN && handleCrown(1f)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * 表冠方向系数：+1 正转往下翻（看后面的内容），-1 反之。
     * 不同机型表冠的 AXIS_SCROLL 符号约定不一致，觉得反了只改这里一个数字。
     */
    private static final int CROWN_DIR = -1;

    private boolean handleCrown(float delta) {
        int dy = (int) (delta * CROWN_DIR * dp(42));
        if (dy == 0) dy = (delta * CROWN_DIR > 0) ? dp(18) : -dp(18);

        if (visible(pgLyric) && lyricView != null) {
            lyricView.scrollByCrown(dy);
            return true;
        }
        if (visible(pgList) && listView != null) {
            listView.scrollListBy(dy);
            return true;
        }
        if (visible(pgDownloads) && downloadList != null) {
            downloadList.scrollListBy(dy);
            return true;
        }
        if (visible(pgSearch) && searchList != null) {
            searchList.scrollListBy(dy);
            return true;
        }
        if (visible(pgMine) && mineScroll != null) {
            mineScroll.scrollBy(0, dy);
            return true;
        }
        if (visible(pgDiscover) && discoverScroll != null) {
            discoverScroll.scrollBy(0, dy);
            return true;
        }
        if (visible(pgLogin) && loginScroll != null) {
            loginScroll.scrollBy(0, dy);
            return true;
        }
        return false;
    }

    private boolean visible(View v) {
        return v != null && v.getVisibility() == View.VISIBLE;
    }

    /* =============================== 手势 =============================== */

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        boolean volOpen = volPanel != null && volPanel.getVisibility() == View.VISIBLE;
        // 只有播放页在最上层时才响应切歌手势；歌词页盖在上面时必须让位给歌词滚动
        boolean playerOnTop = !stack.isEmpty() && stack.peek() == pgPlayer;
        if (playerOnTop && !volOpen) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downAt = System.currentTimeMillis();
                    break;
                case MotionEvent.ACTION_UP:
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    long dt = System.currentTimeMillis() - downAt;
                    // 只处理从屏幕中部开始的滑动，边缘留给系统返回手势
                    if (dt < 700 && downX > dp(24) && downX < getResources().getDisplayMetrics().widthPixels - dp(24)) {
                        if (Math.abs(dx) > dp(45) && Math.abs(dx) > Math.abs(dy) * 1.5f && dx > 0) {
                            back();
                        } else if (Math.abs(dy) > dp(42) && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                            if (dy < 0) Player.get().next();
                            else Player.get().prev();
                        }
                    }
                    break;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    /* =============================== 播放器回调 =============================== */

    @Override
    public void onSong(Qq.Song s) {
        if (pSong == null || cover == null || lyricView == null || progress == null) return;
        pSong.setText(s.name);
        pSinger.setText(s.singer + (s.album.isEmpty() ? "" : " · " + s.album));
        showMini(s);
        cover.setBitmap(null);
        ImageLoader.loadBitmap(s.pic, cover::setBitmap);
        updateFavUi();
        updateDownloadUi();
        progress.reset(0);
        tCur.setText("0:00");
        tDur.setText("0:00");

        // 歌词（独立页）
        if (lyricTitle != null) lyricTitle.setText(s.name);
        lyricView.setLines(new ArrayList<>());
        async(() -> Api.q.lyric(s.mid), new Cb<String>() {
            @Override
            public void ok(String raw) {
                lyricView.setLines(Lrc.parse(raw));
            }

            @Override
            public void fail(String msg) {
                lyricView.setLines(new ArrayList<>());
            }
        });
        saveLast(s);
    }

    @Override
    public void onState(boolean playing) {
        if (playIcon == null) return;
        playIcon.setType(playing ? IconView.PAUSE : IconView.PLAY);
        miniIcon.setType(playing ? IconView.PAUSE : IconView.PLAY);
    }

    @Override
    public void onProgress(int pos, int dur) {
        if (progress == null || lyricView == null) return;
        if (dur > 0) {
            progress.setProgress(pos / (float) dur);
            tDur.setText(fmt(dur));
        }
        tCur.setText(fmt(pos));
        lyricView.setTime(pos);
    }

    @Override
    public void onError(String msg) {
        toast(msg);
    }

    private void syncVol() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        paintVol(max <= 0 ? 0 : (float) cur / max);
    }

    /* =============================== 收藏 / 最近 =============================== */

    private void updateDownloadUi() {
        if (downloadIcon == null) return;
        Qq.Song s = Player.get().current();
        if (s == null) {
            downloadIcon.setType(IconView.DOWNLOAD);
            downloadIcon.setColor(TEXT);
            return;
        }
        if (downloading.contains(s.mid)) {
            downloadIcon.setType(IconView.CLOCK);
            downloadIcon.setColor(DIM);
        } else if (LocalStore.has(s.mid)) {
            downloadIcon.setType(IconView.CHECK);
            downloadIcon.setColor(ACCENT);
        } else {
            downloadIcon.setType(IconView.DOWNLOAD);
            downloadIcon.setColor(TEXT);
        }
    }

    private void updateDownloadLabel() {
        if (mineDownloadsLabel == null) return;
        int n = LocalStore.count();
        mineDownloadsLabel.setText(n == 0 ? "离线缓存"
                : "离线缓存 · " + n + " 首 " + LocalStore.sizeText(LocalStore.totalSize()));
    }

    /** 下载当前歌曲（默认音质），存本地供离线播放 */
    private void downloadCurrent() {
        final Qq.Song s = Player.get().current();
        if (s == null) {
            toast("先播放一首歌");
            return;
        }
        if (LocalStore.has(s.mid)) {
            toast("已缓存，去「我的 → 离线缓存」查看");
            return;
        }
        if (downloading.contains(s.mid)) {
            toast("正在下载…");
            return;
        }

        downloading.add(s.mid);
        updateDownloadUi();
        toast("开始下载");

        async(() -> {
            Exception last = null;

            // 先要最低流畅档；该曲没有这一档、或地址不可用，就回退默认音质。
            // 宁可文件大一点，也别让一次下载直接失败。
            try {
                return downloadTo(Api.q.songUrl(s.mid, s.mediaMid, Qq.Q_LOW), s.mid);
            } catch (Exception e) {
                last = e;
            }
            try {
                return downloadTo(Api.q.songUrl(s.mid), s.mid);
            } catch (Exception e) {
                last = e;
            }
            throw last == null ? new RuntimeException("下载失败") : last;
        }, new Cb<java.io.File>() {
            @Override
            public void ok(java.io.File f) {
                downloading.remove(s.mid);
                LocalStore.add(s, f.getAbsolutePath());
                updateDownloadUi();
                updateDownloadLabel();
                toast("已缓存 " + LocalStore.sizeText(f.length()));
            }

            @Override
            public void fail(String msg) {
                downloading.remove(s.mid);
                updateDownloadUi();
                toast("下载失败：" + brief(msg));
            }
        });
    }

    /**
     * 下载到本地缓存目录。先写 .part 再改名，失败不会留下半截文件。
     * 错误信息一律不含整串地址 —— 异常的 getMessage() 可能就是 URL 本身。
     */
    private java.io.File downloadTo(String url, String mid) throws Exception {
        if (url == null || !url.startsWith("http")) {
            throw new RuntimeException("音源地址无效");
        }

        java.io.File dir = LocalStore.dir();
        if (dir == null) throw new RuntimeException("缓存目录不可用");
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("无法创建缓存目录");

        java.io.File target = new java.io.File(dir, mid + ".m4a");
        java.io.File tmp = new java.io.File(dir, mid + ".part");

        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Watch) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        c.setRequestProperty("Referer", "https://y.qq.com/");
        c.setRequestProperty("Accept", "*/*");

        // 用 getResponseCode 而不是直接 getInputStream，
        // 否则 403/404 抛出的异常消息就是整串地址，用户会看到「失败 + 一个链接」
        int code;
        try {
            code = c.getResponseCode();
        } catch (Exception e) {
            c.disconnect();
            throw new RuntimeException("连接失败");
        }
        if (code != 200) {
            c.disconnect();
            throw new RuntimeException("服务器返回 " + code);
        }

        long total = 0;
        java.io.InputStream is = null;
        java.io.FileOutputStream fos = null;
        try {
            is = c.getInputStream();
            fos = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("传输中断");
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) { }
            try { if (is != null) is.close(); } catch (Exception ignored) { }
            c.disconnect();
        }

        // 太小的多半是错误页而不是音频
        if (total < 10240) {
            tmp.delete();
            throw new RuntimeException("音源无效（" + total + " 字节）");
        }

        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new RuntimeException("写入缓存失败");
        }
        return target;
    }

    /** 提示信息里若含地址，只说结论；顺手截断过长内容，表盘放不下 */
    private String brief(String msg) {
        if (msg == null || msg.trim().isEmpty()) return "网络异常";
        String m = msg.trim();
        if (m.matches("(?s).*https?://.*")) return "音源地址无效，稍后重试";
        return m.length() > 30 ? m.substring(0, 30) : m;
    }

    private void updateFavUi() {
        Qq.Song s = Player.get().current();
        boolean on = s != null && isFav(s.mid);
        favIcon.setColor(on ? ACCENT : DIM);
        favIcon.setFilled(on);
        View favBtn = (View) favIcon.getParent();
        if (favBtn != null && favBtn.getTag() instanceof TextView) {
            ((TextView) favBtn.getTag()).setText(on ? "已收藏" : "收藏");
            ((TextView) favBtn.getTag()).setTextColor(on ? ACCENT : DIM);
        }
    }

    private boolean favContains(String mid) {
        for (Qq.Song s : fav) if (s.mid.equals(mid)) return true;
        return false;
    }

    /** 已收藏的判断要同时看本地和云端「我喜欢」，否则云端的歌点开是空心 */
    private boolean isFav(String mid) {
        return favContains(mid) || cloudFavMids.contains(mid);
    }

    private void loadCloudFav() {
        if (!Api.loggedIn()) {
            cloudFavMids.clear();
            updateFavUi();
            return;
        }
        if (System.currentTimeMillis() - lastCloudFavFetch < 60000) return;
        lastCloudFavFetch = System.currentTimeMillis();

        async(() -> Api.q.favSongs(), new Cb<List<Qq.Song>>() {
            @Override
            public void ok(List<Qq.Song> list) {
                cloudFavMids.clear();
                for (Qq.Song s : list) cloudFavMids.add(s.mid);
                updateFavUi();
            }

            @Override
            public void fail(String msg) {
                lastCloudFavFetch = 0;
            }
        });
    }

    /**
     * 收藏/取消：本地立即生效，再写云端「我喜欢」。
     * 云端失败就回滚，不让本地和账号状态对不上。
     */
    private void toggleFav() {
        final Qq.Song s = Player.get().current();
        if (s == null) {
            toast("先播放一首歌");
            return;
        }

        final boolean inLocal = favContains(s.mid);
        final boolean inCloud = cloudFavMids.contains(s.mid);
        final boolean removing = inLocal || inCloud;

        // 乐观更新：先动本地，界面立刻有反馈
        if (inLocal) {
            for (int i = 0; i < fav.size(); i++) {
                if (fav.get(i).mid.equals(s.mid)) {
                    fav.remove(i);
                    break;
                }
            }
        }
        if (removing) {
            cloudFavMids.remove(s.mid);
        } else {
            fav.add(0, s);
            cloudFavMids.add(s.mid);
        }
        saveLocal();
        updateFavUi();

        if (!Api.loggedIn()) {
            toast(removing ? "已取消收藏（仅本地）" : "已收藏（仅本地）");
            return;
        }

        if (removing) {
            toast("已取消收藏");
            async(() -> Api.q.delFav(s.mid), new Cb<Boolean>() {
                @Override
                public void ok(Boolean done) {
                    if (done) return;
                    // 云端没删掉，回滚本地状态
                    rollback(s, true);
                    toast("云端取消失败，已恢复");
                }

                @Override
                public void fail(String msg) {
                    rollback(s, true);
                    toast("云端取消失败：" + msg);
                }
            });
        } else {
            toast("已收藏");
            async(() -> Api.q.addFav(s.mid), new Cb<Boolean>() {
                @Override
                public void ok(Boolean done) {
                    toast(done ? "已同步到云端" : "已收藏（云端同步失败，仅本地）");
                }

                @Override
                public void fail(String msg) {
                    toast("已收藏（云端同步失败，仅本地）");
                }
            });
        }
    }

    /** 回滚到收藏状态 */
    private void rollback(Qq.Song s, boolean restoreFav) {
        if (!restoreFav) return;
        if (!favContains(s.mid)) {
            fav.add(0, s);
            saveLocal();
        }
        cloudFavMids.add(s.mid);
        updateFavUi();
    }

    private void loadLocal() {
        fav.clear();
        recent.clear();
        readList("fav", fav);
        readList("recent", recent);
    }

    private void readList(String key, List<Qq.Song> out) {
        try {
            String raw = sp.getString(key, "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Qq.Song.from(o));
            }
        } catch (Exception ignored) {
        }
    }

    private void saveLocal() {
        try {
            JSONArray a = new JSONArray();
            for (Qq.Song s : fav) a.put(s.toJson());
            JSONArray b = new JSONArray();
            for (Qq.Song s : recent) b.put(s.toJson());
            sp.edit().putString("fav", a.toString()).putString("recent", b.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void saveLast(Qq.Song s) {
        sp.edit().putString("last", s.toJson().toString()).apply();
        recent.removeIf(x -> x.mid.equals(s.mid));
        recent.add(0, s);
        while (recent.size() > 20) recent.remove(recent.size() - 1);
        saveLocal();
        renderRecent();
    }

    private Qq.Song readSong(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Qq.Song.from(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }
}
