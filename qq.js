'use strict';
/**
 * QQ 音乐 Web 接口适配层（零第三方依赖，仅用 Node 内置 fetch）
 *
 * 设计原则：
 *  1. 每个会话一个 CookieJar，扫码登录后的登录态只存在于内存，不落盘。
 *  2. 所有函数都返回扁平结构，前端不接触 QQ 音乐原始 JSON 的字段嵌套。
 *  3. 接口失败一律 throw 带 code 的错误，由 server.js 转成 {ok:false, msg}。
 */

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

const HOST = 'https://u.y.qq.com/cgi-bin/musicu.fcg';
const C = 'https://c.y.qq.com';

/* ------------------------------- Cookie 罐 ------------------------------- */

class Jar {
  constructor(init) {
    this.map = new Map(init ? Object.entries(init) : []);
  }
  setFromResponse(res) {
    const list = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
    for (const raw of list) {
      const [pair] = raw.split(';');
      const i = pair.indexOf('=');
      if (i < 0) continue;
      const k = pair.slice(0, i).trim();
      const v = pair.slice(i + 1).trim();
      if (k) this.map.set(k, v);
    }
  }
  get(k) {
    return this.map.get(k) || '';
  }
  header() {
    return [...this.map].map(([k, v]) => `${k}=${v}`).join('; ');
  }
  dump() {
    return Object.fromEntries(this.map);
  }
  get uin() {
    // uin 可能是 o 开头的加密值，真实 uin 在 qm_keyst 的登录态里另有字段，故优先取纯数字
    for (const k of ['uin', 'p_uin', 'wxuin']) {
      const raw = this.get(k);
      const v = raw.replace(/^o0?/, '');
      if (/^\d+$/.test(raw)) return raw;
      if (/^\d+$/.test(v)) return v;
    }
    return '';
  }
}

/* --------------------------------- 工具 --------------------------------- */

// 腾讯经典 hash33，用于 ptqrtoken 与 g_tk
function hash33(str) {
  let e = 0;
  for (let i = 0; i < str.length; i++) e += (e << 5) + str.charCodeAt(i);
  return e & 0x7fffffff;
}

function guid() {
  return String(Math.floor(Math.random() * 9e9) + 1e9);
}

function picUrl(albumMid, size = 150) {
  if (!albumMid) return '';
  return `https://y.gtimg.cn/music/photo_new/T002R${size}x${size}M000${albumMid}.jpg?max_age=2592000`;
}

// QQ 音乐 CDN 返回 http:// ，页面走 https 会被混合内容拦截，统一升级
function httpsUrl(u) {
  if (!u) return '';
  return u.replace(/^http:\/\//i, 'https://');
}

async function request(
  url,
  { method = 'GET', body, jar, headers = {}, referer = 'https://y.qq.com/', timeout = 15000 } = {}
) {
  const h = {
    'User-Agent': UA,
    Referer: referer,
    'Accept-Language': 'zh-CN,zh;q=0.9',
    ...headers,
  };
  if (jar && jar.header()) h.Cookie = jar.header();
  if (body && !h['Content-Type']) h['Content-Type'] = 'application/json';

  const res = await fetch(url, {
    method,
    headers: h,
    body: typeof body === 'string' ? body : body ? JSON.stringify(body) : undefined,
    redirect: 'manual',
    signal: AbortSignal.timeout(timeout),
  });
  if (jar) jar.setFromResponse(res);
  return res;
}

// 跟随 3xx，逐跳收 cookie（QQ 登录 check_sig 必须这样走）
async function follow(url, jar, maxHop = 6) {
  let cur = url;
  for (let i = 0; i < maxHop; i++) {
    const res = await request(cur, { jar });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get('location');
      if (!loc) break;
      cur = new URL(loc, cur).href;
      continue;
    }
    return { res, url: cur };
  }
  return { res: null, url: cur };
}

async function musicu(payload, jar) {
  const res = await request(HOST, {
    method: 'POST',
    body: payload,
    jar,
    headers: { Origin: 'https://y.qq.com' },
  });
  const txt = await res.text();
  try {
    return JSON.parse(txt);
  } catch {
    throw new Error('QQ 音乐返回了非 JSON 内容（可能被风控拦截）');
  }
}

/* -------------------------------- 扫码登录 ------------------------------- */

const LOGIN_APPID = '716027609';
const LOGIN_DAID = '383';
const LOGIN_AID3 = '100497308';

/**
 * 生成登录二维码：返回 key(用于轮询)、qrsig、图片 base64
 */
async function createQR(jar) {
  const url =
    `https://ptlogin2.qq.com/ptqrshow?appid=${LOGIN_APPID}&e=2&l=M&s=3&d=72&v=4` +
    `&t=${Date.now()}&daid=${LOGIN_DAID}&pt_3rd_aid=${LOGIN_AID3}`;
  const res = await request(url, { jar, referer: 'https://xui.ptlogin2.qq.com/' });
  const buf = Buffer.from(await res.arrayBuffer());
  const qrsig = jar.get('qrsig');
  if (!qrsig) throw new Error('未获取到 qrsig，二维码生成失败');
  return {
    qrsig,
    token: hash33(qrsig),
    image: 'data:image/png;base64,' + buf.toString('base64'),
  };
}

/**
 * 轮询扫码状态
 * 返回 { state: 'waiting'|'scanned'|'expired'|'ok'|'refused', msg, nickname }
 */
async function pollQR(jar, token) {
  const u1 = encodeURIComponent('https://graph.qq.com/oauth2.0/login_jump');
  const url =
    `https://ptlogin2.qq.com/ptqrlogin?u1=${u1}&ptqrtoken=${token}&ptredirect=0&h=1&t=1&g=1` +
    `&from_ui=1&ptlang=2052&action=0-0-${Date.now()}&js_ver=20102616&js_type=1&login_sig=` +
    `&pt_uistyle=40&aid=${LOGIN_APPID}&daid=${LOGIN_DAID}&pt_3rd_aid=${LOGIN_AID3}&has_onekey=1`;
  const res = await request(url, {
    jar,
    referer: 'https://xui.ptlogin2.qq.com/',
    headers: { Cookie: `qrsig=${jar.get('qrsig')}` },
  });
  const txt = await res.text();
  const m = txt.match(/ptuiCB\(([^)]*)\)/);
  if (!m) return { state: 'waiting', msg: '等待扫码' };

  const args = m[1].split(',').map((s) => s.trim().replace(/^'|'$/g, ''));
  const [code, , redirect, , msg, nickname] = args;

  if (code === '66') return { state: 'waiting', msg: '请用手机 QQ 扫码' };
  if (code === '65') return { state: 'scanned', msg: '已扫码，请在手机上确认' };
  if (code === '67') return { state: 'expired', msg: '二维码已失效' };
  if (code === '68') return { state: 'refused', msg: '已取消登录' };
  if (code === '0') {
    // 拿到 check_sig 地址，跟随它换取正式登录态 cookie
    await follow(redirect, jar);
    // 补一次 y.qq.com 首页，部分 cookie(qqmusic_key) 在这一步下发
    await follow('https://y.qq.com/', jar).catch(() => {});
    const uin = jar.uin;
    return { state: 'ok', msg: '登录成功', nickname: nickname || '', uin };
  }
  return { state: 'waiting', msg: msg || '等待扫码' };
}

/* -------------------------------- 微信登录 ------------------------------- */

const WX_APPID = 'wx48db31d50e334801';
const WX_SURL = 'https://y.qq.com/';
const WX_REDIRECT = `https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=${encodeURIComponent(WX_SURL)}`;

/**
 * 生成微信扫码登录二维码。
 * 走 QQ 音乐网页端同一个 appid 与回调地址，扫码后由 music.login.LoginServer.Login 换票。
 */
async function createWxQR(jar) {
  const url =
    `https://open.weixin.qq.com/connect/qrconnect?appid=${WX_APPID}` +
    `&redirect_uri=${encodeURIComponent(WX_REDIRECT)}` +
    `&response_type=code&scope=snsapi_login&state=STATE`;
  const res = await request(url, { jar, referer: 'https://y.qq.com/' });
  const html = await res.text();
  // 二维码地址形如 /connect/qrcode/{uuid}，比解析 JS 变量稳
  const m = html.match(/\/connect\/qrcode\/([A-Za-z0-9_-]+)/);
  if (!m) throw new Error('微信二维码获取失败，接口可能已变');
  const uuid = m[1];

  const img = await request(`https://open.weixin.qq.com/connect/qrcode/${uuid}`, {
    jar,
    referer: 'https://open.weixin.qq.com/',
  });
  const buf = Buffer.from(await img.arrayBuffer());
  return { uuid, image: 'data:image/jpeg;base64,' + buf.toString('base64') };
}

// 用微信返回的 code 换 QQ 音乐登录态
async function wxLogin(code, jar) {
  const res = await request(HOST, {
    method: 'POST',
    jar,
    // 网页端这个请求用的就是 form 类型，虽然 body 是 JSON
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: JSON.stringify({
      comm: { g_tk: 5381, platform: 'yqq', ct: 24, cv: 0, tmeAppID: 'qqmusic', tmeLoginType: '1' },
      req: {
        module: 'music.login.LoginServer',
        method: 'Login',
        param: { strAppid: WX_APPID, code, onlyopenid: null },
      },
    }),
  });
  let d;
  try {
    d = JSON.parse(await res.text());
  } catch {
    throw new Error('微信登录返回异常');
  }
  if (d?.code !== 0 || d?.req?.code !== 0) {
    throw new Error(d?.req?.msg || d?.req?.data?.msg || '微信登录失败');
  }
  return d.req.data || {};
}

/**
 * 轮询扫码状态。微信这个接口是长轮询，无变化时约 15 秒才返回，
 * 所以服务端主动 4.5 秒断开，交给前端下一轮继续问，避免手表干等。
 */
async function pollWxQR(uuid, jar) {
  let txt = '';
  try {
    const res = await request(
      `https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=${uuid}&_=${Date.now()}`,
      { jar, referer: 'https://open.weixin.qq.com/', timeout: 4500 }
    );
    txt = await res.text();
  } catch (e) {
    if (e.name === 'TimeoutError' || e.name === 'AbortError') {
      return { state: 'waiting', msg: '请用微信扫一扫' };
    }
    throw e;
  }

  const err = Number((txt.match(/wx_errcode=(\d+)/) || [])[1] || 0);
  const code = (txt.match(/wx_code='([^']*)'/) || [])[1] || '';

  if (err === 408) return { state: 'waiting', msg: '请用微信扫一扫' };
  if (err === 404) return { state: 'scanned', msg: '已扫码，请在手机上确认' };
  if (err === 403) return { state: 'expired', msg: '二维码已失效' };
  if (err === 405 && code) {
    await wxLogin(code, jar);
    if (!isLoggedIn(jar)) throw new Error('微信授权成功，但未换取到播放凭证');
    return { state: 'ok', msg: '登录成功', uin: jar.uin };
  }
  return { state: 'waiting', msg: '等待扫码' };
}

async function getUserInfo(jar) {
  const uin = jar.uin;
  try {
    const d = await musicu(
      {
        comm: { ct: 24, cv: 0, uin: Number(uin) || 0 },
        req: { module: 'music.UserInfo.userInfoServer', method: 'GetLoginUserInfo', param: {} },
      },
      jar
    );
    const data = d?.req?.data || {};
    if (data.nick || data.nickname) {
      return {
        uin,
        nick: data.nick || data.nickname,
        avatar: data.headurl || data.avatarUrl || '',
        vip: !!data.isVip || !!data.vipFlag,
      };
    }
  } catch {
    /* 落到下面的兜底 */
  }
  return { uin, nick: uin ? 'QQ 用户' : '', avatar: '', vip: false };
}

function isLoggedIn(jar) {
  return !!(jar.get('qm_keyst') || jar.get('qqmusic_key'));
}

/* --------------------------------- 搜索 --------------------------------- */

function normalizeSong(s) {
  const album = s.album || {};
  const singers = (s.singer || []).map((x) => x.name).filter(Boolean).join(' / ');
  return {
    mid: s.mid || s.songmid,
    name: s.name || s.title || s.songname,
    singer: singers || s.singername || '',
    album: album.name || album.title || s.albumname || '',
    albumMid: album.mid || s.albummid || '',
    duration: s.interval || s.duration || 0,
    pic: picUrl(album.mid || s.albummid, 150),
  };
}

async function search(keyword, page = 1, size = 20, jar) {
  const d = await musicu(
    {
      comm: { ct: 11, cv: '1003006', v: '1003006', uin: Number(jar?.uin) || 0 },
      req_1: {
        module: 'music.search.SearchCgiService',
        method: 'DoSearchForQQMusicDesktop',
        param: { num_per_page: size, page_num: page, query: keyword, search_type: 0 },
      },
    },
    jar
  );
  const list = d?.req_1?.data?.body?.song?.list || [];
  return list.map(normalizeSong).filter((x) => x.mid);
}

/* ------------------------------- 播放地址 -------------------------------- */

/**
 * 取播放地址。未登录时 QQ 音乐不再下发 purl，因此显式报 login_required。
 */
async function getSongUrl(mid, jar) {
  const uin = jar?.uin || '';
  const g = guid();
  const d = await musicu(
    {
      comm: { uin: Number(uin) || 0, format: 'json', ct: 24, cv: 0 },
      req: {
        module: 'CDN.SrfCdnDispatchServer',
        method: 'GetCdnDispatch',
        param: { guid: g, calltype: 0, userip: '' },
      },
      req_0: {
        module: 'vkey.GetVkeyServer',
        method: 'CgiGetVkey',
        param: {
          guid: g,
          songmid: [mid],
          songtype: [0],
          uin: String(uin || 0),
          loginflag: 1,
          platform: '20',
        },
      },
    },
    jar
  );

  const info = d?.req_0?.data?.midurlinfo?.[0] || {};
  const sip = (d?.req_0?.data?.sip || d?.req?.data?.sip || [])[0] || '';
  if (!info.purl) {
    const needLogin = !isLoggedIn(jar);
    return {
      ok: false,
      code: needLogin ? 'login_required' : 'no_permission',
      msg: needLogin ? '登录后才能获取播放地址' : '该歌曲无版权或需要会员',
    };
  }
  return {
    ok: true,
    url: httpsUrl(sip + info.purl),
    trial: !!info.pneed || !!info.pneedbuy,
    quality: (info.filename || '').startsWith('M800') ? '320k' : '128k',
  };
}

/* --------------------------------- 歌词 --------------------------------- */

async function getLyric(mid, jar) {
  const url = `${C}/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${encodeURIComponent(
    mid
  )}&format=json&nobase64=1&g_tk=${hash33(jar?.get('qm_keyst') || '')}`;
  const res = await request(url, { jar, referer: 'https://y.qq.com/portal/player.html' });
  const txt = await res.text();
  let d;
  try {
    d = JSON.parse(txt);
  } catch {
    return { lyric: '', trans: '' };
  }
  return {
    lyric: d.lyric || '',
    trans: d.trans || '',
  };
}

/* -------------------------------- 歌单 ---------------------------------- */

function normalizePlaylist(p) {
  return {
    id: String(p.dissid || p.disstid || ''),
    name: p.dissname || p.title || '',
    pic: httpsUrl(p.imgurl || p.logo || '') || picUrl(p.album_pic_mid, 200),
    count: p.song_count || p.songcount || 0,
    listen: p.listennum || p.listenCount || 0,
    desc: p.introduction || p.desc || '',
  };
}

// 热门歌单（按分类）
async function hotPlaylists(limit = 12) {
  const url =
    `${C}/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=${Date.now()}&g_tk=5381` +
    `&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0` +
    `&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5&sin=0&ein=${limit}`;
  const res = await request(url);
  const d = await res.json();
  return (d?.data?.list || []).map(normalizePlaylist).filter((x) => x.id);
}

// 歌单详情（含歌曲列表）
// 旧接口 fcg_ucc_getcdinfo_byids_cp 对广场歌单会返回 "check privacy error!"，
// 因此统一走新版 aiDissInfo，一次取 300 首，够手表端使用。
async function playlistDetail(id, jar) {
  if (!/^\d+$/.test(String(id))) throw new Error('歌单 id 不合法');
  const d = await musicu(
    {
      comm: { ct: 24, cv: 0, uin: Number(jar?.uin) || 0 },
      req: {
        module: 'music.srfDissInfo.aiDissInfo',
        method: 'uniform_get_Dissinfo',
        param: {
          disstid: Number(id),
          enc_host_uin: '',
          tag: 1,
          userinfo: 1,
          song_begin: 0,
          song_num: 300,
          onlysonglist: 0,
        },
      },
    },
    jar
  );
  const r = d?.req?.data;
  if (!r || !r.dirinfo) throw new Error('歌单不存在或已被删除');
  const info = r.dirinfo;
  return {
    id: String(info.id || id),
    name: info.title || '歌单',
    pic: httpsUrl(info.picurl || ''),
    desc: info.desc || '',
    count: r.total_song_num || (r.songlist || []).length,
    listen: info.listennum || 0,
    songs: (r.songlist || []).map(normalizeSong).filter((x) => x.mid),
  };
}

// 登录用户创建/收藏的歌单
async function myPlaylists(jar) {
  const uin = jar?.uin;
  if (!uin) return [];
  const url =
    `${C}/rsc/fcgi-bin/fcg_user_created_diss?hostuin=${uin}&sin=0&size=30&format=json` +
    `&g_tk=${hash33(jar.get('qm_keyst') || '')}`;
  const res = await request(url, { jar });
  const d = await res.json();
  const list = d?.data?.disslist || [];
  return list
    .map((p) => ({
      id: String(p.dissid || p.tid || ''),
      name: p.diss_name || p.dissname || '',
      pic: httpsUrl(p.diss_cover || ''),
      count: p.song_cnt || 0,
    }))
    .filter((x) => x.id);
}

module.exports = {
  Jar,
  hash33,
  picUrl,
  createQR,
  pollQR,
  createWxQR,
  pollWxQR,
  getUserInfo,
  isLoggedIn,
  search,
  getSongUrl,
  getLyric,
  hotPlaylists,
  playlistDetail,
  myPlaylists,
};
