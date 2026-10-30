# 维护记录

记录接手维护后对 llama.cpp-hub 做的改动。每条都写清楚「为什么改」和「改了哪里」，方便以后回溯。

## 2026-10-29

### 1. llama.cpp 版本下载改为「最近 10 个构建版本」列表

**问题**：上游把 `b*` 构建全部标成 prerelease，`GET /repos/ggml-org/llama.cpp/releases/latest` 只返回
`v0.x.y` 这种只挂一个 `nightly-tag.txt` 的空壳 release，页面因此永远看不到新构建、也没得下载。

**改动**：
- 后端新增 `GET /api/llamacpp/releases?limit=10&proxy=...`：拉 `releases?per_page=...` 列表，
  只保留 `tag` 匹配 `^b\d+$` 且带附件的构建，按构建号倒序返回前 N 个；
  `/api/llamacpp/release/latest` 改为「取列表第一个」，旧前端不用改。
- 资产过滤重写：原文只认 `win-x64` / `ubuntu-x64` 这类命名，上游后来把 CPU 构建改成
  `llama-b11980-bin-win-cpu-x64.zip`，导致 CPU 版本整批消失。现在按「x64 + win/ubuntu/linux 平台 + 压缩包」
  判断，排除 arm64，兼容历代命名。
- 页面：旧版设置页加「版本列表」下拉框（`#llamacppVersionSelect`），新版设置页加 `#lcRelSel`，
  切换版本只换资产列表，不重新请求；「最新版本」标签改为「当前版本」。

### 2. 修复 b11867 及之后版本「模型加载不出来」

**问题**：上游从 b11867 起把 server 工作线程名从 `llama_server` 改成 `server_run_i`，
就绪日志从 `srv  llama_server: model loaded` 变成 `srv  server_run_i: model loaded`。
`LlamaServerManager` 原来按 `line.contains("llama_server: model loaded")` 判定就绪，于是永远等不到，
一直卡到 10 分钟超时（或被前端取消），表现为「进程起来了、日志里 model loaded 了，但 hub 说加载失败」。

**改动**（`LlamaServerManager`）：
- 就绪判定改为只看正文 `model loaded`（先去 ANSI 颜色码），不再绑线程名；保留旧的 `update_slots: all slots are idle`。
- 追加兜底：进程启动后每 0.8s 轮询实例 `GET /health`，200 且带 `ok` 也算就绪；加载结束（无论成败）取消轮询。
- 验证：b11866（旧日志格式）、b11867、b11980 三个版本都能加载成功并通过 `/v1/chat/completions` 出结果。

### 3. 模型图标按「名字优先、架构兜底」匹配

**问题**：原来只按 `general.architecture` 匹配。GLM-4.7-Flash 的 arch 是 `deepseek2`，于是显示成 DeepSeek 的图标。

**改动**：`model-icon.js` 改为 `getModelIcon(name, arch)`：先按模型别名/名称/目录 ID 匹配品牌关键字
（glm、qwen、deepseek、hunyuan、kimi、minimax、gemma、mistral、llama…），匹配不到再看架构
（同时匹配去数字后的架构名）。三处调用（模型列表、新版列表、模型详情）都改成传名字。

### 4. 自动加载/卸载默认值

- 新模型（`launch_config.json` 里还没有条目）默认：允许自动加载、允许自动卸载、空闲 **5 分钟** 卸载。
- 后端：`ConfigManager` 的策略/超时读取在缺字段时返回上述默认值；首次保存配置时把三个字段写进条目；
  `/api/auto-load/policy` 会为所有模型（含未配置的新模型）回填默认值，避免前端显示未勾选。
- 前端：弹窗里两个开关默认勾选，超时输入框默认 5 分钟，接口失败时也按「勾选」兜底。

### 5. 参数表与上游 `--help` 全面对齐（重做版）

第一版是照着旧表修补的，类型/默认值/取值多处与上游不符，已整体推翻重写。

- 基准：**b11980** 的 `llama-server --help`（261 个长选项）。逐条解析出「是否带值、默认值、允许值、说明原文」，
  参数表共 **184 项**，分 14 组：基础参数 9、采样参数 26、采样器链 3、性能与缓存 28、线程与 CPU 调度 11、
  上下文扩展（RoPE/YaRN）9、功能开关 9、服务端与接口 12、模板与推理 16、多模态 9、草稿模型（投机解码）29、
  N-gram 投机解码 12、LoRA 与控制向量 6、高级功能 5。
- 默认值全部按上游填（`--ctx-size 0`、`--temp 0.80`、`--parallel -1`、`--checkpoint-min-step 8192`、
  `--cache-ram 8192`、`--slot-prompt-similarity 0.10`、`--mtmd-batch-max-tokens 1024`、
  `--spec-ngram-* 24/48/64 与 12/48/1` 等），不再是旧表里那些拍脑袋的数。
- 枚举值逐项核对：`--cache-type-k/v` 只保留上游支持的 9 种（旧表的 turbo4/turbo3/turbo2 会直接报
  `Unsupported cache type`）；`--load-mode` 补回 `auto`；`--spec-type` 用上游 11 个取值，
  删掉 `--spec-type_ngram-mod`、`--spec-type_ngram-map-k4v` 两个伪参数（后端合并逻辑保留，兼容旧配置）。
- `--mlock` 已从上游删除（传了直接 `error: invalid argument`），改用 `--load-mode mlock`。
- 默认启用的开关（kv-offload/repack/op-offload/jinja/cont-batching/warmup/webui/cache-prompt/
  skip-chat-parsing/prefill-assistant/mmproj-offload/spec-draft-backend-sampling/cache-idle-slots）
  一律以 `--no-xxx` 别名呈现，勾选即传关闭开关，说明里也写明了对应的上游主参数。
- 说明统一成 `--原参数名：做什么/默认值/取值范围/什么时候该改` 的写法，鼠标悬停「?」第一眼看到英文原名；
  新版界面同样给参数标签加了 `title`。
- 验收：
  - 机器审计（`audit_new.py`）0 问题：flag 存在、类型与是否带值一致、默认值一致、枚举值一致、i18n 齐全、
    每条说明都以参数名开头；
  - 真实二进制（`verify_against_binary.py`）184/184 通过：每个 flag 都能被 b11980 接受，每个枚举值都合法，
    开关型参数单独传不报错；
  - 界面实测：旧版/新版都渲染出 184 个字段、14 个分组；勾选新参数后生成的命令行用真实 llama-server
    加载模型成功（`model loaded`）。

**未纳入界面的参数**（79 个）及原因：程序控制（`--help/--version/--cache-list/--list-devices`）、
模型来源（`--model/--model-url/--hf-*/--docker-repo`，hub 自己注入 `-m`）、日志（`--log-*/--verbose`）、
hub 自己管理（`--host/--api-key/--ssl-*/--cors-*/--path/--ui-config/--tools/--mcp-*/--agent/--metrics/--slots`）、
router 模式（`--models-*`）、联网下载预设（`--fim-*/--gpt-oss-*/--vision-gemma-*/--embd-gemma-default`）、
已废弃（`--draft/--draft-min/--spec-ngram-size-*/--defrag-thold/--mlock`）、CLI 专用（`--reverse-prompt/--spm-infill/--lookup-cache-*`）、
设备选择（`--device`，由 hub 的设备勾选表单注入）、`--mmproj-url/--mmproj-auto`（hub 选本地 mmproj）。

**验证脚本**（`E:\hermes_portable\test\15\scripts\`）：
`parse_help_full3.py` 解析 `--help`、`params_a/b/c.py` 是参数表数据源、`gen_params2.py` 生成
`server-params.json` 与 i18n、`audit_new.py` 机器审计、`verify_against_binary.py` 用真实二进制逐项验收。

### 6. 模型列表页「批量切换 llama.cpp 版本」

**需求**：换 llama.cpp 版本时要逐个模型打开配置去改，模型一多很麻烦。

**改动**：
- 后端新增 `POST /api/models/launch/batch-bin`（`ModelActionController`），
  `ConfigManager.setLlamaBinPathForModel(modelId, binPath)` 把该模型**全部**启动配置的
  `llamaBinPathSelect` 一次改完（一次读盘、一次写盘），`runCommand`/`device`/`mg`/自动加载策略等字段不动。
  校验目录存在且含 `llama-server`；运行中的模型只改配置并在 `running` 里标出「重启后生效」；
  没有启动配置的模型放进 `skipped` 并给出原因。
- 前端（`web/js/batch-version.js`，模型列表顶栏）：
  「批量切换版本」按钮 → 弹出版本菜单（`/api/llamacpp/list`，绝对定位挂在按钮容器上）→
  选中后按钮变「确定 / 取消」，列表上方出现「全选 + 已选 N/M + 目标版本 + 提示」，
  每行星星换成复选框且默认全选；`确定` 提交后恢复原按钮并刷新列表，`取消` / `Esc` 不动配置直接退出。
- 细节：全选为三态（部分勾选显示半选）；取消勾选过的模型在列表刷新/筛选后不会被自动勾回；
  未选中任何模型时「确定」置灰；远程节点的模型禁用勾选；接口 404（旧进程）时提示重启 hub；
  网格视图下勾选框单独占一条留白，不压住模型图标。
- 资源热更新：前端改完刷新即可；**后端类改动需要重启 hub 一次**。

### 7. 聊天改用 llama.cpp 自带 WebUI（聊天台）

**需求**：EasyChat 模块多余，直接用 llama.cpp 的 WebUI；点「开始聊天」进单独的对话标签页；
聊天记录存项目内、换设备能带走、换 llama.cpp 版本不丢；MCP/工具用 llama.cpp 原生方案，设置都留在 hub 里。

**结论（实测）**：WebUI 的历史存在浏览器 IndexedDB（库名 `LlamaUi`），会话表里没有模型字段，
所以只要**所有模型共用一个 origin**，换模型看到的就还是同一份历史，可以直接接着聊。
实测：同一端口、同一浏览器 profile，b11981 建的会话在换成 b11867 后仍在。

**改动**：
- 新增 `chathub` 包：`ChatProxyServer`（固定端口 8100 的 Netty 服务，反代到实例，注入 `__hubchat.js`，
  处理 `/__hubchat/{snapshot,status,models,conversations,touch}`）、`ChatSnapshotStore`（`data/chat/*.json.gz`，
  按 id + 时间戳合并、墓碑传播）、`ChatHubSettings`（`config/chathub.json`）。
- 新增 `POST /api/models/chat`：确保模型加载后返回 `http://<host>:8100/?m=<模型id>`；
  `GET/POST /api/chat/proxy/config` 读写设置；`GET /api/chat/proxy/status`、`/api/chat/proxy/models`。
- 启动参数注入：`config/chathub.json` 的 `tools`/`uiMcpProxy`/`mcpServersJson` 会在每次拉起实例时
  追加 `--tools`/`--ui-mcp-proxy`/`--mcp-servers-json`（`LlamaServerManager.appendChatHubFlags`）。
- 前端：模型行加「开始聊天」按钮、左侧导航「开始聊天」改为新标签页打开聊天台（`js/chat-hub.js`）；
  设置页新增「聊天台」标签（端口、按需加载超时、内置工具、MCP 开关、stdio MCP JSON、历史目录）。
- 注入脚本 `web/chathub/hubchat.js`：历史快照双向同步、`LlamaUi.config` 等 localStorage 设置跟着项目走、
  左下角模型切换器 + 「这个模型参与过的会话」。每条消息的模型名是上游自带字段（`messages[].model`），
  不需要 hub 额外记录。
- EasyChat 不再有入口（`chat/index.html` 与 `/api/chat/*` 保留但不引用），可在一个观察期后删除。

**实测记录**：
- 同一对话：`user → assistant(qwen05b) → user → assistant(qwen05b-2)`，换模型接着聊成立；
- 全新浏览器 profile（模拟换设备）→ 从 `data/chat/` 导入 2 个会话、8 条消息，自动落到最近会话；
- 换 llama.cpp 版本（b11981 → b11867，同端口）→ 3 个会话都在，继续聊天正常；
- `tools=all` 后启动命令带 `--tools all --ui-mcp-proxy`，`/tools` 列出 read_file 等工具。

**后续修复（同日）**：点「开始聊天」返回 `{"error":{"message":"File Not Found","code":404}}`。
根因是 EasyChat 时代的参数表把 `--no-webui` 当默认项，25 个模型的 `cmd` 里都存了这个开关，
llama-server 不提供 UI。修法：`LlamaServerManager.stripWebUiDisableFlags` 在起飞前剥掉 `--no-webui`/`--no-ui`，
参数表删掉该项（184 → 183）、i18n 同步清理。复现+验证：带 `--no-webui` 的配置拉起后启动命令已无该开关，
聊天页 200、能正常对话。

### 8. 彻底拆除 EasyChat 模块

聊天已经由聊天台（llama.cpp 自带 WebUI）接管，EasyChat 这一整套闲置模块删除。

**影响面核对（拆之前逐个查过引用）**：
- 性能测试：独立模块（llama-bench + 直连 `/v1/chat/completions`），**不引用** EasyChat 任何类。
- 用量报表：数据来自 hub 的 `/v1` 记录（`AnthropicService`/`OpenAIService` 写 `LlamaRecordService`），
  EasyChat 从来没有往报表写过东西，**零影响**。反过来，聊天台直连实例，聊天 token 也不进报表
  （这是替换前就有的空白，不是拆除造成的）。
- `ChatStreamSession`、`ChatTemplateKwargsService` 名字带 Chat 但属于 `/v1` 流式与采样注入，**保留**。
- `StaticFileGzipCache`、`HttpContentLimitHandler` 里只有注释提到 EasyChat，已改写。

**删除清单**：
- Java 11 个文件：`EasyChatController`、`ChatStateController`、`EasyChatService`、`EasyChatStorage`、
  `EasyChatRequestWriter`、`EasyChatAvatarService`、`EasyChatBackgroundService`、`EasyChatGlobalLock`、
  `EasyChatStreamingHandler`、`ChatRequestStreamingTransformer`、`ChatSession`（共约 5.8k 行）。
- 管道：`BasicRouterHandler` 去掉两个 controller 注册，`HttpHttpsUnificationHandler` 去掉 `EasyChatStreamingHandler`。
- 前端：整个 `web/chat/`（约 640 KB）；旧版左侧导航「开始聊天」、新版侧栏与顶栏的聊天入口、`updateChatCompletionLinkForDevice` 死代码。
- 聊天入口只剩**模型行上的「开始聊天」**：一个模型一个按钮，多模型同时运行时不会指向“上次用的模型”而打架。
- 旧聊天记录 `cache/easy-chat/`（约 51 MB）保留不动，需要清理由主人自行删除。

**验证**：重建后 hub 正常启动；`/api/chat/*` 回 `{"success":false,"error":"404 Not Found"}`（与其它未知接口一致），
`/chat/index.html` 404；模型列表、参数表（183 项）、性能测试、用量报表、聊天台均正常，浏览器控制台无错误。

### 9. 聊天台模型列表：与 hub「模型列表」同源 + 行内启停

**需求**：聊天页左下角的模型列表要和 hub 模型列表完全一致；每行末尾加启停按钮（运行中显示红色方块，
未运行显示绿色三角），「运行中」文字放在按钮左边；名字只显示，点了不动作。

**改动**：
- `ModelActionController.buildLocalModelList()` 改为 `public static`，聊天台的 `/__hubchat/models` 直接复用它，
  所以列表 = 扫描到的 GGUF + 有效克隆体（带别名），和 hub「模型列表」同一份数据，并额外带 `running`/`loading`。
- 新增 `/__hubchat/start?m=` 与 `/__hubchat/stop?m=`：启动走 `autoLoadModelFromConfig`（异步，页面先显示「启动中…」），
  停止走 `manager.stopModel` 并广播 `modelStop` 事件；两者都在聊天台自己的 origin 上，不跨端口。
- 注入脚本：行结构改成「名字（不绑定点击）｜运行中｜切换（仅运行中且非当前模型）｜▶/■」，
  只有启停按钮、切换按钮和会话行有点击行为；菜单打开时每 5 秒刷新一次状态。

**说明**：名字按要求不做点击；为了让「已运行的其他模型」还能切过去，运行中的非当前模型多一个小的「切换」按钮。

**验证**：两个模型实测——初始 `qwen05b` 运行中（红方块/停止）、`qwen05b-2` 未运行（绿三角/启动）；
点名字地址不变；点启动 → 约 2 秒后变「运行中 + 停止」并出现「切换」；点停止 → 回到绿三角；接口 `running` 同步。

### 10. 「刷新模型列表」补上完成提示

**问题**：点刷新只弹「正在刷新模型列表」，之后没有任何反馈。`/api/models/refresh` 是同步扫描
（`manager.listModel(true)`），模型多时要几十秒，用户以为卡死了。

**改动**（纯前端，刷新页面即生效）：
- `loadModels()` 返回 Promise，渲染完成时唤醒等待者（带本次模型数）；
- `refreshModels()` 等 `loadModels()` 真正渲染完再弹「刷新完成，共 N 个模型」；
- 扫描超过 10 秒再补一条「还在扫描模型目录，请稍候…」；
- 刷新按钮在过程中置灰 + 图标 `fa-spin`，结束恢复。

**验证**：小目录 49ms 完成（正在刷新 → 刷新完成，共 2 个模型）；用延迟 11 秒的桩测慢路径，
三条提示依次在 0s / 10.0s / 11.05s 出现，按钮状态正确复位。

### 11. 模型启停收敛到 hub：聊天页不再自动拉起模型

**问题**：点「开始聊天」进 WebUI 后经常同时启动两个模型（常是「上次用过的那个」）。
根因是聊天台代理在**任何请求**发现模型没加载时就自动 `autoLoadModelFromConfig`，而模型解析还有
「上次使用的模型 / 第一个模型」兜底——一次没带 `?m=` 的请求就会把另一个模型拉起来。

**改动**：
- 代理不再自动加载：模型没运行就返回「未运行」说明页（带一个显式「启动模型」按钮），API 请求返回
  503 `model_not_running`，绝不偷偷启动。
- 模型解析只认 `?m=` / Referer 的 `m` / cookie；认不出来就展示选择页（API 给 400），去掉 lastModelId 与“第一个模型”兜底。
- 实例的模型管理接口 `/models/load`、`/models/unload`、`/models/sse` 在聊天台上直接 403，
  WebUI 自己不能启停模型；唯一的启动入口是 hub 的「开始聊天」（`/api/models/chat` 触发一次加载）
  和未运行页上的「启动模型」按钮（用户点击）。
- 停止：hub 的停止照旧走 `manager.stopModel`（会取消加载中的任务），聊天台同时清掉加载态/错误，
  页面的下一个请求只会得到 503，不会重启模型。

**验证**：A/B 两个模型实测——点 A 的开始聊天只加载 A；直接打开 B 的页面 B 不启动；
停掉 A 后打开 A 的页面显示「未运行」，loaded 为空；点页面上的「启动模型」才加载；
`POST /models/load` 返回 403；`/v1/chat/completions` 无模型上下文返回 400。

### 12. 全项目测试 + 清理 + README 重写（2026-10-30）

**全项目测试**（用部署版 classes，配置副本跑在 8099/8119，未动主人的 8080）：
- 接口：models/list（10）、loaded、param/server/list（183）、param/benchmark/list（35）、record/speed、
  auto-load/policy、report/{token-summary,daily-tokens,available-years,request-logs,records}、
  models/benchmark/list、llamacpp/list（3 版本）、llamacpp/releases、mcp/tools、sys/sysinfo、sys/setting、
  node/list、chat/proxy/{status,config,models} 全部 `success:true`；`/api/chat/*` 与 `/chat/index.html` 均为 404。
- 页面：模型列表 / 性能测试 / 下载管理 / 模型搜索 / 系统日志 / 系统信息 / 用量报表 / 设置 八个板块逐个切换，无 JS 报错；
  设置里 8 个标签齐全（含「聊天台」），参数表 183 项。
- 功能：刷新模型列表（253ms，提示成对）；批量切版本（3 个版本、10 个复选框、全选/取消）；
  真实模型 `Gemma4-12B-IT-QAT-Q4_K_XL` 加载 6s → 聊天页 200 → `/v1/chat/completions` 正常出 usage →
  hub 停止后进程数 1→0、页面变「未运行」、不会自动重启。

**清理**：
- `cache/easy-chat`（51MB，81 个会话）打包为 `backup/easy-chat-20261030.zip`（34.9MB，387 条目）后删除。
- `cache/static-gzip`、`cache/latest.json`、`cache/tasks.json`、`config/launch_config.json.bak` 删除（均可重建）。
- `logs/` 删除 7 天前的 16 个文件（904KB），1.5MB → 548KB。
- `classes/web/tools/` 删除 5 个无入口页面（benchmark-v2、cert-gen、gguf-mem-test、upload-test、mcp-manager，144KB），
  已确认全站 0 引用。
- `classes/web/param.md`、docs 里 11 个历史文档 + 2 个 EasyChat 说明归档到 `docs/legacy/`。
- 保留：`cache/record`（用量报表数据）、`data/chat/`（聊天历史）、`logs/app.log`、`classes/tools/`（gpu-info）。

**README 重写**：从「原作者自述 + 死链（NOTES.md / screenshot/）」改成本维护版的完整说明——
核心概念、功能、快速开始、目录结构、端口布局（8080 / 8100 / 8081+ / 8075）、聊天台规则、
配置表、11 条维护记录、6 条 FAQ（含 `--no-webui` 404、历史丢失、刷新慢）、安全声明、编译方式。
所有相对链接已校验存在。
