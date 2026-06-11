package com.github.hbq969.ai.zephyr.workspace.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
import com.github.hbq969.ai.zephyr.workspace.service.WorkspaceService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {

    @Resource
    private WorkspaceDao workspaceDao;

    @Override
    public List<WorkspaceEntity> list(String userName) {
        return workspaceDao.queryByUserName(userName);
    }

    @Override
    @Transactional
    public WorkspaceEntity create(Map<String, String> body, String userName) {
        String name = body.get("name");
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            throw new RuntimeException("目录路径不能为空");
        }
        if (name == null || name.isBlank()) {
            name = java.nio.file.Path.of(path).getFileName().toString();
        }
        long now = System.currentTimeMillis() / 1000;
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setName(name);
        entity.setPath(path);
        entity.setUserName(userName);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        workspaceDao.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        workspaceDao.delete(id, userName);
    }
}
