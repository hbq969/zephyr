package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.springframework.beans.factory.annotation.Value;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
@Slf4j
public class DocxParser {

    @Value("${server.servlet.context-path}")
    private String contextPath;

    public ParseResult parse(InputStream in, String kbId, String docId, Path imageDir) {
        try {
            XWPFDocument doc = new XWPFDocument(in);
            StringBuilder md = new StringBuilder();
            int imgIdx = 0;
            Files.createDirectories(imageDir);

            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    md.append(convertParagraph(para, doc));
                    // 段落内嵌图片（drawing）
                    imgIdx += extractRunImages(para, imageDir, kbId, docId, imgIdx, md);
                } else if (elem instanceof XWPFTable table) {
                    md.append(convertTable(table));
                }
            }
            // ponytail: 移除 doc.getAllPictures() 重复提取，等遇到真实需求再补
            doc.close();
            return new ParseResult(md.toString().strip(), imgIdx, null);
        } catch (Exception e) {
            log.error("DocxParser 解析失败: {}", e.getMessage());
            return new ParseResult("", 0, PARSE_ERROR_CORRUPT);
        }
    }

    private String convertParagraph(XWPFParagraph para, XWPFDocument doc) {
        String text = para.getText();
        if (text == null || text.isBlank()) return "\n";

        int hl = detectHeadingLevel(para, doc);
        String prefix = hl > 0 ? "#".repeat(hl) + " " : "";
        return prefix + text + "\n\n";
    }

    private int detectHeadingLevel(XWPFParagraph para, XWPFDocument doc) {
        // 1. 段落属性 outlineLvl（最可靠）
        if (para.getCTP().getPPr() != null) {
            CTPPr ppr = para.getCTP().getPPr();
            if (ppr.isSetOutlineLvl()) {
                int lvl = ppr.getOutlineLvl().getVal().intValue();
                if (lvl >= 0 && lvl <= 8) return lvl + 1;
            }
        }

        String style = para.getStyle() != null ? para.getStyle() : "";
        String styleId = para.getStyleID();

        // 2. 段落 style name/ID 含 heading/标题
        String s = (style + " " + (styleId != null ? styleId : "")).toLowerCase();
        if (s.contains("heading") || s.contains("标题") || s.contains("toc")) {
            for (int i = 1; i <= 6; i++) if (s.contains(String.valueOf(i))) return i;
            return 1;
        }

        // 3. 文档样式表中查找样式名
        if (styleId != null && doc.getStyles() != null) {
            XWPFStyle st = doc.getStyles().getStyle(styleId);
            if (st != null && st.getName() != null) {
                String name = st.getName().toLowerCase();
                if (name.contains("heading") || name.contains("标题") || name.contains("toc")) {
                    for (int i = 1; i <= 6; i++) if (name.contains(String.valueOf(i))) return i;
                    return 1;
                }
            }
        }

        return 0;
    }

    private String convertTable(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        for (int ri = 0; ri < table.getRows().size(); ri++) {
            XWPFTableRow row = table.getRow(ri);
            sb.append("|");
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(" ").append(cell.getText().replace("\n", " ")).append(" |");
            }
            sb.append("\n");
            if (ri == 0) {
                sb.append("|");
                for (int ci = 0; ci < row.getTableCells().size(); ci++) sb.append(" --- |");
                sb.append("\n");
            }
        }
        return sb + "\n";
    }

    private int extractRunImages(XWPFParagraph para, Path dir, String kbId, String docId, int startIdx, StringBuilder md) {
        int count = 0;
        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pics = run.getEmbeddedPictures();
            for (XWPFPicture pic : pics) {
                XWPFPictureData data = pic.getPictureData();
                String ext = data.suggestFileExtension();
                String name = "img_" + String.format("%03d", startIdx + count + 1) + "." + ext;
                try { Files.write(dir.resolve(name), data.getData()); } catch (IOException ignored) {}
                md.append("![").append(name).append("](").append(imageUrl(contextPath, kbId, docId, name)).append(")\n\n");
                count++;
            }
        }
        return count;
    }
}
