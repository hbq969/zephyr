package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class TikaParser {
    private final Tika tika = new Tika();

    public String parse(InputStream in) throws IOException, TikaException {
        return tika.parseToString(in);
    }
}
