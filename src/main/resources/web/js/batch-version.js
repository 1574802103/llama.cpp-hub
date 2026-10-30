/**
 * 模型列表「批量切换 llama.cpp 版本」。
 *
 * 交互（按主人要求，另加了几处防呆）：
 *   1. 点「批量切换版本」→ 弹出已安装版本列表；
 *   2. 选中版本 → 按钮变成「确定 / 取消」，列表上方出现全选条，每行星星变成复选框（默认全选）；
 *   3. 点确定 → 只改所选模型的 llamaBinPathSelect（每个模型的全部启动配置一起改），
 *      运行中的模型提示「重启后生效」，没配过启动参数的模型在结果里列出来；
 *   4. 点取消 / Esc → 退出选择模式，配置不动。
 */
(function () {
    const LOCAL = 'local';

    const state = {
        mode: false,
        versions: [],
        target: null,
        selected: new Set(),   // key = "nodeId|modelId"
        userUnchecked: new Set(), // 用户手动取消过的 key，刷新列表时不再自动勾回
        knownKeys: [],         // 当前列表里可选的（本地）模型
        busy: false
    };

    function $(id) { return document.getElementById(id); }

    function esc(v) {
        return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function tr(key, fallback) {
        return (typeof t === 'function') ? t(key, fallback) : fallback;
    }

    function toast(type, message) {
        if (typeof showToast === 'function') {
            showToast(tr('page.model.batch_version.title', '批量切换 llama.cpp 版本'), message, type);
        }
    }

    function decodeKey(raw) {
        try { return decodeURIComponent(raw || ''); } catch (e) { return raw || ''; }
    }

    function splitKey(key) {
        const idx = key.indexOf('|');
        if (idx < 0) return { nodeId: LOCAL, modelId: key };
        return { nodeId: key.slice(0, idx), modelId: key.slice(idx + 1) };
    }

    /* ------------------------------------------------------------------ 版本菜单 */
    function closeMenu() {
        const menu = $('batchVersionMenu');
        if (menu) menu.style.display = 'none';
    }

    function openMenu(ev) {
        if (ev) { ev.preventDefault(); ev.stopPropagation(); }
        const menu = $('batchVersionMenu');
        const btn = $('batchVersionBtn');
        if (!menu || !btn) return;
        if (state.mode) return;
        if (menu.style.display === 'block') { closeMenu(); return; }

        // 绝对定位挂在按钮容器上（.top-bar 有 backdrop-filter，fixed 会以它为包含块而错位）
        const rect = btn.getBoundingClientRect();
        menu.style.maxHeight = Math.max(160, Math.min(340, window.innerHeight - rect.bottom - 24)) + 'px';
        menu.style.left = '0';
        menu.style.top = 'calc(100% + 6px)';
        menu.style.display = 'block';
        menu.innerHTML = '<div class="bv-title">' + esc(tr('page.model.batch_version.loading', '正在读取已安装的 llama.cpp 版本…')) + '</div>';

        fetch('/api/llamacpp/list', { headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.json(); })
            .then(function (res) {
                const items = (res && res.data && res.data.items) || [];
                state.versions = items;
                if (!items.length) {
                    menu.innerHTML = '<div class="bv-title">' +
                        esc(tr('page.model.batch_version.empty',
                            '还没有安装任何 llama.cpp 版本。请先到「设置 → 更新 → llama.cpp 下载」安装后再来切换。')) +
                        '</div>';
                    return;
                }
                let html = '<div class="bv-title">' + esc(tr('page.model.batch_version.menu_title', '选择要应用到的版本')) + '</div>';
                items.forEach(function (it) {
                    const name = it.name || it.path || '';
                    html += '<div class="batch-version-item" data-path="' + esc(encodeURIComponent(it.path || '')) + '" title="' + esc(it.path || '') + '">' +
                        '<span class="bv-name">' + esc(name) + '</span>' +
                        '<span class="bv-meta">' + esc(it.source === 'scanned' ? tr('page.model.batch_version.scanned', '自动扫描') : tr('page.model.batch_version.added', '已添加')) + '</span>' +
                        '</div>';
                });
                menu.innerHTML = html;
                menu.querySelectorAll('.batch-version-item').forEach(function (row) {
                    row.addEventListener('click', function () {
                        choose(decodeKey(row.dataset.path));
                    });
                });
            })
            .catch(function () {
                menu.innerHTML = '<div class="bv-title">' + esc(tr('page.model.batch_version.load_failed', '读取版本列表失败')) + '</div>';
            });
    }

    /* ------------------------------------------------------------------ 进入/退出选择模式 */
    function choose(path) {
        const hit = (state.versions || []).find(function (v) { return v.path === path; });
        if (!hit) return;
        state.target = hit;
        closeMenu();
        enterMode();
    }

    function enterMode() {
        state.mode = true;
        state.selected = new Set();
        state.userUnchecked = new Set();
        state.knownKeys = [];
        document.body.classList.add('batch-select-mode');
        $('batchVersionBtn').style.display = 'none';
        $('batchVersionConfirm').style.display = '';
        $('batchVersionCancel').style.display = '';
        $('batchTargetVersion').textContent = state.target.name || state.target.path;
        // 默认全选当前列表里的本地模型
        collectKeys();
        state.knownKeys.forEach(function (k) { state.selected.add(k); });
        syncCheckboxes();
        updateBar();
    }

    function exitMode() {
        state.mode = false;
        state.target = null;
        state.selected = new Set();
        state.userUnchecked = new Set();
        state.busy = false;
        document.body.classList.remove('batch-select-mode');
        closeMenu();
        const btn = $('batchVersionBtn');
        const ok = $('batchVersionConfirm');
        const cancel = $('batchVersionCancel');
        if (btn) btn.style.display = '';
        if (ok) { ok.style.display = 'none'; ok.disabled = false; ok.innerHTML = '<i class="fas fa-check"></i> <span>' + esc(tr('page.model.batch_version.confirm', '确定')) + '</span>'; }
        if (cancel) cancel.style.display = 'none';
        const all = $('batchSelectAll');
        if (all) { all.checked = true; all.indeterminate = false; }
        const target = $('batchTargetVersion');
        if (target) target.textContent = '-';
    }

    function collectKeys() {
        const keys = [];
        document.querySelectorAll('.model-batch-check').forEach(function (box) {
            if (box.dataset.remote === '1') return;
            const key = decodeKey(box.dataset.key);
            if (key && keys.indexOf(key) < 0) keys.push(key);
        });
        state.knownKeys = keys;
    }

    function syncCheckboxes() {
        document.querySelectorAll('.model-batch-check').forEach(function (box) {
            if (box.dataset.remote === '1') {
                box.disabled = true;
                box.checked = false;
                box.title = tr('page.model.batch_version.remote_disabled', '远程节点上的模型请到对应节点上操作');
                return;
            }
            const key = decodeKey(box.dataset.key);
            box.checked = state.selected.has(key);
        });
    }

    function updateBar() {
        const total = state.knownKeys.length;
        const picked = state.knownKeys.filter(function (k) { return state.selected.has(k); }).length;
        const count = $('batchSelectCount');
        if (count) {
            count.textContent = tr('page.model.batch_version.selected', '已选 {n} / {m}')
                .replace('{n}', String(picked)).replace('{m}', String(total));
        }
        const all = $('batchSelectAll');
        if (all) {
            all.checked = total > 0 && picked === total;
            all.indeterminate = picked > 0 && picked < total;
        }
        const ok = $('batchVersionConfirm');
        if (ok && !state.busy) ok.disabled = picked === 0;
    }

    /* ------------------------------------------------------------------ 勾选 */
    function toggleAll(checked) {
        state.selected = new Set();
        state.userUnchecked = new Set();
        if (checked) {
            state.knownKeys.forEach(function (k) { state.selected.add(k); });
        } else {
            state.knownKeys.forEach(function (k) { state.userUnchecked.add(k); });
        }
        syncCheckboxes();
        updateBar();
    }

    function onCheckChange(box) {
        if (!state.mode || !box) return;
        if (box.dataset.remote === '1') { box.checked = false; return; }
        const key = decodeKey(box.dataset.key);
        if (!key) return;
        if (box.checked) {
            state.selected.add(key);
            state.userUnchecked.delete(key);
        } else {
            state.selected.delete(key);
            state.userUnchecked.add(key);
        }
        updateBar();
    }

    /* ------------------------------------------------------------------ 提交 */
    function confirm() {
        if (!state.mode || state.busy || !state.target) return;
        const ids = [];
        state.selected.forEach(function (k) {
            const parts = splitKey(k);
            if (parts.nodeId === LOCAL && parts.modelId) ids.push(parts.modelId);
        });
        if (!ids.length) {
            toast('warning', tr('page.model.batch_version.none_selected', '请至少勾选一个模型'));
            return;
        }
        state.busy = true;
        const ok = $('batchVersionConfirm');
        if (ok) {
            ok.disabled = true;
            ok.innerHTML = '<i class="fas fa-spinner fa-spin"></i> <span>' + esc(tr('page.model.batch_version.switching', '切换中…')) + '</span>';
        }

        fetch('/api/models/launch/batch-bin', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ llamaBinPath: state.target.path, modelIds: ids })
        }).then(function (r) { return r.json(); }).then(function (res) {
            state.busy = false;
            if (!res || !res.success) {
                if (ok) { ok.disabled = false; ok.innerHTML = '<i class="fas fa-check"></i> <span>' + esc(tr('page.model.batch_version.confirm', '确定')) + '</span>'; }
                let errText = (res && res.error) ? res.error : tr('page.model.batch_version.failed', '批量切换失败');
                if (String(errText).indexOf('404') >= 0) {
                    errText = tr('page.model.batch_version.need_restart',
                        '当前 hub 进程还是旧版本，重启一次 llama.cpp-hub 后该功能才可用。');
                }
                toast('error', errText);
                return;
            }
            const d = res.data || {};
            const updated = d.updatedCount || 0;
            const skipped = Array.isArray(d.skipped) ? d.skipped : [];
            const running = Array.isArray(d.running) ? d.running : [];
            const version = d.versionName || (state.target && (state.target.name || state.target.path)) || '';

            let msg;
            if (updated === 0) {
                const firstReason = skipped.length && skipped[0].reason ? skipped[0].reason : '';
                msg = tr('page.model.batch_version.all_skipped', '没有模型被切换');
                if (firstReason) msg += '：' + firstReason;
                if (firstReason.indexOf('启动参数') >= 0) {
                    msg += '。' + tr('page.model.batch_version.save_config_first',
                        '先给这些模型各保存一次启动配置再试。');
                }
                toast('error', msg);
                exitMode();
                return;
            }
            msg = tr('page.model.batch_version.success', '已把 {n} 个模型切换到 {v}')
                .replace('{n}', String(updated)).replace('{v}', version);
            if (skipped.length) {
                msg += '；' + tr('page.model.batch_version.skipped_tail', '{s} 个被跳过').replace('{s}', String(skipped.length));
            }
            if (running.length) {
                msg += '；' + tr('page.model.batch_version.running_tail', '{r} 个正在运行，重启模型后生效').replace('{r}', String(running.length));
            }
            toast('success', msg);
            if (skipped.length && window.console) {
                console.warn('[批量切版本] 被跳过的模型:', skipped);
            }
            exitMode();
            if (typeof refreshModels === 'function') refreshModels();
        }).catch(function (e) {
            state.busy = false;
            if (ok) { ok.disabled = false; ok.innerHTML = '<i class="fas fa-check"></i> <span>' + esc(tr('page.model.batch_version.confirm', '确定')) + '</span>'; }
            toast('error', tr('page.model.batch_version.network_failed', '网络请求失败：') + (e && e.message ? e.message : ''));
        });
    }

    /* ------------------------------------------------------------------ 渲染后同步 */
    function afterRender() {
        if (!state.mode) return;
        collectKeys();
        // 新出现的模型默认也勾上，但用户手动取消过的不再自动勾回
        state.knownKeys.forEach(function (k) {
            if (!state.selected.has(k) && !state.userUnchecked.has(k)) state.selected.add(k);
        });
        syncCheckboxes();
        updateBar();
    }

    /* ------------------------------------------------------------------ 事件 */
    document.addEventListener('click', function (ev) {
        const menu = $('batchVersionMenu');
        const wrap = $('batchVersionWrap');
        if (!menu || menu.style.display !== 'block') return;
        if (wrap && wrap.contains(ev.target)) return;
        closeMenu();
    });

    document.addEventListener('keydown', function (ev) {
        if (ev.key !== 'Escape') return;
        const menu = $('batchVersionMenu');
        if (menu && menu.style.display === 'block') { closeMenu(); return; }
        if (state.mode) exitMode();
    });

    window.addEventListener('resize', function () {
        if (state.mode) return;
        closeMenu();
    });

    window.BatchVersion = {
        openMenu: openMenu,
        choose: choose,
        confirm: confirm,
        cancel: exitMode,
        toggleAll: toggleAll,
        onCheckChange: onCheckChange,
        afterRender: afterRender,
        isMode: function () { return state.mode; }
    };
})();
