/**
 * 聊天台入口：点「开始聊天」→ hub 确保模型已加载 → 新标签页打开 llama.cpp 自带 WebUI。
 *
 * 所有模型共用聊天台的同一个 origin，所以换模型后历史还是同一份（存在浏览器 IndexedDB，
 * 由注入脚本同步到项目内 data/chat/）。远程节点上的模型不在聊天台里，直接提示。
 */
(function () {
  'use strict';

  function toast(type, message) {
    if (typeof showToast === 'function') {
      showToast('聊天台', message, type);
    } else {
      console.log('[chat]', type, message);
    }
  }

  async function openChatHub(event, modelId, nodeId) {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    if (nodeId && nodeId !== 'local') {
      toast('warning', '远程节点上的模型请到对应节点上打开聊天台');
      return;
    }
    let url = null;
    try {
      const response = await fetch('/api/models/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(modelId ? { modelId: modelId } : {})
      });
      const data = await response.json();
      if (!data || !data.success) {
        toast('error', (data && data.error) ? data.error : '打开聊天台失败');
        return;
      }
      url = data.data && data.data.url;
    } catch (e) {
      toast('error', '打开聊天台失败：' + (e && e.message ? e.message : ''));
      return;
    }
    if (!url) {
      toast('error', '聊天台地址为空');
      return;
    }
    window.open(url, '_blank', 'noopener');
  }

  function $(id) { return document.getElementById(id); }

  async function loadChatHubSettings() {
    try {
      const response = await fetch('/api/chat/proxy/config', { cache: 'no-store' });
      const data = await response.json();
      if (!data || !data.success) { return; }
      const config = data.data || {};
      if ($('chatPortInput')) { $('chatPortInput').value = config.port || ''; }
      if ($('chatAutoLoadInput')) { $('chatAutoLoadInput').value = Math.round((config.autoLoadTimeoutMs || 180000) / 1000); }
      if ($('chatToolsInput')) { $('chatToolsInput').value = config.tools || ''; }
      if ($('chatMcpProxyInput')) { $('chatMcpProxyInput').checked = !!config.uiMcpProxy; }
      if ($('chatMcpJsonInput')) { $('chatMcpJsonInput').value = config.mcpServersJson || ''; }
      if ($('chatHistoryDirInput')) { $('chatHistoryDirInput').value = config.historyDir || ''; }
    } catch (e) {
      console.warn('[chat] 读取聊天台设置失败', e);
    }
  }

  async function saveChatHubSettings() {
    const payload = {
      port: parseInt(($('chatPortInput') || {}).value, 10),
      autoLoadTimeoutMs: parseInt(($('chatAutoLoadInput') || {}).value, 10) * 1000,
      tools: (($('chatToolsInput') || {}).value || '').trim(),
      uiMcpProxy: !!($('chatMcpProxyInput') || {}).checked,
      mcpServersJson: (($('chatMcpJsonInput') || {}).value || '').trim()
    };
    if (!payload.port || payload.port < 1024 || payload.port > 65535) {
      toast('warning', '端口要在 1024-65535 之间');
      return;
    }
    try {
      const response = await fetch('/api/chat/proxy/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await response.json();
      if (!data || !data.success) {
        toast('error', (data && data.error) ? data.error : '保存失败');
        return;
      }
      toast('success', '聊天台设置已保存，端口 ' + data.data.port + (data.data.running ? '（运行中）' : '（未启动）'));
      loadChatHubSettings();
    } catch (e) {
      toast('error', '保存失败：' + (e && e.message ? e.message : ''));
    }
  }

  document.addEventListener('click', function (event) {
    const tab = event.target.closest && event.target.closest('[data-tab="chat"]');
    if (tab) { loadChatHubSettings(); }
  });

  document.addEventListener('DOMContentLoaded', function () {
    const button = $('saveChatHubBtn');
    if (button) { button.addEventListener('click', saveChatHubSettings); }
  });

  window.openChatHub = openChatHub;
  window.loadChatHubSettings = loadChatHubSettings;
})();
