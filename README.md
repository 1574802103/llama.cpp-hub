# llama.cpp-hub（维护版）

给 llama.cpp 套一个可视化的 Web 外壳：模型加载、参数调节、性能测试、用量统计都在一个页面里完成。
**聊天直接用 llama.cpp 自带 WebUI**，hub 只负责启停和配置——聊天界面永远跟着 llama.cpp 官方升级走。

> **项目状态**：原作者（[IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)）已停止维护。
> 本仓库是接续维护的版本：基线 0.9.8.3，本仓库版本号从 **0.0.1** 起，当前 **0.0.2**，
> 所有改动记录在 [docs/CHANGELOG.md](./docs/CHANGELOG.md)。
> 「设置 → 版本更新」检查的就是**本仓库**的 Release（`1574802103/llama.cpp-hub`），不会再跑去问原作者。

> **下载**：Windows 打包版（内置 JRE 与 llama.cpp **b12651** CUDA 13.5）见
> [Releases → v0.0.2](https://github.com/1574802103/llama.cpp-hub/releases/tag/v0.0.2)。
> 随包只带这一个 CUDA 版本；CPU / ROCm / 别的构建号请自行放进 `llamacpp/`（见包内 `llamacpp/README.txt`）。

---

## 目录

- [核心概念](#核心概念)
- [环境要求](#环境要求)
- [功能](#功能)
- [快速开始](#快速开始)
- [目录结构](#目录结构)
- [端口布局](#端口布局)
- [聊天台（重点）](#聊天台重点)
- [参数面板](#参数面板)
- [用量与速度统计](#用量与速度统计)
- [配置说明](#配置说明)
- [维护改动记录](#维护改动记录)
- [自检与冒烟测试](#自检与冒烟测试)
- [常见问题](#常见问题)
- [安全声明](#安全声明)
- [技术栈与编译](#技术栈与编译)

---

## 核心概念

| 东西 | 是什么 |
|---|---|
| **llama.cpp-hub** | 一个 Java 写的本地 Web 服务（默认 8080），管模型、管参数、管进程 |
| **实例进程** | 每个模型加载时起一个独立的 `llama-server.exe`，端口从 8081 起自动分配，只绑 `127.0.0.1` |
| **聊天台** | hub 固定端口 **8100** 上的代理，把 llama.cpp 自带 WebUI 端出来，所有模型共用同一个 origin |
| **配置** | `config/` 下的 JSON：模型列表、启动参数、聊天台设置，全部在项目目录内，复制项目即带走 |

---

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Windows 10 / 11 x64（Linux 可用 `run.sh`，未做发布包） |
| 显卡 | 随包为 CUDA 13.5 构建，需 NVIDIA 驱动 **580.x 及以上**；无独显请换 CPU 版放进 `llamacpp/` |
| 内存 | 建议 16 GB 以上；模型越大显存要求越高 |
| 浏览器 | Edge / Chrome 近两年版本（聊天台依赖 IndexedDB 与 ES Module） |
| 运行环境 | **不需要另装 Java**，包内自带 JRE |

---

## 功能

### 模型管理

- 加载 / 停止 GGUF 模型，实时显示运行状态、并发槽位、Token 速度
- **每个模型可存多套启动配置**（命令行、设备、显存、采样、模板），随时切换
- **多版本 llama.cpp 并存**，每个模型可选不同版本；模型列表支持**批量切换版本**（全选 / 半选 / 逐个取消）
- 自动识别同目录的 `mmproj`（视觉 / 音频），也可手动指定
- 克隆体：同一个 GGUF 文件用不同参数起两个实例
- 自动加载 / 自动卸载：默认允许，**无工作 5 分钟**卸载（模型卡片里可改）
  - 「无工作」只按**真正在算 token 的请求**判断，WebUI 开着轮询 `/props`、`/models` 不会续命
  - 正在出字的请求会标记 busy，长回答不会被中途停掉
- 按目录扫描，一个文件夹一个模型

### 参数面板

- **198 个参数 / 14 个分组**，与 llama.cpp **b12651** 的 `--help` 逐项对齐（默认值、枚举值照抄上游）
- 悬停「?」看说明，**开头就是原始英文参数名**，例如：
  `--ctx-size：设置模型可用的上下文窗口大小（单位：token）……设置为 0 表示从模型文件元数据读取`
- 覆盖基础、采样、采样器链、性能与缓存、线程与 CPU 调度、上下文扩展（RoPE / YaRN）、功能开关、
  服务端与接口、模板与推理、多模态、草稿模型、N-gram 投机解码、LoRA 与控制向量、高级功能
- 新版本引入的选项会同步补齐（如 b12648 起新增的 `--ui-mcp-proxy-allow-private-urls`）

### 聊天（llama.cpp 自带 WebUI）

- 模型行点「**开始聊天**」→ 新标签页打开该模型的 WebUI（英文界面）
- 模型没在跑时先显示「**正在启动 · 模型名**」等待页，加载完成后自动进入聊天界面
- **所有模型共用 8100 端口**，切换模型时历史是同一份，可以接着聊
- 左下角有一个**可拖动**的模型胶囊，点开可以看「这个模型参与过的会话」、直接起停其它模型
- 每条回复自带模型名与速度；聊天历史自动快照到项目内 `data/chat/`，换机器 / 换 llama.cpp 版本都不丢
- **模型启停只由 hub 控制**：打开聊天页不会自动启动任何模型；WebUI 自身的启停接口被禁用

### 其它

- **性能测试**：`llama-bench` 与「直连 `/v1/chat/completions` 测 timings」两种，结果存 `benchmarks/`
- **用量报表**：按模型 / 按天统计 token 与速度，来自 `cache/record/<模型>.requests.bin`
- **WebSocket 实时日志**、系统信息（GPU / 内存）、模型搜索（HF 镜像）、下载管理（断点续传）
- **多节点聚合**：多台机器上的 hub 用一个入口统一管理
- **MCP / 内置工具**：走 llama.cpp 原生 `--tools` / `--ui-mcp-proxy` / `--mcp-servers-json`
- 设置页可改监听地址（`127.0.0.1` 仅本机 / `0.0.0.0` 允许局域网），**保存并重启 hub** 一键生效
- PWA：可安装到桌面 / 任务栏

---

## 快速开始

1. 解压程序包（已带 JRE 与 llama.cpp 版本目录）
2. 把每个 GGUF 放进**独立文件夹**：`models/MyModel/MyModel.gguf`
3. 启动：
   - Windows：双击 `llama.cpp-hub.exe`（无控制台，日志见 `launcher.log`），或双击 `run.bat`（带控制台输出）
   - Linux：`./run.sh`
4. 浏览器打开 `http://localhost:8080`
5. 模型列表 → 选模型 → 保存启动配置 → 加载；要聊天就点卡片上的「开始聊天」
6. 若 8080 被占用，改 `config/application.json` 的 `server.webPort`，或在设置页改完点「保存并重启 hub」

> 外部客户端（OpenAI / Anthropic SDK 等）一律填 hub 的 8080，不需要管实例端口。
> `/v1/models` 只返回「运行中的模型」和「允许自动加载的模型」——避免客户端以为没加载的模型也能用。

---

## 目录结构

```
llama.cpp-hub/
├─ llama.cpp-hub.exe      启动器（读 launcher.conf，无控制台）
├─ run.bat / run.sh       直接用 jre 启动（能看到控制台输出）
├─ launcher.conf          exe 启动器的 JVM 参数与 classpath
├─ classes/               后端 class + 前端静态资源
│  ├─ web/                index.html（桌面）、index-new.html（移动）、js/css/i18n
│  ├─ web/chathub/        聊天台注入脚本 hubchat.js
│  ├─ server-params.json  参数面板定义（198 项）
│  └─ tools/              gpu-info 等运行时工具（不要删）
├─ lib/                   依赖 jar（netty / gson / log4j / slf4j）
├─ jre/                   内置运行时
├─ llamacpp/              各版本 llama.cpp（llama-bXXXXX…），可多版本并存
├─ models/                默认模型目录（可在设置里改）
├─ config/
│  ├─ application.json    服务端口、监听地址、下载目录、安全、HTTPS
│  ├─ launch_config.json  每个模型的启动配置
│  ├─ chathub.json        聊天台：端口、按需加载超时、工具与 MCP 开关、历史目录
│  ├─ modelpaths.json     模型扫描目录
│  └─ capabilities/       模型能力覆盖（多模态等）
├─ data/chat/             聊天历史快照（跟项目走）
├─ cache/                 运行期缓存（record 用量记录、temp）
├─ benchmarks/            性能测试输出
├─ logs/                  app.log + 每个模型的进程日志
└─ docs/                  API.md、CHAT-HUB.md、CHANGELOG.md、legacy/（历史文档）
```

---

## 端口布局

| 端口 | 用途 | 备注 |
|---|---|---|
| **8080** | hub WebUI + `/v1`、`/api` + WebSocket | 对外唯一入口 |
| **8100** | 聊天台（llama.cpp WebUI 代理） | 固定端口，**不要随便改**，改了历史 origin 就变 |
| 8081–8199 | 每个模型的 llama-server 实例 | 自动分配，**只绑 127.0.0.1** |
| 8075 | 内置 MCP 服务端（可选） | 默认关闭 |

> 实例只绑回环地址：b12xxx 起 llama.cpp 在「开了工具 / MCP + 非回环 + 无 api-key」时会直接拒绝启动。
> 对外访问走 hub 的 8080，实例端口本来也不需要暴露。

---

## 聊天台（重点）

**开一次聊天**：模型列表 →「开始聊天」。没加载的模型会先由 hub 加载，页面显示「正在启动」，就绪后自动进入 WebUI。

**为什么历史不会丢**

- WebUI 的历史存在浏览器 IndexedDB（库名 `LlamaUi`），**按 origin 隔离**；
- 所有模型共用 8100 这一个 origin，所以换模型看到的是同一份会话列表；
- hub 会把会话与设置快照到 `data/chat/*.json.gz`，换电脑时自动灌回；
- 删除会话会写「墓碑」并在快照里同步删除，重启不会复活；
- 换 llama.cpp 版本不影响：端口没变、origin 没变，历史还在。

**模型启停规则**

- 打开 / 刷新聊天页**不会**启动任何模型；真没在跑就显示「未运行」页（带启动按钮）；
- WebUI 自己的 `/models/load`、`/models/unload` 在聊天台上被禁用（403）；
- 只有两个入口会启动模型：hub 里点「开始聊天」，或「未运行」页上点「启动模型」；
- 在 hub 里停止模型 = 杀进程 + 取消加载，立即生效。

**左下角胶囊**

- 按住可拖动，位置记在浏览器本地，刷新后还在原处；
- 点开是模型列表（带运行状态与起停按钮）+「这个模型参与过的会话」；
- 菜单贴着胶囊弹出，并自动避免超出屏幕。

**已知边界**

- 界面是英文（llama.cpp 自带，无 i18n 层）；
- 一个标签页对应一个模型，切模型是换标签 / 换 `?m=`；
- 两个标签页同时开着时，一边新聊的内容要刷新才在另一边出现。

细节与排错见 [docs/CHAT-HUB.md](./docs/CHAT-HUB.md)。

---

## 参数面板

- 定义文件：`classes/server-params.json`（推理参数，198 项）、`classes/benchmark-params.json`（llama-bench，38 项）
- 说明文案：`classes/web/i18n/zh-CN.json`、`en-US.json`，两套 key 完全一致
- 当前对齐基线：llama.cpp **b12651**（`--help` 共 267 个选项，面板收录其中对使用有意义的 198 项，0 死项、0 缺失）

---

## 用量与速度统计

- 模型列表里卡片的 `In xx t/s` / `Out xx t/s` 是**累计平均**，来自 `cache/record/<模型>.requests.bin`
- **聊天台产生的请求也算**：代理会从响应的 `timings` 里取出 prompt / predicted 速度与 token 数再落盘
- 「用量报表」按天、按模型聚合；可在报表里删除某个模型的记录重算
- 只有真正走 `/v1/chat/completions`、`/v1/completions` 等生成接口的请求才计入

---

## 配置说明

| 文件 | 关键字段 |
|---|---|
| `application.json` | `server.webPort`、`server.listenAddress`（`0.0.0.0` 允许局域网）、`download.directory`、`security.apiKeyEnabled` |
| `launch_config.json` | 每个模型：`configs`（多套）、`selectedConfig`、`autoLoad`（allow/deny）、`autoUnloadTimeoutMs`（默认 300000） |
| `chathub.json` | `port`（8100）、`autoLoadTimeoutMs`、`tools`（`all` 或逗号列表）、`uiMcpProxy`、`mcpServersJson`、`historyDir` |
| `modelpaths.json` | 扫描的模型根目录列表 |

> 改端口需要**重启 hub**：设置页改完直接点「保存并重启 hub」，页面会自己等新地址起来。

---

## 维护改动记录

接续维护后的主要改动（每条的「为什么 / 怎么验证」见 [docs/CHANGELOG.md](./docs/CHANGELOG.md)）：

| # | 内容 |
|---|---|
| 1 | llama.cpp 版本下载改为「最近 10 个构建」，兼容上游 prerelease 与 `win-cpu-x64` 资产命名 |
| 2 | 修复 b11867 起模型加载不出来（就绪判定改用 `/health` 兜底） |
| 3 | 模型图标按「名称优先、架构兜底」匹配 |
| 4 | 自动卸载默认 5 分钟，新模型默认勾选「允许自动加载 / 自动卸载」 |
| 5 | 参数面板按上游重做并持续跟版（当前 198 项，对齐 b12651） |
| 6 | 模型列表新增「批量切换 llama.cpp 版本」 |
| 7 | 聊天改为 llama.cpp 自带 WebUI（聊天台），EasyChat 整体删除；历史落 `data/chat/` |
| 8 | 聊天台模型列表与 hub 同源；行内启停 |
| 9 | 「刷新模型列表」补上完成提示与转圈 |
| 10 | 模型启停收敛到 hub：打开聊天页不再自动拉起模型 |
| 11 | 设置页新增「保存并重启 hub」，改监听地址不用手动重启 |
| 12 | 实例统一绑 `127.0.0.1`，绕开 b12xxx 的安全护栏 |
| 13 | 会话删除写墓碑，删掉的会话重启后不再复活 |
| 14 | 修复「模型还在加载就直接进 WebUI」：等待页覆盖列表加载 / 自动加载，并禁用文档缓存 |
| 15 | 修复「WebUI 开着时 5 分钟自动卸载不生效」：只有生成类请求才算活跃 |
| 16 | 修复左下角模型名显示成另一个模型（缓存 304 复用旧注入变量） |
| 17 | 左下角模型胶囊可拖动，菜单跟随，位置记忆 |
| 18 | 修复模型列表 In/Out 速度不再更新（聊天台代理的 timings 没进统计） |
| 19 | 修复 slots 存/取缺参数时的空指针，补 9 个缺失的 i18n key |

---

## 自检与冒烟测试

发布前跑过的检查：

| 项目 | 规模 | 结果 |
|---|---|---|
| 全路由扫描 | 123 条 `/api` × GET/POST = 246 次 | 0 个 5xx |
| 功能链路 | 加载 → props/slots/metrics → 流式/非流式聊天 → 统计 → 报表 → 停止 | 全通过 |
| 前端 | 旧 UI 9 页 + 新 UI 8 页 | 控制台 0 报错、0 失败请求 |
| 静态资源 | 27 个引用 + 12 个模块 js | 全部 200 |
| 稳定性 | 1500 次请求、进程重启、记录删除 | 内存 165.3 → 165.6 MB；重启 2 秒恢复 |
| 参数表 | 对 `llama-server --help` 复核 | 0 死项、0 缺失 |

---

## 常见问题

**Q：点「开始聊天」返回 `{"error":{"message":"File Not Found","code":404}}`？**
旧配置里存了 `--no-webui`，带它的 llama-server 不提供界面。启动时会自动剥离该参数；
如果还出现，到模型配置里删掉 `--no-webui` 后重新加载。

**Q：一直停在「正在启动」？**
看 `logs/<模型名>.log` 与 `logs/app.log`。常见原因：CUDA 版本与驱动不匹配、显存不足、`mmproj` 路径失效、
启动参数用了新版本已删除的项。加载前 hub 会做显存估算，估算不通过会直接拒绝。

**Q：模型没有输出，但卡片显示运行中？**
实例只绑 `127.0.0.1`，请通过 hub 的 8080 访问；不要直接用实例端口。

**Q：聊天历史「不见了」？**
确认地址还是 `http://<同一主机>:8100`。历史绑定 origin（host + port），换端口或换浏览器 profile 就会看到空的。
项目内的历史在 `data/chat/`，换机器会自动恢复。

**Q：模型空闲了却一直不卸载？**
先确认卡片里「自动卸载」是允许、超时不是 0；再确认没有客户端在持续发**生成**请求。

**Q：刷新模型列表很慢？**
`/api/models/refresh` 是同步重扫目录 + 读 GGUF 头，模型多时要几十秒，页面会转圈并给等待提示。

**Q：端口占用 / 启动失败？**
8080 被占用改 `server.webPort`；8100 被占用改 `chathub.json` 的 `port`（注意：改了聊天就换了历史 origin）。
异常退出后若残留 `llama-server.exe`，重新启动 hub 会自动清理。

**Q：为什么一个文件夹里只能放一个模型？**
hub 按「目录 = 模型」扫描：一个文件夹里的 GGUF（含分片）视为同一个模型，文件夹名就是模型 ID。

**Q：能暴露到公网吗？**
不建议，见下方安全声明。

---

## 安全声明

> **本应用是面向局域网的个人工具，不具备互联网级安全防护。**
>
> - 默认监听 `0.0.0.0`；若做了端口转发 / 内网穿透，请自行用反向代理加 HTTPS、访问控制、限流
> - `security.apiKeyEnabled` 只保护 `/v1`，不是完整认证
> - `/api/sys/setting` 会返回当前设置（含 HTTPS keystore 密码），不要把管理端口直接暴露给不可信网络
> - 聊天台按设计**不自动启动模型**，但模型一旦运行，其实例端口在本机可达
> - 内置工具（`--tools`）能让模型读写文件 / 执行命令，**只在可信环境开启**
>
> 因公网暴露导致的任何损失，后果自负。

---

## 技术栈与编译

- 后端：Java 21 语法 + Netty 4.1.35 + Gson + Log4j2
- 前端：原生 JS（无框架、无打包器）
- 模型：llama.cpp 子进程，一个模型一个进程

改动 Java 源码后重新编译：

```bash
# 需要 JDK 21+
find src/main/java -name "*.java" > build/srcs.txt
javac -source 21 -target 21 -encoding UTF-8 -nowarn -d build/classes -cp "lib/*" @build/srcs.txt
cp -a src/main/resources/. build/classes/     # 前端资源
```

> 部署时**不要整目录覆盖** `classes/`：`classes/tools/` 里的二进制（如 `gpu-info-x64.exe`）
> 不在源码里，覆盖会丢。建议按文件比对后只替换改动项。

---

## 相关文档

- [docs/API.md](./docs/API.md) — HTTP 接口清单（含聊天台接口）
- [docs/CHAT-HUB.md](./docs/CHAT-HUB.md) — 聊天台设计、部署与排错
- [docs/CHAT-WEBUI-RESEARCH.md](./docs/CHAT-WEBUI-RESEARCH.md) — EasyChat vs llama.cpp WebUI 的调研与结论
- [docs/CHANGELOG.md](./docs/CHANGELOG.md) — 每次改动的「为什么」与验证方式
- [docs/legacy/](./docs/legacy/) — 原项目历史文档

---

## 致谢

原项目 [IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub) 提供了完整的产品骨架与前端；
上游 [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) 提供全部推理能力与自带 WebUI。
