# 调研：用 llama.cpp 自带 WebUI 替换 EasyChatService

调研日期 2026-10-29；基准 llama.cpp-hub 0.9.8.3（Java），实测用 llama.cpp b11981 与 b11867；环境 E:/llama.cpp-hub。

## 一、结论

**可行**，而且这条路上游一直在替我们做功能。推荐做法是「每个模型一个固定端口的聊天代理 + history 快照回项目目录」，
不是把 UI 移植进 hub。

三条判定：

1. 技术前提成立：WebUI 是 llama-server 内置的 SvelteKit 应用，`--no-webui` 在当前代码里已经被注释掉（`LlamaServerManager:2576`），
   每个实例本来就能访问 WebUI，不需要额外下载什么。
2. 两个关键诉求都有解：
   - 记录不随版本切换消失 —— 实测：同一端口 8299、同一个浏览器 profile，b11981 建的会话在换成 b11867 之后仍然在（见第四节）。
   - 记录随项目走 —— 浏览器 IndexedDB 不会跟着项目走，需要 hub 做快照落盘到 `data/chat/<模型id>/`，这块 Go 版已经实现并验证过。
3. 代价明确：**界面只有英文**（bundle 里 0 处 zh-CN/i18n），EasyChat 的助手人格/头像/背景/PNG 导出/录音/单会话切模型都没有对应物。

## 二、现状盘点：EasyChatService（Java 版）

### 2.1 代码体量

| 文件 | 行数 |
|---|---|
| `service/EasyChatService.java` | 2461 |
| `service/EasyChatStorage.java` | 693 |
| `controller/EasyChatController.java` | 686 |
| `controller/ChatStateController.java` | 568 |
| `service/EasyChatBackgroundService.java` | 529 |
| `service/EasyChatRequestWriter.java` | 310 |
| `service/EasyChatAvatarService.java` | 296 |
| `channel/EasyChatStreamingHandler.java` | 201 |
| `service/EasyChatGlobalLock.java` | 76 |
| 合计 | **5820** |

外加前端 `web/chat/`：`index.html` 498,623 B、`chat-mcp.js` 43,688 B、`index.css` 95,987 B（约 638 KB，单文件 SPA）。

### 2.2 存储

- 位置：`cache/easy-chat/fragments/<conversationId>/`，每条消息一个 `NNNNNNNNNNNNNNNN.bin` 分片，最多 10 个变体；
  目录内 `index.bin`（固定长度索引）+ `model_index.json`（消息序号 → 模型名）。
- 现场数据：81 个会话、约 50 MB。**已经在项目目录内，天然便携**。
- 会话内可切模型：`model_index.json` 记录了每条消息由哪个模型回答。

### 2.3 功能

助手人格（名称/头像）、聊天背景（图库/透明度）、会话列表、新对话、系统提示词、消息模板、采样参数、
思维链展示、重新生成/编辑/删除、变体（≤10）、导出 JSON / PNG、录音、拖放文件（图片）、
AI 帮答、聊天总结提示词、标题生成（可指定模型）、MCP 工具调用（hub 侧管理）、全局单飞锁、中英日 i18n 文案。

## 三、llama.cpp WebUI 实测（b11981）

### 3.1 形态

- 入口：`http://<实例>/`，由 `llama-server` 直接内嵌；`/props` 里有 `ui`、`ui_settings` 字段。
- 首页 HTML 12,639 B，JS bundle 单文件 **9.0 MB**（`_app/immutable/bundle.DZMSWjjb.js`），另有一个 CSS bundle。
- **只认 gzip**：不带 `Accept-Encoding: gzip` 时首页和资源都只返回 44 字节 `Error: gzip is not supported by this browser`（实测）。
- 语言：bundle 里 `zh-CN`/`i18n` 命中 0 次，UI 文案硬编码英文，无语言设置。

### 3.2 存储（实测）

- IndexedDB 库名 `LlamaUi`，Dexie，`version(1)`（b11946 时代是 10，说明上游动过版本号）；
  两个 store：`conversations(id, lastModified, currNode, name)`、`messages(id, convId, type, role, timestamp, parent, children)`。
- localStorage 前缀 `LlamaCppWebui`（旧版）→ `LlamaUi`（新版），bundle 里带迁移逻辑（`idxdb-database-v1`）。
- 消息是**树**结构（parent/children），所以有分支/fork。
- 库内**没有模型字段、没有服务器地址字段**：UI 永远打自己所在的 origin。

### 3.3 功能

多会话、搜索（`#/search`）、分支/重新生成、编辑、消息树 fork、附件（图片/PDF/音频/视频/文本/提示词/MCP 资源）、
System Message、标题生成（首行 / LLM 提示词两种）、导出/导入会话（ZIP of JSONL）、导入导出设置、
上下文压缩（Compact）、reasoning effort、MCP 服务器管理（**仅远程 URL**，见 3.4）、工具页（`--tools` 内置文件/shell 工具）、
路由模式下的模型选择（`/models/load`、`/models/unload`）。

### 3.4 MCP 与工具

- MCP 面板实测：`MCP Servers` 弹窗 → `Add New Server` → 字段只有 **URL**（`https://mcp.example.com/sse`）+ name，
  外加「Recommended Servers」（Exa 等）。没有 stdio / command 形式。
- 服务端开关（b11981 `--help` 实测存在）：`--ui-mcp-proxy`（允许 UI 自带 MCP 服务器）、`--mcp-servers-json`（服务端内联 MCP，支持 stdio）、
  `--tools read_file,write_file,exec_shell_command,...`（内置文件与命令工具）。

## 四、核心可行性实测：换版本历史还在不在

做法：固定端口 8299 + 固定浏览器 profile。

1. 起 b11981 → 打开 UI → 发一条「请用一句话介绍你自己」→ 回复正常（879 t/s prefill、67.7 t/s decode，CPU 0.5B），
   IndexedDB：1 个会话、3 条消息（user/assistant/system-root），标题自动取首行。
2. 杀掉 b11981，在**同一端口 8299** 起 b11867（`build b11867-f3269ef96`），用**同一个 profile** 再开。
3. 结果：会话仍在（1 会话 / 3 消息），侧栏标题「请用一句话介绍你自己」可见。
   截图 `test\15\shots\webui_switch.png`。

结论：**历史绑定的是 origin（host:port），不是 llama.cpp 版本**。端口固定即满足「换版本不丢」。
反之，Java 版当前用 `getNextAvailablePort()` 递增分配端口（`LlamaServerManager:2155`），
不固定端口的话每次重启都会换 origin，历史看起来就「没了」——这是必须改的第一件事。

跨版本兼容性：b11867 与 b11981 的库名都是 `LlamaUi`，b11867 能正常读出 b11981 写的库，未触发迁移报错。

## 五、差异对照

| 维度 | EasyChatService（现） | llama.cpp WebUI（b11981） |
|---|---|---|
| 形态 | hub 内 5.8k 行 + 638 KB 单页 | llama-server 内置，9 MB bundle |
| 语言 | 中文（i18n 多语言） | **仅英文** |
| 存储 | 服务端二进制分片，项目目录内 | 浏览器 IndexedDB，按 origin 隔离 |
| 便携 | 复制项目即带走 | 需 hub 快照到 `data/chat/` |
| 换版本 | 无关（存服务端） | 端口固定即可保留（已实测） |
| 助手人格/头像 | 有 | 无 |
| 聊天背景 | 有（图库/透明度） | 无 |
| 单会话切模型 | 有（记录每条消息的模型） | **无**（一个 origin 一个模型） |
| 模型自动加载/卸载 | 有（聊天里直接 load/stop） | 无（需先由 hub 加载） |
| 附件 | 图片（服务端只做透传） | 图片/PDF/音频/视频/文本，功能更强 |
| 分支/变体 | 变体 ≤10 | 消息树 + fork，更强 |
| 导出 | JSON / PNG | ZIP(JSONL) 导入导出 |
| 搜索 | 会话列表（弱） | 全文搜索页 |
| 上下文压缩 | 有「聊天总结提示词」 | Compact 一键压缩 |
| 标题生成 | 可指定模型 | 首行 / LLM（用当前模型） |
| MCP | hub 侧 stdio+URL 管理（1142 行） | UI 内 URL 服务器 + 服务端 `--mcp-servers-json`(stdio) |
| 文件工具 | hub 的 `/api/tools/execute` | `--tools` 内置 read/write/glob/grep/shell |
| 单飞锁 | 有 | 无（各自并发，靠实例 slot） |
| 中文参数名/提示 | 有 | 无 |

## 六、推荐方案（A：固定端口代理 + 快照）

```
浏览器 ──> hub:8100+ (每模型固定端口, 反代)
              ├─ 静态/API 透传 ──> 127.0.0.1:<实例端口>
              ├─ 注入 __hubchat.js（快照脚本）
              └─ /__hubchat/db  GET/PUT ──> data/chat/<模型id>/webui-db.json.gz
```

要点（都是 Go 版已经踩过并解决的）：

1. **固定端口**：`config/model_ports.json` 持久化 `modelId → port`（8100–8199），冲突顺延；模型 ID 与二进制版本无关，
   所以换版本后端口不变、origin 不变、历史不变。
2. **反代**：UI 文档强制 `Accept-Encoding: gzip` → 解压 → 注入 `<script src="/__hubchat.js" defer>` → 去掉 `Content-Encoding` 重设长度；
   `/v1`、`/props`、`/health`、`/metrics`、`/slots`、`/tokenize` 一律不压缩，SSE 逐块 flush。
3. **快照**：脚本把 `LlamaUi` 的 conversations/messages 打包 PUT 给代理，代理 gzip 写盘（先 `.tmp` 再 rename）；
   打开页面时本地为空就导入并跳到最近会话；非空按 `id + lastModified + 正文 hash` 合并，删除用墓碑。
4. **端口与访问**：实例继续绑 127.0.0.1，对外只暴露 hub 的聊天端口（可以挂 API Key）；局域网访问用 `location.hostname`。
5. **自动卸载/用量**：直连 WebUI 不经过 hub，`modelLastUsedTime` 不会刷新 → 每 10 秒轮询实例 `/metrics`，
   有增量就刷新 lastUsed；打开过聊天页的模型自动卸载宽限 ×3。
6. **前端**：「开始聊天」→ `POST /api/models/chat {modelId}`（未加载先走现有加载流程）→ `window.open(url)` 新标签页。

### 工作量估计（Java 侧）

| 项 | 预估 |
|---|---|
| 聊天反代 + gzip 注入 + SSE 透传（Netty，可参考 `tools/proxy`） | ~350 行 |
| 端口持久化 + `/api/models/chat` | ~120 行 |
| 快照存取（GET/PUT、gzip、原子写、合并元数据） | ~200 行 |
| `hubchat.js`（IndexedDB 导出/导入/合并/墓碑） | ~250 行 |
| metrics 轮询 + 卸载宽限 | ~100 行 |
| 前端按钮/新标签 + 移除 EasyChat 入口 | ~80 行 |
| **合计新增** | **~1100 行** |
| **可删** | EasyChat 5,820 行 + 638 KB 页面（MCP 若保留另算） |

## 七、风险与降级

1. **英文界面**：无法低成本汉化（436 个 Svelte 组件里硬编码）。要么接受，要么保留 EasyChat 双轨。
2. **上游 UI 重构**：注入点/存储结构一变，快照脚本失效 → 降级为「浏览器本地历史」，聊天本身不受影响（Go 版就是这么降级的）。
3. **IndexedDB 版本漂移**：b11946(10) → b11981(1)，说明上游会改 schema。快照按行 JSON 存，恢复失败时不阻塞聊天。
4. **多设备合并**：同一模型在两台机器聊过，靠 `lastModified` + 墓碑合并，极端情况会丢并发删除的那条（可接受）。
5. **直连端口无鉴权**：实例绑 127.0.0.1，只走代理端口对外；否则 hub 的 API Key 保护不到。
6. **单会话切模型做不到**：WebUI 一个 origin 一个模型。要跨模型就在两个标签页里聊。

## 八、需要拍板的问题

1. 英文界面能不能接受？（不能 → 只能双轨：WebUI 做默认，EasyChat 留作可选入口）
2. 助手人格/头像/背景、PNG 导出、录音、AI 帮答这些 EasyChat 独有能力，直接砍掉还是保留？
3. 单会话里换模型这个能力重要吗？（WebUI 里做不到）
4. MCP 怎么办：用 llama.cpp 原生的「UI 里加远程 URL + `--tools` 内置文件工具」，还是继续用 hub 的 stdio MCP 管理（用 `--mcp-servers-json` 注入，两套并存）？

## 九、验收清单（开干后按这个验）

- 点「开始聊天」→ 新标签页打开该模型 WebUI，流式对话正常
- 重启 hub → 端口不变，历史还在
- 换 llama.cpp 版本（同模型同端口）→ 历史还在（已用 b11981→b11867 验证过原理）
- 把整个项目目录复制到另一台机器 → 打开聊天能看到之前的会话（从 `data/chat/` 恢复）
- 聊天时模型不会被自动卸载；用量报表能看到聊天 token
- 删除会话后，快照里也删掉（墓碑生效），换设备不会「复活」
