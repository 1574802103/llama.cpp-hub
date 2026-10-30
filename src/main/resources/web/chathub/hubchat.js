/**
 * llama.cpp-hub 聊天台注入脚本。
 *
 * 做三件事：
 *   1. 把浏览器 IndexedDB（库名 LlamaUi）里的会话/消息同步到项目内 data/chat/，打开页面时再灌回来；
 *   2. 同步 WebUI 的 localStorage 配置（MCP 服务器、工具开关、采样设置），让设置跟着项目走；
 *   3. 左下角加模型切换器 + 「这个模型参与过的会话」列表：切换模型只是换 ?m=，历史仍是同一份。
 *
 * 上游改版导致结构变化时，任何一步失败都只降级为「浏览器本地历史」，不影响聊天本身。
 */
(function () {
  'use strict';

  var MODEL = window.__HUB_MODEL || '';
  var API = '/__hubchat';
  var DB_NAME = 'LlamaUi';
  var CONFIG_KEYS = [
    'LlamaUi.config',
    'LlamaUi.alwaysAllowedTools',
    'LlamaUi.disabledTools',
    'LlamaUi.favoriteModels',
    'LlamaUi.userOverrides'
  ];

  var db = null;
  var lastPushSignature = '';
  var knownConversations = null;
  var models = [];

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function log() {
    try { console.log.apply(console, ['[hubchat]'].concat([].slice.call(arguments))); } catch (e) { /* ignore */ }
  }

  /* ------------------------------------------------------------------ IndexedDB */

  function openDb() {
    return new Promise(function (resolve, reject) {
      // 不带版本号打开：LlamaUi 的 version 随上游变（见过 1 和 10），写死会抛 VersionError
      var request = indexedDB.open(DB_NAME);
      request.onsuccess = function () { resolve(request.result); };
      request.onerror = function () { reject(request.error); };
    });
  }

  async function waitForDb(timeoutMs) {
    var deadline = Date.now() + (timeoutMs || 20000);
    while (Date.now() < deadline) {
      try {
        var databases = await indexedDB.databases();
        if (databases.some(function (item) { return item.name === DB_NAME; })) {
          var candidate = await openDb();
          if (candidate.objectStoreNames.contains('conversations')
              && candidate.objectStoreNames.contains('messages')) {
            return candidate;
          }
          candidate.close();
        }
      } catch (e) {
        try {
          var fallback = await openDb();
          if (fallback.objectStoreNames.contains('conversations')) {
            return fallback;
          }
          fallback.close();
        } catch (ignored) { /* keep waiting */ }
      }
      await sleep(400);
    }
    return null;
  }

  function getAll(store) {
    return new Promise(function (resolve, reject) {
      var request = store.getAll();
      request.onsuccess = function () { resolve(request.result || []); };
      request.onerror = function () { reject(request.error); };
    });
  }

  async function dumpDb() {
    var tx = db.transaction(['conversations', 'messages'], 'readonly');
    var conversations = await getAll(tx.objectStore('conversations'));
    var messages = await getAll(tx.objectStore('messages'));
    return { conversations: conversations, messages: messages };
  }

  function signatureOf(payload) {
    var maxConv = 0;
    var maxMsg = 0;
    for (var i = 0; i < payload.conversations.length; i++) {
      maxConv = Math.max(maxConv, Number(payload.conversations[i].lastModified || 0));
    }
    for (var j = 0; j < payload.messages.length; j++) {
      maxMsg = Math.max(maxMsg, Number(payload.messages[j].timestamp || 0));
    }
    return payload.conversations.length + ':' + payload.messages.length + ':' + maxConv + ':' + maxMsg;
  }

  async function applySnapshot(snapshot) {
    if (!snapshot) { return false; }
    var incomingConversations = snapshot.conversations || [];
    var incomingMessages = snapshot.messages || [];
    var tombs = snapshot.tombs || {};
    if (!incomingConversations.length && !incomingMessages.length
        && !(tombs.conversations || []).length && !(tombs.messages || []).length) {
      return false;
    }

    var tx = db.transaction(['conversations', 'messages'], 'readwrite');
    var convStore = tx.objectStore('conversations');
    var msgStore = tx.objectStore('messages');

    var localConversations = new Map();
    (await getAll(convStore)).forEach(function (row) { localConversations.set(row.id, row); });
    var localMessages = new Map();
    (await getAll(msgStore)).forEach(function (row) { localMessages.set(row.id, row); });

    var changed = false;
    (tombs.conversations || []).forEach(function (id) {
      if (localConversations.has(id)) {
        convStore.delete(id);
        localConversations.delete(id);
        changed = true;
      }
    });
    (tombs.messages || []).forEach(function (id) {
      if (localMessages.has(id)) {
        msgStore.delete(id);
        localMessages.delete(id);
        changed = true;
      }
    });

    incomingConversations.forEach(function (row) {
      if (!row || !row.id) { return; }
      var local = localConversations.get(row.id);
      if (!local || Number(row.lastModified || 0) > Number(local.lastModified || 0)) {
        convStore.put(row);
        changed = true;
      }
    });
    incomingMessages.forEach(function (row) {
      if (!row || !row.id) { return; }
      var local = localMessages.get(row.id);
      if (!local || Number(row.timestamp || 0) > Number(local.timestamp || 0)) {
        msgStore.put(row);
        changed = true;
      }
    });

    await new Promise(function (resolve, reject) {
      tx.oncomplete = function () { resolve(); };
      tx.onerror = function () { reject(tx.error); };
      tx.onabort = function () { reject(tx.error); };
    });
    return changed;
  }

  /* ------------------------------------------------------------------ 与 hub 同步 */

  async function pullSnapshot() {
    var response = await fetch(API + '/snapshot?m=' + encodeURIComponent(MODEL), { cache: 'no-store' });
    return response.ok ? response.json() : null;
  }

  function collectConfigItems() {
    var items = [];
    CONFIG_KEYS.forEach(function (key) {
      try {
        var value = localStorage.getItem(key);
        if (value !== null) { items.push({ key: key, value: value }); }
      } catch (e) { /* ignore */ }
    });
    return items;
  }

  function restoreConfig(snapshot) {
    var values = snapshot && snapshot.config && snapshot.config.values;
    if (!values) { return; }
    CONFIG_KEYS.forEach(function (key) {
      try {
        if (localStorage.getItem(key) === null && typeof values[key] === 'string') {
          localStorage.setItem(key, values[key]);
          log('恢复配置', key);
        }
      } catch (e) { /* ignore */ }
    });
  }

  async function pushSnapshot(reason) {
    if (!db) { return; }
    var payload = await dumpDb();
    var signature = signatureOf(payload);
    if (signature === lastPushSignature) { return; }
    lastPushSignature = signature;

    var tombstones = { conversations: [], messages: [] };
    var ids = new Set(payload.conversations.map(function (row) { return row.id; }));
    if (knownConversations) {
      knownConversations.forEach(function (id) {
        if (!ids.has(id)) { tombstones.conversations.push(id); }
      });
    }
    knownConversations = ids;

    try {
      await fetch(API + '/snapshot?m=' + encodeURIComponent(MODEL), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          db: { conversations: payload.conversations, messages: payload.messages, tombs: tombstones },
          config: { items: collectConfigItems() }
        })
      });
      log('已同步历史', reason || '', signature);
    } catch (e) {
      log('同步失败', e && e.message);
    }
  }

  /* ------------------------------------------------------------------ 界面 */

  var styleInjected = false;
  function injectStyle() {
    if (styleInjected) { return; }
    styleInjected = true;
    var style = document.createElement('style');
    style.textContent = [
      '#hubchat-switch{position:fixed;left:14px;bottom:14px;z-index:2147483000;font:12px/1.3 system-ui,-apple-system,"Segoe UI",sans-serif;',
      'background:#161a22;color:#e5e7eb;border:1px solid #2b3240;border-radius:10px;padding:7px 10px;cursor:pointer;',
      'box-shadow:0 6px 20px rgba(0,0,0,.35);max-width:240px;display:flex;align-items:center;gap:8px}',
      '#hubchat-switch:hover{border-color:#3f4a5c}',
      '#hubchat-switch .dot{width:7px;height:7px;border-radius:50%;background:#22c55e;flex:0 0 auto}',
      '#hubchat-switch span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
      '#hubchat-menu{position:fixed;left:14px;bottom:54px;z-index:2147483001;background:#161a22;border:1px solid #2b3240;',
      'border-radius:12px;padding:6px;min-width:340px;max-width:480px;max-height:66vh;overflow:auto;box-shadow:0 10px 30px rgba(0,0,0,.45)}',
      '#hubchat-menu .item{padding:6px 8px 6px 10px;border-radius:8px;font:12px/1.3 system-ui,sans-serif;color:#e5e7eb;',
      'display:flex;gap:8px;align-items:center}',
      '#hubchat-menu .item:hover{background:#1a202b}',
      '#hubchat-menu .item.active{background:#1d2a3f}',
      '#hubchat-menu .item.active .name{color:#93c5fd}',
      '#hubchat-menu .item .name{flex:1 1 auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;cursor:default}',
      '#hubchat-menu .item .state{font-size:10px;color:#64748b;flex:0 0 auto;white-space:nowrap}',
      '#hubchat-menu .item .state.on{color:#4ade80}',
      '#hubchat-menu .item .mini{font:10px/1 system-ui,sans-serif;padding:4px 7px;border-radius:6px;border:1px solid #2b3240;',
      'background:transparent;color:#93c5fd;cursor:pointer;flex:0 0 auto}',
      '#hubchat-menu .item .mini:hover{border-color:#3b82f6;background:rgba(59,130,246,.12)}',
      '#hubchat-menu .item .act{width:26px;height:26px;border-radius:6px;border:1px solid #2b3240;background:#0f141c;',
      'display:flex;align-items:center;justify-content:center;cursor:pointer;flex:0 0 auto;padding:0}',
      '#hubchat-menu .item .act.start:hover{border-color:#16a34a;background:rgba(34,197,94,.14)}',
      '#hubchat-menu .item .act.stop:hover{border-color:#dc2626;background:rgba(239,68,68,.14)}',
      '#hubchat-menu .item .act:disabled{opacity:.6;cursor:default}',
      '.ico-play{width:0;height:0;border-left:9px solid #22c55e;border-top:5.5px solid transparent;border-bottom:5.5px solid transparent;margin-left:3px}',
      '.ico-stop{width:10px;height:10px;background:#ef4444;border-radius:1.5px}',
      '.hubchat-spin{width:11px;height:11px;border:2px solid #334155;border-top-color:#60a5fa;border-radius:50%;animation:hubchat-spin .8s linear infinite}',
      '@keyframes hubchat-spin{to{transform:rotate(360deg)}}',
      '#hubchat-menu .title{font-size:11px;color:#64748b;padding:8px 10px 4px}',
      '#hubchat-menu .sep{height:1px;background:#232a36;margin:6px 4px}'
    ].join('');
    document.head.appendChild(style);
  }

  function buildSwitcher() {
    injectStyle();
    var button = document.createElement('div');
    button.id = 'hubchat-switch';
    button.innerHTML = '<i class="dot"></i><span></span>';
    document.body.appendChild(button);

    var menu = null;
    var rowsBox = null;
    var conversations = [];

    function closeMenu() {
      if (menu && menu.parentNode) { menu.parentNode.removeChild(menu); }
      menu = null;
      rowsBox = null;
    }

    function renderName() {
      var hit = models.filter(function (item) { return item.id === MODEL; })[0];
      var label = hit ? (hit.name || hit.id) : (MODEL || '未选择模型');
      button.querySelector('span').textContent = label;
      button.title = '当前模型：' + label + '（点击查看模型状态/启停）';
    }

    async function loadData() {
      try {
        var response = await fetch(API + '/models?m=' + encodeURIComponent(MODEL), { cache: 'no-store' });
        var data = await response.json();
        models = data.models || [];
      } catch (e) { models = []; }
      try {
        var convResponse = await fetch(API + '/conversations?m=' + encodeURIComponent(MODEL), { cache: 'no-store' });
        var convData = await convResponse.json();
        conversations = convData.conversations || [];
      } catch (e) { conversations = []; }
      renderName();
      if (rowsBox) { renderRows(); }
    }

    function addTitle(text) {
      var title = document.createElement('div');
      title.className = 'title';
      title.textContent = text;
      menu.appendChild(title);
    }

    function addSeparator() {
      var sep = document.createElement('div');
      sep.className = 'sep';
      menu.appendChild(sep);
    }

    /** 名字只做展示；只有启停按钮（以及运行中模型的「切换」）有动作。 */
    function renderRows() {
      if (!rowsBox) { return; }
      rowsBox.innerHTML = '';
      models.forEach(function (item) {
        var row = document.createElement('div');
        row.className = 'item' + (item.id === MODEL ? ' active' : '');

        var name = document.createElement('span');
        name.className = 'name';
        name.textContent = item.name || item.id;
        name.title = item.id;

        var state = document.createElement('span');
        state.className = 'state';
        if (item.loading) {
          state.textContent = '启动中…';
        } else if (item.running) {
          state.textContent = '运行中';
          state.className += ' on';
        }

        row.appendChild(name);
        row.appendChild(state);

        if (item.running && item.id !== MODEL) {
          var sw = document.createElement('button');
          sw.className = 'mini';
          sw.textContent = '切换';
          sw.title = '切到这个模型聊天（历史共用同一份）';
          sw.addEventListener('click', function (event) {
            event.stopPropagation();
            window.location.href = '/?m=' + encodeURIComponent(item.id);
          });
          row.appendChild(sw);
        }

        var act = document.createElement('button');
        act.className = 'act ' + (item.running ? 'stop' : 'start');
        act.title = item.running ? '停止' : '启动';
        act.disabled = !!item.loading;
        if (item.loading) {
          act.innerHTML = '<span class="hubchat-spin"></span>';
        } else {
          act.innerHTML = item.running ? '<span class="ico-stop"></span>' : '<span class="ico-play"></span>';
        }
        act.addEventListener('click', function (event) {
          event.stopPropagation();
          if (item.loading) { return; }
          control(item, item.running ? 'stop' : 'start');
        });
        row.appendChild(act);

        rowsBox.appendChild(row);
      });
    }

    async function control(item, action) {
      item.loading = action === 'start';
      renderRows();
      try {
        await fetch(API + '/' + action + '?m=' + encodeURIComponent(item.id), { method: 'POST' });
      } catch (e) { /* ignore */ }
      setTimeout(loadData, 700);
      setTimeout(loadData, 2500);
      setTimeout(loadData, 6000);
    }

    function openMenu() {
      closeMenu();
      menu = document.createElement('div');
      menu.id = 'hubchat-menu';

      addTitle('模型（点右侧按钮启动/停止）');
      rowsBox = document.createElement('div');
      menu.appendChild(rowsBox);
      renderRows();

      if (conversations.length) {
        addSeparator();
        addTitle('这个模型参与过的会话（点击打开）');
        conversations.slice(0, 20).forEach(function (conversation) {
          var row = document.createElement('div');
          row.className = 'item';
          var name = document.createElement('span');
          name.className = 'name';
          name.textContent = conversation.name || conversation.id;
          name.title = conversation.id;
          var tag = document.createElement('span');
          tag.className = 'state';
          tag.textContent = (conversation.models || []).join(', ');
          row.appendChild(name);
          row.appendChild(tag);
          row.addEventListener('click', function () {
            window.location.href = '/?m=' + encodeURIComponent(MODEL) + '#/chat/' + conversation.id;
          });
          menu.appendChild(row);
        });
      }

      document.body.appendChild(menu);
      setTimeout(function () {
        document.addEventListener('click', function onDoc(event) {
          if (menu && !menu.contains(event.target) && event.target !== button) {
            closeMenu();
            document.removeEventListener('click', onDoc);
          }
        });
      }, 0);
    }

    button.addEventListener('click', function () {
      if (menu) { closeMenu(); return; }
      openMenu();
    });

    loadData();
    setInterval(loadData, 5000);
  }

  /* ------------------------------------------------------------------ 启动 */

  async function boot() {
    db = await waitForDb(20000);
    if (!db) { log('没等到 LlamaUi 库，跳过历史同步'); return; }

    var snapshot = null;
    try { snapshot = await pullSnapshot(); } catch (e) { log('读取快照失败', e && e.message); }

    var local = await dumpDb();
    var isEmpty = local.conversations.length === 0;

    if (snapshot && snapshot.db) {
      var changed = await applySnapshot(snapshot.db);
      restoreConfig(snapshot);
      if (isEmpty && changed) {
        var conversations = (snapshot.db.conversations || []).slice().sort(function (a, b) {
          return Number(b.lastModified || 0) - Number(a.lastModified || 0);
        });
        var target = conversations.length ? conversations[0].id : null;
        if (target && !location.hash) {
          location.replace('/?m=' + encodeURIComponent(MODEL) + '#/chat/' + target);
          return;
        }
      }
    }

    knownConversations = new Set((await dumpDb()).conversations.map(function (row) { return row.id; }));
    lastPushSignature = signatureOf(await dumpDb());

    buildSwitcher();
    setInterval(function () { pushSnapshot('tick'); }, 5000);
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') { pushSnapshot('hidden'); }
    });
    window.addEventListener('beforeunload', function () { pushSnapshot('unload'); });
    log('已启动，模型', MODEL);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
