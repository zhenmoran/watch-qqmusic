package com.henmoran.watchmusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;

/**
 * 前台服务 + MediaSession。
 *
 * 两个作用：
 *  1. 让进程在息屏后不被冻结，音乐能继续放；
 *  2. 通过 MediaSession 把「正在播放」暴露给系统，
 *     这样手表控制中心（下拉媒体面板）和蓝牙耳机线控都能看到歌名并操作。
 *
 * 只用 android.media.session（API 21+ 内置），不引 AndroidX MediaSessionCompat。
 */
public class PlayService extends Service implements Player.Listener {

    private static final String CHANNEL = "play";
    private static final int NOTI_ID = 1;

    private static final String ACTION_PREV = "com.henmoran.watchmusic.PREV";
    private static final String ACTION_NEXT = "com.henmoran.watchmusic.NEXT";
    private static final String ACTION_TOGGLE = "com.henmoran.watchmusic.TOGGLE";

    private NotificationManager nm;
    private MediaSession session;
    private long lastProgressPush = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "播放控制", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "WatchMusic");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (!Player.get().isPlaying()) Player.get().toggle();
            }

            @Override
            public void onPause() {
                if (Player.get().isPlaying()) Player.get().toggle();
            }

            @Override
            public void onSkipToNext() {
                Player.get().next();
            }

            @Override
            public void onSkipToPrevious() {
                Player.get().prev();
            }

            @Override
            public void onSeekTo(long pos) {
                Player.get().seekTo((int) pos);
            }

            @Override
            public void onStop() {
                Player.get().stop();
            }
        });
        session.setActive(true);

        Player.get().addListener(this);

        // 已有正在播的歌就先同步一次元数据，避免控制中心空着
        Qq.Song cur = Player.get().current();
        if (cur != null) applyMetadata(cur);
        pushState();
        startForeground(NOTI_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PREV.equals(action)) {
            Player.get().prev();
        } else if (ACTION_NEXT.equals(action)) {
            Player.get().next();
        } else if (ACTION_TOGGLE.equals(action)) {
            Player.get().toggle();
        }
        return START_STICKY;
    }

    /* ------------------------------ 通知构建 ------------------------------ */

    private Notification buildNotification() {
        Qq.Song s = Player.get().current();
        boolean playing = Player.get().isPlaying();

        String title = s == null ? "音乐" : s.name;
        String text = s == null ? "未在播放" : s.singer;

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .addAction(action(android.R.drawable.ic_media_previous, "上一首", ACTION_PREV))
                .addAction(action(playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", ACTION_TOGGLE))
                .addAction(action(android.R.drawable.ic_media_next, "下一首", ACTION_NEXT))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }

    private Notification.Action action(int iconRes, String title, String actionName) {
        Intent i = new Intent(this, PlayService.class).setAction(actionName);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getService(this, actionName.hashCode(), i, flags);
        return new Notification.Action.Builder(
                Icon.createWithResource(this, iconRes), title, pi).build();
    }

    /* ------------------------------ 状态同步 ------------------------------ */

    private void applyMetadata(Qq.Song song) {
        if (session == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.singer)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album);
        if (song.duration > 0) {
            b.putLong(MediaMetadata.METADATA_KEY_DURATION, song.duration * 1000L);
        }
        session.setMetadata(b.build());
    }

    private void pushState() {
        if (session == null) return;
        boolean playing = Player.get().isPlaying();
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        Player.get().positionMs(), playing ? 1f : 0f)
                .build();
        session.setPlaybackState(state);
    }

    private void refreshNotification() {
        if (nm != null) nm.notify(NOTI_ID, buildNotification());
    }

    /* ------------------------------ 播放回调 ------------------------------ */

    @Override
    public void onSong(Qq.Song song) {
        applyMetadata(song);
        pushState();
        refreshNotification();
    }

    @Override
    public void onState(boolean playing) {
        pushState();
        refreshNotification();
    }

    @Override
    public void onProgress(int posMs, int durMs) {
        // 控制中心的进度条不需要 2Hz 刷新，1 秒一次足够，省电
        long now = System.currentTimeMillis();
        if (now - lastProgressPush < 1000) return;
        lastProgressPush = now;
        pushState();
    }

    @Override
    public void onError(String msg) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Player.get().removeListener(this);
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}
