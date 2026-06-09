package com.github.hbq969.ai.zephyr.config.dao;

import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface ModelConfigDao {
    void createModelConfigsTable();
    List<ModelConfigEntity> queryByUserName(@Param("userName") String userName);
    void insert(ModelConfigEntity entity);
    void update(ModelConfigEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    ModelConfigEntity queryById(@Param("id") String id);
    void clearDefault(@Param("userName") String userName);
    void setDefault(@Param("id") String id, @Param("userName") String userName);
}
