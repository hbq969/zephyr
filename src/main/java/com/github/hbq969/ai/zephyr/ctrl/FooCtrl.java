package com.github.hbq969.ai.zephyr.ctrl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.code.common.cache.Expire;
import com.github.hbq969.code.common.encrypt.ext.config.Decrypt;
import com.github.hbq969.code.common.encrypt.ext.config.Encrypt;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.advice.log.LogSet;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import com.google.common.collect.ImmutableMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Tag(name = "示例接口")
@RestController
@RequestMapping(path = "/example")
public class FooCtrl {
    @Operation(summary="GET请求示例")
    @RequestMapping(path = "/get", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> queryForGet() {
        return ReturnMessage.success("bar");
    }

    @Operation(summary="POST请求示例")
    @RequestMapping(path = "/post", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> submitForPost(@RequestBody Map body) {
        return ReturnMessage.success("bar");
    }

    @Cacheable(keyGenerator = "apiKeyGenerator", value = "default", unless = "#result.state.value!='OK'")
    @Expire(methodKey = "queryForCache", time = 5, unit = TimeUnit.SECONDS)
    @Operation(summary="接口缓存示例")
    @RequestMapping(path = "/restful/cache", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> queryForCache() {
        String uuid = UUID.fastUUID().toString(true);
        log.info("创建UUID: {}", uuid);
        return ReturnMessage.success(uuid);
    }

    @Cacheable(keyGenerator = "apiKeyGenerator", value = "default", unless = "#result.state.value!='OK'")
    @Expire(methodKey = "queryForCache", time = 5, unit = TimeUnit.SECONDS)
    @CacheEvict(keyGenerator = "apiKeyGenerator", value = "default")
    @Operation(summary="接口缓存清理示例")
    @RequestMapping(path = "/restful/evict", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> evictForCache() {
        return ReturnMessage.success("清理成功");
    }

    @Operation(summary="RBAC接口权限控制示例")
    @RequestMapping(path = "/rbac", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "rbac", apiKey = "example", apiDesc = "RBAC接口权限控制示例菜单")
    public ReturnMessage<?> rbac(@RequestBody Map body) {
        Map map = ImmutableMap.of("@SMRequiresPermissions.menu", "系统管理中配置的菜单英文名称"
                , "@SMRequiresPermissions.apiKey", "接口方法名称",
                "@SMRequiresPermissions.apiDesc", "接口方法描述",
                "remark", "此@SMRequiresPermissions注解控制的权限只控制到菜单权限，一个菜单会包含多个接口");
        return ReturnMessage.success(map);
    }

    @Operation(summary="restful接口日志打印控制")
    @RequestMapping(path = "/restful/log", method = RequestMethod.GET)
    @ResponseBody
    @LogSet(printIn = false, printResult = false)
    public ReturnMessage<?> restfulLogSet() {
        String msg = "如果开启了advice.log.enabled=true，接口会打印入参和出参日志，\n"
                + "如果说因为处于安全原因，可通过 @LogSet进行关闭控制。";
        return ReturnMessage.success(msg);
    }

    @Operation(summary="restful接口加解密实例")
    @RequestMapping(path = "/restful/encrypt", method = RequestMethod.POST)
    @ResponseBody
    @Encrypt
    // @Decrypt
    public ReturnMessage<?> restfulEncrypt(@RequestBody Map body) {
        String msg = "1、@Encrypt 注解表示对请求响应进行加密处理，默认使用AES算法加密。\n"
                + "2、@Decrypt 注解表示对请求进行解密处理，默认使用AES算法解密。\n"
                + "3、支持的加解密算法有：AES（对称加密）、RSA（非对称加密）、RAS（AES+RSA）。\n"
                + "4、如果需要使用RAS算法，请求体格式是固定的 {key:'AES的key', iv:'AES的iv向量', body:'使用AES加密的请求体'}。\n"
                + "5、算法为RSA、RAS时需要服务端口配置RSA密钥对，客户端或前端需要先获取RSA的公钥。\n"
                + "6、算法为RAS时，需要客户端或前端使用自己生成AES的key和iv来加密请求体。";
        return ReturnMessage.success(msg);
    }
}
