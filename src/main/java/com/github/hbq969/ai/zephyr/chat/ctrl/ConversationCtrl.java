package com.github.hbq969.ai.zephyr.chat.ctrl;

import com.github.hbq969.ai.zephyr.chat.model.ConversationVO;
import com.github.hbq969.code.common.restful.ReturnMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "会话接口")
@RestController
@RequestMapping(path = "/conversations")
public class ConversationCtrl {

    private static final List<ConversationVO> MOCK = new ArrayList<>();

    static {
        long now = System.currentTimeMillis() / 1000;
        MOCK.add(ConversationVO.builder().id("c1").title("Spring Boot + MyBatis 配置方案").updatedAt(now - 3600).createdAt(now - 7200).messageCount(12).build());
        MOCK.add(ConversationVO.builder().id("c2").title("SSE 流式响应调试记录").updatedAt(now - 7200).createdAt(now - 14400).messageCount(8).build());
        MOCK.add(ConversationVO.builder().id("c3").title("Vue3 组件通信方式对比").updatedAt(now - 172800).createdAt(now - 259200).messageCount(24).build());
        MOCK.add(ConversationVO.builder().id("c4").title("数据库索引优化方案").updatedAt(now - 345600).createdAt(now - 432000).messageCount(15).build());
        MOCK.add(ConversationVO.builder().id("c5").title("MyBatis 多方言配置实践").updatedAt(now - 432000).createdAt(now - 518400).messageCount(10).build());
        MOCK.add(ConversationVO.builder().id("c6").title("MCP 协议接入调研").updatedAt(now - 864000).createdAt(now - 1209600).messageCount(6).build());
        MOCK.add(ConversationVO.builder().id("c7").title("Skill 插件机制设计").updatedAt(now - 1209600).createdAt(now - 1555200).messageCount(18).build());
        MOCK.add(ConversationVO.builder().id("c8").title("上下文压缩策略讨论").updatedAt(now - 2419200).createdAt(now - 2764800).messageCount(9).build());
        MOCK.add(ConversationVO.builder().id("c9").title("LLM 多模型路由规划").updatedAt(now - 2505600).createdAt(now - 2851200).messageCount(13).build());
        MOCK.add(ConversationVO.builder().id("c10").title("前端 SSE 数据流设计").updatedAt(now - 3628800).createdAt(now - 3974400).messageCount(7).build());
        MOCK.add(ConversationVO.builder().id("c11").title("Maven 多模块构建优化").updatedAt(now - 3888000).createdAt(now - 4233600).messageCount(5).build());
        MOCK.add(ConversationVO.builder().id("c12").title("Element Plus 暗黑模式适配").updatedAt(now - 4147200).createdAt(now - 4492800).messageCount(11).build());
        MOCK.add(ConversationVO.builder().id("c13").title("H2 数据库迁移到 PostgreSQL").updatedAt(now - 5184000).createdAt(now - 5529600).messageCount(14).build());
        MOCK.add(ConversationVO.builder().id("c14").title("Vite 构建配置与性能优化").updatedAt(now - 5443200).createdAt(now - 5788800).messageCount(8).build());
        MOCK.add(ConversationVO.builder().id("c15").title("Feign 客户端超时与重试策略").updatedAt(now - 5702400).createdAt(now - 6048000).messageCount(6).build());
        MOCK.add(ConversationVO.builder().id("c16").title("XXL-Job 定时任务接入指南").updatedAt(now - 5961600).createdAt(now - 6307200).messageCount(11).build());
    }

    @Operation(summary = "获取会话列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> list() {
        return ReturnMessage.success(MOCK);
    }

    @Operation(summary = "新建会话")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> create(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "新对话");
        ConversationVO conv = ConversationVO.builder()
            .id(UUID.randomUUID().toString().replace("-", "").substring(0, 8))
            .title(title)
            .updatedAt(System.currentTimeMillis() / 1000)
            .createdAt(System.currentTimeMillis() / 1000)
            .messageCount(0)
            .build();
        return ReturnMessage.success(conv);
    }

    @Operation(summary = "重命名会话")
    @RequestMapping(path = "/rename", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> rename(@RequestBody Map<String, String> body) {
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除会话")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body) {
        return ReturnMessage.success("ok");
    }
}
