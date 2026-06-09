package com.github.hbq969.ai.zephyr.service.impl;

import com.github.hbq969.ai.zephyr.service.FooService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FooServiceImpl implements FooService {
    @Override
    public String greeting(String name) {
        return "Hello " + name;
    }
}
