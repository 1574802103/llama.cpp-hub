package org.mark.llamacpp.server.chathub;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.controller.ModelActionController;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * 聊天台：把 llama.cpp 自带 WebUI 挂在 hub 自己的一个固定端口上，所有模型共用同一个 origin。
 *
 * <p>为什么共用一个 origin：WebUI 的历史存在浏览器 IndexedDB，按 origin 隔离，会话表里没有模型字段。
 * 只要 origin 不变，换模型（{@code ?m=}）看到的还是同一份历史，接着聊就行；同时 hub 负责把历史快照到
 * {@code data/chat/}，换设备也能带走。</p>
 *
 * <p>路由：请求里的 {@code ?m=模型ID}（其次 Referer 上的 m、其次 cookie、最后上次使用的模型）决定转发到哪个实例；
 * 没加载就先走 hub 现有的自动加载流程，前端显示等待页。</p>
 */
public final class ChatProxyServer {

	private static final Logger logger = LoggerFactory.getLogger(ChatProxyServer.class);

	private static final Gson GSON = new Gson();

	private static ChatProxyServer instance;

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel serverChannel;
	private int boundPort;

	private final HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	/** 正在加载的模型 -> 开始时间 */
	private final Map<String, Long> loading = new ConcurrentHashMap<>();
	/** 最近一次加载失败 */
	private final Map<String, String> loadErrors = new ConcurrentHashMap<>();

	private ChatProxyServer() {
	}

	public static synchronized ChatProxyServer getInstance() {
		if (instance == null) {
			instance = new ChatProxyServer();
		}
		return instance;
	}

	public synchronized void start() {
		ChatHubSettings settings = ChatHubSettings.get();
		if (!settings.enabled) {
			logger.info("聊天台未启用（config/chathub.json enabled=false）");
			return;
		}
		if (serverChannel != null) {
			return;
		}
		String address = settings.listenAddress == null || settings.listenAddress.isBlank()
				? LlamaServer.getListenAddress()
				: LlamaServer.normalizeListenAddress(settings.listenAddress);
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup(2);
		try {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 128)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new HttpServerCodec());
							ch.pipeline().addLast(new HttpObjectAggregator(32 * 1024 * 1024));
							ch.pipeline().addLast(new Handler());
						}
					});
			serverChannel = bootstrap.bind(address, settings.port).sync().channel();
			boundPort = settings.port;
			logger.info("聊天台已启动: http://{}:{}/ （所有模型共用同一 origin，历史目录 {}）",
					address, settings.port, settings.historyPath());
		} catch (Exception e) {
			logger.error("聊天台启动失败: {}", e.getMessage(), e);
			stop();
		}
	}

	public synchronized void stop() {
		if (serverChannel != null) {
			serverChannel.close().syncUninterruptibly();
			serverChannel = null;
		}
		if (bossGroup != null) {
			bossGroup.shutdownGracefully().syncUninterruptibly();
			bossGroup = null;
		}
		if (workerGroup != null) {
			workerGroup.shutdownGracefully().syncUninterruptibly();
			workerGroup = null;
		}
	}

	public boolean isRunning() {
		return serverChannel != null && serverChannel.isActive();
	}

	public int getPort() {
		return boundPort > 0 ? boundPort : ChatHubSettings.get().port;
	}

	/* ------------------------------------------------------------------ 请求处理 */

	private final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
			QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
			String path = decoder.path();

			if (path.startsWith("/__hubchat")) {
				handleInternal(ctx, request, path, decoder);
				return;
			}

			// 模型启停只认 hub 的操作：聊天页/WebUI 自己不能加载或卸载模型（避免「打开页面又启动一个」）。
			if (path.equals("/models/load") || path.equals("/models/unload") || path.equals("/models/sse")) {
				JsonObject denied = new JsonObject();
				denied.addProperty("error", "模型启停由 llama.cpp-hub 控制，请在 hub 的模型列表里操作");
				sendJson(ctx, denied, HttpResponseStatus.FORBIDDEN);
				return;
			}

			String modelId = resolveModel(request, decoder);
			if (modelId == null) {
				if (!isDocumentPath(request.uri())) {
					JsonObject body = new JsonObject();
					JsonObject error = new JsonObject();
					error.addProperty("message", "没有指定模型（缺少 ?m= 或 cookie）");
					error.addProperty("type", "model_not_specified");
					error.addProperty("code", 400);
					body.add("error", error);
					sendJson(ctx, body, HttpResponseStatus.BAD_REQUEST);
					return;
				}
				sendHtml(ctx, HttpResponseStatus.OK, modelPickerPage());
				return;
			}

			Integer port = LlamaServerManager.getInstance().getModelPort(modelId);
			if (port == null) {
				if (isLoading(modelId)) {
					sendHtml(ctx, HttpResponseStatus.OK, waitingPage(modelId, null));
					return;
				}
				// 页面给说明页；API 请求给 OpenAI 风格的错误，别把 HTML 塞给聊天前端
				if (!isDocumentPath(request.uri())) {
					JsonObject body = new JsonObject();
					JsonObject error = new JsonObject();
					error.addProperty("message", "模型未运行，请在 llama.cpp-hub 的模型列表里启动");
					error.addProperty("type", "model_not_running");
					error.addProperty("code", 503);
					body.add("error", error);
					sendJson(ctx, body, HttpResponseStatus.SERVICE_UNAVAILABLE);
					return;
				}
				sendHtml(ctx, HttpResponseStatus.OK, notRunningPage(modelId, loadErrors.get(modelId)));
				return;
			}

			LlamaServerManager.getInstance().updateModelLastUsedTime(modelId);
			proxy(ctx, request, port, modelId);
		}
	}

	private void handleInternal(ChannelHandlerContext ctx, FullHttpRequest request, String path,
			QueryStringDecoder decoder) {
		try {
			if (path.equals("/__hubchat.js")) {
				byte[] script = readResource("/web/chathub/hubchat.js");
				if (script == null) {
					sendText(ctx, HttpResponseStatus.NOT_FOUND, "hubchat.js not found");
					return;
				}
				FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
						HttpResponseStatus.OK, Unpooled.wrappedBuffer(script));
				response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/javascript; charset=utf-8");
				response.headers().set(HttpHeaderNames.CONTENT_LENGTH, script.length);
				response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
				ctx.writeAndFlush(response);
				return;
			}

			if (path.equals("/__hubchat/status")) {
				String modelId = firstParam(decoder, "m");
				JsonObject json = new JsonObject();
				json.addProperty("model", modelId == null ? "" : modelId);
				Integer port = modelId == null ? null : LlamaServerManager.getInstance().getModelPort(modelId);
				json.addProperty("ready", port != null);
				json.addProperty("loading", modelId != null && isLoading(modelId));
				String error = modelId == null ? null : loadErrors.get(modelId);
				if (error != null) {
					json.addProperty("error", error);
				}
				sendJson(ctx, json);
				return;
			}

			if (path.equals("/__hubchat/snapshot")) {
				if ("PUT".equalsIgnoreCase(request.method().name()) || "POST".equalsIgnoreCase(request.method().name())) {
					byte[] body = readBody(request);
					JsonObject incoming = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
							.getAsJsonObject();
					if (incoming.has("db") && incoming.get("db").isJsonObject()) {
						ChatSnapshotStore.merge(incoming.getAsJsonObject("db"));
					}
					if (incoming.has("meta") && incoming.get("meta").isJsonObject()) {
						ChatSnapshotStore.mergeMeta(incoming.getAsJsonObject("meta"));
					}
					if (incoming.has("config") && incoming.get("config").isJsonObject()) {
						ChatSnapshotStore.mergeConfig(incoming.getAsJsonObject("config"));
					}
				}
				JsonObject payload = new JsonObject();
				payload.add("db", ChatSnapshotStore.load());
				payload.add("meta", ChatSnapshotStore.loadMeta());
				payload.add("config", ChatSnapshotStore.loadConfig());
				sendJson(ctx, payload);
				return;
			}

			if (path.equals("/__hubchat/conversations")) {
				String model = firstParam(decoder, "m");
				JsonObject snapshot = ChatSnapshotStore.load();
				JsonArray conversations = snapshot.getAsJsonArray("conversations");
				JsonArray messages = snapshot.getAsJsonArray("messages");
				Map<String, java.util.Set<String>> modelsByConv = new LinkedHashMap<>();
				Map<String, Long> lastByConv = new LinkedHashMap<>();
				for (var element : messages) {
					if (!element.isJsonObject()) {
						continue;
					}
					JsonObject message = element.getAsJsonObject();
					String convId = message.has("convId") ? message.get("convId").getAsString() : null;
					if (convId == null) {
						continue;
					}
					if (message.has("model") && !message.get("model").isJsonNull()) {
						modelsByConv.computeIfAbsent(convId, key -> new java.util.LinkedHashSet<>())
								.add(message.get("model").getAsString());
					}
					long stamp = message.has("timestamp") && message.get("timestamp").isJsonPrimitive()
							? message.get("timestamp").getAsLong() : 0L;
					lastByConv.merge(convId, stamp, Math::max);
				}
				List<JsonObject> rows = new ArrayList<>();
				for (var element : conversations) {
					if (!element.isJsonObject()) {
						continue;
					}
					JsonObject conversation = element.getAsJsonObject();
					String id = conversation.has("id") ? conversation.get("id").getAsString() : null;
					if (id == null) {
						continue;
					}
					java.util.Set<String> used = modelsByConv.getOrDefault(id, java.util.Set.of());
					if (model != null && !model.isBlank() && !used.contains(model)) {
						continue;
					}
					JsonObject row = new JsonObject();
					row.addProperty("id", id);
					row.addProperty("name", conversation.has("name") ? conversation.get("name").getAsString() : id);
					row.addProperty("lastModified", Math.max(
							conversation.has("lastModified") && conversation.get("lastModified").isJsonPrimitive()
									? conversation.get("lastModified").getAsLong() : 0L,
							lastByConv.getOrDefault(id, 0L)));
					JsonArray usedArray = new JsonArray();
					used.forEach(usedArray::add);
					row.add("models", usedArray);
					rows.add(row);
				}
				rows.sort((left, right) -> Long.compare(right.get("lastModified").getAsLong(),
						left.get("lastModified").getAsLong()));
				JsonArray limited = new JsonArray();
				for (int i = 0; i < Math.min(rows.size(), 50); i++) {
					limited.add(rows.get(i));
				}
				JsonObject payload = new JsonObject();
				payload.add("conversations", limited);
				payload.addProperty("total", rows.size());
				sendJson(ctx, payload);
				return;
			}

			if (path.equals("/__hubchat/models")) {
				JsonObject payload = new JsonObject();
				payload.add("models", modelsJson());
				payload.addProperty("current", resolveModel(request, decoder));
				sendJson(ctx, payload);
				return;
			}

			if (path.equals("/__hubchat/start") || path.equals("/__hubchat/stop")) {
				String modelId = firstParam(decoder, "m");
				JsonObject payload = new JsonObject();
				if (modelId == null || modelId.isBlank()) {
					payload.addProperty("success", false);
					payload.addProperty("error", "缺少模型 id");
					sendJson(ctx, payload);
					return;
				}
				LlamaServerManager manager = LlamaServerManager.getInstance();
				if (path.equals("/__hubchat/stop")) {
					loading.remove(modelId);
					loadErrors.remove(modelId);
					String sourceModelId = manager.getSourceModelId(modelId);
					boolean stopped = manager.stopModel(modelId);
					if (stopped) {
						LlamaServer.sendModelStopEvent(modelId, sourceModelId, true, "已停止");
					}
					payload.addProperty("success", stopped);
					payload.addProperty("state", stopped ? "stopped" : "idle");
					if (!stopped) {
						payload.addProperty("error", "停止失败或模型未运行");
					}
					sendJson(ctx, payload);
					return;
				}
				if (manager.getModelPort(modelId) != null) {
					payload.addProperty("success", true);
					payload.addProperty("state", "running");
					sendJson(ctx, payload);
					return;
				}
				if (!isLoading(modelId)) {
					loadErrors.remove(modelId);
					loading.put(modelId, System.currentTimeMillis());
					LlamaServer.sendModelLoadStartEvent(modelId, manager.getModelPort(modelId), "正在加载模型");
					long timeout = ChatHubSettings.get().autoLoadTimeoutMs;
					Thread.ofVirtual().name("chat-start-" + modelId).start(() -> {
						try {
							String error = manager.autoLoadModelFromConfig(modelId, timeout);
							if (error != null) {
								loadErrors.put(modelId, error);
								LlamaServer.sendModelLoadEvent(modelId, false, error);
							} else {
								manager.updateModelLastUsedTime(modelId);
								LlamaServer.sendModelLoadEvent(modelId, true, "加载成功", manager.getModelPort(modelId));
							}
						} catch (Exception e) {
							loadErrors.put(modelId, String.valueOf(e.getMessage()));
							LlamaServer.sendModelLoadEvent(modelId, false, String.valueOf(e.getMessage()));
						} finally {
							loading.remove(modelId);
						}
					});
				}
				payload.addProperty("success", true);
				payload.addProperty("state", "loading");
				sendJson(ctx, payload);
				return;
			}

			if (path.equals("/__hubchat/touch")) {
				String modelId = firstParam(decoder, "m");
				if (modelId != null) {
					LlamaServerManager.getInstance().updateModelLastUsedTime(modelId);
				}
				JsonObject json = new JsonObject();
				json.addProperty("ok", true);
				sendJson(ctx, json);
				return;
			}

			sendText(ctx, HttpResponseStatus.NOT_FOUND, "unknown endpoint");
		} catch (Exception e) {
			logger.warn("聊天台内部接口失败 {}: {}", path, e.getMessage());
			sendText(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, String.valueOf(e.getMessage()));
		}
	}

	/* ------------------------------------------------------------------ 模型解析与加载 */

	private String resolveModel(FullHttpRequest request, QueryStringDecoder decoder) {
		String modelId = firstParam(decoder, "m");
		if (modelId == null) {
			String referer = request.headers().get(HttpHeaderNames.REFERER);
			if (referer != null) {
				try {
					QueryStringDecoder refDecoder = new QueryStringDecoder(referer);
					modelId = firstParam(refDecoder, "m");
				} catch (Exception ignored) {
				}
			}
		}
		if (modelId == null) {
			String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
			if (cookieHeader != null) {
				for (String part : cookieHeader.split(";")) {
					String[] pair = part.trim().split("=", 2);
					if (pair.length == 2 && "hub_model".equals(pair[0])) {
						modelId = pair[1];
						break;
					}
				}
			}
		}
		if (modelId != null && !modelId.isBlank() && modelExists(modelId.trim())) {
			ChatHubSettings settings = ChatHubSettings.get();
			String trimmed = modelId.trim();
			if (!trimmed.equals(settings.lastModelId)) {
				settings.lastModelId = trimmed;
				settings.save();
			}
			return trimmed;
		}
		// 认不出模型就展示选择页，绝不猜一个模型去加载（那会凭空启动第二个模型）
		return null;
	}

	private boolean modelExists(String modelId) {
		Map<String, Map<String, Object>> configs = ConfigManager.getInstance().loadAllLaunchConfigs();
		return configs.containsKey(modelId);
	}

	private List<String> modelIds() {
		Map<String, Map<String, Object>> configs = ConfigManager.getInstance().loadAllLaunchConfigs();
		List<String> ids = new ArrayList<>();
		for (String id : configs.keySet()) {
			if (!"__shared__".equals(id)) {
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * 与 hub「模型列表」同一份数据（扫描到的 GGUF + 有效克隆体），额外补 running/loading。
	 */
	private JsonArray modelsJson() {
		JsonArray array = new JsonArray();
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			for (Map<String, Object> model : ModelActionController.buildLocalModelList()) {
				Object idValue = model.get("id");
				if (idValue == null) {
					continue;
				}
				String id = String.valueOf(idValue);
				JsonObject item = new JsonObject();
				item.addProperty("id", id);
				Object name = model.get("name");
				item.addProperty("name", name == null || String.valueOf(name).isBlank() ? id : String.valueOf(name));
				Object alias = model.get("alias");
				if (alias != null && !String.valueOf(alias).isBlank()) {
					item.addProperty("alias", String.valueOf(alias));
				}
				item.addProperty("running", manager.getModelPort(id) != null);
				item.addProperty("loading", isLoading(id));
				if (Boolean.TRUE.equals(model.get("isClone"))) {
					item.addProperty("isClone", true);
				}
				array.add(item);
			}
		} catch (Exception e) {
			logger.warn("构建聊天台模型列表失败: {}", e.getMessage());
		}
		return array;
	}

	public boolean isLoading(String modelId) {
		Long started = loading.get(modelId);
		if (started == null) {
			return false;
		}
		if (System.currentTimeMillis() - started > ChatHubSettings.get().autoLoadTimeoutMs + 30_000L) {
			loading.remove(modelId);
			return false;
		}
		return true;
	}

	public void startLoad(String modelId) {
		loading.put(modelId, System.currentTimeMillis());
		loadErrors.remove(modelId);
		long timeout = ChatHubSettings.get().autoLoadTimeoutMs;
		Thread.ofVirtual().name("chat-autoload-" + modelId).start(() -> {
			try {
				logger.info("[聊天台] 按需加载模型: {}", modelId);
				String error = LlamaServerManager.getInstance().autoLoadModelFromConfig(modelId, timeout);
				if (error != null) {
					loadErrors.put(modelId, error);
					logger.warn("[聊天台] 加载失败 {}: {}", modelId, error);
				} else {
					LlamaServerManager.getInstance().updateModelLastUsedTime(modelId);
					logger.info("[聊天台] 加载完成: {}", modelId);
				}
			} catch (Exception e) {
				loadErrors.put(modelId, String.valueOf(e.getMessage()));
				logger.warn("[聊天台] 加载异常 {}: {}", modelId, e.getMessage());
			} finally {
				loading.remove(modelId);
			}
		});
	}

	/* ------------------------------------------------------------------ 反向代理 */

	private void proxy(ChannelHandlerContext ctx, FullHttpRequest request, int port, String modelId) {
		String method = request.method().name();
		String uri = request.uri();
		byte[] body = readBody(request);
		boolean clientGzip = request.headers().containsValue(HttpHeaderNames.ACCEPT_ENCODING, "gzip", true);
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			String name = entry.getKey();
			if (HttpHeaderNames.HOST.contentEqualsIgnoreCase(name)
					|| HttpHeaderNames.CONNECTION.contentEqualsIgnoreCase(name)
					|| HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(name)
					|| HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(name)
					|| HttpHeaderNames.ACCEPT_ENCODING.contentEqualsIgnoreCase(name)) {
				continue;
			}
			headers.computeIfAbsent(name, key -> new ArrayList<>()).add(entry.getValue());
		}

		Thread.ofVirtual().name("chat-proxy").start(() -> {
			try {
				HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + uri));
				builder.method(method, body.length > 0
						? HttpRequest.BodyPublishers.ofByteArray(body)
						: HttpRequest.BodyPublishers.noBody());
				builder.header("Accept-Encoding", "gzip");
				for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
					if (entry.getValue().size() == 1) {
						builder.header(entry.getKey(), entry.getValue().get(0));
					} else {
						for (String value : entry.getValue()) {
							builder.header(entry.getKey(), value);
						}
					}
				}
				boolean isDocument = isDocumentPath(uri);
				if (!isStreamingPath(uri)) {
					builder.timeout(Duration.ofSeconds(120));
				}
				HttpResponse<InputStream> response = httpClient.send(builder.build(),
						HttpResponse.BodyHandlers.ofInputStream());

				if (isDocument && response.statusCode() == 200) {
					byte[] raw = readAll(response.body(), 6 * 1024 * 1024);
					if (isGzip(response.headers()) && raw.length > 0) {
						raw = gunzip(raw);
					}
					if (raw.length == 0) {
						FullHttpResponse empty = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
								HttpResponseStatus.valueOf(response.statusCode()), Unpooled.EMPTY_BUFFER);
						copyHeaders(response, empty, false);
						empty.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
						ctx.writeAndFlush(empty);
						return;
					}
					String html = new String(raw, StandardCharsets.UTF_8);
					html = inject(html, modelId);
					byte[] out = html.getBytes(StandardCharsets.UTF_8);
					FullHttpResponse full = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
							HttpResponseStatus.valueOf(response.statusCode()), Unpooled.wrappedBuffer(out));
					copyHeaders(response, full, true);
					full.headers().set(HttpHeaderNames.CONTENT_LENGTH, out.length);
					full.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
					full.headers().add(HttpHeaderNames.SET_COOKIE,
							"hub_model=" + modelId + "; Path=/; Max-Age=31536000; SameSite=Lax");
					ctx.writeAndFlush(full);
					return;
				}

				int statusCode = response.statusCode();
				if (statusCode == 304 || statusCode == 204 || "HEAD".equalsIgnoreCase(method)) {
					FullHttpResponse empty = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
							HttpResponseStatus.valueOf(statusCode), Unpooled.EMPTY_BUFFER);
					copyHeaders(response, empty, false);
					ctx.writeAndFlush(empty);
					return;
				}

				boolean gzip = isGzip(response.headers());
				DefaultHttpResponse out = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
						HttpResponseStatus.valueOf(statusCode));
				copyHeaders(response, out, false);
				out.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
				out.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
				if (gzip && !clientGzip) {
					out.headers().remove(HttpHeaderNames.CONTENT_ENCODING);
				}
				ctx.writeAndFlush(out);

				try (InputStream input = response.body()) {
					InputStream source = (gzip && !clientGzip) ? new GZIPInputStream(input) : input;
					byte[] buffer = new byte[16 * 1024];
					int read;
					while ((read = source.read(buffer)) >= 0) {
						if (read == 0) {
							continue;
						}
						if (!ctx.channel().isActive()) {
							return;
						}
						if (!ctx.channel().isWritable()) {
							Thread.sleep(5);
						}
						ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(buffer, 0, read)));
					}
				}
				ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			} catch (Exception e) {
				String message = String.valueOf(e.getMessage());
				boolean clientAbort = e instanceof java.io.IOException
						&& (message.contains("Connection reset") || message.contains("Broken pipe")
								|| message.contains("connection was aborted"));
				if (clientAbort) {
					logger.debug("[聊天台] 客户端断开 {}: {}", uri, message);
				} else {
					logger.warn("[聊天台] 代理失败 {}: {} ({})", uri, message, e.getClass().getSimpleName(), e);
				}
				if (ctx.channel().isActive()) {
					sendHtml(ctx, HttpResponseStatus.BAD_GATEWAY, errorPage(modelId, e.getMessage()));
				}
			}
		});
	}

	private boolean isDocumentPath(String uri) {
		String path = new QueryStringDecoder(uri).path();
		return path.equals("/") || path.endsWith(".html");
	}

	private boolean isStreamingPath(String uri) {
		return uri.startsWith("/v1/chat/completions") || uri.startsWith("/v1/completions")
				|| uri.contains("/slots") || uri.startsWith("/models/sse");
	}

	private boolean isGzip(io.netty.handler.codec.http.HttpHeaders headers) {
		return false;
	}

	private boolean isGzip(java.net.http.HttpHeaders headers) {
		return headers.firstValue("content-encoding").orElse("").toLowerCase().contains("gzip");
	}

	private void copyHeaders(HttpResponse<InputStream> response, io.netty.handler.codec.http.HttpResponse out,
			boolean skipEncoding) {
		for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
			String name = entry.getKey();
			if (name == null) {
				continue;
			}
			if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(name)
					|| HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(name)
					|| HttpHeaderNames.CONNECTION.contentEqualsIgnoreCase(name)
					|| HttpHeaderNames.KEEP_ALIVE.contentEqualsIgnoreCase(name)
					|| (skipEncoding && HttpHeaderNames.CONTENT_ENCODING.contentEqualsIgnoreCase(name))) {
				continue;
			}
			for (String value : entry.getValue()) {
				out.headers().add(name, value);
			}
		}
	}

	private String inject(String html, String modelId) {
		String stamp = "<script>window.__HUB_MODEL=" + GSON.toJson(modelId)
				+ ";window.__HUB_CHAT_PORT=" + getPort() + ";</script>";
		String script = "<script src=\"/__hubchat.js\" defer></script>";
		int index = html.lastIndexOf("</body>");
		if (index < 0) {
			return html + stamp + script;
		}
		return html.substring(0, index) + stamp + script + html.substring(index);
	}

	/* ------------------------------------------------------------------ 页面 */

	private String modelPickerPage() {
		StringBuilder builder = new StringBuilder();
		builder.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
				.append("<title>llama.cpp-hub · 聊天</title><style>")
				.append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#0f1115;color:#e6e6e6;")
				.append("display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}")
				.append(".box{max-width:520px;padding:32px;border:1px solid #2a2f3a;border-radius:16px;background:#161a22}")
				.append("h1{font-size:18px;margin:0 0 8px}p{color:#9aa4b2;font-size:13px;margin:0 0 20px}")
				.append("a{display:block;padding:10px 12px;margin:6px 0;border:1px solid #2a2f3a;border-radius:10px;")
				.append("color:#e6e6e6;text-decoration:none;font-size:14px}a:hover{border-color:#4b5563;background:#1d2230}")
				.append("</style></head><body><div class=\"box\"><h1>选择要聊天的模型</h1>")
				.append("<p>模型启停由 llama.cpp-hub 控制；这里点进去只打开界面，不会替你启动模型。</p>");
		JsonArray models = modelsJson();
		for (var element : models) {
			JsonObject model = element.getAsJsonObject();
			builder.append("<a href=\"/?m=").append(escapeAttr(model.get("id").getAsString())).append("\">")
					.append(escapeHtml(model.get("name").getAsString())).append("</a>");
		}
		builder.append("</div></body></html>");
		return builder.toString();
	}

	private String waitingPage(String modelId, String error) {
		String name = modelId;
		try {
			String alias = ConfigManager.getInstance().loadAliasMap().get(modelId);
			if (alias != null && !alias.isBlank()) {
				name = alias;
			}
		} catch (Exception ignored) {
		}
		StringBuilder builder = new StringBuilder();
		builder.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
				.append("<title>正在启动 · ").append(escapeHtml(name)).append("</title><style>")
				.append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#0f1115;color:#e6e6e6;")
				.append("display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}")
				.append(".box{max-width:460px;padding:32px;border:1px solid #2a2f3a;border-radius:16px;background:#161a22;text-align:center}")
				.append(".dot{width:36px;height:36px;margin:0 auto 18px;border-radius:50%;border:3px solid #2a2f3a;border-top-color:#60a5fa;animation:s 1s linear infinite}")
				.append("@keyframes s{to{transform:rotate(360deg)}}h1{font-size:16px;margin:0 0 8px}")
				.append("p{color:#9aa4b2;font-size:13px;margin:0;line-height:1.6}code{color:#f87171;font-size:12px;word-break:break-all}")
				.append("</style></head><body><div class=\"box\">");
		if (error == null) {
			builder.append("<div class=\"dot\"></div><h1>正在启动 ").append(escapeHtml(name)).append("</h1>")
					.append("<p>模型加载完成后会自动进入聊天页，通常需要几秒到几十秒。</p>");
		} else {
			builder.append("<h1>模型启动失败</h1><p>").append(escapeHtml(error)).append("</p>")
					.append("<p style=\"margin-top:14px\"><a style=\"color:#60a5fa\" href=\"javascript:location.reload()\">重试</a></p>");
		}
		builder.append("</div><script>");
		if (error == null) {
			builder.append("(function poll(){fetch('/__hubchat/status?m=' + encodeURIComponent(")
					.append(GSON.toJson(modelId))
					.append(")).then(r=>r.json()).then(function(s){if(s.ready){location.replace('/?m=' + encodeURIComponent(")
					.append(GSON.toJson(modelId))
					.append("));}else if(s.error){location.reload();}else{setTimeout(poll,1000);}}).catch(function(){setTimeout(poll,2000);});})();");
		}
		builder.append("</script></body></html>");
		return builder.toString();
	}

	/** 模型没在运行时的页面：不自动加载，只给一个「启动模型」按钮（点了才算用户操作）。 */
	private String notRunningPage(String modelId, String error) {
		String name = modelId;
		try {
			String alias = ConfigManager.getInstance().loadAliasMap().get(modelId);
			if (alias != null && !alias.isBlank()) {
				name = alias;
			}
		} catch (Exception ignored) {
		}
		StringBuilder builder = new StringBuilder();
		builder.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
				.append("<title>模型未运行 · ").append(escapeHtml(name)).append("</title><style>")
				.append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#0f1115;color:#e6e6e6;")
				.append("display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}")
				.append(".box{max-width:460px;padding:32px;border:1px solid #2a2f3a;border-radius:16px;background:#161a22;text-align:center}")
				.append("h1{font-size:16px;margin:0 0 10px}p{color:#9aa4b2;font-size:13px;margin:0;line-height:1.7}")
				.append("button{margin-top:20px;padding:9px 18px;border-radius:10px;border:1px solid #16a34a;background:#132a1c;")
				.append("color:#86efac;font-size:13px;cursor:pointer}button:hover{background:#173a24}")
				.append("a{display:block;margin-top:12px;color:#60a5fa;font-size:12px;text-decoration:none}")
				.append("</style></head><body><div class=\"box\">")
				.append("<h1>").append(escapeHtml(name)).append(" 未运行</h1>")
				.append("<p>模型启停由 llama.cpp-hub 统一控制。请先在 hub 的模型列表里点启动，或者在这里点下面的按钮。</p>");
		if (error != null && !error.isBlank()) {
			builder.append("<p style=\"margin-top:10px;color:#f87171\">上次启动失败：").append(escapeHtml(error)).append("</p>");
		}
		builder.append("<button onclick=\"startModel()\">启动模型</button>")
				.append("<a href=\"javascript:history.back()\">返回</a>")
				.append("</div><script>function startModel(){")
				.append("var b=document.querySelector('button');b.disabled=true;b.textContent='正在启动…';")
				.append("fetch('/__hubchat/start?m=").append(escapeAttr(java.net.URLEncoder.encode(modelId, java.nio.charset.StandardCharsets.UTF_8)))
				.append("',{method:'POST'}).then(function(){location.reload();}).catch(function(){location.reload();});}")
				.append("</script></body></html>");
		return builder.toString();
	}

	private String errorPage(String modelId, String message) {
		return "<!doctype html><html><head><meta charset=\"utf-8\"><title>聊天台代理失败</title></head>"
				+ "<body style=\"font-family:system-ui;background:#0f1115;color:#e6e6e6;padding:40px\">"
				+ "<h1 style=\"font-size:16px\">聊天台代理失败</h1><p style=\"color:#9aa4b2;font-size:13px\">"
				+ escapeHtml(String.valueOf(message)) + "</p><p style=\"font-size:13px\">模型：" + escapeHtml(modelId)
				+ "</p></body></html>";
	}

	/* ------------------------------------------------------------------ 工具 */

	private static byte[] readResource(String path) {
		try (InputStream input = ChatProxyServer.class.getResourceAsStream(path)) {
			return input == null ? null : readAll(input, 4 * 1024 * 1024);
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] readBody(FullHttpRequest request) {
		byte[] body = new byte[request.content().readableBytes()];
		request.content().getBytes(request.content().readerIndex(), body);
		return body;
	}

	private static byte[] readAll(InputStream input, int limit) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) >= 0) {
			out.write(buffer, 0, read);
			if (out.size() > limit) {
				break;
			}
		}
		return out.toByteArray();
	}

	private static byte[] gunzip(byte[] data) throws IOException {
		try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(data))) {
			return readAll(input, 8 * 1024 * 1024);
		}
	}

	private static String firstParam(QueryStringDecoder decoder, String name) {
		List<String> values = decoder.parameters().get(name);
		if (values == null || values.isEmpty()) {
			return null;
		}
		String value = values.get(0);
		return value == null || value.isBlank() ? null : value;
	}

	private static String escapeHtml(String value) {
		return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String escapeAttr(String value) {
		return escapeHtml(value).replace("\"", "&quot;");
	}

	private static void sendJson(ChannelHandlerContext ctx, JsonObject json) {
		sendJson(ctx, json, HttpResponseStatus.OK);
	}

	private static void sendJson(ChannelHandlerContext ctx, JsonObject json, HttpResponseStatus status) {
		byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
				Unpooled.wrappedBuffer(data));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
	}

	private static void sendHtml(ChannelHandlerContext ctx, HttpResponseStatus status, String html) {
		byte[] data = html.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
				Unpooled.wrappedBuffer(data));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
	}

	private static void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String text) {
		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
				Unpooled.wrappedBuffer(data));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
	}
}
