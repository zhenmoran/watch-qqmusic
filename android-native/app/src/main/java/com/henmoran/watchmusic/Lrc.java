package com.henmoran.watchmusic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC 解析与当前行二查找 */
public class Lrc {

    public static class Line {
        public long time;
        public String text;

        Line(long t, String s) {
            time = t;
            text = s;
        }
    }

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    public static List<Line> parse(String raw) {
        List<Line> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;

        for (String row : raw.split("\n")) {
            Matcher m = TAG.matcher(row);
            List<Long> times = new ArrayList<>();
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String frac = m.group(3);
                long ms = 0;
                if (frac != null) {
                    // 两位补成 1/100 秒，三位是毫秒
                    ms = frac.length() == 3 ? Long.parseLong(frac) : Long.parseLong(frac) * 10;
                }
                times.add(min * 60000 + sec * 1000 + ms);
            }
            String text = TAG.matcher(row).replaceAll("").trim();
            if (text.isEmpty() || times.isEmpty()) continue;
            for (long t : times) out.add(new Line(t, text));
        }
        // 按时间排序，原文件不一定严格有序
        out.sort((a, b) -> Long.compare(a.time, b.time));
        return out;
    }

    /** 返回当前时间点应对应的行号，没有则 -1 */
    public static int indexAt(List<Line> lines, long ms) {
        if (lines == null || lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).time <= ms) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
