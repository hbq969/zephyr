package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON Todo 预处理器：将每个 task 展平为自包含文本块，
 * 确保 title/status/tags 等字段始终在一起，不被 TextSplitter 拆分。
 * <p>
 * 仅处理包含 "tasks" 数组且数组元素含 "status" 字段的 JSON，
 * 其他格式原样返回。
 */
@Slf4j
@Component
public class JsonTaskPreprocessor {

    private static final Gson gson = new Gson();

    public String preprocess(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) return raw;

        try {
            JsonObject root = JsonParser.parseString(trimmed).getAsJsonObject();
            if (!root.has("tasks")) return raw;

            JsonArray tasks = root.getAsJsonArray("tasks");
            if (tasks.isEmpty()) return raw;

            JsonElement first = tasks.get(0);
            if (!first.isJsonObject() || !first.getAsJsonObject().has("status")) {
                return raw;
            }

            log.info("检测到 JSON todo 格式，共 {} 个任务，开始展平", tasks.size());
            return flattenTasks(tasks);
        } catch (Exception e) {
            log.debug("JSON 解析失败，按原文处理: {}", e.getMessage());
            return raw;
        }
    }

    private String flattenTasks(JsonArray tasks) {
        List<String> blocks = new ArrayList<>();
        for (JsonElement el : tasks) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            blocks.add(flattenOne(t));
        }
        return String.join("\n\n", blocks);
    }

    private String flattenOne(JsonObject t) {
        StringBuilder sb = new StringBuilder();
        sb.append("[task]\n");
        appendField(sb, "title", t);
        appendField(sb, "status", t);
        appendField(sb, "priority", t);
        appendField(sb, "dueDate", t);
        appendField(sb, "progress", t);
        appendField(sb, "tags", t);
        appendField(sb, "notes", t);
        appendField(sb, "description", t);
        sb.append("[/task]");
        return sb.toString();
    }

    private void appendField(StringBuilder sb, String key, JsonObject obj) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            sb.append(key).append(":\n");
            return;
        }
        JsonElement val = obj.get(key);
        if (val.isJsonArray()) {
            List<String> items = new ArrayList<>();
            for (JsonElement item : val.getAsJsonArray()) {
                items.add(item.getAsString());
            }
            sb.append(key).append(": ").append(String.join(", ", items)).append("\n");
        } else if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isNumber()) {
            double d = val.getAsDouble();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                sb.append(key).append(": ").append((long) d).append("\n");
            } else {
                sb.append(key).append(": ").append(d).append("\n");
            }
        } else {
            String str = val.getAsString();
            if (str == null || str.isEmpty()) {
                sb.append(key).append(":\n");
            } else {
                sb.append(key).append(": ").append(str.replace("\n", "\n  ")).append("\n");
            }
        }
    }
}
