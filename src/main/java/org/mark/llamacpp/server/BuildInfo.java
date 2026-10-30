package org.mark.llamacpp.server;

public final class BuildInfo {

	private static final String TAG = "{tag}";

	private static final String VERSION = "{version}";

	private static final String CREATED_TIME = "{createdTime}";

	/** 占位符未被替换（本地 javac 直接编译）时使用的默认值：本维护版从 0.0.1 起。 */
	private static final String DEFAULT_TAG = "v0.0.1";

	private static final String DEFAULT_VERSION = "0.0.1";

	private static final String DEFAULT_CREATED_TIME = "2026-10-30T11:00:00Z";

	private BuildInfo() {
	}

	public static String getTag() {
		return isPlaceholder(TAG) ? DEFAULT_TAG : TAG;
	}

	public static String getVersion() {
		return isPlaceholder(VERSION) ? DEFAULT_VERSION : VERSION;
	}

	public static String getCreatedTime() {
		return isPlaceholder(CREATED_TIME) ? DEFAULT_CREATED_TIME : CREATED_TIME;
	}

	private static boolean isPlaceholder(String value) {
		return value == null || value.isEmpty() || value.startsWith("{");
	}
}
