package com.henmoran.watchmusic;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 会话门面：把 Qq 的登录态存进 SharedPreferences，重启 App 不用重新扫码。
 *
 * 以前这里是 HTTP 客户端，指向电脑上跑的 server.js；
 * 现在直连 QQ 音乐，不再需要任何服务器 —— 网络请求全部由 Qq 完成。
 */
public class Api {

    public static final Qq q = new Qq();

    private static SharedPreferences sp;
    private static String lastUin = "";

    public static void init(Context c) {
        if (sp != null) return;
        sp = c.getSharedPreferences("wm", Context.MODE_PRIVATE);
        q.loadCookies(sp.getString("cookies", ""));
        lastUin = sp.getString("uin", "");
    }

    public static void saveCookies() {
        if (sp == null) return;
        sp.edit()
                .putString("cookies", q.dumpCookies())
                .putString("uin", q.uin())
                .apply();
        lastUin = q.uin();
    }

    public static void logout() {
        q.logout();
        if (sp != null) sp.edit().remove("cookies").remove("uin").apply();
        lastUin = "";
    }

    public static boolean loggedIn() {
        return q.isLoggedIn();
    }

    public static String uin() {
        return q.uin().isEmpty() ? lastUin : q.uin();
    }
}
