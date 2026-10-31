package com.henmoran.watchmusic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 音乐接口层（纯 Java，不依赖 Android），让 App 脱离电脑独立运行。
 *
 * 为什么要自己写 Cookie 罐：扫码登录要靠 qrsig 和最终下发的 qm_keyst，
 * 而 HttpURLConnection 的默认 CookieHandler 拿不到重定向链路上的中间 Cookie，
 * 所以手动收集、手动跟随 302。
 *
 * 这里的所有方法都会抛异常，由界面层统一转成提示。
 */
public class Qq {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String CY = "https://c.y.qq.com";

    private static final String LOGIN_APPID = "716027609";
    private static final String LOGIN_DAID = "383";
    private static final String LOGIN_AID3 = "100497308";

    private static final String WX_APPID = "wx48db31d50e334801";
    private static final String WX_SURL = "https://y.qq.com/";
    private static final String WX_REDIRECT =
            "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=" + enc(WX_SURL);

    /* ============================== 数据模型 ============================== */

    public static class Song {
        public String mid = "";
        public String mediaMid = "";
        public String name = "";
        public String singer = "";
        public String album = "";
        public String pic = "";
        public int duration = 0;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("mid", mid);
                o.put("mediaMid", mediaMid);
                o.put("name", name);
                o.put("singer", singer);
                o.put("album", album);
                o.put("pic", pic);
                o.put("duration", duration);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Song from(JSONObject o) {
            Song s = new Song();
            if (o == null) return s;
            s.mid = o.optString("mid", "");
            s.mediaMid = o.optString("mediaMid", "");
            s.name = o.optString("name", "");
            s.singer = o.optString("singer", "");
            s.album = o.optString("album", "");
            s.pic = o.optString("pic", "");
            s.duration = o.optInt("duration", 0);
            return s;
        }
    }

    public static class Playlist {
        public String id = "";
        public String name = "";
        public String pic = "";
        public String desc = "";
        public int count = 0;
    }

    public static class User {
        public String uin = "";
        public String nick = "";
        public String avatar = "";
        public boolean vip = false;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("uin", uin);
                o.put("nick", nick);
                o.put("avatar", avatar);
                o.put("vip", vip);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static User from(JSONObject o) {
            User u = new User();
            if (o == null) return u;
            u.uin = o.optString("uin", "");
            u.nick = o.optString("nick", "");
            u.avatar = o.optString("avatar", "");
            u.vip = o.optBoolean("vip", false);
            return u;
        }
    }

    /** 二维码：图片已是 base64（不含 data: 前缀） */
    public static class Qr {
        public String image = "";
        public String tip = "";
    }

    public static class QqException extends Exception {
        public final String code;

        public QqException(String code, String msg) {
            super(msg);
            this.code = code == null ? "" : code;
        }
    }

    /* ============================== 状态 ============================== */

    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String qqQrsig = "";
    private String wxUuid = "";

    /**
     * 登录返回体里的 uin。微信登录的用户没有 QQ 号，Cookie 里的 uin 可能是加密串，
     * 直接猜会拿到错误值，导致 vkey 请求被当成未登录。所以优先用登录接口明确返回的 uin。
     */
    private String loginUin = "";

    /** 最近一次取到的音源地址，诊断用（vkey 打码） */
    private String lastUrl = "";

    /** 把 vkey 之类凭证打码，日志里能看结构但不泄露 */
    static String mask(String u) {
        if (u == null || u.isEmpty()) return "(空)";
        String s = u.replaceAll("(?i)(vkey|key|authst)=([^&]{0,4})[^&]*", "$1=$2***");
        return s.length() > 150 ? s.substring(0, 150) + "…" : s;
    }

    public String uin() {
        if (!loginUin.isEmpty()) return loginUin;
        String stored = get("__uin");
        if (!stored.isEmpty()) {
            loginUin = stored;
            return loginUin;
        }
        for (String k : new String[]{"uin", "p_uin", "wxuin"}) {
            String raw = get(k);
            if (raw == null || raw.isEmpty()) continue;
            if (raw.matches("\\d+")) {
                loginUin = raw;
                return raw;
            }
            String v = raw.replaceFirst("^o0?", "");
            if (v.matches("\\d+")) {
                loginUin = v;
                return v;
            }
        }
        return "";
    }

    /** uin 可能是空或非数字，转 long 时兜底 */
    private long uinLong() {
        String u = uin();
        if (u.isEmpty()) return 0;
        try {
            return Long.parseLong(u);
        } catch (Exception e) {
            return 0;
        }
    }

    public void setLoginUin(String u) {
        if (u != null && u.matches("\\d+")) loginUin = u;
    }

    public boolean isLoggedIn() {
        return !get("qm_keyst").isEmpty() || !get("qqmusic_key").isEmpty();
    }

    public String dumpCookies() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : cookies.entrySet()) o.put(e.getKey(), e.getValue());
            if (!loginUin.isEmpty()) o.put("__uin", loginUin);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    public void loadCookies(String json) {
        cookies.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                cookies.put(k, o.optString(k, ""));
            }
        } catch (Exception ignored) {
        }
    }

    public void logout() {
        cookies.clear();
        qqQrsig = "";
        wxUuid = "";
    }

    private String get(String k) {
        String v = cookies.get(k);
        return v == null ? "" : v;
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (e.getKey().startsWith("__")) continue;   // 内部键，不发出去
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /* ============================== HTTP ============================== */

    public static class Resp {
        public int status;
        public byte[] body;
        public Map<String, List<String>> headers;

        public String text() {
            try {
                return new String(body, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        public JSONObject json() throws Exception {
            return new JSONObject(text());
        }
    }

    private Resp exec(String method, String url, String body, String referer, int timeoutMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setRequestProperty("Referer", referer == null ? "https://y.qq.com/" : referer);
        String ck = cookieHeader();
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);

        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }

        Resp r = new Resp();
        try {
            r.status = c.getResponseCode();
            r.headers = c.getHeaderFields();
            InputStream is = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = is == null ? new byte[0] : readAll(is);
            absorbCookies(r.headers);
        } finally {
            c.disconnect();
        }
        return r;
    }

    private void absorbCookies(Map<String, List<String>> headers) {
        if (headers == null) return;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            if (name == null || !name.equalsIgnoreCase("Set-Cookie")) continue;
            for (String line : e.getValue()) {
                String kv = line.split(";")[0].trim();
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                cookies.put(kv.substring(0, i), kv.substring(i + 1));
            }
        }
    }

    /** 手动跟随 3xx，逐跳收 Cookie（登录 check_sig 必须这样走） */
    private Resp follow(String url, int maxHop) throws Exception {
        String cur = url;
        Resp last = null;
        for (int i = 0; i < maxHop; i++) {
            last = exec("GET", cur, null, "https://y.qq.com/", 15000);
            if (last.status >= 300 && last.status < 400) {
                String loc = header(last, "Location");
                if (loc == null || loc.isEmpty()) break;
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            break;
        }
        return last;
    }

    private static String header(Resp r, String key) {
        if (r.headers == null) return "";
        for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return "";
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private JSONObject musicu(JSONObject payload, int timeout) throws Exception {
        Resp r = exec("POST", MUSICU, payload.toString(), "https://y.qq.com/", timeout);
        try {
            return r.json();
        } catch (Exception e) {
            throw new QqException("", "QQ 音乐返回异常，可能被风控拦截");
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static int hash33(String s) {
        int e = 0;
        for (int i = 0; i < s.length(); i++) e += (e << 5) + s.charAt(i);
        return e & 0x7fffffff;
    }

    private static String pic(String albumMid, int size) {
        if (albumMid == null || albumMid.isEmpty()) return "";
        return "https://y.gtimg.cn/music/photo_new/T002R" + size + "x" + size + "M000" + albumMid + ".jpg";
    }

    private static String https(String u) {
        if (u == null) return "";
        return u.startsWith("http://") ? "https://" + u.substring(7) : u;
    }

    /* ============================== 业务接口 ============================== */

    private static Song normalize(JSONObject s) {
        Song song = new Song();
        song.mid = s.optString("mid", s.optString("songmid", ""));
        JSONObject f = s.optJSONObject("file");
        if (f != null) song.mediaMid = f.optString("media_mid", "");
        song.name = s.optString("name", s.optString("title", s.optString("songname", "")));
        song.duration = s.optInt("interval", s.optInt("duration", 0));

        JSONArray singers = s.optJSONArray("singer");
        StringBuilder sb = new StringBuilder();
        if (singers != null) {
            for (int i = 0; i < singers.length(); i++) {
                JSONObject x = singers.optJSONObject(i);
                if (x == null) continue;
                String n = x.optString("name", "");
                if (!n.isEmpty()) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(n);
                }
            }
        }
        song.singer = sb.length() > 0 ? sb.toString() : s.optString("singername", "");

        JSONObject album = s.optJSONObject("album");
        String albumMid = "";
        if (album != null) {
            song.album = album.optString("name", album.optString("title", ""));
            albumMid = album.optString("mid", "");
        } else {
            song.album = s.optString("albumname", "");
            albumMid = s.optString("albummid", "");
        }
        song.pic = pic(albumMid, 150);
        return song;
    }

    public List<Song> search(String keyword) throws Exception {
        JSONObject payload = new JSONObject();
        JSONObject comm = new JSONObject();
        comm.put("ct", 11);
        comm.put("cv", "1003006");
        comm.put("v", "1003006");
        comm.put("uin", uinLong());
        payload.put("comm", comm);

        JSONObject req = new JSONObject();
        req.put("module", "music.search.SearchCgiService");
        req.put("method", "DoSearchForQQMusicDesktop");
        JSONObject p = new JSONObject();
        p.put("num_per_page", 30);
        p.put("page_num", 1);
        p.put("query", keyword);
        p.put("search_type", 0);
        req.put("param", p);
        payload.put("req_1", req);

        JSONObject d = musicu(payload, 15000);
        List<Song> out = new ArrayList<>();
        JSONObject body = d.optJSONObject("req_1");
        if (body == null) return out;
        JSONObject data = body.optJSONObject("data");
        if (data == null) return out;
        JSONObject songObj = data.optJSONObject("body");
        if (songObj == null) return out;
        JSONObject song = songObj.optJSONObject("song");
        if (song == null) return out;
        JSONArray list = song.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            Song s = normalize(x);
            if (!s.mid.isEmpty()) out.add(s);
        }
        return out;
    }

    public List<Playlist> hot() throws Exception {
        String url = CY + "/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd="
                + System.currentTimeMillis() + "&g_tk=5381&loginUin=0&hostUin=0&format=json"
                + "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
                + "&categoryId=10000000&sortId=5&sin=0&ein=15";
        JSONObject d = exec("GET", url, null, "https://y.qq.com/", 15000).json();

        List<Playlist> out = new ArrayList<>();
        JSONObject data = d.optJSONObject("data");
        if (data == null) return out;
        JSONArray list = data.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            Playlist p = new Playlist();
            p.id = x.optString("dissid", "");
            p.name = x.optString("dissname", "");
            p.pic = https(x.optString("imgurl", ""));
            p.desc = x.optString("introduction", "");
            if (!p.id.isEmpty()) out.add(p);
        }
        return out;
    }

    public List<Song> playlist(String id) throws Exception {
        JSONObject payload = new JSONObject();
        JSONObject comm = new JSONObject();
        comm.put("ct", 24);
        comm.put("cv", 0);
        comm.put("uin", uinLong());
        payload.put("comm", comm);

        JSONObject req = new JSONObject();
        req.put("module", "music.srfDissInfo.aiDissInfo");
        req.put("method", "uniform_get_Dissinfo");
        JSONObject p = new JSONObject();
        p.put("disstid", Long.parseLong(id));
        p.put("enc_host_uin", "");
        p.put("tag", 1);
        p.put("userinfo", 1);
        p.put("song_begin", 0);
        p.put("song_num", 300);
        p.put("onlysonglist", 0);
        req.put("param", p);
        payload.put("req", req);

        JSONObject d = musicu(payload, 20000);
        List<Song> out = new ArrayList<>();
        JSONObject reqObj = d.optJSONObject("req");
        if (reqObj == null) throw new QqException("", "歌单不存在或已删除");
        JSONObject data = reqObj.optJSONObject("data");
        if (data == null || data.optJSONObject("dirinfo") == null) {
            throw new QqException("", "歌单不存在或已删除");
        }
        JSONArray list = data.optJSONArray("songlist");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            Song s = normalize(x);
            if (!s.mid.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * 取播放地址。未登录时 QQ 音乐不再下发 purl，这里显式报错让界面提示去登录。
     */
    /** 音质前缀：C100=48k 流畅，C200=96k，C400=128k m4a */
    public static final String Q_LOW = "C100";
    public static final String Q_MID = "C200";

    public String songUrl(String mid) throws Exception {
        return songUrl(mid, null, null);
    }

    /**
     * 指定音质取地址。prefix 为空则用服务端默认。
     * 指定后如果这首歌没有该档次（返回空 purl），自动回退默认，避免下不了。
     */
    public String songUrl(String mid, String mediaMid, String prefix) throws Exception {
        if (prefix != null && mediaMid != null && !mediaMid.isEmpty()) {
            try {
                String url = fetchUrl(mid, prefix + mediaMid + ".m4a");
                if (url != null && !url.isEmpty()) return url;
            } catch (Exception ignored) {
            }
        }
        String url = fetchUrl(mid, null);
        if (url == null || url.isEmpty()) {
            if (!isLoggedIn()) throw new QqException("login_required", "登录后才能播放");
            throw new QqException("no_permission", "该歌曲无版权或需要会员");
        }
        return url;
    }

    private String fetchUrl(String mid, String filename) throws Exception {
        String u = uin();
        String guid = String.valueOf(1000000000L + (long) (Math.random() * 8000000000L));

        JSONObject payload = new JSONObject();
        JSONObject comm = new JSONObject();
        comm.put("uin", uinLong());
        comm.put("format", "json");
        comm.put("ct", 24);
        comm.put("cv", 0);
        comm.put("platform", "yqq");
        // authst 就是 qm_keyst，缺了它服务端会当成未登录，会员曲直接拒绝
        String authst = get("qm_keyst");
        if (authst.isEmpty()) authst = get("qqmusic_key");
        if (!authst.isEmpty()) comm.put("authst", authst);
        payload.put("comm", comm);

        JSONObject req = new JSONObject();
        req.put("module", "CDN.SrfCdnDispatchServer");
        req.put("method", "GetCdnDispatch");
        JSONObject p1 = new JSONObject();
        p1.put("guid", guid);
        p1.put("calltype", 0);
        p1.put("userip", "");
        req.put("param", p1);
        payload.put("req", req);

        JSONObject req0 = new JSONObject();
        req0.put("module", "vkey.GetVkeyServer");
        req0.put("method", "CgiGetVkey");
        JSONObject p0 = new JSONObject();
        p0.put("guid", guid);
        p0.put("songmid", new JSONArray().put(mid));
        p0.put("songtype", new JSONArray().put(0));
        p0.put("uin", u.isEmpty() ? "0" : u);
        p0.put("loginflag", 1);
        p0.put("platform", "20");
        if (filename != null) {
            JSONArray fn = new JSONArray();
            fn.put(filename);
            p0.put("filename", fn);
        }
        req0.put("param", p0);
        payload.put("req_0", req0);

        JSONObject d = musicu(payload, 15000);
        JSONObject r0 = d.optJSONObject("req_0");
        if (r0 == null) return "";
        JSONObject data = r0.optJSONObject("data");
        if (data == null) return "";

        JSONArray urls = data.optJSONArray("midurlinfo");
        String purl = "";
        if (urls != null && urls.length() > 0) {
            JSONObject info = urls.optJSONObject(0);
            if (info != null) purl = info.optString("purl", "");
        }
        if (purl.isEmpty()) return "";

        JSONArray sip = data.optJSONArray("sip");
        String host = sip != null && sip.length() > 0 ? sip.optString(0, "") : "";
        if (host.isEmpty()) {
            JSONObject reqData = d.optJSONObject("req");
            if (reqData != null) {
                JSONObject rd = reqData.optJSONObject("data");
                if (rd != null) {
                    JSONArray s2 = rd.optJSONArray("sip");
                    if (s2 != null && s2.length() > 0) host = s2.optString(0, "");
                }
            }
        }
        // 个别 CDN 调度直接返回绝对地址（http://... 或 https://...），
        // 这时再拼 host 会得到 "https://host/http://..." 这种无效地址，
        // 表现为「下载失败 + 弹一串链接」。
        String full;
        String p = purl.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) {
            full = p;
        } else if (host.isEmpty()) {
            full = "";                       // 没有 CDN 前缀，相对路径拼不出可用地址
        } else {
            String h = https(host.trim());
            if (!h.endsWith("/") && !p.startsWith("/")) h = h + "/";
            full = h + p;
        }

        if (!full.startsWith("http")) {
            lastUrl = "(无效) " + mask(host + purl);
            return "";
        }

        full = full.replace(" ", "%20");
        lastUrl = mask(full);
        return full;
    }

    public String lyric(String mid) throws Exception {
        String url = CY + "/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + enc(mid)
                + "&format=json&nobase64=1&g_tk=" + hash33(get("qm_keyst"));
        Resp r = exec("GET", url, null, "https://y.qq.com/portal/player.html", 15000);
        try {
            return r.json().optString("lyric", "");
        } catch (Exception e) {
            return "";
        }
    }

    private JSONObject withAuth() throws Exception {
        JSONObject comm = new JSONObject();
        comm.put("ct", 24);
        comm.put("cv", 0);
        comm.put("uin", uinLong());
        comm.put("platform", "yqq");
        comm.put("format", "json");
        String authst = get("qm_keyst");
        if (authst.isEmpty()) authst = get("qqmusic_key");
        if (!authst.isEmpty()) comm.put("authst", authst);
        return comm;
    }

    /** 原始用户信息，诊断用（截断） */
    private String lastUserRaw = "";

    public String lastUserRaw() {
        return lastUserRaw;
    }

    /**
     * 按实际类型取值。org.json 的 optInt 遇到 boolean 会返回 0，
     * 导致 "isVip": true 被判成非会员 —— 这是之前显示「会员否」的原因。
     */
    private static boolean truthy(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() > 0;
        if (v instanceof String) {
            String t = ((String) v).trim();
            return !t.isEmpty() && !"0".equals(t) && !"false".equalsIgnoreCase(t)
                    && !"null".equalsIgnoreCase(t);
        }
        return false;
    }

    /** 从任意一个用户信息返回体里尽量抠出昵称/头像/VIP */
    private boolean parseUser(JSONObject data, User u) {
        if (data == null) return false;
        String nick = data.optString("nick", data.optString("nickname", ""));
        if (nick.isEmpty()) return false;

        u.nick = nick;
        u.avatar = https(data.optString("headurl",
                data.optString("avatarUrl", data.optString("head_url", ""))));

        String raw = data.toString();
        lastUserRaw = raw.length() > 700 ? raw.substring(0, 700) + "..." : raw;

        u.vip = detectVip(data);
        return true;
    }

    private boolean detectVip(JSONObject data) {
        // 1) 常见字段名，按真实类型判断
        for (String k : new String[]{"isVip", "vip", "vipFlag", "vipType", "greenVip",
                "yearVip", "isGreenVip", "isYearVip", "svip", "isSvip", "vipStatus"}) {
            if (truthy(data, k)) return true;
        }

        // 2) 通用扫描：任何带 vip 的键只要为真就算
        for (java.util.Iterator<String> it = data.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.toLowerCase().contains("vip")) continue;
            if (truthy(data, k)) return true;
        }

        // 3) 嵌套：vipInfo / iconList（绿钻图标）
        JSONObject vipInfo = data.optJSONObject("vipInfo");
        if (vipInfo != null) {
            for (java.util.Iterator<String> it = vipInfo.keys(); it.hasNext(); ) {
                String k = it.next();
                if (truthy(vipInfo, k)) return true;
            }
        }
        JSONArray icons = data.optJSONArray("iconList");
        if (icons != null) {
            for (int i = 0; i < icons.length(); i++) {
                JSONObject ic = icons.optJSONObject(i);
                if (ic == null) continue;
                String t = ic.optString("type", "") + ic.optString("title", "")
                        + ic.optString("tag", "");
                if (t.toLowerCase().contains("vip") || t.contains("绿钻")
                        || t.contains("会员")) {
                    return true;
                }
            }
        }
        return false;
    }

    public User userInfo() {
        User u = new User();
        u.uin = uin();
        if (!isLoggedIn()) return u;

        // 1) GetLoginUserInfo —— 通常够用
        try {
            JSONObject payload = new JSONObject();
            payload.put("comm", withAuth());

            JSONObject a = new JSONObject();
            a.put("module", "music.UserInfo.userInfoServer");
            a.put("method", "GetLoginUserInfo");
            a.put("param", new JSONObject());
            payload.put("req", a);

            JSONObject b = new JSONObject();
            b.put("module", "music.UserInfo.userInfoServer");
            b.put("method", "GetUserInfo");
            JSONObject bp = new JSONObject();
            bp.put("uin", uinLong());
            b.put("param", bp);
            payload.put("req_2", b);

            JSONObject d = musicu(payload, 12000);
            JSONObject r1 = d.optJSONObject("req");
            if (r1 != null && parseUser(r1.optJSONObject("data"), u)) return u;
            JSONObject r2 = d.optJSONObject("req_2");
            if (r2 != null && parseUser(r2.optJSONObject("data"), u)) return u;
        } catch (Exception ignored) {
        }

        // 2) 兜底：老版个人主页接口，微信登录用户有时只有这个能出数据
        try {
            String url = CY + "/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?cid=205360838"
                    + "&userid=" + uin() + "&reqfrom=1&g_tk=" + hash33(get("qm_keyst"));
            JSONObject d = exec("GET", url, null, "https://y.qq.com/", 12000).json();
            JSONObject data = d.optJSONObject("data");
            if (data != null) {
                JSONObject creator = data.optJSONObject("creator");
                if (creator != null && parseUser(creator, u)) return u;
            }
        } catch (Exception ignored) {
        }

        if (u.nick.isEmpty() && !u.uin.isEmpty()) u.nick = "已登录";
        return u;
    }

    /* ---------------------------- 我的收藏歌单 ---------------------------- */

    /** 我创建/收藏的歌单，第一条通常是「我喜欢」 */
    public List<Playlist> myPlaylists() throws Exception {
        List<Playlist> out = new ArrayList<>();
        if (!isLoggedIn() || uin().isEmpty()) return out;

        String url = CY + "/rsc/fcgi-bin/fcg_user_created_diss?hostuin=" + uin()
                + "&sin=0&size=40&format=json&g_tk=" + hash33(get("qm_keyst"));
        JSONObject d = exec("GET", url, null, "https://y.qq.com/", 15000).json();

        JSONObject data = d.optJSONObject("data");
        if (data == null) return out;
        JSONArray list = data.optJSONArray("disslist");
        if (list == null) return out;

        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            Playlist p = new Playlist();
            p.id = String.valueOf(x.optLong("dissid", x.optLong("tid", 0)));
            p.name = x.optString("diss_name", x.optString("dissname", ""));
            p.pic = https(x.optString("diss_cover", ""));
            p.count = x.optInt("song_cnt", 0);
            if (!p.id.equals("0") && !p.name.isEmpty()) out.add(p);
        }
        return out;
    }

    /** 云端「我喜欢」里的歌 */
    public List<Song> favSongs() throws Exception {
        List<Song> songs = new ArrayList<>();
        if (!isLoggedIn()) return songs;

        List<Playlist> mine = myPlaylists();
        Playlist target = null;
        for (Playlist p : mine) {
            if (p.name.contains("喜欢")) {
                target = p;
                break;
            }
        }
        if (target == null && !mine.isEmpty()) target = mine.get(0);
        if (target == null) return songs;

        return playlist(target.id);
    }

    /* ---------------------------- 云端收藏写入 ---------------------------- */

    /**
     * 收藏 / 取消收藏到「我喜欢」（dirid=201）。
     * 新老两套接口都试一遍：新的是 musicu 模块，老的是 c.y.qq.com 的 fcg_*，谁通算谁。
     * 拿不到确定结果时返回 false，由界面提示用户。
     */
    public boolean addFav(String mid) {
        if (!isLoggedIn()) return false;

        try {
            JSONObject payload = new JSONObject();
            payload.put("comm", withAuth());

            JSONObject req = new JSONObject();
            req.put("module", "music.srfDissInfo.DissInfo");
            req.put("method", "CgiAddSonglist");

            JSONObject p = new JSONObject();
            p.put("dirid", 201);
            JSONArray arr = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("mid", mid);
            one.put("type", 0);
            arr.put(one);
            p.put("songinfo", arr);
            req.put("param", p);
            payload.put("req", req);

            JSONObject d = musicu(payload, 12000);
            JSONObject r = d.optJSONObject("req");
            if (r != null && r.optInt("code", -1) == 0) return true;
        } catch (Exception ignored) {
        }

        try {
            String url = CY + "/fav/fcgi-bin/fcg_addfav_song.fcg?songmid=" + enc(mid)
                    + "&type=1&uin=" + uin() + "&g_tk=" + hash33(get("qm_keyst"))
                    + "&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq";
            JSONObject d = exec("GET", url, null, "https://y.qq.com/", 12000).json();
            return d.optInt("code", -1) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean delFav(String mid) {
        if (!isLoggedIn()) return false;

        try {
            JSONObject payload = new JSONObject();
            payload.put("comm", withAuth());

            JSONObject req = new JSONObject();
            req.put("module", "music.srfDissInfo.DissInfo");
            req.put("method", "CgiDelSonglist");

            JSONObject p = new JSONObject();
            p.put("dirid", 201);
            JSONArray arr = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("mid", mid);
            one.put("type", 0);
            arr.put(one);
            p.put("songinfo", arr);
            req.put("param", p);
            payload.put("req", req);

            JSONObject d = musicu(payload, 12000);
            JSONObject r = d.optJSONObject("req");
            if (r != null && r.optInt("code", -1) == 0) return true;
        } catch (Exception ignored) {
        }

        try {
            String url = CY + "/fav/fcgi-bin/fcg_delfav_song.fcg?songmid=" + enc(mid)
                    + "&uin=" + uin() + "&g_tk=" + hash33(get("qm_keyst"))
                    + "&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq";
            JSONObject d = exec("GET", url, null, "https://y.qq.com/", 12000).json();
            return d.optInt("code", -1) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 诊断用：当前会话关键信息，方便反馈问题 */
    public String diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("uin: ").append(uin().isEmpty() ? "(空)" : uin()).append('\n');
        sb.append("登录态: ").append(isLoggedIn() ? "是" : "否").append('\n');
        sb.append("qm_keyst: ").append(get("qm_keyst").isEmpty() ? "(空)" : "有").append('\n');
        sb.append("Cookie 数: ").append(cookies.size()).append('\n');
        User u = userInfo();
        sb.append("昵称: ").append(u.nick.isEmpty() ? "(未取到)" : u.nick).append('\n');
        sb.append("VIP: ").append(u.vip ? "是" : "否").append('\n');
        sb.append("最近音源: ").append(lastUrl.isEmpty() ? "(未取过)" : lastUrl).append('\n');
        sb.append("userInfo: ").append(lastUserRaw.isEmpty() ? "(未取到)" : lastUserRaw);
        return sb.toString();
    }

    /* ============================== QQ 扫码登录 ============================== */

    public Qr startQqLogin() throws Exception {
        String url = "https://ptlogin2.qq.com/ptqrshow?appid=" + LOGIN_APPID
                + "&e=2&l=M&s=3&d=72&v=4&t=" + System.currentTimeMillis()
                + "&daid=" + LOGIN_DAID + "&pt_3rd_aid=" + LOGIN_AID3;
        Resp r = exec("GET", url, null, "https://xui.ptlogin2.qq.com/", 15000);
        if (r.status != 200 || r.body.length < 100) throw new QqException("", "二维码获取失败");

        qqQrsig = get("qrsig");
        if (qqQrsig.isEmpty()) throw new QqException("", "二维码获取失败");

        Qr qr = new Qr();
        qr.image = java.util.Base64.getEncoder().encodeToString(r.body);
        qr.tip = "打开手机 QQ 扫一扫";
        return qr;
    }

    /** 返回 waiting / scanned / expired / refused / ok */
    public String pollQqLogin() throws Exception {
        if (qqQrsig.isEmpty()) throw new QqException("", "请先获取二维码");
        String u1 = enc("https://graph.qq.com/oauth2.0/login_jump");
        String url = "https://ptlogin2.qq.com/ptqrlogin?u1=" + u1
                + "&ptqrtoken=" + hash33(qqQrsig)
                + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-"
                + System.currentTimeMillis()
                + "&js_ver=20102616&js_type=1&login_sig=&pt_uistyle=40&aid=" + LOGIN_APPID
                + "&daid=" + LOGIN_DAID + "&pt_3rd_aid=" + LOGIN_AID3 + "&has_onekey=1";

        Resp r = exec("GET", url, null, "https://xui.ptlogin2.qq.com/", 15000);
        String txt = r.text();
        int start = txt.indexOf("ptuiCB(");
        if (start < 0) return "waiting";

        String args = txt.substring(start + 7);
        int end = args.indexOf(')');
        if (end > 0) args = args.substring(0, end);
        String[] parts = args.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim().replaceAll("^'", "").replaceAll("'$", "");
        }
        String code = parts.length > 0 ? parts[0] : "";
        String redirect = parts.length > 2 ? parts[2] : "";

        if ("66".equals(code)) return "waiting";
        if ("65".equals(code)) return "scanned";
        if ("67".equals(code)) return "expired";
        if ("68".equals(code)) return "refused";
        if ("0".equals(code)) {
            follow(redirect, 6);
            // 补一次首页，qqmusic_key 这一步才下发
            try {
                exec("GET", "https://y.qq.com/", null, "https://y.qq.com/", 15000);
            } catch (Exception ignored) {
            }
            if (!isLoggedIn()) throw new QqException("", "登录成功但未拿到凭证，请重试");
            // 缓存里解析出来的 uin 可能不准，登录后以 cookie 为准重新算一次
            loginUin = "";
            uin();
            qqQrsig = "";
            return "ok";
        }
        return "waiting";
    }

    /* ============================== 微信扫码登录 ============================== */

    public Qr startWxLogin() throws Exception {
        String url = "https://open.weixin.qq.com/connect/qrconnect?appid=" + WX_APPID
                + "&redirect_uri=" + enc(WX_REDIRECT)
                + "&response_type=code&scope=snsapi_login&state=STATE";
        Resp r = exec("GET", url, null, "https://y.qq.com/", 15000);
        String html = r.text();

        int idx = html.indexOf("/connect/qrcode/");
        if (idx < 0) throw new QqException("", "微信二维码获取失败");
        String rest = html.substring(idx + "/connect/qrcode/".length());
        StringBuilder uuid = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') uuid.append(ch);
            else break;
        }
        if (uuid.length() < 8) throw new QqException("", "微信二维码获取失败");
        wxUuid = uuid.toString();

        Resp img = exec("GET", "https://open.weixin.qq.com/connect/qrcode/" + wxUuid,
                null, "https://open.weixin.qq.com/", 15000);
        Qr qr = new Qr();
        qr.image = java.util.Base64.getEncoder().encodeToString(img.body);
        qr.tip = "打开微信扫一扫";
        return qr;
    }

    public String pollWxLogin() throws Exception {
        if (wxUuid.isEmpty()) throw new QqException("", "请先获取二维码");
        String url = "https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=" + wxUuid
                + "&_=" + System.currentTimeMillis();

        String txt;
        try {
            // 微信是长轮询，无变化要等 15 秒；主动 4.5 秒断开，交给下一轮
            txt = exec("GET", url, null, "https://open.weixin.qq.com/", 4500).text();
        } catch (java.net.SocketTimeoutException e) {
            return "waiting";
        }

        int errAt = txt.indexOf("wx_errcode=");
        String err = "";
        if (errAt >= 0) {
            int p = errAt + "wx_errcode=".length();
            int q = p;
            while (q < txt.length() && Character.isDigit(txt.charAt(q))) q++;
            err = txt.substring(p, q);
        }
        String code = "";
        int cAt = txt.indexOf("wx_code='");
        if (cAt >= 0) {
            int p = cAt + "wx_code='".length();
            int q = txt.indexOf('\'', p);
            if (q > p) code = txt.substring(p, q);
        }

        if ("408".equals(err)) return "waiting";
        if ("404".equals(err)) return "scanned";
        if ("403".equals(err)) return "expired";
        if ("405".equals(err) && !code.isEmpty()) {
            wxLogin(code);
            wxUuid = "";
            return "ok";
        }
        return "waiting";
    }

    private void wxLogin(String code) throws Exception {
        JSONObject payload = new JSONObject();
        JSONObject comm = new JSONObject();
        comm.put("g_tk", 5381);
        comm.put("platform", "yqq");
        comm.put("ct", 24);
        comm.put("cv", 0);
        comm.put("tmeAppID", "qqmusic");
        comm.put("tmeLoginType", "1");
        payload.put("comm", comm);

        JSONObject req = new JSONObject();
        req.put("module", "music.login.LoginServer");
        req.put("method", "Login");
        JSONObject p = new JSONObject();
        p.put("strAppid", WX_APPID);
        p.put("code", code);
        p.put("onlyopenid", JSONObject.NULL);
        req.put("param", p);
        payload.put("req", req);

        Resp r = exec("POST", MUSICU, payload.toString(), "https://y.qq.com/", 15000);
        JSONObject d;
        try {
            d = r.json();
        } catch (Exception e) {
            throw new QqException("", "微信登录返回异常");
        }
        JSONObject reqObj = d.optJSONObject("req");
        boolean ok = d.optInt("code", -1) == 0 && reqObj != null && reqObj.optInt("code", -1) == 0;
        if (ok) {
            JSONObject data = reqObj.optJSONObject("data");
            if (data != null) {
                setLoginUin(String.valueOf(data.optLong("uin", 0)));
                if (loginUin.equals("0")) loginUin = "";
            }
        }
        if (!ok) {
            String msg = reqObj == null ? "微信登录失败" : reqObj.optString("msg", "微信登录失败");
            throw new QqException("", msg);
        }
        if (!isLoggedIn()) throw new QqException("", "微信授权成功，但未换取到播放凭证");
    }
}
