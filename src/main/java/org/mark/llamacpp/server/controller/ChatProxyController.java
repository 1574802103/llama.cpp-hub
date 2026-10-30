package org.mark.llamacpp.server.controller;

import java.util.Map;

import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.chathub.ChatHubSettings;
import org.mark.llamacpp.server.chathub.ChatProxyServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpHeaderNames;

/**
 * 聊天台接口：给前端「开始聊天」用，返回该模型在聊天台上的地址；另外提供聊天台的设置读写。
 *
 * <p>聊天台把 llama.cpp 自带 WebUI 挂在固定端口上，所有模型共用一个 origin —— 这样切换模型时
 * 历史（浏览器 IndexedDB + 项目内 {@code data/chat/} 快照）都是同一份。</p>
 */
public class ChatProxyController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(ChatProxyController.class);

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		if (uri.equals("/api/models/chat")) {
			handleOpenChat(ctx, request);
			return true;
		}
		if (uri.equals("/api/chat/proxy/status")) {
			handleStatus(ctx, request);
			return true;
		}
		if (uri.equals("/api/chat/proxy/config")) {
			handleConfig(ctx, request);
			return true;
		}
		if (uri.equals("/api/chat/proxy/models")) {
			handleModels(ctx, request);
			return true;
		}
		return false;
	}

	private void handleOpenChat(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		assertRequestMethod(request.method() != HttpMethod.POST, "只支持 POST 请求");
		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			JsonObject json = body == null || body.length == 0 ? new JsonObject() : JsonUtil.tryParseObject(body);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体不是合法的 JSON"));
				return;
			}
			String modelId = JsonUtil.getJsonString(json, "modelId", "");
			if (modelId == null || modelId.isBlank()) {
				modelId = ChatHubSettings.get().lastModelId;
			}
			if (modelId == null || modelId.isBlank()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少 modelId"));
				return;
			}
			Map<String, Map<String, Object>> configs = ConfigManager.getInstance().loadAllLaunchConfigs();
			if (!configs.containsKey(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型不存在: " + modelId));
				return;
			}
			ChatProxyServer proxy = ChatProxyServer.getInstance();
			if (!proxy.isRunning()) {
				proxy.start();
			}
			if (!proxy.isRunning()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("聊天台未启动，请检查端口占用或 config/chathub.json"));
				return;
			}
			ChatHubSettings.get().lastModelId = modelId;
			ChatHubSettings.get().save();

			String host = request.headers().get(HttpHeaderNames.HOST, "127.0.0.1");
			int colon = host.lastIndexOf(':');
			if (colon > 0 && host.indexOf(']') < colon) {
				host = host.substring(0, colon);
			}
			// 用户点了「开始聊天」= 明确要启动这个模型，由 hub 触发一次加载；
			// 聊天页自己不会启动任何模型（模型没跑就显示「未运行」页）。
			if (LlamaServerManager.getInstance().getModelPort(modelId) == null
					&& !ChatProxyServer.getInstance().isLoading(modelId)) {
				ChatProxyServer.getInstance().startLoad(modelId);
			}

			String url = "http://" + host + ":" + proxy.getPort() + "/?m=" + java.net.URLEncoder.encode(modelId, "UTF-8");

			JsonObject data = new JsonObject();
			data.addProperty("url", url);
			data.addProperty("modelId", modelId);
			data.addProperty("port", proxy.getPort());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.warn("打开聊天台失败: {}", e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("打开聊天台失败: " + e.getMessage()));
		}
	}

	private void handleStatus(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		assertRequestMethod(request.method() != HttpMethod.GET, "只支持 GET 请求");
		ChatProxyServer proxy = ChatProxyServer.getInstance();
		ChatHubSettings settings = ChatHubSettings.get();
		JsonObject data = new JsonObject();
		data.addProperty("enabled", settings.enabled);
		data.addProperty("running", proxy.isRunning());
		data.addProperty("port", proxy.getPort());
		data.addProperty("lastModelId", settings.lastModelId);
		data.addProperty("historyDir", settings.historyPath().toString());
		LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
	}

	private void handleConfig(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		if (request.method() == HttpMethod.GET) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(ChatHubSettings.get().toJson()));
			return;
		}
		assertRequestMethod(request.method() != HttpMethod.POST && request.method() != HttpMethod.PUT,
				"只支持 GET/POST 请求");
		try {
			byte[] body = JsonUtil.readRequestBytes(request);
			JsonObject json = body == null || body.length == 0 ? null : JsonUtil.tryParseObject(body);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体不是合法的 JSON"));
				return;
			}
			int oldPort = ChatHubSettings.get().port;
			ChatHubSettings updated = ChatHubSettings.applyPatch(json);
			ChatProxyServer proxy = ChatProxyServer.getInstance();
			if (updated.port != oldPort || !proxy.isRunning()) {
				proxy.stop();
				proxy.start();
			}
			JsonObject data = updated.toJson();
			data.addProperty("running", proxy.isRunning());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.warn("保存聊天台设置失败: {}", e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存失败: " + e.getMessage()));
		}
	}

	private void handleModels(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		assertRequestMethod(request.method() != HttpMethod.GET, "只支持 GET 请求");
		Map<String, Map<String, Object>> configs = ConfigManager.getInstance().loadAllLaunchConfigs();
		Map<String, String> aliases = ConfigManager.getInstance().loadAliasMap();
		com.google.gson.JsonArray array = new com.google.gson.JsonArray();
		for (String id : configs.keySet()) {
			if ("__shared__".equals(id)) {
				continue;
			}
			JsonObject item = new JsonObject();
			item.addProperty("id", id);
			String alias = aliases.get(id);
			item.addProperty("name", alias == null || alias.isBlank() ? id : alias);
			array.add(item);
		}
		JsonObject data = new JsonObject();
		data.add("models", array);
		data.addProperty("lastModelId", ChatHubSettings.get().lastModelId);
		LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
	}
}
