package com.github.hbq969.ai.zephyr.mcp.ctrl;

import com.github.hbq969.ai.zephyr.mcp.service.McpService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.session.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "MCP管理")
@RestController
@RequestMapping(path = "/zephyr-ui/mcp")
public class McpCtrl {

    @Resource
    private McpService mcpService;

    private String userName() {
        com.github.hbq969.code.common.spring.context.UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "MCP服务器列表")
    @RequestMapping(path = "/server/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> listServers() {
        return ReturnMessage.success(mcpService.listServers(userName()));
    }

    @Operation(summary = "新增MCP服务器")
    @RequestMapping(path = "/server/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> createServer(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(mcpService.createServer(body, userName()));
    }

    @Operation(summary = "修改MCP服务器")
    @RequestMapping(path = "/server/update", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> updateServer(@RequestBody Map<String, String> body) {
        mcpService.updateServer(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除MCP服务器")
    @RequestMapping(path = "/server/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> deleteServer(@RequestBody Map<String, String> body) {
        mcpService.deleteServer(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "连接MCP服务器")
    @RequestMapping(path = "/server/connect", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> connect(@RequestBody Map<String, String> body) {
        mcpService.connect(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "断开MCP服务器")
    @RequestMapping(path = "/server/disconnect", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> disconnect(@RequestBody Map<String, String> body) {
        mcpService.disconnect(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "MCP工具列表")
    @RequestMapping(path = "/tool/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> listTools(@RequestParam("serverId") String serverId) {
        return ReturnMessage.success(mcpService.listTools(serverId, userName()));
    }

    @Operation(summary = "手动添加MCP工具")
    @RequestMapping(path = "/tool/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> createTool(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(mcpService.createTool(body, userName()));
    }

    @Operation(summary = "修改MCP工具")
    @RequestMapping(path = "/tool/update", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> updateTool(@RequestBody Map<String, String> body) {
        mcpService.updateTool(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除MCP工具")
    @RequestMapping(path = "/tool/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> deleteTool(@RequestBody Map<String, String> body) {
        mcpService.deleteTool(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "启用/禁用MCP工具")
    @RequestMapping(path = "/tool/toggle", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> toggleTool(@RequestBody Map<String, String> body) {
        mcpService.toggleTool(body.get("id"), Integer.parseInt(body.get("enabled")), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "已启用工具数")
    @RequestMapping(path = "/tool/count", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> countEnabledTools() {
        return ReturnMessage.success(mcpService.countEnabledTools(userName()));
    }
}
