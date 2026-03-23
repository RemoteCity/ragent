/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * OpenAI 协议风格 SSE 解析器
 * 支持从 delta/message 中提取 content，以及可选的 reasoning_content
 */
final class OpenAIStyleSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    private OpenAIStyleSseParser() {
    }

    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ParsedEvent.empty();
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        String content = extractText(choice0, "content");
        String reasoning = reasoningEnabled ? extractReasoning(choice0) : null;
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed);
    }

    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    private static String extractText(JsonObject choice, String fieldName) {
        if (choice == null) {
            return null;
        }
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    /**
     * 提取推理/思考内容。
     * 支持两种格式：
     * 1. reasoning_content（旧版，直接字符串字段）
     * 2. reasoning_details（MiniMax reasoning_split 模式，数组格式：[{"text": "..."}]）
     */
    public static String extractReasoning(JsonObject choice) {
        if (choice == null) {
            return null;
        }

        // 先尝试 reasoning_content（旧版字段）
        String reasoning = extractText(choice, "reasoning_content");
        if (reasoning != null && !reasoning.isEmpty()) {
            return reasoning;
        }

        // 再尝试 reasoning_details 数组（MiniMax reasoning_split 模式）
        JsonArray details = extractReasoningDetails(choice);
        if (details != null && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : details) {
                if (el.isJsonObject() && el.getAsJsonObject().has("text")) {
                    String text = el.getAsJsonObject().get("text").getAsString();
                    if (text != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text);
                    }
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }

        return null;
    }

    private static JsonArray extractReasoningDetails(JsonObject choice) {
        JsonObject container = null;
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            container = choice.getAsJsonObject("delta");
        } else if (choice.has("message") && choice.get("message").isJsonObject()) {
            container = choice.getAsJsonObject("message");
        }
        if (container != null && container.has("reasoning_details")) {
            JsonElement el = container.get("reasoning_details");
            if (el.isJsonArray()) {
                return el.getAsJsonArray();
            }
        }
        return null;
    }

    record ParsedEvent(String content, String reasoning, boolean completed) {

        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false);
        }

        static ParsedEvent done() {
            return new ParsedEvent(null, null, true);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}
