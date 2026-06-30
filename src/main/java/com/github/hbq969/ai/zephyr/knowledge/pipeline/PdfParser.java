package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.RenderedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
@Slf4j
public class PdfParser {

    @Value("${server.servlet.context-path}")
    private String contextPath;

    public ParseResult parse(InputStream in, String kbId, String docId, Path imageDir) {
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            return new ParseResult("", 0, PARSE_ERROR_CORRUPT);
        }
        PDDocument doc = null;
        try {
            doc = Loader.loadPDF(bytes);
            int pageCount = doc.getNumberOfPages();

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            // 扫描件检测
            String alphaOnly = text.replaceAll("[^a-zA-Z\\u4e00-\\u9fa5]", "");
            double ratio = (double) alphaOnly.length() / Math.max(text.length(), 1);
            int expectedMin = pageCount * PDF_MIN_TEXT_CHARS_PER_PAGE;
            if (alphaOnly.length() < expectedMin && ratio < PDF_MIN_TEXT_RATIO) {
                return new ParseResult("", 0, PARSE_ERROR_SCANNED);
            }

            // Markdown 转换
            StringBuilder md = new StringBuilder();
            String prevLine = "";
            for (String line : text.split("\n")) {
                String t = line.strip();
                if (t.isEmpty()) { md.append("\n"); prevLine = ""; continue; }
                boolean looksHeading = t.length() < 80 && !t.endsWith(".") && !t.endsWith("。") && prevLine.isBlank();
                md.append(looksHeading ? "## " : "").append(t).append("\n\n");
                prevLine = t;
            }

            int imgIdx = extractImages(doc, imageDir, kbId, docId, md);
            return new ParseResult(md.toString().strip(), imgIdx, null);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Cryptography") || msg.contains("encrypted"))
                return new ParseResult("", 0, PARSE_ERROR_ENCRYPTED);
            log.error("PdfParser 解析失败: {}", msg);
            return new ParseResult("", 0, PARSE_ERROR_CORRUPT);
        } finally {
            try { if (doc != null) doc.close(); } catch (Exception ignored) { /* cleanup */ }
        }
    }

    private int extractImages(PDDocument doc, Path dir, String kbId, String docId, StringBuilder md) throws IOException {
        int idx = 0;
        Files.createDirectories(dir);
        for (PDPage page : doc.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;
            for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
                try {
                    org.apache.pdfbox.pdmodel.graphics.PDXObject xobj = resources.getXObject(name);
                    if (xobj instanceof PDImageXObject img) {
                        String fname = "img_" + String.format("%03d", idx + 1) + ".png";
                        RenderedImage ri = img.getImage();
                        ImageIO.write(ri, "png", dir.resolve(fname).toFile());
                        md.append("![").append(fname).append("](")
                          .append(imageUrl(contextPath, kbId, docId, fname)).append(")\n\n");
                        idx++;
                    }
                } catch (Exception ignored) { /* 单图失败不影响整体 */ }
            }
        }
        return idx;
    }
}
