# 聊天台（llama.cpp 自带 WebUI）

> EasyChat 模块已删除（见 CHANGELOG 第 8 节）。聊天入口只剩模型行上的「开始聊天」，左侧导航不再有入口。

接手维护后把原来的 EasyChat 换成了 llama.cpp 自带的 WebUI。这里记录它怎么工作、怎么排错。

## 设计

```
浏览器 ──> hub:8100 （聊天台，固定端口，所有模型共用这个 origin）
              ├─ 页面/资源/SSE 透传 ──> 127.0.0.1:<实例端口>
              ├─ 注入 __hubchat.js（同步历史 + 模型切换器）
              └─ /__hubchat/* 由 hub 自己处理 ──> data/chat/*.json.gz
```

- **所有模型共用一个 origin**：WebUI 的历史存在浏览器 IndexedDB（库名 `LlamaUi`），按 origin 隔离，
  会话表里没有模型字段。共用 origin 后，换模型（`?m=<模型id>`）看到的还是同一份历史，可以直接接着聊。
- **路由**：`?m=` → Referer 上的 `m=` → cookie `hub_model` → 上次使用的模型 → 第一个模型。
- **模型启停只认 hub**：聊天页/WebUI 永远不会自己拉起模型。模型没运行就显示「未运行」说明页
  （带一个显式的「启动模型」按钮）；API 请求返回 503 `model_not_running`；实例的 `/models/load`、
  `/models/unload`、`/models/sse` 在聊天台上直接 403。唯一会加载的时机是：hub 里点「开始聊天」
  （`/api/models/chat` 触发一次）或用户在「未运行」页点「启动模型」。
- **历史落盘**：注入脚本每 5 秒（以及页面隐藏/关闭时）把 conversations/messages 推到 `/__hubchat/snapshot`，
  服务端按 id + 时间戳合并后写 `data/chat/webui-db.json.gz`；打开页面时先拉快照、本地为空就导入并跳到最近会话。
- **设置也跟着走**：`LlamaUi.config`、`alwaysAllowedTools`、`disabledTools`、`favoriteModels`、`userOverrides`
  一起存进 `data/chat/webui-config.json.gz`，新机器打开时自动灌回 localStorage —— MCP 服务器列表、采样设置都在里面。
- **模型标注是上游自带的**：每条 assistant 消息里有 `model` 字段，UI 自己会显示模型名；所以换模型接着聊时，
  每条回复标注的是当时用的模型，不需要 hub 额外记录。
- **会话按模型筛选**：`/__hubchat/conversations?m=<模型>` 从快照里按消息的 `model` 字段筛，
  聊天页左下角切换器里直接列出「这个模型参与过的会话」。
- **模型列表与 hub 同源**：`/__hubchat/models` 直接复用 `ModelActionController.buildLocalModelList()`，
  和 hub「模型列表」是同一份数据（扫描到的 GGUF + 有效克隆体），额外带 `running`/`loading`。
- **行内启停**：`/__hubchat/start?m=` / `/__hubchat/stop?m=`；运行中显示红色方块（点击停止），未运行显示绿色三角
  （点击启动，先显示「启动中…」）。名字只做展示，不绑定点击；运行中的非当前模型额外给一个「切换」按钮。

## 文件

| 位置 | 作用 |
|---|---|
| `org/mark/llamacpp/server/chathub/ChatProxyServer.java` | 聊天台 Netty 服务 + 反代 + 注入 + `/__hubchat/*` |
| `ChatSnapshotStore.java` | `data/chat/*.json.gz` 的读写与合并（newer wins + 墓碑） |
| `ChatHubSettings.java` | `config/chathub.json`（端口、超时、tools、MCP、历史目录） |
| `controller/ChatProxyController.java` | `POST /api/models/chat`、`/api/chat/proxy/{status,config,models}` |
| `web/chathub/hubchat.js` | 注入脚本：快照同步 + 模型切换器 + 按模型筛会话 |
| `web/js/chat-hub.js` | hub 前端入口：点「开始聊天」→ 新标签页 |

## 设置项（config/chathub.json）

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 是否启动聊天台 |
| `port` | 8100 | 聊天台端口（固定，历史绑在它上面） |
| `listenAddress` | "" | 空表示跟随主服务 |
| `autoLoadTimeoutMs` | 180000 | 按需加载等待上限 |
| `uiMcpProxy` | true | 传 `--ui-mcp-proxy`，WebUI 里加远程 MCP 服务器需要 |
| `tools` | "" | 传 `--tools`：`all` 或逗号列表；空表示不给模型工具 |
| `mcpServersJson` | "" | 传 `--mcp-servers-json`（stdio 服务器） |
| `lastModelId` | "" | 最近聊天模型，`开始聊天` 不带模型时的兜底 |
| `historyDir` | "" | 空表示 `data/chat` |

## 踩过的坑

1. **`position: fixed` 不受影响，但 `backdrop-filter` 会影响**：`.top-bar` 有 `backdrop-filter: blur(8px)`，
   它会让 fixed 子元素以顶栏为包含块。聊天台的下拉是注入到自己 fixed 层里的，别在这个页面里再踩一次。
2. **llama-server 的 UI 只认 gzip**：不带 `Accept-Encoding: gzip` 会返回 44 字节 `Error: gzip is not supported by this browser`。
   代理对所有请求都强制 `Accept-Encoding: gzip`，文档解压后注入再发；客户端不接受 gzip 时再解压。
3. **304 别去解压**：浏览器二次加载会拿到 304 + 空 body，`GZIPInputStream` 会抛 `EOFException`（表现为「聊天台代理失败 null」）。
   现在只有 `200` 才走注入分支，304/204/HEAD 直接转发状态和头。
4. **SSE 不能缓冲**：`/v1/chat/completions` 等路径不设 Content-Length，逐块 `writeAndFlush`，
   `channel.isWritable()` 为 false 时 sleep 5ms 做背压。
5. **IndexedDB 版本别写死**：`LlamaUi` 的 version 在 b11946 是 10、b11981 是 1；打开时不带版本号，
   并且先等 `indexedDB.databases()` 里出现库再开，避免抢先建空库。
6. **端口就是历史**：换端口 = 换 origin = 历史「消失」。所以端口写在 `config/chathub.json` 里，别随手改。
7. **老配置里的 `--no-webui` 会让聊天页 404**（实测踩过）：EasyChat 时代的参数表把 `--no-webui` 当成默认开启项，
   25 个模型的 `cmd` 里都带了它，llama-server 因此不提供 UI，聊天台只会把
   `{"error":{"message":"File Not Found","type":"not_found_error","code":404}}` 原样转给浏览器。
   现在两处兜底：`LlamaServerManager.stripWebUiDisableFlags` 在拉起实例前把 `--no-webui`/`--no-ui`
   从 `cmd` 和 `extraParams` 里剥掉；参数表也删掉了这一项，前端不会再写回去。

## 验证清单

- 点「开始聊天」→ 新标签页打开 `http://<host>:8100/?m=<模型>`，流式对话正常
- 模型列表里换一个模型开聊天 → 侧栏里能看到另一个模型的会话，点开能接着聊（回复标注的是新模型）
- 重启 hub → 端口不变、历史还在
- 换 llama.cpp 版本（同端口）→ 历史还在（b11981 → b11867 实测过）
- 复制整个项目到另一台机器 → 打开聊天台能看到之前的会话（从 `data/chat/` 导入）
- 删掉的会话不会在别的机器上「复活」（墓碑）
- `config/chathub.json` 里 `tools=all` 后，进程启动命令带 `--tools all`，`/tools` 能列出工具
