# llama.cpp-hub (maintained fork)

A maintenance fork of [IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)
(baseline **0.9.8.3**). The upstream author stopped maintaining the project; this fork keeps it working with
current llama.cpp builds and replaces the built-in chat with **llama.cpp's own WebUI**.

Current fork version: **0.0.2** (Windows package ships llama.cpp **b12651**, CUDA 13.5).

中文说明见 [README.md](./README.md)。

## What's different

- **Chat = llama.cpp WebUI.** All models share one fixed origin (`http://host:8100`), so switching models keeps
  the same conversation list and you can continue a thread with another model. History is snapshotted into
  `data/chat/`, so copying the project (or switching llama.cpp builds) does not lose chats.
- **The hub owns start/stop.** Opening a chat page never spawns a model: if it is not running you get a
  "not running" page. The WebUI's `/models/load` and `/models/unload` are blocked (403) on the chat port.
- **Model list / llama.cpp releases** fixed for upstream changes: recent-build listing (prereleases, `win-cpu-x64`
  asset names), batch version switching, icons matched by model name first.
- **Parameter panel** rebuilt from `llama-server --help` and kept in sync: **198 parameters / 14 groups**
  (baseline b12651), upstream defaults and enums, each description prefixed with its `--flag`.
- **Idle auto-unload works with the WebUI open**: only requests that actually generate tokens count as activity,
  so the WebUI polling `/props` and `/models` no longer keeps a model alive; in-flight generations are marked busy
  and are never unloaded mid-answer.
- **Chat page shows a waiting screen while a model loads** ("正在启动 · <model>"), then enters the WebUI.
- **Draggable model pill** in the bottom-left: drag it anywhere (position is remembered), click it for the model
  list with start/stop buttons and that model's conversations.
- **Speed stats include chat traffic**: the chat proxy reads `timings` from the response and records them, so the
  `In / Out t/s` figures on the model cards keep moving.
- **Settings → "save and restart hub"**: changing the listen address no longer needs a manual restart.
- **EasyChat removed** (5.8k lines + 640 KB of front-end).

## Quick start

1. Download the latest release package (JRE + one llama.cpp CUDA 13.5 build included) and unpack it.
   Latest: [v0.0.2](https://github.com/1574802103/llama.cpp-hub/releases/tag/v0.0.2).
2. Put each GGUF into its own folder: `models/MyModel/MyModel.gguf`.
3. Windows: double-click `llama.cpp-hub.exe` (or `run.bat`); Linux: `./run.sh`.
4. Open `http://localhost:8080`.

Only the CUDA build shipped in the package is supported out of the box — for other builds (CPU, ROCm, other CUDA
versions) drop your own `llama-bXXXXX-*` folder into `llamacpp/` and pick it in the model's launch config.

## Ports

| Port | Purpose |
|---|---|
| 8080 | hub Web UI + `/v1` + `/api` + WebSocket |
| 8100 | chat workspace (llama.cpp WebUI proxy, shared by all models) |
| 8081–8199 | per-model `llama-server` instances (bound to `127.0.0.1` only) |

## Docs

- [docs/CHANGELOG.md](./docs/CHANGELOG.md) — every change with rationale and verification
- [docs/API.md](./docs/API.md) — HTTP API, including the chat proxy
- [docs/CHAT-HUB.md](./docs/CHAT-HUB.md) — chat workspace design and troubleshooting
- [docs/CHAT-WEBUI-RESEARCH.md](./docs/CHAT-WEBUI-RESEARCH.md) — EasyChat vs llama.cpp WebUI research

## Build

```bash
find src/main/java -name "*.java" > build/srcs.txt
javac -source 21 -target 21 -encoding UTF-8 -nowarn -d build/classes -cp "lib/*" @build/srcs.txt
cp -a src/main/resources/. build/classes/
```

## License

Same as upstream (see [LICENSE](./LICENSE)).
