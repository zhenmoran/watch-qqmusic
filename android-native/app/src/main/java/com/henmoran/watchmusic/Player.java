package com.henmoran.watchmusic;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 播放核心。全局单例，界面只是它的观察者。
 *
 * 设计要点：
 *  - MediaPlayer 只有一个，切歌即 release，不累积解码器
 *  - 取播放地址走单线程池，切歌用 token 丢弃过期回调（防止快速切歌时串音）
 *  - 进度用 Handler 500ms 轮询，比 requestAnimationFrame 省电
 */
public class Player {

    /** 由界面层注入：给定 mid 返回本地缓存文件路径，没有则 null */
    public interface LocalLocator {
        String localPath(String mid);
    }

    public static LocalLocator localLocator;

    public interface Listener {
        void onSong(Qq.Song song);

        void onState(boolean playing);

        void onProgress(int posMs, int durMs);

        void onError(String msg);
    }

    private static Player instance;

    public static synchronized Player get() {
        if (instance == null) instance = new Player();
        return instance;
    }

    private MediaPlayer mp;
    private final List<Qq.Song> queue = new ArrayList<>();
    private int index = -1;
    private String contextName = "正在播放";
    private int loadToken = 0;
    private boolean preparing = false;
    private int pendingSeekMs = -1;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (mp != null) {
                try {
                    int pos = mp.getCurrentPosition();
                    int dur = mp.getDuration();
                    for (Listener l : listeners) l.onProgress(pos, dur);
                } catch (Exception ignored) {
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    private Player() {
    }

    /* ------------------------------ 观察者 ------------------------------ */

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /* ------------------------------- 状态 ------------------------------- */

    public Qq.Song current() {
        if (index < 0 || index >= queue.size()) return null;
        return queue.get(index);
    }

    public String contextName() {
        return contextName;
    }

    public boolean isPlaying() {
        try {
            return mp != null && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public int positionMs() {
        try {
            return mp == null ? 0 : mp.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public int durationMs() {
        try {
            return mp == null ? 0 : mp.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    /* ------------------------------- 控制 ------------------------------- */

    public void play(List<Qq.Song> list, int start, String ctx) {
        if (list == null || list.isEmpty()) return;
        queue.clear();
        queue.addAll(list);
        index = Math.max(0, Math.min(start, queue.size() - 1));
        if (ctx != null && !ctx.isEmpty()) contextName = ctx;
        load();
    }

    /** 恢复上次那首（不自动播放） */
    public void restore(Qq.Song s) {
        if (s == null) return;
        queue.clear();
        queue.add(s);
        index = 0;
        for (Listener l : listeners) l.onSong(s);
    }

    public void toggle() {
        if (current() == null) return;
        if (mp == null) {
            load();
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                notifyState(false);
            } else {
                mp.start();
                notifyState(true);
            }
        } catch (Exception e) {
            load();
        }
    }

    public void next() {
        if (queue.isEmpty()) return;
        index = index >= queue.size() - 1 ? 0 : index + 1;
        load();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        // 播过 3 秒后按上一首 = 回到开头，符合常见播放器习惯
        if (positionMs() > 3000 && isPlaying()) {
            seekTo(0);
            return;
        }
        index = index <= 0 ? queue.size() - 1 : index - 1;
        load();
    }

    public void seekTo(int ms) {
        if (mp == null) {
            pendingSeekMs = ms;
            return;
        }
        try {
            mp.seekTo(ms);
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------- 加载 ------------------------------- */

    private void load() {
        final Qq.Song song = current();
        if (song == null) return;

        final int token = ++loadToken;
        preparing = true;
        for (Listener l : listeners) l.onSong(song);

        net.execute(() -> {
            String url = null;
            String err = null;

            // 已缓存的直接播本地文件，断网也能放
            String local = localLocator == null ? null : localLocator.localPath(song.mid);
            if (local != null) {
                url = local;
            } else {
                try {
                    url = Api.q.songUrl(song.mid);
                } catch (Exception e) {
                    err = e.getMessage();
                    if (err == null || err.isEmpty()) err = "获取播放地址失败";
                }
            }
            final String u = url;
            final String e2 = err;
            ui.post(() -> {
                if (token != loadToken) return;   // 已经切到别的歌了
                if (u == null) {
                    preparing = false;
                    notifyState(false);
                    for (Listener l : listeners) l.onError(e2);
                    return;
                }
                start(u, token);
            });
        });
    }

    private void start(String url, final int token) {
        releaseMp();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(m -> {
                if (token != loadToken) {
                    m.release();
                    return;
                }
                preparing = false;
                if (pendingSeekMs > 0) {
                    m.seekTo(pendingSeekMs);
                    pendingSeekMs = -1;
                }
                m.start();
                notifyState(true);
                ui.removeCallbacks(ticker);
                ui.post(ticker);
            });
            mp.setOnCompletionListener(m -> next());
            mp.setOnErrorListener((m, what, extra) -> {
                // 网络抖动、seek 越界这类错误之后 MediaPlayer 往往还能继续播，
                // 立刻弹「版权原因」是误报。等 1.5 秒确认真的没在响再报。
                final int t = token;
                ui.postDelayed(() -> {
                    if (t != loadToken) return;
                    boolean playing = false;
                    try {
                        playing = m.isPlaying();
                    } catch (Exception ignored) {
                    }
                    if (playing) return;
                    preparing = false;
                    notifyState(false);
                    for (Listener l : listeners) l.onError("播放失败，可能是该曲目无版权");
                }, 1500);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            notifyState(false);
            for (Listener l : listeners) l.onError("播放失败");
        }
    }

    private void releaseMp() {
        if (mp == null) return;
        try {
            mp.reset();
            mp.release();
        } catch (Exception ignored) {
        }
        mp = null;
    }

    private void notifyState(boolean playing) {
        for (Listener l : listeners) l.onState(playing);
    }

    public void stop() {
        loadToken++;
        releaseMp();
        notifyState(false);
        ui.removeCallbacks(ticker);
    }
}
