package com.github.hbq969.ai.zephyr.chat.ctrl;

import com.github.hbq969.ai.zephyr.chat.model.ChatEvent;
import com.github.hbq969.code.common.restful.ReturnMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Tag(name = "聊天接口")
@RestController
@RequestMapping(path = "/chat")
public class ChatCtrl {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Operation(summary = "发送消息（SSE 流式）")
    @RequestMapping(path = "/send", method = RequestMethod.POST)
    @ResponseBody
    public SseEmitter sendMessage(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        SseEmitter emitter = new SseEmitter(300000L);

        executor.execute(() -> {
            try {
                String[] chunks = {
                    "好的，", "让我来", "帮你分析", "这个问题。\n\n",
                    "根据", "你提供", "的信息，", "以下是", "我的分析", "结果。"
                };
                for (String chunk : chunks) {
                    ChatEvent event = ChatEvent.builder()
                        .type("token")
                        .content(chunk)
                        .build();
                    emitter.send(SseEmitter.event()
                        .name("message")
                        .data(event));
                    Thread.sleep(80);
                }
                ChatEvent doneEvent = ChatEvent.builder()
                    .type("done")
                    .usage(Map.of("inputTokens", 4200, "outputTokens", 180))
                    .build();
                emitter.send(SseEmitter.event().name("message").data(doneEvent));
                emitter.complete();
            } catch (IOException | InterruptedException e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> log.error("SSE error", throwable));
        return emitter;
    }

    @Operation(summary = "取消当前对话")
    @RequestMapping(path = "/cancel", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> cancel() {
        return ReturnMessage.success("ok");
    }
}
