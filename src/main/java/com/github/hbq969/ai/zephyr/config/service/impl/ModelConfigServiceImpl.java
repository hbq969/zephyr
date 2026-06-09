package com.github.hbq969.ai.zephyr.config.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.config.service.ModelConfigService;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ModelConfigServiceImpl implements ModelConfigService {

    @Value("${encrypt.restful.aes.key}")
    private String aesKey;

    @Value("${encrypt.restful.aes.iv}")
    private String aesIv;

    @Resource
    private ModelConfigDao modelConfigDao;

    @Override
    public List<ModelConfigEntity> list(String userName) {
        List<ModelConfigEntity> list = modelConfigDao.queryByUserName(userName);
        for (ModelConfigEntity e : list) {
            String key = e.getApiKeyEncrypted();
            if (key != null && !key.isEmpty()) {
                e.setApiKeyEncrypted(maskApiKey(key));
            }
        }
        return list;
    }

    @Override
    @Transactional
    public ModelConfigEntity create(Map<String, String> body, String userName) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setBaseUrl(body.getOrDefault("baseUrl", ""));
        String apiKey = body.get("apiKey");
        entity.setApiKeyEncrypted(apiKey != null && !apiKey.isEmpty() ? encryptApiKey(apiKey) : "");
        entity.setIsDefault(0);
        entity.setCreatedAt(System.currentTimeMillis() / 1000);
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelConfigDao.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public void update(Map<String, String> body, String userName) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(body.get("id"));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setBaseUrl(body.getOrDefault("baseUrl", ""));
        String apiKey = body.get("apiKey");
        if (apiKey != null && !apiKey.isEmpty()) {
            entity.setApiKeyEncrypted(encryptApiKey(apiKey));
        }
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelConfigDao.update(entity);
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        modelConfigDao.delete(id, userName);
    }

    @Override
    @Transactional
    public void setDefault(String id, String userName) {
        modelConfigDao.clearDefault(userName);
        modelConfigDao.setDefault(id, userName);
    }

    private String encryptApiKey(String plain) {
        return AESUtil.encrypt(plain, aesKey, aesIv, StandardCharsets.UTF_8);
    }

    private String maskApiKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }
}
