package com.henmoran.watchmusic;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线缓存：下载下来的音频存应用私有目录，元数据存 SharedPreferences。
 *
 * 目录在 /sdcard/Android/data/com.henmoran.watchmusic/files/music/ ，
 * 卸载 App 会一起清掉，不需要存储权限。
 */
public class LocalStore {

    public static class Item {
        public Qq.Song song = new Qq.Song();
        public String path = "";
        public long size = 0;
    }

    private static SharedPreferences sp;
    private static File dir;
    private static final List<Item> items = new ArrayList<>();

    public static void init(Context c) {
        if (sp != null) return;
        sp = c.getSharedPreferences("wm", Context.MODE_PRIVATE);
        File base = c.getExternalFilesDir(null);
        dir = new File(base == null ? c.getFilesDir() : base, "music");
        if (!dir.exists()) dir.mkdirs();
        load();
    }

    public static File dir() {
        return dir;
    }

    private static void load() {
        items.clear();
        String raw = sp.getString("downloads", "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.song = Qq.Song.from(o.optJSONObject("song"));
                it.path = o.optString("path", "");
                it.size = o.optLong("size", 0);
                // 文件被手动删掉的就丢弃，避免列表里出现播不了的条目
                if (!it.path.isEmpty() && new File(it.path).exists() && !it.song.mid.isEmpty()) {
                    items.add(it);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("song", it.song.toJson());
                o.put("path", it.path);
                o.put("size", it.size);
                arr.put(o);
            }
            sp.edit().putString("downloads", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static boolean has(String mid) {
        return pathOf(mid) != null;
    }

    public static String pathOf(String mid) {
        if (mid == null) return null;
        for (Item it : items) {
            if (mid.equals(it.song.mid)) {
                return new File(it.path).exists() ? it.path : null;
            }
        }
        return null;
    }

    public static void add(Qq.Song song, String path) {
        remove(song.mid);
        Item it = new Item();
        it.song = song;
        it.path = path;
        File f = new File(path);
        it.size = f.exists() ? f.length() : 0;
        items.add(0, it);
        save();
    }

    public static void remove(String mid) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.song.mid.equals(mid)) {
                try {
                    new File(it.path).delete();
                } catch (Exception ignored) {
                }
                items.remove(i);
                save();
                return;
            }
        }
    }

    public static void removeAt(int index) {
        if (index < 0 || index >= items.size()) return;
        Item it = items.remove(index);
        try {
            new File(it.path).delete();
        } catch (Exception ignored) {
        }
        save();
    }

    public static List<Item> all() {
        return new ArrayList<>(items);
    }

    public static int count() {
        return items.size();
    }

    public static long totalSize() {
        long t = 0;
        for (Item it : items) t += it.size;
        return t;
    }

    public static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
