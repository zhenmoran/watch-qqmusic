'use strict';
/**
 * 手表版 QQ 音乐 —— 前端主逻辑
 *
 * 内存控制策略（手表 RAM 普遍 512MB~1GB，WebView 进程要给系统让路）：
 *   - 零框架，无虚拟 DOM，直接操作节点
 *   - 列表分批渲染，每次 40 条，滚到底再续，切页面即清空
 *   - 全局只用一个 <audio>，切歌即释放上一首的 src
 *   - 歌词一次性渲染但节点数受歌曲长度限制（通常 <120 行）
 *   - 进度更新走 timeupdate（约 4Hz），不用 rAF 轮询
 */

(function () {
  const $ = (s) => document.querySelector(s);
  const $$ = (s) => Array.from(document.querySelectorAll(s));

  /* ============================== 常量 ============================== */

  const LS = {
    fav: 'wm.fav',
    recent: 'wm.recent',
    last: 'wm.last',
  };
  const HOT_WORDS = ['周杰伦', '林俊杰', '薛之谦', '周深', '华语流行', '纯音乐', '摇滚', '轻音乐', '陈奕迅', '英文歌'];
  const PAGE = 40;
  const TRANSPARENT =
    'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

  /* ============================== 状态 ============================== */

  const state = {
    tab: 'discover',
    queue: [],
    index: -1,
    playing: false,
    dragging: false,
    lyricMode: false,
    loggedIn: false,
    user: null,
    fav: [],
    recent: [],
    lyricLines: [],
    lyricIndex: -1,
    playToken: 0,
    listCtx: null,
    hotLoaded: false,
    qrTimer: 0,
    loginKind: 'qq',
    volume: 1,
    lastVolume: 0.8,
  };

  const store = {
    get(k, d) {
      try {
        const v = localStorage.getItem(k);
        return v ? JSON.parse(v) : d;
      } catch {
        return d;
      }
    },
    set(k, v) {
      try {
        localStorage.setItem(k, JSON.stringify(v));
      } catch {}
    },
  };

  /* ============================== 工具 ============================== */

  const fmtTime = (s) => {
    if (!isFinite(s) || s < 0) s = 0;
    const m = Math.floor(s / 60);
    const r = Math.floor(s % 60);
    return m + ':' + String(r).padStart(2, '0');
  };

  function fitRoot() {
    // 以屏幕短边为基准，400px 屏 -> 1rem = 16px
    const s = Math.min(window.innerWidth, window.innerHeight);
    document.documentElement.style.fontSize = s / 25 + 'px';
  }

  let toastTimer = 0;
  function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.classList.add('is-show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('is-show'), 2200);
  }

  async function api(path, opt) {
    const res = await fetch(path, {
      credentials: 'same-origin',
      ...opt,
    });
    let data;
    try {
      data = await res.json();
    } catch {
      throw new Error('服务未响应');
    }
    return data;
  }

  /* ============================== 音频核心 ============================== */

  const audio = new Audio();
  // 挂到 window 方便排查播放问题（不影响功能）
  window.__audio = audio;
  audio.preload = 'none';
  audio.volume = 1;

  audio.addEventListener('playing', () => {
    state.playing = true;
    paintPlayState();
  });
  audio.addEventListener('pause', () => {
    state.playing = false;
    paintPlayState();
  });
  audio.addEventListener('ended', () => next());
  audio.addEventListener('loadedmetadata', () => {
    $('#tDur').textContent = fmtTime(audio.duration);
  });
  audio.addEventListener('timeupdate', () => {
    if (state.dragging) return;
    paintProgress(audio.currentTime, audio.duration || curSong()?.duration || 0);
  });
  audio.addEventListener('error', () => {
    if (!audio.src) return;
    // 直链被拒时回退到服务端中转
    const u = audio.dataset.src;
    if (u && !u.startsWith(location.origin) && !audio.dataset.fallback) {
      audio.dataset.fallback = '1';
      audio.src = '/api/stream?u=' + encodeURIComponent(u);
      audio.play().catch(() => {});
      return;
    }
    toast('播放失败，可能是版权限制');
    state.playing = false;
    paintPlayState();
  });

  /* ============================== 音量 ============================== */

  // 装了 APK 外壳时优先调系统媒体音量（更符合手表习惯）；
  // 浏览器里退回 audio.volume。两者不能同时生效，否则会双重衰减。
  const hasNativeAudio = () =>
    !!(window.AndroidAudio && typeof window.AndroidAudio.setVolume === 'function');

  function applyVolume(v) {
    v = Math.max(0, Math.min(1, v));
    state.volume = v;
    if (v > 0) state.lastVolume = v;
    if (hasNativeAudio()) {
      audio.volume = 1;
      try {
        window.AndroidAudio.setVolume(Math.round(v * 100));
      } catch {}
    } else {
      audio.volume = v;
    }
    const pct = (v * 100).toFixed(1) + '%';
    $('#volFill').style.width = pct;
    $('#volDot').style.left = pct;
    $('#volIco').innerHTML = `<use href="${v === 0 ? '#i-mute' : '#i-vol'}"/>`;
    store.set('wm.vol', v);
  }

  function toggleMute() {
    applyVolume(state.volume > 0 ? 0 : state.lastVolume || 0.8);
  }

  function bindVolume() {
    const bar = $('#volBar');
    let dragging = false;

    const set = (clientX) => {
      const r = bar.getBoundingClientRect();
      applyVolume((clientX - r.left) / r.width);
    };

    bar.addEventListener('touchstart', (e) => {
      dragging = true;
      bar.classList.add('is-drag');
      set(e.touches[0].clientX);
    }, { passive: true });

    bar.addEventListener('touchmove', (e) => {
      if (!dragging) return;
      e.preventDefault();
      set(e.touches[0].clientX);
    }, { passive: false });

    const end = () => {
      dragging = false;
      bar.classList.remove('is-drag');
    };
    bar.addEventListener('touchend', end);
    bar.addEventListener('touchcancel', end);

    // 桌面调试可拖
    let mouseDown = false;
    bar.addEventListener('mousedown', (e) => {
      mouseDown = true;
      bar.classList.add('is-drag');
      set(e.clientX);
    });
    window.addEventListener('mousemove', (e) => {
      if (mouseDown) set(e.clientX);
    });
    window.addEventListener('mouseup', () => {
      if (!mouseDown) return;
      mouseDown = false;
      bar.classList.remove('is-drag');
    });
  }

  const curSong = () => state.queue[state.index] || null;

  async function playQueue(list, index, ctxName) {
    if (!list || !list.length) return;
    state.queue = list.slice(0, 200); // 队列上限，避免长时间占用
    state.index = Math.max(0, Math.min(index | 0, state.queue.length - 1));
    state.listCtx = ctxName || '正在播放';
    openPlayer();
    await loadCurrent();
  }

  async function loadCurrent() {
    const song = curSong();
    if (!song) return;

    const token = ++state.playToken;
    paintSongMeta(song);
    paintLyricPlaceholder();
    markPlayingRow();

    const cache = (state.urlCache ||= new Map());
    try {
      let r = cache.get(song.mid);
      if (!r) {
        r = await api('/api/url?mid=' + encodeURIComponent(song.mid));
        if (r.ok) {
          cache.set(song.mid, r);
          if (cache.size > 30) cache.delete(cache.keys().next().value);
        }
      }
      if (token !== state.playToken) return; // 已经切到别的歌了

      if (!r.ok) {
        toast(r.msg || '无法播放');
        state.playing = false;
        paintPlayState();
        if (r.code === 'login_required') setTimeout(openLogin, 900);
        return;
      }

      audio.pause();
      audio.removeAttribute('src');
      delete audio.dataset.fallback;
      audio.dataset.src = r.url;
      audio.src = r.url;
      audio.load();
      await audio.play().catch(() => {
        toast('点击播放键开始');
      });

      loadLyric(song, token);
      pushRecent(song);
      store.set(LS.last, song);
    } catch (e) {
      if (token === state.playToken) toast(e.message || '网络异常');
    }
  }

  function togglePlay() {
    if (!curSong()) {
      if (state.recent.length) playQueue(state.recent, 0, '最近播放');
      else toast('还没有播放歌曲');
      return;
    }
    if (audio.paused) {
      if (!audio.src) loadCurrent();
      else audio.play().catch(() => toast('播放失败'));
    } else audio.pause();
  }

  function next() {
    if (!state.queue.length) return;
    state.index = state.index >= state.queue.length - 1 ? 0 : state.index + 1;
    loadCurrent();
  }

  function prev() {
    if (!state.queue.length) return;
    // 播放超过 3 秒时，上一首先回到开头，符合主流播放器习惯
    if (audio.currentTime > 3 && !audio.paused) {
      audio.currentTime = 0;
      return;
    }
    state.index = state.index <= 0 ? state.queue.length - 1 : state.index - 1;
    loadCurrent();
  }

  function pushRecent(song) {
    state.recent = [song, ...state.recent.filter((s) => s.mid !== song.mid)].slice(0, 20);
    store.set(LS.recent, state.recent);
    if (state.tab === 'discover') renderRecent();
    $('#recentCount').textContent = state.recent.length || '';
  }

  /* ============================== 播放器绘制 ============================== */

  function paintSongMeta(song) {
    $('#pSong').textContent = song.name || '';
    $('#pSinger').textContent = [song.singer, song.album].filter(Boolean).join(' · ');
    $('#miniSong').textContent = song.name || '';
    $('#miniCover').src = song.pic || '';
    $('#plFrom').textContent = state.listCtx || '正在播放';
    $('#coverImg').classList.remove('is-on');
    if (song.pic) {
      $('#coverImg').src = song.pic;
      $('#coverImg').classList.add('is-on');
    } else {
      $('#coverImg').removeAttribute('src');
    }
    $('#mini').hidden = false;
    document.body.classList.add('has-mini');
    paintFavState();
  }

  function paintPlayState() {
    const id = state.playing ? '#i-pause' : '#i-play';
    $('#playIco').innerHTML = `<use href="${id}"/>`;
    $('#miniIco').innerHTML = `<use href="${id}"/>`;
    $('#btnPlay').setAttribute('aria-label', state.playing ? '暂停' : '播放');
  }

  function paintProgress(cur, dur) {
    const p = dur > 0 ? Math.min(1, cur / dur) : 0;
    $('#barFill').style.width = p * 100 + '%';
    $('#barDot').style.left = p * 100 + '%';
    $('#miniFill').style.width = p * 100 + '%';
    $('#tCur').textContent = fmtTime(cur);
    if (dur > 0) $('#tDur').textContent = fmtTime(dur);
    syncLyric(cur);
  }

  function paintFavState() {
    const song = curSong();
    const on = song && state.fav.some((s) => s.mid === song.mid);
    $('#btnFav').classList.toggle('is-on', !!on);
    $('#favLabel').textContent = on ? '已收藏' : '收藏';
  }

  function markPlayingRow() {
    const mid = curSong()?.mid;
    $$('.row[data-mid]').forEach((el) => el.classList.toggle('is-playing', el.dataset.mid === mid));
  }

  /* ============================== 收藏 / 最近 ============================== */

  function toggleFav() {
    const song = curSong();
    if (!song) return toast('先播放一首歌');
    const i = state.fav.findIndex((s) => s.mid === song.mid);
    if (i >= 0) {
      state.fav.splice(i, 1);
      toast('已取消收藏');
    } else {
      state.fav.unshift(song);
      state.fav = state.fav.slice(0, 300);
      toast('已收藏');
    }
    store.set(LS.fav, state.fav);
    paintFavState();
    $('#favCount').textContent = state.fav.length || '';
    if (state.listCtx === '我的收藏') renderList(state.fav, '我的收藏');
  }

  /* ============================== 列表渲染 ============================== */

  function rowEl(song, ctx) {
    const row = document.createElement('div');
    row.className = 'row';
    row.dataset.mid = song.mid;
    if (curSong()?.mid === song.mid) row.classList.add('is-playing');
    row.innerHTML =
      '<img class="row-img" alt="" loading="lazy" decoding="async">' +
      '<div class="row-txt"><div class="row-name"></div><div class="row-sub"></div></div>';
    const img = row.querySelector('.row-img');
    img.src = song.pic || TRANSPARENT;
    row.querySelector('.row-name').textContent = song.name || '未知歌曲';
    row.querySelector('.row-sub').textContent = [song.singer, song.album].filter(Boolean).join(' · ');
    row.addEventListener('click', () => {
      const q = ctx.list;
      const i = ctx.list.indexOf(song);
      playQueue(q, i < 0 ? 0 : i, ctx.name);
    });
    return row;
  }

  /**
   * 分批渲染：先铺 40 条，容器滚到底部再补 40 条
   */
  function renderList(songs, name, container) {
    const box = container || $('#listRows');
    box.innerHTML = '';
    if (!songs.length) {
      box.innerHTML = '<div class="empty">这里还是空的</div>';
      return;
    }
    const ctx = { list: songs, name };
    let shown = 0;
    const more = () => {
      const frag = document.createDocumentFragment();
      const end = Math.min(shown + PAGE, songs.length);
      for (let i = shown; i < end; i++) frag.appendChild(rowEl(songs[i], ctx));
      shown = end;
      box.appendChild(frag);
    };
    more();
    const host = box.classList.contains('rows--full') ? box : $('#v-list').querySelector('.rows--full');
    if (shown < songs.length && host) {
      host.onscroll = () => {
        if (host.scrollTop + host.clientHeight > host.scrollHeight - 80) more();
      };
    }
  }

  /* ============================== 歌词 ============================== */

  function parseLrc(text) {
    if (!text) return [];
    const out = [];
    for (const raw of text.split('\n')) {
      const tags = raw.match(/\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]/g);
      if (!tags) continue;
      const content = raw.replace(/\[[^\]]*\]/g, '').trim();
      if (!content) continue;
      for (const t of tags) {
        const m = t.match(/\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/);
        if (!m) continue;
        const ms = m[3] ? Number(m[3].padEnd(3, '0')) : 0;
        out.push({ t: Number(m[1]) * 60 + Number(m[2]) + ms / 1000, s: content });
      }
    }
    return out.sort((a, b) => a.t - b.t);
  }

  function paintLyricPlaceholder() {
    $('#lyricInner').innerHTML = '<div class="lyric-empty">歌词加载中…</div>';
    $('#lyricInner').style.transform = 'translateY(0)';
    state.lyricLines = [];
    state.lyricIndex = -1;
  }

  async function loadLyric(song, token) {
    try {
      const r = await api('/api/lyric?mid=' + encodeURIComponent(song.mid));
      if (token !== state.playToken) return;
      state.lyricLines = parseLrc(r.lyric || '');
      const box = $('#lyricInner');
      if (!state.lyricLines.length) {
        box.innerHTML = '<div class="lyric-empty">这首没有歌词</div>';
        return;
      }
      const frag = document.createDocumentFragment();
      state.lyricLines.forEach((l) => {
        const d = document.createElement('div');
        d.className = 'lyric-line';
        d.textContent = l.s;
        frag.appendChild(d);
      });
      box.innerHTML = '';
      box.appendChild(frag);
      state.lyricIndex = -1;
      syncLyric(audio.currentTime || 0);
    } catch {
      if (token === state.playToken) {
        $('#lyricInner').innerHTML = '<div class="lyric-empty">歌词加载失败</div>';
      }
    }
  }

  function syncLyric(cur) {
    const lines = state.lyricLines;
    if (!lines.length) return;
    // 二分找当前行
    let lo = 0,
      hi = lines.length - 1,
      idx = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (lines[mid].t <= cur) {
        idx = mid;
        lo = mid + 1;
      } else hi = mid - 1;
    }
    if (idx === state.lyricIndex) return;

    const box = $('#lyricInner');
    const nodes = box.children;
    if (state.lyricIndex >= 0 && nodes[state.lyricIndex]) {
      nodes[state.lyricIndex].classList.remove('is-on');
    }
    if (idx >= 0 && nodes[idx]) {
      nodes[idx].classList.add('is-on');
      // 相邻行做次级高亮，营造渐隐层次
      for (let k = 0; k < nodes.length; k++) {
        const d = Math.abs(k - idx);
        nodes[k].classList.toggle('is-near', d === 1 && !nodes[k].classList.contains('is-on'));
      }
      const offset = nodes[idx].offsetTop;
      box.style.transform = `translateY(${-offset}px)`;
    }
    state.lyricIndex = idx;
  }

  function toggleLyric() {
    state.lyricMode = !state.lyricMode;
    $('#lyricWrap').hidden = !state.lyricMode;
    $('#coverWrap').hidden = state.lyricMode;
    if (state.lyricMode && state.lyricLines.length) {
      state.lyricIndex = -1;
      syncLyric(audio.currentTime || 0);
    }
  }

  /* ============================== 视图导航 ============================== */

  const TAB_VIEWS = { discover: '#v-discover', search: '#v-search', mine: '#v-mine' };
  const overStack = [];

  function setTab(name) {
    state.tab = name;
    closeAllOver();
    Object.entries(TAB_VIEWS).forEach(([k, sel]) => {
      $(sel).classList.toggle('is-active', k === name);
    });
    $$('.tab').forEach((t) => t.classList.toggle('is-on', t.dataset.tab === name));
    document.body.classList.remove('deep');
    if (name === 'discover' && !state.hotLoaded) loadHot();
    if (name === 'mine') refreshProfile();
    if (name === 'search') setTimeout(() => $('#searchInput').focus(), 120);
  }

  function openOver(sel) {
    const el = $(sel);
    el.classList.add('is-active');
    el.classList.remove('is-leaving');
    document.body.classList.add('deep');
    if (!overStack.includes(sel)) overStack.push(sel);
  }

  function closeOver() {
    const sel = overStack.pop();
    if (sel) {
      const el = $(sel);
      el.classList.remove('is-active');
      el.classList.add('is-leaving');
      setTimeout(() => el.classList.remove('is-leaving'), 240);
    }
    if (!overStack.length) {
      document.body.classList.remove('deep');
      $('#mini').hidden = !curSong();
    }
  }

  function closeAllOver() {
    overStack.length = 0;
    $$('.view--over').forEach((v) => v.classList.remove('is-active'));
    document.body.classList.remove('deep');
  }

  function openPlayer() {
    openOver('#v-player');
    $('#mini').hidden = true;
    if (!store.get('wm.hint', 0)) {
      const h = $('#swipeHint');
      h.classList.add('is-show');
      setTimeout(() => h.classList.remove('is-show'), 2600);
      store.set('wm.hint', 1);
    }
  }

  function openList(title, songs, meta) {
    state.listCtx = title;
    $('#listTitle').textContent = title;
    $('#listMeta').textContent = meta || songs.length + ' 首';
    renderList(songs, title);
    openOver('#v-list');
    $('#v-list').querySelector('.rows--full').scrollTop = 0;
  }

  /* ============================== 数据加载 ============================== */

  async function loadHot() {
    const spin = $('#hotSpin');
    spin.classList.add('is-on');
    try {
      const r = await api('/api/hot?n=12');
      if (!r.ok) throw new Error(r.msg);
      const box = $('#hotCards');
      box.innerHTML = '';
      const frag = document.createDocumentFragment();
      r.list.forEach((p) => {
        const card = document.createElement('div');
        card.className = 'card';
        card.innerHTML =
          '<img class="card-img" alt="" loading="lazy" decoding="async">' +
          '<div class="card-name"></div><div class="card-play"></div>';
        if (p.pic) card.querySelector('.card-img').src = p.pic;
        card.querySelector('.card-name').textContent = p.name;
        card.querySelector('.card-play').textContent = p.listen
          ? (p.listen > 10000 ? Math.round(p.listen / 10000) + '万' : p.listen) + ' 播放'
          : '';
        card.addEventListener('click', () => openPlaylist(p));
        frag.appendChild(card);
      });
      box.appendChild(frag);
      state.hotLoaded = true;
    } catch (e) {
      $('#hotCards').innerHTML = '<div class="empty">歌单加载失败，检查网络</div>';
    } finally {
      spin.classList.remove('is-on');
    }
  }

  async function openPlaylist(p) {
    const box = $('#listRows');
    $('#listTitle').textContent = p.name;
    $('#listMeta').textContent = '';
    box.innerHTML = '<div class="empty">加载中…</div>';
    openOver('#v-list');
    try {
      const r = await api('/api/playlist?id=' + encodeURIComponent(p.id));
      if (!r.ok) throw new Error(r.msg);
      state.listCtx = p.name;
      $('#listMeta').textContent = r.data.songs.length + ' 首';
      renderList(r.data.songs, p.name);
    } catch (e) {
      box.innerHTML = '<div class="empty">' + (e.message || '加载失败') + '</div>';
    }
  }

  function renderRecent() {
    const sec = $('#recentSec');
    $('#startSec').hidden = state.recent.length > 0;
    if (!state.recent.length) {
      sec.hidden = true;
    } else {
      sec.hidden = false;
      const box = $('#recentRows');
      box.innerHTML = '';
      state.recent.slice(0, 3).forEach((s) => {
        box.appendChild(rowEl(s, { list: state.recent, name: '最近播放' }));
      });
    }
    $('#recentCount').textContent = state.recent.length || '';
  }

  function renderFavPreview() {
    const sec = $('#favSec');
    if (!state.fav.length) {
      sec.hidden = true;
    } else {
      sec.hidden = false;
      const box = $('#favRows');
      box.innerHTML = '';
      state.fav.slice(0, 3).forEach((s) => {
        box.appendChild(rowEl(s, { list: state.fav, name: '我的收藏' }));
      });
    }
    $('#favCount').textContent = state.fav.length || '';
  }

  /* ============================== 搜索 ============================== */

  let searchTimer = 0;
  async function doSearch(kw) {
    kw = (kw || '').trim();
    if (!kw) return;
    $('#hotWords').hidden = true;
    $('#searchHint').hidden = false;
    $('#searchHint').textContent = '搜索中…';
    try {
      let r = await api('/api/search?w=' + encodeURIComponent(kw) + '&n=30');
      // QQ 音乐偶发返回空列表（限流），隔 700ms 重试一次再下结论
      if (r.ok && !r.list.length) {
        await new Promise((s) => setTimeout(s, 700));
        r = await api('/api/search?w=' + encodeURIComponent(kw) + '&n=30');
      }
      if (!r.ok) throw new Error(r.msg);
      $('#searchHint').textContent = r.list.length ? `找到 ${r.list.length} 首` : '没有找到相关歌曲';
      renderList(r.list, '搜索：' + kw, $('#searchRows'));
    } catch (e) {
      $('#searchHint').textContent = e.message || '搜索失败';
      $('#searchRows').innerHTML = '';
    }
  }

  function bindSearch() {
    const input = $('#searchInput');
    input.addEventListener('input', () => {
      clearTimeout(searchTimer);
      const v = input.value.trim();
      if (!v) {
        $('#searchRows').innerHTML = '';
        $('#searchHint').hidden = true;
        $('#hotWords').hidden = false;
        return;
      }
      searchTimer = setTimeout(() => doSearch(v), 450);
    });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        input.blur();
        doSearch(input.value);
      }
    });

    const chips = $('#hotWords');
    HOT_WORDS.forEach((w) => {
      const b = document.createElement('button');
      b.className = 'chip';
      b.textContent = w;
      b.addEventListener('click', () => {
        input.value = w;
        doSearch(w);
      });
      chips.appendChild(b);
    });
  }

  /* ============================== 扫码登录 ============================== */

  async function openLogin() {
    openOver('#v-login');
    if (state.loggedIn) {
      // 已登录则显示账号页，不再重复扫码
      closeOver();
      setTab('mine');
      return;
    }
    $$('.ltab').forEach((t) => t.classList.toggle('is-on', t.dataset.kind === state.loginKind));
    await refreshQR();
  }

  async function refreshQR() {
    clearTimeout(state.qrTimer);
    $('#qrMask').hidden = true;
    $('#qrRetry').hidden = true;
    $('#qrTip').textContent = '正在获取二维码…';
    const isWx = state.loginKind === 'wx';
    try {
      const r = await api(isWx ? '/api/login/wx/qr' : '/api/login/qr');
      if (!r.ok) throw new Error(r.msg);
      const img = $('#qrImg');
      img.src = r.image;
      img.hidden = false;
      $('#qrTip').textContent = isWx ? '打开微信扫一扫' : '打开手机 QQ 扫一扫';
      schedulePoll();
    } catch (e) {
      $('#qrTip').textContent = e.message || '二维码获取失败';
      $('#qrRetry').hidden = false;
      $('#qrMaskText').textContent = '获取失败';
      $('#qrMask').hidden = false;
    }
  }

  // 串行轮询：上一次返回后再排下一次，避免微信长轮询把请求堆起来
  function schedulePoll(delay) {
    clearTimeout(state.qrTimer);
    const gap = delay != null ? delay : state.loginKind === 'wx' ? 400 : 900;
    state.qrTimer = setTimeout(pollQR, gap);
  }

  async function pollQR() {
    const isWx = state.loginKind === 'wx';
    try {
      const r = await api(isWx ? '/api/login/wx/check' : '/api/login/check');
      if (!r.ok) {
        schedulePoll(1200);
        return;
      }
      if (r.state === 'waiting') {
        $('#qrTip').textContent = isWx ? '打开微信扫一扫' : '打开手机 QQ 扫一扫';
        schedulePoll();
      } else if (r.state === 'scanned') {
        $('#qrTip').textContent = '已扫码，请在手机上确认登录';
        schedulePoll();
      } else if (r.state === 'expired' || r.state === 'refused') {
        clearTimeout(state.qrTimer);
        $('#qrMaskText').textContent = r.state === 'expired' ? '二维码已失效' : '已取消登录';
        $('#qrMask').hidden = false;
        $('#qrRetry').hidden = false;
      } else if (r.state === 'ok') {
        clearTimeout(state.qrTimer);
        state.loggedIn = true;
        state.user = r.user;
        toast('登录成功');
        refreshProfile();
        setTimeout(() => {
          closeOver();
          setTab('mine');
        }, 600);
      }
    } catch (e) {
      $('#qrTip').textContent = e.message || '网络异常，重试中…';
      schedulePoll(1500);
    }
  }

  async function refreshProfile() {
    try {
      const r = await api('/api/session');
      if (!r.ok) return;
      state.loggedIn = r.loggedIn;
      state.user = r.user;
    } catch {}
    const u = state.user;
    if (state.loggedIn && u) {
      $('#pName').textContent = u.nick || 'QQ 用户';
      $('#pSub').textContent = u.vip ? 'VIP 会员' : '账号已登录';
      $('#loginText').textContent = '切换账号';
      const av = $('#avatar');
      av.innerHTML = '';
      if (u.avatar) {
        const img = document.createElement('img');
        img.src = u.avatar.replace(/^http:/, 'https:');
        img.alt = '';
        av.appendChild(img);
      } else av.innerHTML = '<svg><use href="#i-user"/></svg>';
    } else {
      $('#pName').textContent = '未登录';
      $('#pSub').textContent = '扫码登录后可听完整歌曲';
      $('#loginText').textContent = '扫码登录';
      $('#avatar').innerHTML = '<svg><use href="#i-user"/></svg>';
    }
  }

  /* ============================== 手势 ============================== */

  function bindGestures() {
    const player = $('#v-player');
    let sx = 0,
      sy = 0,
      st = 0,
      moved = false;

    player.addEventListener(
      'touchstart',
      (e) => {
        if (e.target.closest('#progress, .vol-bar, .vol')) return;
        const t = e.touches[0];
        // 左右边缘留给系统返回手势，否则会和网页右滑打架
        if (t.clientX < 24 || t.clientX > window.innerWidth - 24) {
          st = 0;
          return;
        }
        sx = t.clientX;
        sy = t.clientY;
        st = Date.now();
        moved = false;
      },
      { passive: true }
    );

    player.addEventListener(
      'touchend',
      (e) => {
        if (e.target.closest('#progress, .vol-bar, .vol')) return;
        if (!st) return;
        const t = e.changedTouches[0];
        const dx = t.clientX - sx;
        const dy = t.clientY - sy;
        const dt = Date.now() - st;
        st = 0;
        if (dt > 700) return; // 长按不算手势

        // 水平右滑返回
        if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.5) {
          if (dx > 0) closeOver();
          return;
        }
        // 垂直切歌
        if (Math.abs(dy) > 42 && Math.abs(dy) > Math.abs(dx) * 1.2) {
          const wrap = $('#coverWrap');
          if (dy < 0) {
            wrap.classList.add('swipe-up');
            setTimeout(() => {
              wrap.classList.remove('swipe-up');
              next(false);
            }, 150);
          } else {
            wrap.classList.add('swipe-down');
            setTimeout(() => {
              wrap.classList.remove('swipe-down');
              prev();
            }, 150);
          }
        }
      },
      { passive: true }
    );
  }

  function bindProgress() {
    const el = $('#progress');
    const seek = (clientX) => {
      const rect = el.getBoundingClientRect();
      const p = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
      const dur = audio.duration || curSong()?.duration || 0;
      if (!dur) return;
      $('#barFill').style.width = p * 100 + '%';
      $('#barDot').style.left = p * 100 + '%';
      $('#tCur').textContent = fmtTime(p * dur);
      return p * dur;
    };

    let target = 0;
    el.addEventListener('touchstart', (e) => {
      if (!audio.src) return;
      state.dragging = true;
      el.classList.add('is-drag');
      target = seek(e.touches[0].clientX) || 0;
    });
    el.addEventListener(
      'touchmove',
      (e) => {
        if (!state.dragging) return;
        e.preventDefault();
        target = seek(e.touches[0].clientX) || target;
      },
      { passive: false }
    );
    const end = () => {
      if (!state.dragging) return;
      state.dragging = false;
      el.classList.remove('is-drag');
      if (target > 0 && isFinite(target)) audio.currentTime = target;
    };
    el.addEventListener('touchend', end);
    el.addEventListener('touchcancel', end);

    // 桌面/调试环境用鼠标也能拖
    el.addEventListener('click', (e) => {
      if (!audio.src) return;
      const t = seek(e.clientX);
      if (t) audio.currentTime = t;
    });
  }

  /* ============================== 事件委托 ============================== */

  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn) {
      // 点迷你条进播放器
      if (e.target.closest('#mini') && curSong()) openPlayer();
      return;
    }
    const act = btn.dataset.act;
    switch (act) {
      case 'tab':
        if (state.tab === btn.dataset.tab && !overStack.length) {
          $('#discoverScroll')?.scrollTo({ top: 0, behavior: 'smooth' });
        }
        setTab(btn.dataset.tab);
        break;
      case 'back':
        closeOver();
        break;
      case 'toggle':
        togglePlay();
        break;
      case 'next':
        next(false);
        break;
      case 'prev':
        prev();
        break;
      case 'fav':
        toggleFav();
        break;
      case 'mute':
        toggleMute();
        break;
      case 'toggle-lyric':
        toggleLyric();
        break;
      case 'login':
        openLogin();
        break;
      case 'refresh-qr':
        refreshQR();
        break;
      case 'login-tab': {
        const kind = btn.dataset.kind;
        if (state.loginKind === kind) break;
        state.loginKind = kind;
        $$('.ltab').forEach((t) => t.classList.toggle('is-on', t.dataset.kind === kind));
        refreshQR();
        break;
      }
      case 'open-fav':
        if (!state.fav.length) return toast('还没有收藏歌曲');
        openList('我的收藏', state.fav, state.fav.length + ' 首');
        break;
      case 'open-recent':
        if (!state.recent.length) return toast('还没有播放记录');
        openList('最近播放', state.recent, state.recent.length + ' 首');
        break;
    }
  });

  /* ============================== 初始化 ============================== */

  function init() {
    fitRoot();
    window.addEventListener('resize', fitRoot);
    window.addEventListener('orientationchange', () => setTimeout(fitRoot, 120));

    state.fav = store.get(LS.fav, []);
    state.recent = store.get(LS.recent, []);

    bindSearch();
    bindGestures();
    bindProgress();
    bindVolume();
    applyVolume(store.get('wm.vol', 0.8));
    paintPlayState();

    // 恢复上次播放的歌曲（不自动发声，避免突兀）
    const last = store.get(LS.last, null);
    if (last && last.mid) {
      state.queue = [last];
      state.index = 0;
      $('#miniSong').textContent = last.name || '';
      if (last.pic) $('#miniCover').src = last.pic;
      $('#mini').hidden = false;
      document.body.classList.add('has-mini');
      $('#pSong').textContent = last.name || '';
      $('#pSinger').textContent = last.singer || '';
      if (last.pic) {
        $('#coverImg').src = last.pic;
        $('#coverImg').classList.add('is-on');
      }
    }

    renderRecent();
    renderFavPreview();
    setTab('discover');
    refreshProfile();

  }

  // 供 Wear OS 壳调用：有覆盖层则关闭并返回 true，否则交回系统退出 App
  window.__wmBack = function () {
    if (overStack.length) {
      closeOver();
      return true;
    }
    return false;
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  // 离开页面时释放音频，减少后台占用
  window.addEventListener('pagehide', () => {
    audio.pause();
    audio.removeAttribute('src');
  });
})();
