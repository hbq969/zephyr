package com.github.hbq969.ai.zephyr.builtintool.service.impl;

import com.github.hbq969.ai.zephyr.builtintool.dao.BuiltinToolDao;
import com.github.hbq969.ai.zephyr.builtintool.dao.entity.BuiltinToolControlEntity;
import com.github.hbq969.ai.zephyr.builtintool.service.BuiltinToolService;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.code.common.initial.event.ScriptInitialDoneEvent;
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BuiltinToolServiceImpl implements BuiltinToolService, ApplicationListener<ScriptInitialDoneEvent> {

    @Resource
    private BuiltinToolDao builtinToolDao;

    @Resource
    private McpDao mcpDao;

    private volatile Map<String, Boolean> requireAdminCache = new ConcurrentHashMap<>();
    private volatile Set<String> mcpToolNames = Collections.emptySet();

    @Override
    public void onApplicationEvent(ScriptInitialDoneEvent event) {
        refreshCache();
    }

    @Override
    public void refreshCache() {
        try {
            List<BuiltinToolControlEntity> list = builtinToolDao.queryAll();
            if (list.isEmpty()) {
                log.warn("[内置工具管控] 配置表为空，所有工具放行");
                requireAdminCache = new ConcurrentHashMap<>();
            } else {
                Map<String, Boolean> map = new ConcurrentHashMap<>();
                for (BuiltinToolControlEntity e : list) {
                    map.put(e.getToolName(), e.getRequireAdmin() != null && e.getRequireAdmin() == 1);
                }
                requireAdminCache = map;
            }
        } catch (Exception e) {
            log.warn("[内置工具管控] 加载配置失败，所有工具放行", e);
            requireAdminCache = new ConcurrentHashMap<>();
        }

        try {
            List<String> names = mcpDao.queryAllDistinctToolNames();
            mcpToolNames = new HashSet<>(names);
            log.info("[内置工具管控] MCP 工具名集合已加载: {} 个", mcpToolNames.size());
        } catch (Exception e) {
            log.warn("[内置工具管控] 加载 MCP 工具名失败，MCP 管控暂时失效", e);
            mcpToolNames = Collections.emptySet();
        }

        log.info("[内置工具管控] 缓存已刷新: controls={}, mcpToolCount={}", requireAdminCache, mcpToolNames.size());
    }

    @Override
    public boolean requiresAdmin(String userName, String toolName) {
        UserInfo ui = UserContext.getNoCheck();
        if (ui != null && ui.isAdmin()) {
            log.debug("[内置工具管控] admin 用户豁免: tool={}, user={}", toolName, userName);
            return false;
        }

        String lookupKey = mcpToolNames.contains(toolName) ? "mcp_all" : toolName;
        Boolean v = requireAdminCache.get(lookupKey);
        boolean blocked = v != null && v;
        if (blocked) {
            log.info("[内置工具管控] 非 admin 用户被拦截: tool={}, lookupKey={}, user={}, roles={}",
                    toolName, lookupKey, userName,
                    ui == null ? "[]" : ui.getRoleNames());
        }
        return blocked;
    }

    @Override
    public List<BuiltinToolControlEntity> list() {
        try {
            return builtinToolDao.queryAll();
        } catch (Exception e) {
            log.warn("[内置工具管控] 查询列表失败", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void toggle(String toolName, int requireAdmin) {
        builtinToolDao.updateRequireAdmin(toolName, requireAdmin, System.currentTimeMillis() / 1000);
        refreshCache();
        log.info("[内置工具管控] {} 已切换: requireAdmin={}", toolName, requireAdmin);
    }
}
