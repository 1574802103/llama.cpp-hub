# llama.cpp-hub (maintained fork)

A maintenance fork of [IIIIIllllIIIIIlllll/llama.cpp-hub](https://github.com/IIIIIllllIIIIIlllll/llama.cpp-hub)
(baseline **0.9.8.3**). The upstream author stopped maintaining the project; this fork keeps it working with
current llama.cpp builds and replaces the built-in chat with **llama.cpp's own WebUI**.

中文说明见 [README.md](./README.md)。

## What's different

- **Chat = llama.cpp WebUI.** All models share one fixed origin (`http://host:8100`), so switching models keeps
  the same conversation list and you can continue a thread with another model. History is snapshotted into
  `data/chat/`, so copying the project (or switching llama.cpp builds) does not lose chats.
- **The hub owns start/stop.** Opening a chat page never spawns a model: if it is not running you get a
  "not running" page. The WebUI's `/models/load` and `/models/unload` are blocked (403) on the chat port.
- **Model list / llama.cpp releases** fixed for upstream changes: recent-build listing (prereleases, `win-cpu-x64`
  asset names), batch version switching, icons matched by model name first.
- **Parameter panel** rebuilt from `llama-server --help`: 183 parameters / 14 groups, upstream defaults and enums,
  each description prefixed with its `--flag`.
- **EasyChat removed** (5.8k lines + 640 KB of front-end).

## Quick start

1. Download the release package (JRE + one llama.cpp CUDA build included) and unpack it.
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
| 8081–8199 | per-model `llama-server` instances |

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
