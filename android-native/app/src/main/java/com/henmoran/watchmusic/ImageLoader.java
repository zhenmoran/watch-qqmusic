package com.henmoran.watchmusic;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简图片加载：内存 LruCache + 2 线程池 + 主线程回填。
 * 手表封面都是 150×150 的小图，不值得引 Glide。
 */
public class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(48) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface BitmapCb {
        void onBitmap(Bitmap bm);
    }

    /** 不绑定 ImageView 的加载，供自绘控件用 */
    public static void loadBitmap(final String url, final BitmapCb cb) {
        if (url == null || url.isEmpty()) {
            cb.onBitmap(null);
            return;
        }
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        POOL.execute(() -> {
            Bitmap bm = download(url);
            if (bm != null) CACHE.put(url, bm);
            MAIN.post(() -> cb.onBitmap(bm));
        });
    }

    public static void load(final String url, final ImageView target) {
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        // 标记当前请求，避免列表复用时图片错位
        target.setTag(url);

        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            target.setImageBitmap(hit);
            return;
        }
        target.setImageDrawable(null);

        POOL.execute(() -> {
            Bitmap bm = download(url);
            if (bm == null) return;
            CACHE.put(url, bm);
            MAIN.post(() -> {
                if (url.equals(target.getTag())) target.setImageBitmap(bm);
            });
        });
    }

    private static Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WatchMusic");
            InputStream is = c.getInputStream();
            Bitmap bm = BitmapFactory.decodeStream(is);
            is.close();
            return bm;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static void clear() {
        CACHE.evictAll();
    }
}
