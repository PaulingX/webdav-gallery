'use strict';

const $ = (sel) => document.querySelector(sel);

const state = {
  sources: [],
  sourceId: null,
  path: '/',
  items: [],        // 已加载的条目（分页累积）
  total: 0,         // 后端返回的目录总条数
  images: [],       // 已加载的图片（按展示顺序），供查看器使用
  editingId: null,
  searching: false, // 是否处于搜索结果视图
  keyword: '',
  sort: localStorage.getItem('wg_sort') || 'name_asc',
  page: { offset: 0, limit: 100, hasMore: false, loading: false },
};

/* ---------------- 基础工具 ---------------- */

async function api(url, options = {}) {
  let res;
  try {
    res = await fetch(url, options);
  } catch (e) {
    throw new Error('网络请求失败，请检查服务是否可用');
  }
  if (res.status === 401) {
    // 会话过期，回到登录页
    location.href = '/login.html';
    throw new Error('未登录或会话已过期');
  }
  let json;
  try {
    json = await res.json();
  } catch (e) {
    throw new Error('服务器响应异常 (HTTP ' + res.status + ')');
  }
  if (!json || json.code !== 0) {
    throw new Error((json && json.message) || '请求失败');
  }
  return json.data;
}

function jsonBody(data) {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  };
}

function rawUrl(id, path) {
  return '/api/sources/' + id + '/raw?path=' + encodeURIComponent(path);
}

function previewUrl(id, path) {
  return '/api/sources/' + id + '/preview?path=' + encodeURIComponent(path);
}

/** 去掉扩展名的文件名（小写），用于图片/视频同名配对 */
function baseName(name) {
  const i = name.lastIndexOf('.');
  return (i > 0 ? name.slice(0, i) : name).toLowerCase();
}

/** 路径的父目录，如 /a/b/c.jpg -> /a/b */
function dirOf(path) {
  const i = path.lastIndexOf('/');
  return i <= 0 ? '/' : path.slice(0, i);
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatSize(bytes) {
  if (!bytes || bytes <= 0) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
}

let toastTimer = null;
function toast(message, ok = true) {
  const el = $('#toast');
  el.textContent = message;
  el.className = 'toast' + (ok ? '' : ' toast-error');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 2600);
}

function show(el) { el.classList.remove('hidden'); }
function hide(el) { el.classList.add('hidden'); }

/** 记住各目录的滚动位置，返回时恢复（如从第二页中间进入子目录，返回后仍在那里） */
const scrollMemory = new Map();
const memKey = (id, path) => id + '|' + path;

/* ---------------- 分页（无限滚动） ---------------- */

/** 根目录用用户选择的排序，子目录固定名称自然排序（后端执行，保证分页顺序正确） */
function currentSortParam() {
  return state.path === '/' ? state.sort : 'name_asc';
}

/** 拉取下一页并增量渲染；返回是否成功 */
async function loadPage() {
  if (state.page.loading) return false;
  state.page.loading = true;
  const first = state.page.offset === 0;
  try {
    const data = await api(`/api/sources/${state.sourceId}/list?path=${encodeURIComponent(state.path)}`
      + `&sort=${currentSortParam()}&offset=${state.page.offset}&limit=${state.page.limit}`);
    state.page.offset += (data.items || []).length;
    state.page.hasMore = !!data.hasMore;
    state.total = data.total || 0;
    if (first) hide($('#loading'));
    appendItems(data.items || []);
    updateGridSentinel();
    updateViewerSentinel();
    return true;
  } catch (e) {
    if (first) hide($('#loading'));
    $('#errorBar').textContent = e.message;
    show($('#errorBar'));
    return false;
  } finally {
    state.page.loading = false;
  }
}

/** 无限滚动哨兵：进入视口附近时加载下一页 */
let gridSentinel = null;
let gridObserver = null;

function updateGridSentinel() {
  if (!gridSentinel) return;
  gridSentinel.classList.toggle('hidden', !state.page.hasMore || state.searching);
}

function ensureGridSentinel() {
  if (gridSentinel && document.contains(gridSentinel)) return;
  gridSentinel = document.createElement('div');
  gridSentinel.id = 'gridSentinel';
  gridSentinel.className = 'grid-sentinel hidden';
  gridSentinel.innerHTML = '<span class="spinner"></span><span>加载更多…</span>';
  document.querySelector('.main').appendChild(gridSentinel);
  gridObserver = new IntersectionObserver((entries) => {
    if (entries.some((en) => en.isIntersecting) && state.page.hasMore && !state.searching) {
      loadPage();
    }
  }, { rootMargin: '800px 0px' });
  gridObserver.observe(gridSentinel);
}

/** 搜索与排序仅在根目录（第一层）提供，子目录固定按名称自然排序 */
function updateToolbar() {
  $('#toolbar').style.display = state.path === '/' ? '' : 'none';
}

/* ---------------- 源列表 ---------------- */

let syncPollTimer = null;
const syncWatchIds = new Set();

function syncStatusText(s) {
  switch (s.syncStatus) {
    case 1: return '⏳ 资源同步中…';
    case 2: return `✅ 已同步 ${s.syncTime ? new Date(s.syncTime).toLocaleString('zh-CN', { hour12: false }) : ''}`;
    case 3: return `❌ 同步失败：${s.syncMessage || '未知错误'}`;
    default: return '未同步（浏览时自动按需同步）';
  }
}

async function loadSources() {
  state.sources = (await api('/api/sources')) || [];
  const select = $('#sourceSelect');
  select.innerHTML = '';
  if (!state.sources.length) {
    const opt = document.createElement('option');
    opt.textContent = '暂无源';
    opt.value = '';
    select.appendChild(opt);
    select.disabled = true;
    show($('#emptyState'));
    $('#grid').innerHTML = '';
    scheduleSyncPoll();
    return;
  }
  select.disabled = false;
  for (const s of state.sources) {
    const opt = document.createElement('option');
    opt.value = s.id;
    opt.textContent = s.name;
    select.appendChild(opt);
  }
  if (state.sourceId) select.value = state.sourceId;
  hide($('#emptyState'));
  scheduleSyncPoll();
}

/** 同步期间轮询源状态；当前源同步完成时自动刷新目录 */
function scheduleSyncPoll() {
  clearTimeout(syncPollTimer);
  const syncing = state.sources.filter((s) => s.syncStatus === 1);
  if (!syncing.length) {
    let refreshed = false;
    for (const id of [...syncWatchIds]) {
      syncWatchIds.delete(id);
      const s = state.sources.find((x) => String(x.id) === id);
      if (s && String(s.id) === String(state.sourceId) && !refreshed) {
        refreshed = true;
        browse(state.path);
      }
    }
    return;
  }
  syncing.forEach((s) => syncWatchIds.add(String(s.id)));
  syncPollTimer = setTimeout(async () => {
    try {
      await loadSources();
    } catch (e) { /* 轮询失败静默重试 */ }
  }, 2500);
}

async function onSync(id) {
  try {
    await api(`/api/sources/${id}/sync`, { method: 'POST' });
    toast('已开始同步资源');
    await loadSources();
  } catch (e) {
    toast(e.message, false);
  }
}

function selectSource(id) {
  if (!id) return;
  state.sourceId = id;
  localStorage.setItem('wg_source', String(id));
  scrollMemory.clear();
  $('#searchInput').value = '';
  state.searching = false;
  browse('/');
}

/* ---------------- 目录浏览 / 搜索 ---------------- */

async function browse(path) {
  if (!state.sourceId) return;
  // 记住当前目录滚动位置，返回时恢复
  scrollMemory.set(memKey(state.sourceId, state.path), window.scrollY);
  const saved = scrollMemory.get(memKey(state.sourceId, path));
  show($('#loading'));
  hide($('#errorBar'));
  $('#grid').innerHTML = '';
  $('#statText').textContent = '';
  state.path = path;
  state.searching = false;
  state.keyword = '';
  state.items = [];
  state.images = [];
  state.total = 0;
  state.page = { offset: 0, limit: 100, hasMore: false, loading: false };
  renderBreadcrumb();
  updateToolbar();
  try {
    const ok = await loadPage();
    if (ok && saved > 0) {
      // 恢复较靠下的位置需要先加载足够多的页（每页渲染后高度增长）
      let guard = 0;
      while (state.page.hasMore
        && document.documentElement.scrollHeight < saved + window.innerHeight
        && guard++ < 80) {
        if (!(await loadPage())) break;
      }
    }
    window.scrollTo({ top: saved == null ? 0 : saved });
  } finally {
    updateFab();
  }
}

let searchTimer = null;

function onSearchInput() {
  const kw = $('#searchInput').value.trim();
  clearTimeout(searchTimer);
  $('#searchClear').classList.toggle('hidden', kw === '');
  if (!kw) {
    // 清空搜索 → 回到当前目录
    if (state.searching) browse(state.path);
    return;
  }
  searchTimer = setTimeout(() => runSearch(kw), 400);
}

async function runSearch(kw) {
  if (!state.sourceId || !kw) return;
  show($('#loading'));
  hide($('#errorBar'));
  $('#grid').innerHTML = '';
  try {
    const results = await api(
      `/api/sources/${state.sourceId}/search?path=${encodeURIComponent(state.path)}&keyword=${encodeURIComponent(kw)}`);
    hide($('#loading'));
    state.searching = true;
    state.keyword = kw;
    state.items = [];
    state.images = [];
    state.total = 0;
    state.page = { offset: (results || []).length, limit: 100, hasMore: false, loading: false };
    renderBreadcrumb();
    updateToolbar();
    appendItems(results || []);
    updateGridSentinel();
    window.scrollTo({ top: 0 });
  } catch (e) {
    hide($('#loading'));
    $('#errorBar').textContent = e.message;
    show($('#errorBar'));
  } finally {
    updateFab();
  }
}

function exitSearch() {
  clearSearchBox();
  browse(state.path);
}

/** 清空搜索框并退出搜索态（不触发重新加载） */
function clearSearchBox() {
  $('#searchInput').value = '';
  $('#searchClear').classList.add('hidden');
  if (state.searching) {
    state.searching = false;
    state.keyword = '';
  }
}

function renderBreadcrumb() {
  const nav = $('#breadcrumb');
  nav.innerHTML = '';
  const mk = (label, path) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'crumb';
    b.textContent = label;
    b.addEventListener('click', () => {
      clearSearchBox();
      browse(path);
    });
    return b;
  };
  nav.appendChild(mk('🏠 根目录', '/'));
  if (state.path !== '/') {
    let acc = '';
    for (const seg of state.path.split('/').filter(Boolean)) {
      acc += '/' + seg;
      const sep = document.createElement('span');
      sep.className = 'crumb-sep';
      sep.textContent = '/';
      nav.appendChild(sep);
      nav.appendChild(mk(seg, acc));
    }
  }
  if (state.searching) {
    const sep = document.createElement('span');
    sep.className = 'crumb-sep';
    sep.textContent = '🔍';
    nav.appendChild(sep);
    const tag = document.createElement('span');
    tag.className = 'crumb crumb-active';
    tag.textContent = `搜索「${state.keyword}」`;
    nav.appendChild(tag);
  }
}

/* ---------------- 网格渲染 ---------------- */

/* ---------------- 网格渲染（增量分页，顺序与后端一致：目录在前） ---------------- */

/** 追加一批条目到网格（每页调用）；同名图片配对视频封面逻辑对每页增量计算 */
function appendItems(items) {
  if (!items || !items.length) {
    updateStat();
    return;
  }
  state.items = state.items.concat(items);
  const images = items.filter((i) => !i.dir && i.mediaType === 'image');
  const videos = items.filter((i) => !i.dir && i.mediaType === 'video');

  // 同目录下同名图片作为视频封面：movie.mp4 + movie.jpg → 视频用图片做预览，图片不再单独展示
  const imgByBase = new Map();
  for (const im of images) imgByBase.set(dirOf(im.path) + '|' + baseName(im.name), im);
  const posters = new Map();
  for (const v of videos) {
    const im = imgByBase.get(dirOf(v.path) + '|' + baseName(v.name));
    if (im) posters.set(v.path, im);
  }
  const paired = new Set(Array.from(posters.values()).map((i) => i.path));
  const shownImages = images.filter((i) => !paired.has(i.path));
  state.images = state.images.concat(shownImages);

  const grid = $('#grid');
  const frag = document.createDocumentFragment();
  for (const item of items) {
    if (item.dir) frag.appendChild(dirCard(item));
    else if (item.mediaType === 'image' && !paired.has(item.path)) frag.appendChild(imageCard(item));
    else if (item.mediaType === 'video') frag.appendChild(videoCard(item, posters.get(item.path)));
    else if (item.mediaType === 'other') frag.appendChild(fileCard(item));
  }
  grid.appendChild(frag);

  // 查看器打开中（触底加载更多图片）时同步追加
  appendToViewer(shownImages);
  updateStat();
}

function updateStat() {
  $('#statText').textContent = state.searching
    ? `找到 ${state.items.length} 项`
    : (state.total > state.items.length
      ? `已加载 ${state.items.length} / ${state.total} 项`
      : (state.items.length ? `${state.items.length} 项` : '空目录'));
}

function dirCard(item) {
  const div = document.createElement('div');
  div.className = 'card';
  div.innerHTML =
    `<div class="thumb-wrap">` +
    `<span class="dir-fallback">📁</span>` +
    `<img class="thumb" loading="lazy" decoding="async" alt="" src="${previewUrl(state.sourceId, item.path)}">` +
    `</div>` +
    `<div class="card-name" title="${esc(item.name)}">${esc(item.name)}</div>`;
  // 目录（含递归子目录）没有图片（预览接口 404）时移除图片，仅显示文件夹图标
  div.querySelector('img').addEventListener('error', (e) => e.target.remove());
  div.addEventListener('click', () => {
    clearSearchBox();
    browse(item.path);
  });
  return div;
}

function imageCard(item) {
  const idx = state.images.indexOf(item);
  const div = document.createElement('div');
  div.className = 'card';
  div.innerHTML =
    `<div class="thumb-wrap">` +
    `<img class="thumb" loading="lazy" decoding="async" alt="${esc(item.name)}" src="${rawUrl(state.sourceId, item.path)}">` +
    `</div>` +
    `<div class="card-name" title="${esc(item.name)}">${esc(item.name)}</div>`;
  div.addEventListener('click', () => openViewer(idx));
  return div;
}

function videoCard(item, poster) {
  const div = document.createElement('div');
  div.className = 'card';
  if (poster) {
    div.innerHTML =
      `<div class="thumb-wrap">` +
      `<img class="thumb" loading="lazy" decoding="async" alt="${esc(item.name)}" src="${rawUrl(state.sourceId, poster.path)}">` +
      `<span class="play-badge">▶</span>` +
      `</div>` +
      `<div class="card-name" title="${esc(item.name)}">${esc(item.name)}</div>` +
      `<div class="card-meta">${formatSize(item.size)}</div>`;
  } else {
    div.innerHTML =
      `<div class="icon-tile"><span>🎬</span><span class="play-badge">▶</span></div>` +
      `<div class="card-name" title="${esc(item.name)}">${esc(item.name)}</div>` +
      `<div class="card-meta">${formatSize(item.size)}</div>`;
  }
  div.addEventListener('click', () => openVideo(item, poster));
  return div;
}

function fileCard(item) {
  const div = document.createElement('div');
  div.className = 'card';
  div.innerHTML =
    `<div class="icon-tile"><span>📄</span></div>` +
    `<div class="card-name" title="${esc(item.name)}">${esc(item.name)}</div>` +
    `<div class="card-meta">${formatSize(item.size)}</div>`;
  div.addEventListener('click', () => window.open(rawUrl(state.sourceId, item.path), '_blank'));
  return div;
}

/* ---------------- 悬浮返回按钮 ---------------- */

function updateFab() {
  const fab = $('#fabUp');
  if (state.path !== '/' || state.searching) show(fab);
  else hide(fab);
}

/* ---------------- 图片查看器（拼接长图，懒加载 + 触底加载更多页） ---------------- */

let viewerIndex = 0;
let viewerObserver = null;
let viewerSentinel = null;
let viewerSentinelObserver = null;

function openViewer(index) {
  if (!state.images.length) return;
  const strip = $('#viewerStrip');
  strip.innerHTML = '';
  if (viewerObserver) viewerObserver.disconnect();
  if (viewerSentinelObserver) viewerSentinelObserver.disconnect();

  // 进入视口附近才创建 <img> 并真正加载，其余保持占位，绝不一次性全量加载
  viewerObserver = new IntersectionObserver((entries) => {
    for (const en of entries) {
      if (!en.isIntersecting) continue;
      const item = en.target;
      viewerObserver.unobserve(item);
      const img = document.createElement('img');
      img.decoding = 'async';
      img.alt = item.dataset.name;
      img.addEventListener('load', () => item.classList.remove('placeholder'));
      img.src = item.dataset.src;
      item.appendChild(img);
    }
  }, { root: strip, rootMargin: '1200px 0px' });

  // 触底哨兵：滚动到末尾附近时加载下一页图片并追加
  viewerSentinel = document.createElement('div');
  viewerSentinel.className = 'viewer-sentinel hidden';
  viewerSentinel.innerHTML = '<span class="spinner"></span><span>加载更多…</span>';
  viewerSentinelObserver = new IntersectionObserver((entries) => {
    if (entries.some((en) => en.isIntersecting)
      && state.page.hasMore && !state.searching && !state.page.loading) {
      loadPage();
    }
  }, { root: strip, rootMargin: '1500px 0px' });
  viewerSentinelObserver.observe(viewerSentinel);

  for (const im of state.images) {
    strip.appendChild(buildViewerItem(im));
  }
  strip.appendChild(viewerSentinel);
  updateViewerSentinel();

  show($('#viewer'));
  viewerIndex = Math.max(0, Math.min(index, state.images.length - 1));
  // 占位高度一致，可按 index 精确定位
  const target = strip.children[viewerIndex];
  if (target) strip.scrollTo({ top: target.offsetTop, behavior: 'auto' });
  updateViewerCaption();
}

function buildViewerItem(im) {
  const item = document.createElement('div');
  item.className = 'viewer-item placeholder';
  item.dataset.src = rawUrl(state.sourceId, im.path);
  item.dataset.name = im.name;
  viewerObserver.observe(item);
  return item;
}

/** 新一页图片到达时（查看器打开中）在哨兵前追加 */
function appendToViewer(newImages) {
  if (!newImages || !newImages.length) return;
  const strip = $('#viewerStrip');
  if (!strip || $('#viewer').classList.contains('hidden') || !viewerSentinel) return;
  for (const im of newImages) {
    strip.insertBefore(buildViewerItem(im), viewerSentinel);
  }
  updateViewerCaption();
}

function updateViewerSentinel() {
  if (viewerSentinel) {
    viewerSentinel.classList.toggle('hidden', !state.page.hasMore || state.searching);
  }
}

function viewerStep(delta) {
  const strip = $('#viewerStrip');
  const i = Math.max(0, Math.min(viewerIndex + delta, state.images.length - 1));
  const target = strip.children[i];
  if (target) strip.scrollTo({ top: target.offsetTop - 8, behavior: 'smooth' });
}

function updateViewerCaption() {
  const im = state.images[viewerIndex];
  $('#viewerCaption').textContent = im ? `${viewerIndex + 1}/${state.images.length} ${im.name}` : '';
}

function closeViewer() {
  if (viewerObserver) viewerObserver.disconnect();
  if (viewerSentinelObserver) viewerSentinelObserver.disconnect();
  viewerSentinel = null;
  $('#viewerStrip').innerHTML = '';
  hide($('#viewer'));
}

/* ---------------- 视频播放 ---------------- */

function openVideo(item, poster) {
  $('#videoTitle').textContent = item.name;
  const v = $('#videoPlayer');
  v.poster = poster ? rawUrl(state.sourceId, poster.path) : '';
  v.src = rawUrl(state.sourceId, item.path);
  show($('#videoModal'));
  v.play().catch(() => {});
}

function closeVideo() {
  const v = $('#videoPlayer');
  v.pause();
  v.removeAttribute('src');
  v.load();
  hide($('#videoModal'));
}

/* ---------------- 弹窗通用 ---------------- */

function openModal(id) {
  show(document.getElementById(id));
}

function closeModal(id) {
  if (id === 'videoModal') closeVideo();
  else hide(document.getElementById(id));
}

/* ---------------- 源管理 ---------------- */

function renderSourceList() {
  const ul = $('#sourceList');
  ul.innerHTML = '';
  if (!state.sources.length) {
    const li = document.createElement('li');
    li.className = 'source-item';
    li.innerHTML = '<div class="source-info"><div class="source-url">暂无源，请在下方添加</div></div>';
    ul.appendChild(li);
    return;
  }
  for (const s of state.sources) {
    const li = document.createElement('li');
    li.className = 'source-item';
    const syncing = s.syncStatus === 1;
    li.innerHTML =
      `<div class="source-info">` +
      `<div class="source-name">${esc(s.name)}${s.enabled ? '' : ' <span class="tag-disabled">已停用</span>'}</div>` +
      `<div class="source-url">${esc(s.serverUrl)}</div>` +
      `<div class="source-sync">${syncStatusText(s)}</div>` +
      `</div>` +
      `<div class="source-actions">` +
      `<button type="button" class="btn btn-small" data-act="sync" data-id="${s.id}" ${syncing ? 'disabled' : ''}>更新资源</button>` +
      `<button type="button" class="btn btn-small" data-act="edit" data-id="${s.id}">编辑</button>` +
      `<button type="button" class="btn btn-small btn-danger" data-act="del" data-id="${s.id}">删除</button>` +
      `</div>`;
    ul.appendChild(li);
  }
}

function fillForm(source) {
  state.editingId = source ? source.id : null;
  $('#editingId').value = source ? source.id : '';
  $('#formTitle').textContent = source ? '编辑源：' + source.name : '添加源';
  $('#f_name').value = source ? source.name : '';
  $('#f_serverUrl').value = source ? source.serverUrl : '';
  $('#f_username').value = source ? (source.username || '') : '';
  $('#f_password').value = '';
  $('#f_password').placeholder = source ? '留空表示不修改' : '可留空';
  $('#f_enabled').checked = source ? !!source.enabled : true;
  if (source) show($('#cancelEditBtn'));
  else hide($('#cancelEditBtn'));
}

function formPayload() {
  return {
    name: $('#f_name').value.trim(),
    serverUrl: $('#f_serverUrl').value.trim(),
    username: $('#f_username').value.trim(),
    password: $('#f_password').value,
    enabled: $('#f_enabled').checked,
  };
}

async function onTest() {
  const data = formPayload();
  if (!data.serverUrl) return toast('请先填写服务器地址', false);
  const btn = $('#testBtn');
  btn.disabled = true;
  btn.textContent = '测试中…';
  try {
    await api('/api/sources/test', jsonBody(data));
    toast('连接成功');
  } catch (e) {
    toast(e.message, false);
  } finally {
    btn.disabled = false;
    btn.textContent = '测试连接';
  }
}

async function onSave(e) {
  e.preventDefault();
  const data = formPayload();
  if (!data.name) return toast('请填写名称', false);
  if (!data.serverUrl) return toast('请填写服务器地址', false);
  try {
    if (state.editingId) {
      await api('/api/sources/' + state.editingId, { ...jsonBody(data), method: 'PUT' });
      toast('已保存');
    } else {
      const created = await api('/api/sources', jsonBody(data));
      toast('添加成功');
      state.sourceId = created.id;
    }
    await loadSources();
    if (state.sourceId) $('#sourceSelect').value = state.sourceId;
    fillForm(null);
    hide($('#sourceModal'));
    if (state.sourceId) selectSource(state.sourceId);
  } catch (err) {
    toast(err.message, false);
  }
}

async function onDelete(id) {
  const s = state.sources.find((x) => String(x.id) === String(id));
  if (!confirm(`确定删除源「${s ? s.name : id}」吗？`)) return;
  try {
    await api('/api/sources/' + id, { method: 'DELETE' });
    toast('已删除');
    if (String(state.sourceId) === String(id)) state.sourceId = null;
    await loadSources();
    const target = state.sources[0];
    if (target) {
      $('#sourceSelect').value = target.id;
      selectSource(target.id);
    }
  } catch (e) {
    toast(e.message, false);
  }
}

/* ---------------- 事件绑定 ---------------- */

function bindEvents() {
  $('#sourceSelect').addEventListener('change', (e) => selectSource(e.target.value));
  $('#manageBtn').addEventListener('click', () => { fillForm(null); renderSourceList(); openModal('sourceModal'); });
  $('#logoutBtn').addEventListener('click', async () => {
    try { await fetch('/api/logout', { method: 'POST' }); } catch (e) { }
    location.href = '/login.html';
  });
  $('#emptyAddBtn').addEventListener('click', () => { fillForm(null); renderSourceList(); openModal('sourceModal'); });
  $('#testBtn').addEventListener('click', onTest);
  $('#sourceForm').addEventListener('submit', onSave);
  $('#cancelEditBtn').addEventListener('click', () => fillForm(null));

  $('#sourceList').addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    if (btn.dataset.act === 'edit') {
      const s = state.sources.find((x) => String(x.id) === btn.dataset.id);
      if (s) {
        fillForm(s);
        $('#sourceModal .modal-body').scrollTo({ top: 99999, behavior: 'smooth' });
      }
    } else if (btn.dataset.act === 'sync') {
      onSync(btn.dataset.id);
    } else if (btn.dataset.act === 'del') {
      onDelete(btn.dataset.id);
    }
  });

  document.querySelectorAll('[data-close]').forEach((btn) =>
    btn.addEventListener('click', () => closeModal(btn.dataset.close)));

  ['sourceModal', 'videoModal'].forEach((id) =>
    document.getElementById(id).addEventListener('click', (e) => {
      if (e.target.id === id) closeModal(id);
    }));

  // 搜索（输入防抖）
  $('#searchInput').addEventListener('input', onSearchInput);
  $('#searchClear').addEventListener('click', exitSearch);

  // 排序（后端分页排序，切换后重新拉取第一页）
  const sortSelect = $('#sortSelect');
  sortSelect.value = state.sort;
  sortSelect.addEventListener('change', () => {
    state.sort = sortSelect.value;
    localStorage.setItem('wg_sort', state.sort);
    if (state.path === '/') browse('/');
  });

  // 悬浮返回上级
  $('#fabUp').addEventListener('click', () => {
    if (state.searching) exitSearch();
    else browse(dirOf(state.path));
  });

  // 查看器
  $('#viewerClose').addEventListener('click', closeViewer);
  $('#viewerPrev').addEventListener('click', () => viewerStep(-1));
  $('#viewerNext').addEventListener('click', () => viewerStep(1));
  // 滚动时更新当前图片序号（取视口上部 30% 处所在的图片）
  const strip = $('#viewerStrip');
  let captionRaf = 0;
  strip.addEventListener('scroll', () => {
    if (captionRaf) return;
    captionRaf = requestAnimationFrame(() => {
      captionRaf = 0;
      if (!state.images.length) return;
      const probe = strip.scrollTop + strip.clientHeight * 0.3;
      let idx = 0;
      for (let i = 0; i < strip.children.length; i++) {
        if (strip.children[i].offsetTop <= probe) idx = i;
        else break;
      }
      if (idx !== viewerIndex) {
        viewerIndex = idx;
        updateViewerCaption();
      }
    });
  }, { passive: true });

  document.addEventListener('keydown', (e) => {
    if (!$('#viewer').classList.contains('hidden')) {
      if (e.key === 'Escape') closeViewer();
      else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') viewerStep(-1);
      else if (e.key === 'ArrowDown' || e.key === 'ArrowRight') viewerStep(1);
    } else if (!$('#videoModal').classList.contains('hidden')) {
      if (e.key === 'Escape') closeVideo();
    } else if (!$('#sourceModal').classList.contains('hidden')) {
      if (e.key === 'Escape') hide($('#sourceModal'));
    }
  });
}

/* ---------------- 启动 ---------------- */

async function init() {
  bindEvents();
  ensureGridSentinel();
  try {
    await loadSources();
  } catch (e) {
    $('#errorBar').textContent = e.message;
    show($('#errorBar'));
    return;
  }
  const remembered = localStorage.getItem('wg_source');
  const target = state.sources.find((s) => String(s.id) === remembered) || state.sources[0];
  if (target) {
    $('#sourceSelect').value = target.id;
    selectSource(target.id);
  }
}

document.addEventListener('DOMContentLoaded', init);
