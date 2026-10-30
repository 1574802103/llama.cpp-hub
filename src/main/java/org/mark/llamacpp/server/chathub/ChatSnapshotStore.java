package org.mark.llamacpp.server.chathub;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 聊天历史的项目内快照。
 *
 * <p>浏览器把历史存在 IndexedDB（按 origin），整个项目拷到别的机器就没了。这里由注入脚本把
 * {@code LlamaUi} 的 conversations/messages 推到 hub，落成 {@code data/chat/webui-db.json.gz}；
 * 打开页面时再灌回浏览器。合并放在服务端做：按 id + 时间戳 newer wins，删除用墓碑传播。</p>
 */
public final class ChatSnapshotStore {

	private static final Logger logger = LoggerFactory.getLogger(ChatSnapshotStore.class);

	private static final Object LOCK = new Object();

	private ChatSnapshotStore() {
	}

	private static Path dbFile() {
		return ChatHubSettings.get().historyPath().resolve("webui-db.json.gz");
	}

	private static Path metaFile() {
		return ChatHubSettings.get().historyPath().resolve("webui-meta.json.gz");
	}

	private static Path configFile() {
		return ChatHubSettings.get().historyPath().resolve("webui-config.json.gz");
	}

	/** 读历史快照；没有文件时返回空结构。 */
	public static JsonObject load() {
		synchronized (LOCK) {
			JsonObject stored = readGzip(dbFile());
			if (stored == null) {
				return emptySnapshot();
			}
			normalize(stored);
			return stored;
		}
	}

	/**
	 * 合并并落盘。返回合并后的完整快照（调用方通常忽略）。
	 */
	public static JsonObject merge(JsonObject incoming) {
		synchronized (LOCK) {
			JsonObject stored = readGzip(dbFile());
			if (stored == null) {
				stored = emptySnapshot();
			}
			normalize(stored);

			mergeRows(stored, incoming, "conversations", "lastModified");
			mergeRows(stored, incoming, "messages", "timestamp");
			mergeTombs(stored, incoming);

			stored.addProperty("updatedAt", System.currentTimeMillis());
			writeGzip(dbFile(), stored);
			return stored;
		}
	}

	/** 每个会话/消息由哪个模型产生（上游没有这个字段，hub 自己记）。 */
	public static JsonObject loadMeta() {
		synchronized (LOCK) {
			JsonObject meta = readGzip(metaFile());
			if (meta == null) {
				meta = new JsonObject();
			}
			if (!meta.has("turns") || !meta.get("turns").isJsonObject()) {
				meta.add("turns", new JsonObject());
			}
			return meta;
		}
	}

	public static JsonObject mergeMeta(JsonObject incoming) {
		synchronized (LOCK) {
			JsonObject meta = loadMeta();
			JsonObject turns = meta.getAsJsonObject("turns");
			if (incoming != null && incoming.has("turns") && incoming.get("turns").isJsonObject()) {
				for (var entry : incoming.getAsJsonObject("turns").entrySet()) {
					turns.add(entry.getKey(), entry.getValue());
				}
			}
			meta.addProperty("updatedAt", System.currentTimeMillis());
			writeGzip(metaFile(), meta);
			return meta;
		}
	}

	/** WebUI 的 localStorage 配置（MCP 服务器、主题等），跟历史一起走。 */
	public static JsonObject loadConfig() {
		synchronized (LOCK) {
			JsonObject config = readGzip(configFile());
			return config == null ? new JsonObject() : config;
		}
	}

	public static JsonObject mergeConfig(JsonObject incoming) {
		synchronized (LOCK) {
			JsonObject config = readGzip(configFile());
			if (config == null) {
				config = new JsonObject();
			}
			if (incoming != null) {
				// items: [{key, value}] —— value 是 localStorage 里的原始字符串
				JsonArray items = incoming.has("items") && incoming.get("items").isJsonArray()
						? incoming.getAsJsonArray("items")
						: new JsonArray();
				JsonObject stored = config.has("items") && config.get("items").isJsonArray()
						? config.getAsJsonObject()
						: new JsonObject();
				JsonObject values = stored.has("values") && stored.get("values").isJsonObject()
						? stored.getAsJsonObject("values")
						: new JsonObject();
				for (JsonElement element : items) {
					if (!element.isJsonObject()) {
						continue;
					}
					JsonObject item = element.getAsJsonObject();
					if (item.has("key") && item.has("value")) {
						values.addProperty(item.get("key").getAsString(), item.get("value").getAsString());
					}
				}
				config = new JsonObject();
				config.add("values", values);
			}
			config.addProperty("updatedAt", System.currentTimeMillis());
			writeGzip(configFile(), config);
			return config;
		}
	}

	/* ------------------------------------------------------------------ 内部 */

	private static JsonObject emptySnapshot() {
		JsonObject snapshot = new JsonObject();
		snapshot.add("conversations", new JsonArray());
		snapshot.add("messages", new JsonArray());
		JsonObject tombs = new JsonObject();
		tombs.add("conversations", new JsonArray());
		tombs.add("messages", new JsonArray());
		snapshot.add("tombs", tombs);
		snapshot.addProperty("updatedAt", 0L);
		return snapshot;
	}

	private static void normalize(JsonObject snapshot) {
		if (!snapshot.has("conversations") || !snapshot.get("conversations").isJsonArray()) {
			snapshot.add("conversations", new JsonArray());
		}
		if (!snapshot.has("messages") || !snapshot.get("messages").isJsonArray()) {
			snapshot.add("messages", new JsonArray());
		}
		if (!snapshot.has("tombs") || !snapshot.get("tombs").isJsonObject()) {
			JsonObject tombs = new JsonObject();
			tombs.add("conversations", new JsonArray());
			tombs.add("messages", new JsonArray());
			snapshot.add("tombs", tombs);
		}
		JsonObject tombs = snapshot.getAsJsonObject("tombs");
		if (!tombs.has("conversations") || !tombs.get("conversations").isJsonArray()) {
			tombs.add("conversations", new JsonArray());
		}
		if (!tombs.has("messages") || !tombs.get("messages").isJsonArray()) {
			tombs.add("messages", new JsonArray());
		}
	}

	private static void mergeRows(JsonObject stored, JsonObject incoming, String field, String timeField) {
		if (incoming == null || !incoming.has(field) || !incoming.get(field).isJsonArray()) {
			return;
		}
		JsonArray storedRows = stored.getAsJsonArray(field);
		JsonObject byId = new JsonObject();
		for (JsonElement element : storedRows) {
			if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
				byId.add(element.getAsJsonObject().get("id").getAsString(), element);
			}
		}
		boolean changed = false;
		for (JsonElement element : incoming.getAsJsonArray(field)) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject row = element.getAsJsonObject();
			if (!row.has("id")) {
				continue;
			}
			String id = row.get("id").getAsString();
			JsonObject existing = byId.has(id) ? byId.getAsJsonObject(id) : null;
			if (existing == null) {
				byId.add(id, row);
				changed = true;
				continue;
			}
			long existingTime = time(existing, timeField);
			long incomingTime = time(row, timeField);
			if (incomingTime > existingTime) {
				byId.add(id, row);
				changed = true;
			}
		}
		if (changed) {
			JsonArray rebuilt = new JsonArray();
			for (var entry : byId.entrySet()) {
				rebuilt.add(entry.getValue());
			}
			stored.add(field, rebuilt);
		}
	}

	private static long time(JsonObject row, String field) {
		if (row.has(field) && row.get(field).isJsonPrimitive()) {
			try {
				return row.get(field).getAsLong();
			} catch (Exception ignored) {
				// 时间戳可能是 ISO 字符串
			}
		}
		if (row.has("updatedAt") && row.get("updatedAt").isJsonPrimitive()) {
			try {
				return row.get("updatedAt").getAsLong();
			} catch (Exception ignored) {
			}
		}
		return 0L;
	}

	private static void mergeTombs(JsonObject stored, JsonObject incoming) {
		if (incoming == null || !incoming.has("tombs") || !incoming.get("tombs").isJsonObject()) {
			return;
		}
		JsonObject incomingTombs = incoming.getAsJsonObject("tombs");
		JsonObject tombs = stored.getAsJsonObject("tombs");
		for (String key : new String[] { "conversations", "messages" }) {
			Set<String> ids = new LinkedHashSet<>();
			for (JsonElement element : tombs.getAsJsonArray(key)) {
				ids.add(element.getAsString());
			}
			if (incomingTombs.has(key) && incomingTombs.get(key).isJsonArray()) {
				for (JsonElement element : incomingTombs.getAsJsonArray(key)) {
					ids.add(element.getAsString());
				}
			}
			// 只保留最近 5000 条墓碑，避免无限膨胀
			JsonArray array = new JsonArray();
			int skip = Math.max(0, ids.size() - 5000);
			int index = 0;
			for (String id : ids) {
				if (index++ < skip) {
					continue;
				}
				array.add(id);
			}
			tombs.add(key, array);
		}
	}

	private static JsonObject readGzip(Path path) {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)),
				StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (Exception e) {
			logger.warn("读取聊天快照失败 {}: {}", path, e.getMessage());
			return null;
		}
	}

	private static void writeGzip(Path path, JsonObject json) {
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(tmp)),
					StandardCharsets.UTF_8)) {
				writer.write(json.toString());
			}
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			logger.warn("写入聊天快照失败 {}: {}", path, e.getMessage());
		}
	}
}
