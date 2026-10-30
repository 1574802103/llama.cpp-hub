package org.mark.llamacpp.server.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mark.llamacpp.crawler.NettyHttpUtils;
import org.mark.llamacpp.crawler.UserAgentUtils;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.io.NettyWriteHelper;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LlamaCppDataStruct;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;

/**
 * 	
 */
public class LlamacppController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(LlamacppController.class);
	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

	private static final String I18N_METHOD_POST_ONLY = "common.method.post.only";
	private static final String I18N_METHOD_GET_ONLY = "common.method.get.only";
	private static final String I18N_BODY_EMPTY = "api.error.body.empty";
	private static final String I18N_BODY_PARSE = "api.error.body.parse";
	//private static final String I18N_BODY_NOT_JSON = "api.error.body.not.json";
	private static final String I18N_PARAM_PATH_EMPTY = "api.error.param.path.empty";
	private static final String I18N_PARAM_MODELID_REQUIRED = "api.error.param.modelId.required";
	private static final String I18N_PATH_EXISTS = "api.error.path.exists";
	private static final String I18N_REMOTE_CALL_FAILED = "api.error.remote.call.failed";
	private static final String I18N_MODEL_NOT_LOADED = "api.error.model.not.loaded";
	private static final String I18N_MODEL_PORT_NOT_FOUND = "api.error.model.port.not.found";
	private static final String I18N_LLAMACPP_ADD_FAILED = "api.error.llamacpp.add.failed";
	private static final String I18N_LLAMACPP_REMOVE_FAILED = "api.error.llamacpp.remove.failed";
	private static final String I18N_LLAMACPP_LIST_FAILED = "api.error.llamacpp.list.failed";
	private static final String I18N_LLAMACPP_TEST_FAILED = "api.error.llamacpp.test.failed";
	private static final String I18N_REMOTE_LLAMACPP_LIST_FAILED = "api.error.remote.llamacpp.list.failed";
	private static final String I18N_DIR_INVALID_OR_MISSING_EXE = "api.error.llamacpp.dir.invalid";
	private static final String I18N_PATH_FORMAT_INVALID = "api.error.param.path.invalid";
	private static final String I18N_DIR_IN_USE_BY_LOADED_MODEL = "api.error.llamacpp.dir.in.use";
	private static final String I18N_LLAMA_CLI_NOT_FOUND = "api.error.llamacpp.cli.not.found";
	private static final String I18N_RELEASE_INFO_FETCH_FAILED = "api.error.release.info.fetch.failed";
	private static final String I18N_GITHUB_API_PARSE_FAILED = "api.error.github.api.parse.failed";
	private static final String I18N_PARAM_MESSAGES_MISSING = "api.error.param.messages.missing";
	private static final String I18N_MODEL_RESPONSE_NOT_JSON = "api.error.model.response.not.json";
	private static final String I18N_MODEL_ERROR_HTTP = "api.error.model.error.http";
	private static final String I18N_INFILL_FAILED = "api.error.infill.failed";
	private static final String I18N_PROXY_FORWARD_FAILED = "api.error.proxy.forward.failed";
	private static final String I18N_REMOTE_NODE_ACTION_FAILED = "api.error.remote.node.action.failed";
	private static final String HEADER_NODE_ID = "x-node-id";
	
	
	public LlamacppController() {
		
	}
	
	/**
	 * 	
	 */
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 添加一个llamacpp
		if (uri.equals("/api/llamacpp/add")) {
			this.handleLlamaCppAdd(ctx, request);
			return true;
		}
		// 移除
		if (uri.equals("/api/llamacpp/remove")) {
			this.handleLlamaCppRemove(ctx, request);
			return true;
		}
		// 列出全部
		if (uri.equals("/api/llamacpp/list")) {
			this.handleLlamaCppList(ctx, request);
			return true;
		}
		// 执行测试
		if (uri.equals("/api/llamacpp/test")) {
			this.handleLlamaCppTest(ctx, request);
			return true;
		}
		
		// 代码补全
		if (uri.equals("/infill")) {
			this.handleInfillRequest(ctx, request);
			return true;
		}
		
		// 获取最近若干个 release（版本列表）
		if (uri.equals("/api/llamacpp/releases")) {
			this.handleLlamaCppReleases(ctx, request);
			return true;
		}

		// 获取最新 release
		if (uri.equals("/api/llamacpp/release/latest")) {
			this.handleLlamaCppReleaseLatest(ctx, request);
			return true;
		}
		
		// 处理分词
		if (uri.equals("/tokenize")) {
			this.handleTokenizeRequest(ctx, request);
			return true;
		}
		// 处理模板
		if (uri.equals("/apply-template")) {
			this.handleApplyTemplateRequest(ctx, request);
			return true;
		}
		
		return false;
	}
	
	
	
	/**
	 * 添加llamacpp目录
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyPostRemote(ctx, request, nodeId, "api/llamacpp/add");
				return;
			}
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(bodyBytes, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_PATH_EMPTY));
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			if (items == null) {
				items = new ArrayList<>();
				cfg.setItems(items);
			}
			String normalized = reqData.getPath().trim();
			Path validated = this.validateAndNormalizeLlamaBinDirectory(normalized);
			if (validated == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_INVALID_OR_MISSING_EXE));
				return;
			}
			normalized = validated.toString();
			boolean exists = false;
			for (LlamaCppDataStruct i : items) {
				if (i != null && i.getPath() != null && normalized.equals(i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_EXISTS));
				return;
			}
			LlamaCppDataStruct item = new LlamaCppDataStruct();
			item.setPath(normalized);
			String name = reqData.getName();
			if (name == null || name.trim().isEmpty()) {
				try {
					name = java.nio.file.Paths.get(normalized).getFileName().toString();
				} catch (Exception ex) {
					name = normalized;
				}
			}
			item.setName(name);
			item.setDescription(reqData.getDescription());
			items.add(item);
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加llama.cpp路径成功");
			data.put("added", item);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("添加llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_LLAMACPP_ADD_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 移除一个llamcpp目录
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyPostRemote(ctx, request, nodeId, "api/llamacpp/remove");
				return;
			}
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(bodyBytes, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_PATH_EMPTY));
				return;
			}
			String normalized = reqData.getPath().trim();

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			boolean configChanged = false;
			if (items != null) {
				configChanged = items.removeIf(i -> normalized.equals(i == null || i.getPath() == null ? "" : i.getPath().trim()));
			}
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			if (configChanged) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", "移除llama.cpp路径成功");
				data.put("removed", normalized);
				data.put("count", items == null ? 0 : items.size());
				data.put("changed", true);
				data.put("deletedDirectory", false);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				return;
			}

			Path targetDir;
			try {
				targetDir = Paths.get(normalized).toAbsolutePath().normalize();
			} catch (Exception e) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PATH_FORMAT_INVALID + ": " + normalized));
				return;
			}

			String llamacppRoot = LlamaServer.getDefaultLlamaCppPath();
			Path rootPath;
			try {
				rootPath = Paths.get(llamacppRoot).toAbsolutePath().normalize();
			} catch (Exception e) {
				rootPath = null;
			}
			if (rootPath != null && targetDir.startsWith(rootPath) && !Files.exists(targetDir)) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", "移除llama.cpp路径成功");
				data.put("removed", normalized);
				data.put("count", items == null ? 0 : items.size());
				data.put("changed", false);
				data.put("deletedDirectory", false);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				return;
			}

			if (rootPath == null || !targetDir.startsWith(rootPath)) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", "移除llama.cpp路径成功");
				data.put("removed", normalized);
				data.put("count", items == null ? 0 : items.size());
				data.put("changed", false);
				data.put("deletedDirectory", false);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				return;
			}

			if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", "移除llama.cpp路径成功");
				data.put("removed", normalized);
				data.put("count", items == null ? 0 : items.size());
				data.put("changed", false);
				data.put("deletedDirectory", false);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				return;
			}

			Map<String, LlamaCppProcess> loaded = LlamaServerManager.getInstance().getLoadedProcesses();
			for (LlamaCppProcess proc : loaded.values()) {
				String procPath = proc.getLlamaBinPath();
				if (procPath != null) {
					Path procDir;
					try {
						procDir = Paths.get(procPath.trim()).toAbsolutePath().normalize();
					} catch (Exception ignore) {
						continue;
					}
					if (procDir.equals(targetDir)) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_DIR_IN_USE_BY_LOADED_MODEL));
						return;
					}
				}
			}

			this.deleteDirectoryRecursively(targetDir);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "已删除llama.cpp目录");
			data.put("removed", normalized);
			data.put("count", items == null ? 0 : items.size());
			data.put("changed", false);
			data.put("deletedDirectory", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("移除llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_LLAMACPP_REMOVE_FAILED + ": " + e.getMessage()));
		}
	}

	private void deleteDirectoryRecursively(Path dir) throws java.io.IOException {
		Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, java.io.IOException exc) throws java.io.IOException {
				if (exc != null) throw exc;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
	
	private Path validateAndNormalizeLlamaBinDirectory(String input) {
		if (input == null) return null;
		String s = input.trim();
		if (s.isEmpty()) return null;
		Path p;
		try {
			p = Paths.get(s).toAbsolutePath().normalize();
		} catch (Exception e) {
			return null;
		}
		try {
			if (!Files.exists(p) || !Files.isDirectory(p)) return null;
		} catch (Exception e) {
			return null;
		}
		if (this.pathHasSymlink(p)) return null;
		Path real;
		try {
			real = p.toRealPath();
		} catch (Exception e) {
			real = p;
		}
		
		File dir = real.toFile();
		File serverLinux = new File(dir, "llama-server");
		File serverWin = new File(dir, "llama-server.exe");
		if (!(serverLinux.exists() && serverLinux.isFile()) && !(serverWin.exists() && serverWin.isFile())) {
			return null;
		}
		return real;
	}
	
	private boolean pathHasSymlink(Path p) {
		if (p == null) return false;
		try {
			Path abs = p.toAbsolutePath().normalize();
			Path root = abs.getRoot();
			if (root == null) {
				return Files.isSymbolicLink(abs);
			}
			Path cur = root;
			for (Path part : abs) {
				if (part == null) continue;
				cur = cur.resolve(part);
				try {
					if (Files.isSymbolicLink(cur)) return true;
				} catch (Exception ignore) {
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * 返回全部的llamacpp目录
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.handleLlamaCppListRemote(ctx, nodeId);
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> configuredItems = cfg.getItems();
			List<LlamaCppDataStruct> scannedItems = LlamaServer.scanLlamaCpp();

			String llamacppRoot = LlamaServer.getDefaultLlamaCppPath();
			Path rootPath = null;
			try {
				rootPath = Paths.get(llamacppRoot).toAbsolutePath().normalize();
			} catch (Exception ignore) {}

			List<Map<String, Object>> resultItems = new ArrayList<>();
			if (configuredItems != null) {
				for (LlamaCppDataStruct item : configuredItems) {
					Map<String, Object> entry = this.structToMap(item);
					entry.put("source", "configured");
					resultItems.add(entry);
				}
			}
			if (scannedItems != null && rootPath != null) {
				for (LlamaCppDataStruct item : scannedItems) {
					String itemPath = item.getPath();
					if (itemPath == null) continue;
					Path itemDir;
					try {
						itemDir = Paths.get(itemPath.trim()).toAbsolutePath().normalize();
					} catch (Exception ignore) {
						continue;
					}
					if (!itemDir.startsWith(rootPath)) continue;
					boolean alreadyConfigured = false;
					if (configuredItems != null) {
						for (LlamaCppDataStruct ci : configuredItems) {
							String cp = ci.getPath();
							if (cp != null) {
								try {
									Path cpDir = Paths.get(cp.trim()).toAbsolutePath().normalize();
									if (cpDir.equals(itemDir)) {
										alreadyConfigured = true;
										break;
									}
								} catch (Exception ignore) {}
							}
						}
					}
					if (alreadyConfigured) continue;
					Map<String, Object> entry = this.structToMap(item);
					entry.put("source", "scanned");
					resultItems.add(entry);
				}
			}

			Map<String, Object> data = new HashMap<>();
			data.put("items", resultItems);
			data.put("count", resultItems.size());
			data.put("llamacppRoot", llamacppRoot);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取llama.cpp路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_LLAMACPP_LIST_FAILED + ": " + e.getMessage()));
		}
	}

	private Map<String, Object> structToMap(LlamaCppDataStruct item) {
		Map<String, Object> map = new HashMap<>();
		map.put("path", item.getPath());
		map.put("name", item.getName());
		map.put("description", item.getDescription());
		return map;
	}

	private void handleLlamaCppListRemote(ChannelHandlerContext ctx, String nodeId) {
		try {
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
					nodeId, "GET", "api/llamacpp/list", null);
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[llamacpp远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("获取远程节点llama.cpp列表失败: nodeId={}, error={}", nodeId, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_LLAMACPP_LIST_FAILED + ": " + e.getMessage()));
		}
	}

	private void proxyPostRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		this.proxyPostRemote(ctx, request, nodeId, path, 0, 0);
	}

	private void proxyPostRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path, int connectTimeout, int readTimeout) {
		try {
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			JsonObject body = bodyBytes != null ? JsonUtil.fromJson(bodyBytes, JsonObject.class) : null;
			if (body != null) {
				body.remove("nodeId");
				if (body.size() == 0) body = null;
			}
			NodeManager.HttpResult result;
			if (connectTimeout > 0 && readTimeout > 0) {
				result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", path, body, connectTimeout, readTimeout);
			} else {
				result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", path, body);
			}
			if (result.isSuccess()) {
				NodeManager.writeHttpResultToChannel(ctx, result, "[llamacpp远程]");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": code=" + result.getStatusCode()));
			}
		} catch (Exception e) {
			logger.warn("远程节点调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_REMOTE_CALL_FAILED + ": " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppTest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyPostRemote(ctx, request, nodeId, "api/llamacpp/test", 5000, 15000);
				return;
			}
			byte[] bodyBytes = JsonUtil.readRequestBytes(request);
			if (bodyBytes == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_BODY_EMPTY));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(bodyBytes, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_PARAM_PATH_EMPTY));
				return;
			}

			String llamaBinPath = reqData.getPath().trim();
			String exeName = "llama-cli";
			File exeFile = new File(llamaBinPath, exeName);
			if (!exeFile.exists() || !exeFile.isFile()) {
				String osName = System.getProperty("os.name");
				String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
				if (os.contains("win")) {
					File exeFileWin = new File(llamaBinPath, exeName + ".exe");
					if (exeFileWin.exists() && exeFileWin.isFile()) {
						exeFile = exeFileWin;
					}
				}
			}
			if (!exeFile.exists() || !exeFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_LLAMA_CLI_NOT_FOUND + ": " + exeFile.getAbsolutePath()));
				return;
			}

			String cmdVersion = ParamTool.quoteIfNeeded(exeFile.getAbsolutePath()) + " --version";
			CommandLineRunner.CommandResult versionResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--version" }, 30);

			String cmdListDevices = ParamTool.quoteIfNeeded(exeFile.getAbsolutePath()) + " --list-devices";
			CommandLineRunner.CommandResult listDevicesResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--list-devices" }, 30);

			Map<String, Object> data = new HashMap<>();

			Map<String, Object> version = new HashMap<>();
			version.put("command", cmdVersion);
			version.put("exitCode", versionResult.getExitCode());
			version.put("output", versionResult.getOutput());
			version.put("error", versionResult.getError());

			Map<String, Object> devices = new HashMap<>();
			devices.put("command", cmdListDevices);
			devices.put("exitCode", listDevicesResult.getExitCode());
			devices.put("output", listDevicesResult.getOutput());
			devices.put("error", listDevicesResult.getError());

			data.put("version", version);
			data.put("listDevices", devices);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("执行llama.cpp测试命令时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(I18N_LLAMACPP_TEST_FAILED + ": " + e.getMessage()));
		}
	}
	
	private boolean isRelevantLlamaCppAsset(String name) {
		if (name == null || name.isEmpty()) return false;
		String lower = name.toLowerCase(Locale.ROOT);
		// 只要压缩包
		if (!lower.endsWith(".zip") && !lower.endsWith(".tar.gz") && !lower.endsWith(".tgz")) {
			return false;
		}
		// 只看 x64：排除 arm64 / aarch64 构建
		if (lower.contains("arm64") || lower.contains("aarch64")) {
			return false;
		}
		if (!lower.contains("x64")) {
			return false;
		}
		// 平台：Windows / Linux 构建（上游命名历代有 bin-win-x64、bin-win-cpu-x64、
		// bin-win-cuda-13.4-x64、bin-ubuntu-cuda-12.8-x64 等多种形态，一律放行）
		return lower.contains("-win-") || lower.contains("win-")
				|| lower.contains("-ubuntu-") || lower.contains("-linux-");
	}

	private static final java.util.regex.Pattern LLAMA_CPP_BUILD_TAG =
			java.util.regex.Pattern.compile("^b\\d+$");

	/**
	 * 读取 query 参数里的加速地址（proxy），空值返回 null。
	 */
	private String resolveProxyParam(FullHttpRequest request) {
		Map<String, String> params = ParamTool.getQueryParam(request.uri());
		String proxy = params.get("proxy");
		if (proxy == null || proxy.trim().isEmpty()) {
			return null;
		}
		return proxy.trim();
	}

	/**
	 * 版本号排序用的 key（b11979 -&gt; 11979），非 b 版本排在最后。
	 */
	private long buildTagOrder(String tag) {
		if (tag == null) return -1L;
		String t = tag.trim().toLowerCase(Locale.ROOT);
		if (!LLAMA_CPP_BUILD_TAG.matcher(t).matches()) return -1L;
		try {
			return Long.parseLong(t.substring(1));
		} catch (Exception e) {
			return -1L;
		}
	}

	/**
	 * 把一个 GitHub release 对象转换成前端使用的结构。
	 */
	private JsonObject mapReleaseForUi(JsonObject src, String proxy) {
		JsonObject data = new JsonObject();
		if (src.has("tag_name") && !src.get("tag_name").isJsonNull()) {
			data.addProperty("tag_name", src.get("tag_name").getAsString());
		}
		if (src.has("name") && !src.get("name").isJsonNull()) {
			data.addProperty("name", src.get("name").getAsString());
		}
		if (src.has("published_at") && !src.get("published_at").isJsonNull()) {
			data.addProperty("published_at", src.get("published_at").getAsString());
		}
		if (src.has("created_at") && !src.get("created_at").isJsonNull()) {
			data.addProperty("created_at", src.get("created_at").getAsString());
		}
		if (src.has("html_url") && !src.get("html_url").isJsonNull()) {
			data.addProperty("html_url", src.get("html_url").getAsString());
		}
		if (src.has("prerelease") && !src.get("prerelease").isJsonNull()) {
			data.addProperty("prerelease", src.get("prerelease").getAsBoolean());
		}
		long order = buildTagOrder(data.has("tag_name") ? data.get("tag_name").getAsString() : null);
		data.addProperty("buildNumber", order);

		JsonArray assets = new JsonArray();
		if (src.has("assets") && src.get("assets").isJsonArray()) {
			JsonArray srcAssets = src.getAsJsonArray("assets");
			for (int i = 0; i < srcAssets.size(); i++) {
				com.google.gson.JsonObject srcAsset = srcAssets.get(i).getAsJsonObject();
				String assetName = "";
				if (srcAsset.has("name") && !srcAsset.get("name").isJsonNull()) {
					assetName = srcAsset.get("name").getAsString();
				}
				if (!isRelevantLlamaCppAsset(assetName)) {
					continue;
				}
				JsonObject asset = new JsonObject();
				asset.addProperty("name", assetName);
				if (srcAsset.has("size") && !srcAsset.get("size").isJsonNull()) {
					asset.addProperty("size", srcAsset.get("size").getAsLong());
				}
				if (srcAsset.has("download_count") && !srcAsset.get("download_count").isJsonNull()) {
					asset.addProperty("download_count", srcAsset.get("download_count").getAsInt());
				}
				String bdu = "";
				if (srcAsset.has("browser_download_url") && !srcAsset.get("browser_download_url").isJsonNull()) {
					bdu = srcAsset.get("browser_download_url").getAsString();
				}
				if (proxy != null && !proxy.isEmpty() && !bdu.isEmpty()) {
					String p = proxy;
					if (!p.endsWith("/")) p += "/";
					bdu = p + bdu.replaceFirst("^https?://", "");
				}
				asset.addProperty("browser_download_url", bdu);
				assets.add(asset);
			}
		}
		data.add("assets", assets);
		return data;
	}

	/**
	 * 从上游拉取最近若干条 release，只保留 llama.cpp 的构建版本（tag 形如 b11979）。
	 *
	 * <p>上游把 b* 构建全部标成 prerelease，并在同一仓库里夹着 v0.x.y 这类空壳 release
	 * （只挂一个 nightly-tag.txt），因此不能再用 /releases/latest，必须翻列表再筛。</p>
	 *
	 * @param limit 需要返回的版本数
	 * @param proxy 下载加速地址，可为 null
	 */
	private List<JsonObject> fetchLlamaCppBuildReleases(int limit, String proxy) throws IOException {
		int want = Math.max(1, Math.min(limit, 30));
		// 列表里混着 v* 空壳版本，多取一些再筛，保证够数
		int perPage = Math.min(100, Math.max(want * 3, 30));
		String apiUrl = "https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=" + perPage;

		NettyHttpUtils.Response resp = NettyHttpUtils.request(apiUrl)
				.header("User-Agent", UserAgentUtils.random())
				.header("Accept", "application/vnd.github.v3+json")
				.connectTimeout(15)
				.readTimeout(30)
				.execute();

		String responseBody = resp.bodyAsString();
		if (!resp.isSuccess() || responseBody == null || responseBody.isBlank()) {
			String msg = (responseBody == null || responseBody.isBlank())
					? ("GitHub API 错误: HTTP " + resp.statusCode()) : responseBody;
			throw new IOException(msg);
		}

		JsonArray root = JsonUtil.fromJson(responseBody, JsonArray.class);
		if (root == null) {
			throw new IOException(I18N_GITHUB_API_PARSE_FAILED);
		}

		List<JsonObject> builds = new ArrayList<>();
		for (int i = 0; i < root.size(); i++) {
			if (!root.get(i).isJsonObject()) continue;
			JsonObject release = root.get(i).getAsJsonObject();
			if (release.has("draft") && !release.get("draft").isJsonNull() && release.get("draft").getAsBoolean()) {
				continue;
			}
			String tag = release.has("tag_name") && !release.get("tag_name").isJsonNull()
					? release.get("tag_name").getAsString() : null;
			long order = buildTagOrder(tag);
			if (order < 0) {
				continue; // 跳过 v0.x.y 这类空壳 release
			}
			if (!release.has("assets") || !release.get("assets").isJsonArray()
					|| release.getAsJsonArray("assets").size() == 0) {
				continue; // 没挂任何附件的版本对下载页没有意义
			}
			builds.add(mapReleaseForUi(release, proxy));
		}

		// 按构建号倒序（上游列表本身已倒序，这里再保证一次）
		builds.sort((a, b) -> Long.compare(
				b.has("buildNumber") ? b.get("buildNumber").getAsLong() : -1L,
				a.has("buildNumber") ? a.get("buildNumber").getAsLong() : -1L));

		if (builds.size() > want) {
			return new ArrayList<>(builds.subList(0, want));
		}
		return builds;
	}

	/**
	 * 已安装的本地版本目录名 + 已安装的 cudart 目录名，前端用来标记「已安装」。
	 */
	private void attachLocalState(JsonObject data) {
		JsonArray localBackendsArr = new JsonArray();
		List<LlamaCppDataStruct> scanned = LlamaServer.scanLlamaCpp();
		if (scanned != null) {
			for (LlamaCppDataStruct s : scanned) {
				if (s != null && s.getName() != null && !s.getName().isBlank()) {
					localBackendsArr.add(s.getName());
				}
			}
		}
		data.add("localBackends", localBackendsArr);

		JsonArray localCudartsArr = new JsonArray();
		List<String> cudarts = LlamaServerManager.getInstance().scanCudartPackages();
		if (cudarts != null) {
			for (String c : cudarts) {
				if (c != null && !c.isBlank()) {
					localCudartsArr.add(c);
				}
			}
		}
		data.add("localCudarts", localCudartsArr);
	}

	/**
	 * 获取最近 N 个 llama.cpp 构建版本（默认 10 个），用于下载页的版本选择。
	 */
	private void handleLlamaCppReleases(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			String proxy = this.resolveProxyParam(request);
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			int limit = 10;
			String limitRaw = params.get("limit");
			if (limitRaw != null && !limitRaw.trim().isEmpty()) {
				try {
					limit = Integer.parseInt(limitRaw.trim());
				} catch (NumberFormatException ignore) {
					limit = 10;
				}
			}

			List<JsonObject> releases = this.fetchLlamaCppBuildReleases(limit, proxy);

			JsonArray arr = new JsonArray();
			for (JsonObject r : releases) {
				arr.add(r);
			}
			JsonObject data = new JsonObject();
			data.add("releases", arr);
			if (!releases.isEmpty()) {
				data.add("latest", releases.get(0));
			}
			this.attachLocalState(data);

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("data", data);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (IOException e) {
			logger.info("获取 llama.cpp 版本列表失败", e);
			Map<String, Object> err = new HashMap<>();
			err.put("success", false);
			err.put("error", I18N_RELEASE_INFO_FETCH_FAILED + ": " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, err);
		}
	}

	/**
	 * 获取最新一个 llama.cpp 构建版本（保留旧接口，内部改为「列表 + 取第一个」）。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppReleaseLatest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, I18N_METHOD_GET_ONLY);
		try {
			String proxy = this.resolveProxyParam(request);
			List<JsonObject> releases = this.fetchLlamaCppBuildReleases(1, proxy);
			if (releases.isEmpty()) {
				Map<String, Object> err = new HashMap<>();
				err.put("success", false);
				err.put("error", I18N_RELEASE_INFO_FETCH_FAILED + ": 未找到任何 llama.cpp 构建版本");
				LlamaServer.sendJsonResponse(ctx, err);
				return;
			}
			JsonObject data = releases.get(0);
			this.attachLocalState(data);

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("data", data);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (IOException e) {
			logger.info("获取 llama.cpp release 信息失败", e);
			Map<String, Object> err = new HashMap<>();
			err.put("success", false);
			err.put("error", I18N_RELEASE_INFO_FETCH_FAILED + ": " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, err);
		}
	}


	private void handleTokenizeRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		byte[] bodyBytes = JsonUtil.readRequestBytes(request);
		if (bodyBytes == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_EMPTY);
			return;
		}

		JsonObject obj = JsonUtil.fromJson(bodyBytes, JsonObject.class);
		if (obj == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_PARSE);
			return;
		}

		LlamaServerManager manager = LlamaServerManager.getInstance();
		Map<String, String> params = ParamTool.getQueryParam(request.uri());
		String modelId = JsonUtil.getJsonString(obj, "modelId", null);
		if (modelId == null || modelId.trim().isEmpty()) {
			modelId = params.get("modelId");
		}
		if (modelId != null) {
			modelId = modelId.trim();
			if (modelId.isEmpty()) {
				modelId = null;
			}
		}
		if (modelId == null) {
			modelId = manager.getFirstModelName();
		}

		String nodeId = request.headers().get(HEADER_NODE_ID);
		if (nodeId != null && nodeId.trim().isEmpty()) {
			nodeId = null;
		}
		if (nodeId != null && !nodeId.equals("local")) {
			JsonObject forward = obj.deepCopy();
			forward.addProperty("modelId", modelId);
			proxyRemoteNode(ctx, forward, nodeId, "tokenize");
			return;
		}

		if (!manager.getLoadedProcesses().containsKey(modelId)) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_MODEL_NOT_LOADED + ": " + modelId);
			return;
		}
		if (modelId == null || modelId.trim().isEmpty()) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, I18N_MODEL_NOT_LOADED);
			return;
		}
		Integer port = manager.getModelPort(modelId);
		if (port == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_MODEL_PORT_NOT_FOUND + ": " + modelId);
			return;
		}

		String targetUrl = String.format("http://localhost:%d/tokenize", port.intValue());
		worker.execute(() -> proxyRawBytes(ctx, targetUrl, bodyBytes));
	}
	
	/**
	 * 	模板化接口，纯代理转发到llamacpp子进程。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleApplyTemplateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);

		byte[] bodyBytes = JsonUtil.readRequestBytes(request);
		if (bodyBytes == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_EMPTY);
			return;
		}

		JsonObject obj = JsonUtil.fromJson(bodyBytes, JsonObject.class);
		if (obj == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_PARSE);
			return;
		}

		Map<String, String> params = ParamTool.getQueryParam(request.uri());
		String modelId = JsonUtil.getJsonString(obj, "modelId", null);
		if (modelId == null || modelId.trim().isEmpty()) {
			modelId = params.get("modelId");
		}
		if (modelId == null || modelId.trim().isEmpty()) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_MODELID_REQUIRED);
			return;
		}
		modelId = modelId.trim();

		String nodeId = request.headers().get(HEADER_NODE_ID);
		if (nodeId != null && nodeId.trim().isEmpty()) {
			nodeId = null;
		}
		if (nodeId != null && !nodeId.equals("local")) {
			JsonObject forward = obj.deepCopy();
			forward.addProperty("modelId", modelId);
			proxyRemoteNode(ctx, forward, nodeId, "apply-template");
			return;
		}

		if (!obj.has("messages") || obj.get("messages") == null || obj.get("messages").isJsonNull() || !obj.get("messages").isJsonArray()) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_PARAM_MESSAGES_MISSING);
			return;
		}

		LlamaServerManager manager = LlamaServerManager.getInstance();
		if (!manager.getLoadedProcesses().containsKey(modelId)) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_MODEL_NOT_LOADED + ": " + modelId);
			return;
		}
		Integer port = manager.getModelPort(modelId);
		if (port == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_MODEL_PORT_NOT_FOUND + ": " + modelId);
			return;
		}

		String targetUrl = String.format("http://localhost:%d/apply-template", port.intValue());
		worker.execute(() -> proxyRawBytes(ctx, targetUrl, bodyBytes));
	}
	
	private void handleInfillRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.POST, I18N_METHOD_POST_ONLY);
		HttpURLConnection connection = null;
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, I18N_BODY_EMPTY);
				return;
			}
			
			JsonObject obj = null;
			try {
				obj = JsonUtil.fromJson(content, JsonObject.class);
			} catch (Exception ignore) {
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			if ((modelId == null || modelId.trim().isEmpty()) && obj != null) {
				modelId = JsonUtil.getJsonString(obj, "modelId", null);
				if (modelId == null || modelId.trim().isEmpty()) {
					modelId = JsonUtil.getJsonString(obj, "model", null);
				}
			}
			if (modelId != null) {
				modelId = modelId.trim();
				if (modelId.isEmpty()) {
					modelId = null;
				}
			}
			if (modelId == null) {
				modelId = manager.getFirstModelName();
			} else if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, I18N_MODEL_NOT_LOADED + ": " + modelId);
				return;
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, I18N_MODEL_NOT_LOADED);
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_MODEL_PORT_NOT_FOUND + ": " + modelId);
				return;
			}
			
			String targetUrl = String.format("http://localhost:%d/infill", port.intValue());
			connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			// 把客户端的请求头全发过去
			for (Map.Entry<String, String> entry : request.headers()) {
				String key = entry.getKey();
				if (key == null) {
					continue;
				}
			if ("Host".equalsIgnoreCase(key)
					|| "Connection".equalsIgnoreCase(key)
					|| "Content-Length".equalsIgnoreCase(key)
					|| "Transfer-Encoding".equalsIgnoreCase(key)
					|| "X-Node-Id".equalsIgnoreCase(key)) {
				continue;
			}
				connection.setRequestProperty(key, entry.getValue());
			}
			
			byte[] outBytes = content.getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody = this.readBody(connection, responseCode >= 200 && responseCode < 300);
			JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, JsonElement.class);
			} catch (Exception ignore) {
			}

			if (responseCode >= 200 && responseCode < 300) {
				if (parsed != null) {
					LlamaServer.sendJsonResponse(ctx, parsed);
				} else {
					LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, I18N_MODEL_RESPONSE_NOT_JSON);
				}
				return;
			}

			if (parsed != null) {
				LlamaServer.sendJsonResponse(ctx, parsed);
				return;
			}
			String msg = responseBody == null || responseBody.isBlank() ? (I18N_MODEL_ERROR_HTTP + ": " + responseCode) : responseBody;
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, msg);
		} catch (Exception e) {
			logger.info("infill代理失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, I18N_INFILL_FAILED + ": " + e.getMessage());
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}
	

	private String readBody(HttpURLConnection connection, boolean ok) {
		if (connection == null) return "";
		InputStream in = null;
		try {
			in = ok ? connection.getInputStream() : connection.getErrorStream();
			if (in == null) return "";
			try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = br.readLine()) != null) {
					sb.append(line);
				}
				return sb.toString();
			}
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * 纯字节代理：流式转发，响应分块写回客户端，内存中仅保留单块缓冲区。
	 */
	private void proxyRawBytes(ChannelHandlerContext ctx, String targetUrl, byte[] bodyBytes) {
		HttpURLConnection connection = null;
		boolean responseStarted = false;
		try {
			connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

			try (OutputStream os = connection.getOutputStream()) {
				os.write(bodyBytes);
			}

			int responseCode = connection.getResponseCode();
			boolean success = responseCode >= 200 && responseCode < 300;
			InputStream stream = success ? connection.getInputStream() : connection.getErrorStream();

			HttpResponse header = new DefaultHttpResponse(
				HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
			String contentType = connection.getContentType();
			if (contentType == null || contentType.isBlank()) {
				contentType = "application/json; charset=UTF-8";
			}
			header.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
			header.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
			header.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
			header.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			header.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
			if (!NettyWriteHelper.writeAndFlushBlocking(ctx, header, logger, "[proxyRawBytes]")) {
				return;
			}
			responseStarted = true;

			if (stream != null) {
				try (InputStream responseStream = stream) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = responseStream.read(buffer)) != -1) {
					if (!ctx.channel().isActive()) {
						logger.info("[proxyRawBytes] 客户端连接已断开，中止响应转发: {}", targetUrl);
						break;
					}
					io.netty.buffer.ByteBuf content = ctx.alloc().buffer(read);
					content.writeBytes(buffer, 0, read);
					if (!NettyWriteHelper.writeAndFlushBlocking(
							ctx,
							new DefaultHttpContent(content),
							logger,
							"[proxyRawBytes]")) {
						return;
					}
				}
				}
			}

			if (ctx.channel().isActive()
					&& NettyWriteHelper.writeAndFlushBlocking(ctx, LastHttpContent.EMPTY_LAST_CONTENT, logger, "[proxyRawBytes]")) {
				ctx.close();
			}
		} catch (Exception e) {
			logger.info("proxyRawBytes失败: {}", targetUrl, e);
			if (!responseStarted && ctx.channel().isActive()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, I18N_PROXY_FORWARD_FAILED + ": " + e.getMessage());
			} else if (ctx.channel().isActive()) {
				ctx.close();
			}
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * 远程节点代理转发。
	 */
	private void proxyRemoteNode(ChannelHandlerContext ctx, JsonObject body, String nodeId, String path) {
		NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "POST", path, body);
		if (result.isSuccess()) {
			byte[] bytes = result.getBody().getBytes(StandardCharsets.UTF_8);
			writeRawResponse(ctx, 200, bytes);
		} else {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, I18N_REMOTE_NODE_ACTION_FAILED + " (" + path + "): " + result.getBody());
		}
	}

	/**
	 * 将原始字节写入Netty响应并返回给客户端。
	 */
	private void writeRawResponse(ChannelHandlerContext ctx, int responseCode, byte[] bodyBytes) {
		byte[] bytes = bodyBytes == null ? new byte[0] : bodyBytes;
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		response.content().writeBytes(bytes);
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
	}
}
