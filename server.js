'use strict';
/**
 * 手表版 QQ 音乐 —— 单端口轻服务
 *
 * 为什么需要它：
 *   1. 浏览器直连 c.y.qq.com / u.y.qq.com 会被 CORS 拦下；
 *   2. 扫码登录要靠服务端保存 qrsig 与登录 Cookie，前端拿不到也存不住；
 *   3. 手表 WebView 容量小，静态资源在这里合并托管。
 *
 * 启动：node server.js   （PORT 可覆盖，默认 3000）
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const qq = require('./qq');

const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || '0.0.0.0';
const PUBLIC = path.join(__dirname, 'public');

/* ------------------------------- 会话存储 -------------------------------- */

const sessions = new Map(); // sid -> { jar, token, user, ts }
const SESSION_TTL = 4 * 3600 * 1000;
const MAX_SESSIONS = 200;

function newSession() {
  const sid = crypto.randomBytes(16).toString('hex');
  sessions.set(sid, { jar: new qq.Jar(), token: 0, user: null, ts: Date.now() });
  if (sessions.size > MAX_SESSIONS) {
    // 淘汰最久未活跃的会话，避免长时间运行内存膨胀
    const oldest = [...sessions].sort((a, b) => a[1].ts - b[1].ts)[0];
    if (oldest) sessions.delete(oldest[0]);
  }
  return sid;
}

setInterval(() => {
  const now = Date.now();
  for (const [sid, s] of sessions) if (now - s.ts > SESSION_TTL) sessions.delete(sid);
}, 10 * 60 * 1000).unref();

function pickSession(req, res) {
  const cookies = Object.fromEntries(
    (req.headers.cookie || '')
      .split(';')
      .map((c) => c.trim().split('='))
      .filter((x) => x.length === 2)
  );
  let sid = cookies.wm_sid;
  if (!sid || !sessions.has(sid)) {
    sid = newSession();
    res.setHeader('Set-Cookie', `wm_sid=${sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age=14400`);
  }
  const s = sessions.get(sid);
  s.ts = Date.now();
  return s;
}

/* ------------------------------- 简易限流 -------------------------------- */

const hits = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const rec = hits.get(ip) || { n: 0, t: now };
  if (now - rec.t > 60000) {
    rec.n = 0;
    rec.t = now;
  }
  rec.n++;
  hits.set(ip, rec);
  if (hits.size > 500) hits.clear();
  return rec.n > 180;
}

/* -------------------------------- 工具 ---------------------------------- */

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
};

function json(res, obj, status = 200) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function serveStatic(req, res, pathname) {
  const rel = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
  const file = path.resolve(PUBLIC, rel);
  // 防目录穿越
  if (!file.startsWith(PUBLIC + path.sep) && file !== PUBLIC) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.readFile(file, (err, buf) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found');
      return;
    }
    const ext = path.extname(file).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=3600',
      'Content-Length': buf.length,
    });
    res.end(buf);
  });
}

/* ------------------------------ 音频中转 -------------------------------- */

const AUDIO_HOST_OK = /(^|\.)(qqmusic\.qq\.com|qqmusic\.com|qq\.com|gtimg\.cn|tencentmusic\.com)$/i;

/**
 * 直链播放被 CDN 拒绝时的兜底：服务端流式转发，透传 Range 以便拖动进度。
 * 只允许放过 QQ 音乐自家域名，避免被当成任意代理（SSRF）。
 */
async function proxyAudio(req, res, url) {
  const raw = url.searchParams.get('u') || '';
  let target;
  try {
    target = new URL(raw);
  } catch {
    return json(res, { ok: false, msg: 'bad url' }, 400);
  }
  if (!/^https?:$/.test(target.protocol) || !AUDIO_HOST_OK.test(target.hostname)) {
    return json(res, { ok: false, msg: 'domain not allowed' }, 403);
  }

  const headers = {
    'User-Agent':
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    Referer: 'https://y.qq.com/',
  };
  if (req.headers.range) headers.Range = req.headers.range;

  const upstream = await fetch(target.href, { headers, redirect: 'follow', signal: AbortSignal.timeout(20000) });

  const out = {
    'Content-Type': upstream.headers.get('content-type') || 'audio/mp4',
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
  };
  const len = upstream.headers.get('content-length');
  const range = upstream.headers.get('content-range');
  if (len) out['Content-Length'] = len;
  if (range) out['Content-Range'] = range;

  res.writeHead(upstream.status === 200 || upstream.status === 206 ? upstream.status : 502, out);
  if (!upstream.body) return res.end();
  const { Readable } = require('stream');
  Readable.fromWeb(upstream.body).pipe(res);
}

/* -------------------------------- 路由 ---------------------------------- */

async function handleApi(req, res, url, session) {
  const q = url.searchParams;
  const { jar } = session;

  switch (url.pathname) {
    /* 当前登录态 */
    case '/api/session': {
      const loggedIn = qq.isLoggedIn(jar);
      if (loggedIn && !session.user) session.user = await qq.getUserInfo(jar);
      return json(res, { ok: true, loggedIn, user: loggedIn ? session.user : null });
    }

    /* 生成二维码 */
    case '/api/login/qr': {
      const qr = await qq.createQR(jar);
      session.token = qr.token;
      return json(res, { ok: true, image: qr.image });
    }

    /* 轮询扫码结果 */
    case '/api/login/check': {
      if (!session.token) return json(res, { ok: false, msg: '请先获取二维码' }, 400);
      const r = await qq.pollQR(jar, session.token);
      if (r.state === 'ok') {
        session.user = await qq.getUserInfo(jar);
        session.token = 0;
        return json(res, { ok: true, state: 'ok', msg: '登录成功', user: session.user });
      }
      if (r.state === 'expired' || r.state === 'refused') session.token = 0;
      return json(res, { ok: true, state: r.state, msg: r.msg });
    }

    /* 微信扫码：生成二维码 */
    case '/api/login/wx/qr': {
      const qr = await qq.createWxQR(jar);
      session.wxUuid = qr.uuid;
      return json(res, { ok: true, image: qr.image });
    }

    /* 微信扫码：轮询状态 */
    case '/api/login/wx/check': {
      if (!session.wxUuid) return json(res, { ok: false, msg: '请先获取二维码' }, 400);
      const r = await qq.pollWxQR(session.wxUuid, jar);
      if (r.state === 'ok') {
        session.user = await qq.getUserInfo(jar);
        session.wxUuid = 0;
        return json(res, { ok: true, ...r, user: session.user });
      }
      if (r.state === 'expired') session.wxUuid = 0;
      return json(res, { ok: true, ...r });
    }

    case '/api/logout': {
      const sid = req.headers.cookie?.match(/wm_sid=([^;]+)/)?.[1];
      if (sid) sessions.delete(sid);
      return json(res, { ok: true });
    }

    /* 搜索 */
    case '/api/search': {
      const w = (q.get('w') || '').trim();
      if (!w) return json(res, { ok: false, msg: '请输入关键词' }, 400);
      const list = await qq.search(w, Number(q.get('p') || 1), Number(q.get('n') || 20), jar);
      return json(res, { ok: true, list });
    }

    /* 播放地址 */
    case '/api/url': {
      const mid = q.get('mid');
      if (!mid) return json(res, { ok: false, msg: '缺少 mid' }, 400);
      const r = await qq.getSongUrl(mid, jar);
      return json(res, r.ok ? { ok: true, ...r } : { ok: false, code: r.code, msg: r.msg });
    }

    /* 歌词 */
    case '/api/lyric': {
      const mid = q.get('mid');
      if (!mid) return json(res, { ok: false, msg: '缺少 mid' }, 400);
      return json(res, { ok: true, ...(await qq.getLyric(mid, jar)) });
    }

    /* 热门歌单 */
    case '/api/hot': {
      return json(res, { ok: true, list: await qq.hotPlaylists(Number(q.get('n') || 12)) });
    }

    /* 歌单详情 */
    case '/api/playlist': {
      const id = q.get('id');
      if (!id) return json(res, { ok: false, msg: '缺少歌单 id' }, 400);
      return json(res, { ok: true, data: await qq.playlistDetail(id, jar) });
    }

    /* 我的歌单 */
    case '/api/my/playlists': {
      if (!qq.isLoggedIn(jar)) return json(res, { ok: false, msg: '请先登录' }, 401);
      return json(res, { ok: true, list: await qq.myPlaylists(jar) });
    }

    default:
      return json(res, { ok: false, msg: 'not found' }, 404);
  }
}

const server = http.createServer(async (req, res) => {
  const ip = req.socket.remoteAddress || 'unknown';
  if (rateLimited(ip)) return json(res, { ok: false, msg: '请求过于频繁' }, 429);

  let url;
  try {
    url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  } catch {
    return json(res, { ok: false, msg: 'bad request' }, 400);
  }

  if (!url.pathname.startsWith('/api/')) return serveStatic(req, res, url.pathname);

  const session = pickSession(req, res);
  try {
    if (url.pathname === '/api/stream') return await proxyAudio(req, res, url);
    await handleApi(req, res, url, session);
  } catch (e) {
    const msg = e?.name === 'TimeoutError' ? '请求超时，检查网络' : e.message || '服务异常';
    if (!res.headersSent) json(res, { ok: false, msg }, 502);
    else res.end();
  }
});

server.listen(PORT, HOST, () => {
  console.log(`[watch-qqmusic] http://${HOST}:${PORT}  (node ${process.version})`);
});
