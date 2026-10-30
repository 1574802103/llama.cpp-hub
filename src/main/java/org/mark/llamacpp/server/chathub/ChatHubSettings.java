package org.mark.llamacpp.server.chathub;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 聊天台（llama.cpp 自带 WebUI）的 hub 侧设置。
 *
 * <p>单独存 {@code config/chathub.json}，不走 application.json：那个文件每次保存都会整体重写，
 * 塞新段容易被别处的保存逻辑抹掉。这里的每个字段都落在项目目录内，换 llama.cpp 版本不受影响。</p>
 */
public final class ChatHubSettings {

	private static final Logger logger = LoggerFactory.getLogger(ChatHubSettings.class);

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();
	private static final Path FILE = Paths.get("config", "chathub.json");

	private static volatile ChatHubSettings current;

	/** 是否启用聊天台 */
	public boolean enabled = true;
	/** 聊天台端口：所有模型共用同一个 origin，历史才会共享 */
	public int port = 8100;
	/** 监听地址，空表示跟随主服务的 listenAddress */
	public String listenAddress = "";
	/** 聊天页按需加载模型的超时（毫秒） */
	public long autoLoadTimeoutMs = 180_000L;
	/** 允许 WebUI 自带 MCP 服务器（--ui-mcp-proxy） */
	public boolean uiMcpProxy = true;
	/** 内置工具："" 关闭、all 全开、或逗号列表（--tools） */
	public String tools = "";
	/** stdio MCP 声明，原样作为 --mcp-servers-json 传给每个实例 */
	public String mcpServersJson = "";
	/** 最近一次聊天的模型，用于「开始聊天」不带模型时兜底 */
	public String lastModelId = "";
	/** 历史目录（相对项目根），空表示 data/chat */
	public String historyDir = "";

	private ChatHubSettings() {
	}

	public static ChatHubSettings get() {
		ChatHubSettings local = current;
		if (local != null) {
			return local;
		}
		synchronized (LOCK) {
			if (current == null) {
				current = load();
			}
			return current;
		}
	}

	private static ChatHubSettings load() {
		ChatHubSettings settings = new ChatHubSettings();
		if (!Files.isRegularFile(FILE)) {
			return settings;
		}
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			settings.enabled = bool(root, "enabled", settings.enabled);
			settings.port = integer(root, "port", settings.port);
			settings.listenAddress = string(root, "listenAddress", settings.listenAddress);
			settings.autoLoadTimeoutMs = longValue(root, "autoLoadTimeoutMs", settings.autoLoadTimeoutMs);
			settings.uiMcpProxy = bool(root, "uiMcpProxy", settings.uiMcpProxy);
			settings.tools = string(root, "tools", settings.tools);
			settings.mcpServersJson = string(root, "mcpServersJson", settings.mcpServersJson);
			settings.lastModelId = string(root, "lastModelId", settings.lastModelId);
			settings.historyDir = string(root, "historyDir", settings.historyDir);
		} catch (Exception e) {
			logger.warn("读取 config/chathub.json 失败，使用默认值: {}", e.getMessage());
		}
		return settings;
	}

	/**
	 * 保存（先改内存再落盘，避免读到半截文件）。
	 */
	public void save() {
		synchronized (LOCK) {
			try {
				Files.createDirectories(FILE.getParent());
				Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
				try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
					GSON.toJson(this, writer);
				}
				try {
					Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException atomicFailed) {
					Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				logger.warn("保存 config/chathub.json 失败: {}", e.getMessage());
			}
		}
	}

	/**
	 * 用请求体里的字段覆盖当前设置（白名单），返回生效后的设置。
	 */
	public static ChatHubSettings applyPatch(JsonObject patch) {
		ChatHubSettings settings = get();
		synchronized (LOCK) {
			if (patch.has("enabled")) {
				settings.enabled = patch.get("enabled").getAsBoolean();
			}
			if (patch.has("port")) {
				int port = patch.get("port").getAsInt();
				if (port > 0 && port < 65536) {
					settings.port = port;
				}
			}
			if (patch.has("listenAddress")) {
				settings.listenAddress = patch.get("listenAddress").getAsString();
			}
			if (patch.has("autoLoadTimeoutMs")) {
				long value = patch.get("autoLoadTimeoutMs").getAsLong();
				if (value >= 5_000 && value <= 1_800_000) {
					settings.autoLoadTimeoutMs = value;
				}
			}
			if (patch.has("uiMcpProxy")) {
				settings.uiMcpProxy = patch.get("uiMcpProxy").getAsBoolean();
			}
			if (patch.has("tools")) {
				settings.tools = patch.get("tools").getAsString();
			}
			if (patch.has("mcpServersJson")) {
				settings.mcpServersJson = patch.get("mcpServersJson").getAsString();
			}
			if (patch.has("lastModelId")) {
				settings.lastModelId = patch.get("lastModelId").getAsString();
			}
			if (patch.has("historyDir")) {
				settings.historyDir = patch.get("historyDir").getAsString();
			}
			settings.save();
			current = settings;
		}
		return settings;
	}

	/**
	 * 历史目录：默认 data/chat。
	 */
	public Path historyPath() {
		String dir = historyDir == null ? "" : historyDir.trim();
		if (dir.isEmpty()) {
			return Paths.get("data", "chat").toAbsolutePath().normalize();
		}
		return Paths.get(dir).toAbsolutePath().normalize();
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("enabled", enabled);
		json.addProperty("port", port);
		json.addProperty("listenAddress", listenAddress);
		json.addProperty("autoLoadTimeoutMs", autoLoadTimeoutMs);
		json.addProperty("uiMcpProxy", uiMcpProxy);
		json.addProperty("tools", tools);
		json.addProperty("mcpServersJson", mcpServersJson);
		json.addProperty("lastModelId", lastModelId);
		json.addProperty("historyDir", historyPath().toString());
		return json;
	}

	private static boolean bool(JsonObject root, String key, boolean fallback) {
		try {
			return root.has(key) ? root.get(key).getAsBoolean() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static int integer(JsonObject root, String key, int fallback) {
		try {
			return root.has(key) ? root.get(key).getAsInt() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static long longValue(JsonObject root, String key, long fallback) {
		try {
			return root.has(key) ? root.get(key).getAsLong() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static String string(JsonObject root, String key, String fallback) {
		try {
			return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}
}
