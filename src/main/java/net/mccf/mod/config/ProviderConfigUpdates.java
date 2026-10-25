package net.mccf.mod.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只管校验和暂存配置补丁，不管权限、磁盘或网络（由 ConfigSyncHandler 处理）。
 * 先完整解析再替换运行配置，避免后半段字段非法时前半段已修改并被旧 Provider 引用。
 */
public final class ProviderConfigUpdates {
    private ProviderConfigUpdates() {}

    public record Update(String activeProvider, Map<String, ProviderConfig> providers) {}

    public static Update parse(String activeProvider, Map<String, ProviderConfig> current, String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Config update must be an object.");
        JsonObject root = parsed.getAsJsonObject();
        String requested = string(root, "activeProvider", activeProvider);
        requireProvider(requested);
        Map<String, ProviderConfig> updated = new LinkedHashMap<>();
        current.forEach((id, config) -> updated.put(id, copy(config)));
        if (root.has("providers")) {
            if (!root.get("providers").isJsonObject()) {
                throw new IllegalArgumentException("providers must be an object.");
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("providers").entrySet()) {
                String id = entry.getKey();
                requireProvider(id);
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException("Provider settings must be an object.");
                }
                JsonObject patch = entry.getValue().getAsJsonObject();
                ProviderConfig config = updated.computeIfAbsent(id, ignored -> new ProviderConfig());
                String key = string(patch, "apiKey", "");
                // 空 Key 保留旧客户端的“不修改”语义；删除必须有明确标记，避免误删已有凭据。
                if (bool(patch, "clearApiKey", false)) config.apiKey = "";
                else if (!key.isBlank()) config.apiKey = key.trim();
                config.model = string(patch, "model", config.model);
                config.disableThinking = bool(patch, "disableThinking", config.disableThinking);
                if (bool(patch, "resetEndpoint", false)) config.endpoint = "";
                else config.endpoint = string(patch, "endpoint", config.endpoint).trim();
                validateEndpoint(config.endpoint);
            }
        }
        return new Update(requested, updated);
    }

    public static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return;
        try {
            URI uri = URI.create(endpoint);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null) return;
        } catch (IllegalArgumentException ignored) {
            // 只返回固定错误，URI 解析异常可能带有 URL 内的访问令牌，不能直接回传或写日志。
        }
        throw new IllegalArgumentException("Endpoint must be an HTTP(S) URL with a host and no user info or fragment.");
    }

    private static void requireProvider(String id) {
        if (id == null || !ProviderDefaults.all().containsKey(id)) {
            throw new IllegalArgumentException("Unknown translation provider.");
        }
    }

    private static ProviderConfig copy(ProviderConfig original) {
        if (original == null) return new ProviderConfig();
        ProviderConfig result = new ProviderConfig(empty(original.apiKey), empty(original.model), empty(original.endpoint));
        result.disableThinking = original.disableThinking;
        return result;
    }

    private static String empty(String value) { return value == null ? "" : value; }

    private static String string(JsonObject root, String name, String fallback) {
        if (!root.has(name)) return empty(fallback);
        JsonElement value = root.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Config string field has an invalid type.");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject root, String name, boolean fallback) {
        if (!root.has(name)) return fallback;
        JsonElement value = root.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Config boolean field has an invalid type.");
        }
        return value.getAsBoolean();
    }
}
