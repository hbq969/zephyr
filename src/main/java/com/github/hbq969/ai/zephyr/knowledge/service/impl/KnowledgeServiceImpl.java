package com.github.hbq969.ai.zephyr.knowledge.service.impl;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.model.KnowledgeVO;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.ChromaClient;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.EmbeddingClient;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.KeywordIndex;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.RrfMerger;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TextCleaner;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TextSplitter;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TikaParser;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.DocxParser;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.PdfParser;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.ParseResult;
import com.github.hbq969.ai.zephyr.knowledge.client.LightRagClient;
import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import com.github.hbq969.code.common.initial.event.ScriptInitialDoneEvent;
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService, ApplicationListener<ScriptInitialDoneEvent> {

    @Resource
    private KnowledgeDao knowledgeDao;

    @Resource
    private ModelConfigDao modelConfigDao;

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private TikaParser tikaParser;

    @Resource
    private DocxParser docxParser;

    @Resource
    private PdfParser pdfParser;

    @Resource
    private TextCleaner textCleaner;

    private final TextSplitter textSplitter = new TextSplitter();

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private KeywordIndex keywordIndex;

    @Lazy
    @Resource
    private KnowledgeServiceImpl self;

    @Resource
    private LightRagClient lightRagClient;

    private static final String SCOPE_SHARED = "shared";
    private static final String SCOPE_USER = "user";

    private static final Set<String> STOP_WORDS = Set.of(
            "如何", "怎么", "怎样", "可以", "这个", "那个", "什么", "为什么",
            "哪些", "哪里", "是否", "能不能", "有没有", "怎么办",
            "的", "了", "吗", "呢", "吧", "啊", "嘛"
    );

    /**
     * 查询增强：从查询中提取关键词拼接到原查询后面，拉近与 chunk 的语义距离。
     * <p>
     * 中文：提取 2-4 字连续汉字片段，过滤停用词，去重。
     * 英文：提取长度 ≥ 3 的字母数字 token，去重。
     */
    private String augmentQuery(String query) {
        if (query == null || query.trim().isEmpty()) return query;

        List<String> keywords = new ArrayList<>();

        StringBuilder cnBuf = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cnBuf.append(c);
            } else {
                extractChineseKeywords(cnBuf.toString(), keywords);
                cnBuf.setLength(0);
            }
        }
        extractChineseKeywords(cnBuf.toString(), keywords);

        for (String token : query.split("[\\s\\p{Punct}]+")) {
            token = token.trim().toLowerCase();
            if (token.length() >= 3 && token.matches("[a-z0-9]+")) {
                keywords.add(token);
            }
        }

        if (keywords.isEmpty()) return query;

        String augmented = query + " " + String.join(" ", keywords);
        if (augmented.length() > query.length() * 3) {
            StringBuilder sb = new StringBuilder(query);
            for (String kw : keywords) {
                if (sb.length() + kw.length() + 1 > query.length() * 2) break;
                sb.append(" ").append(kw);
            }
            return sb.toString();
        }
        return augmented;
    }

    private void extractChineseKeywords(String cnText, List<String> out) {
        if (cnText.length() < 2) return;
        int maxWindow = Math.min(4, cnText.length());
        for (int w = maxWindow; w >= 2; w--) {
            for (int i = 0; i <= cnText.length() - w; i++) {
                String kw = cnText.substring(i, i + w);
                if (!STOP_WORDS.contains(kw)) {
                    out.add(kw);
                }
            }
        }
    }

    private boolean isAdmin() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null && ui.isAdmin();
    }

    private void checkSharedManage() {
        if (!isAdmin()) throw new RuntimeException("仅 admin 可管理共享知识库");
    }

    @Override
    public List<KnowledgeVO> listKb(String userName) {
        List<KnowledgeBaseEntity> all = new ArrayList<>();
        all.addAll(knowledgeDao.queryKbByUserName(userName));
        all.addAll(knowledgeDao.querySharedKbs());
        // deduplicate by id (user's own KB takes precedence)
        Map<String, KnowledgeBaseEntity> dedup = new LinkedHashMap<>();
        for (KnowledgeBaseEntity kb : all) {
            dedup.putIfAbsent(kb.getId(), kb);
        }
        boolean admin = isAdmin();
        List<KnowledgeVO> vos = new ArrayList<>();
        for (KnowledgeBaseEntity kb : dedup.values()) {
            KnowledgeVO vo = new KnowledgeVO();
            vo.setId(kb.getId());
            vo.setName(kb.getName());
            vo.setDescription(kb.getDescription());
            vo.setEmbedModelId(kb.getEmbedModelId());
            vo.setScope(kb.getScope() != null ? kb.getScope() : SCOPE_USER);
            vo.setGraphEnabled(Integer.valueOf(1).equals(kb.getGraphEnabled()));
            if (kb.getEmbedModelId() != null) {
                var model = modelConfigDao.queryById(kb.getEmbedModelId());
                if (model != null) vo.setEmbedModelName(model.getName());
            }
            vo.setDocCount(knowledgeDao.queryDocsByKbId(kb.getId()).size());
            vo.setCreatedAt(kb.getCreatedAt());
            vo.setUpdatedAt(kb.getUpdatedAt());
            // canManage: shared KBs only admin; own KBs admin + owner
            if (SCOPE_SHARED.equals(vo.getScope())) {
                vo.setCanManage(admin);
            } else {
                vo.setCanManage(admin || kb.getUserName().equals(userName));
            }
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public KnowledgeBaseEntity createKb(Map<String, Object> body, String userName) {
        String scope = (String) body.getOrDefault("scope", SCOPE_USER);
        if (SCOPE_SHARED.equals(scope)) checkSharedManage();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserName(userName);
        entity.setName((String) body.get("name"));
        entity.setDescription((String) body.getOrDefault("description", ""));
        entity.setEmbedModelId((String) body.get("embedModelId"));
        entity.setScope(scope);
        Object ge = body.get("graphEnabled");
        entity.setGraphEnabled((ge != null && (Boolean.TRUE.equals(ge) || "true".equals(String.valueOf(ge)))) ? 1 : 0);
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        knowledgeDao.insertKb(entity);
        return entity;
    }

    @Override
    public void updateKb(Map<String, Object> body, String userName) {
        KnowledgeBaseEntity entity = knowledgeDao.queryKbById((String) body.get("id"));
        if (entity == null) {
            throw new RuntimeException("知识库不存在");
        }
        if (SCOPE_SHARED.equals(entity.getScope())) checkSharedManage();
        entity.setName((String) body.get("name"));
        entity.setDescription((String) body.getOrDefault("description", ""));
        entity.setEmbedModelId((String) body.get("embedModelId"));
        Object ge = body.get("graphEnabled");
        entity.setGraphEnabled((ge != null && (Boolean.TRUE.equals(ge) || "true".equals(String.valueOf(ge)))) ? 1 : 0);
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.updateKb(entity);
    }

    @Override
    @Transactional
    public void deleteKb(String id, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(id);
        if (kb == null) {
            throw new RuntimeException("知识库不存在");
        }
        if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
        knowledgeDao.deleteConversationKbByKbId(id);
        keywordIndex.removeKb(id);
        knowledgeDao.deleteDocsByKbId(id);
        lightRagClient.deleteKb(id);
        knowledgeDao.deleteKb(id);
        chromaClient.deleteCollection("kb_" + id);
        deleteKbDataDir(id);

        Path imgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), id);
        try {
            if (Files.exists(imgDir)) {
                try (var s = Files.walk(imgDir)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (Exception e) {
            log.warn("清理知识库图片失败: kbId={}", id, e);
        }
    }

    @Override
    public List<KnowledgeDocEntity> listDocs(String kbId) {
        return knowledgeDao.queryDocsByKbId(kbId);
    }

    @Override
    public void deleteDoc(String id) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(id);
        if (doc != null) {
            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(doc.getKbId());
            if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
            keywordIndex.removeDoc(doc.getKbId(), id);
            lightRagClient.deleteDoc(doc.getKbId(), id);
            try { chromaClient.deleteByMetadata("kb_" + doc.getKbId(), Map.of("doc_id", id)); }
            catch (Exception e) { log.warn("清理 Chroma embeddings 失败: docId={}", id, e); }

            Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), doc.getKbId());
            try {
                if (Files.exists(dataDir)) {
                    try (var s = Files.newDirectoryStream(dataDir, id + "_*")) {
                        s.forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
                    }
                }
            } catch (Exception e) {
                log.warn("清理文档文件失败: docId={}", id, e);
            }

            Path imgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), doc.getKbId(), id);
            try {
                if (Files.exists(imgDir)) {
                    try (var s = Files.walk(imgDir)) {
                        s.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                    }
                }
            } catch (Exception e) {
                log.warn("清理文档图片失败: docId={}", id, e);
            }
        }
        knowledgeDao.deleteDoc(id);
    }

    @Override
    public List<String> getConversationKbIds(String conversationId) {
        return knowledgeDao.queryKbIdsByConversation(conversationId);
    }

    @Override
    public void saveConversationKbIds(String conversationId, List<String> kbIds) {
        knowledgeDao.deleteConversationKb(conversationId);
        if (kbIds != null) {
            for (String kbId : kbIds) {
                knowledgeDao.insertConversationKb(conversationId, kbId);
            }
        }
    }

    @Override
    public Map<String, Object> uploadDoc(String kbId, MultipartFile file, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null) throw new RuntimeException("知识库不存在");
        if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

        String ft = fileType(file.getOriginalFilename());
        if (!Set.of("md", "docx", "pdf").contains(ft)) throw new RuntimeException("仅支持 md、docx、pdf 格式文件");
        if (file.getSize() > cfg.getKnowledge().getMaxFileSizeBytes())
            throw new RuntimeException("文件过大（超过" + (cfg.getKnowledge().getMaxFileSizeBytes() / 1024 / 1024) + "MB）");

        String docId = UUID.randomUUID().toString();
        String safeName = Path.of(file.getOriginalFilename()).getFileName().toString();
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        try {
            Files.createDirectories(dataDir);
            Path dest = dataDir.resolve(docId + "_" + safeName).normalize();
            if (!dest.startsWith(dataDir.normalize())) throw new RuntimeException("非法文件名");
            file.transferTo(dest.toFile());
        } catch (IOException e) { throw new RuntimeException("保存文件失败", e); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId); result.put("fileType", ft); result.put("fileName", safeName);

        if ("md".equals(ft)) {
            try {
                String mdContent = Files.readString(dataDir.resolve(docId + "_" + safeName));
                result.put("markdownPreview", mdContent);
                result.put("headings", textSplitter.scanHeadings(mdContent));
            } catch (IOException e) { throw new RuntimeException("读取文件失败", e); }
        } else {
            Path imageDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);
            ParseResult pr;
            try (InputStream in = Files.newInputStream(dataDir.resolve(docId + "_" + safeName))) {
                pr = switch (ft) {
                    case "docx" -> docxParser.parse(in, kbId, docId, imageDir);
                    case "pdf"  -> pdfParser.parse(in, kbId, docId, imageDir);
                    default     -> new ParseResult("", 0, PARSE_ERROR_CORRUPT);
                };
            } catch (IOException e) { throw new RuntimeException("解析文件失败", e); }

            if (!pr.isSuccess()) {
                String msg = switch (pr.getErrorType()) {
                    case PARSE_ERROR_SCANNED -> "扫描件不支持，请使用 OCR 后重新导入";
                    case PARSE_ERROR_ENCRYPTED -> "加密文件不支持，请解密后重新导入";
                    case PARSE_ERROR_CORRUPT -> "文件损坏或格式不支持";
                    default -> "解析失败: " + pr.getErrorType();
                };
                throw new RuntimeException(msg);
            }

            String mdName = safeName.replaceFirst("\\.[^.]+$", "") + ".md";
            try { Files.writeString(dataDir.resolve(docId + "_" + mdName), pr.getMarkdown()); }
            catch (IOException e) { throw new RuntimeException("保存 markdown 失败", e); }

            result.put("markdownPreview", pr.getMarkdown());
            result.put("headings", textSplitter.scanHeadings(pr.getMarkdown()));
        }

        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setId(docId); doc.setKbId(kbId); doc.setFileName(safeName);
        doc.setFileType(ft); doc.setFileSize(file.getSize());
        doc.setStatus(DOC_STATUS_PENDING); doc.setChunkCount(0);
        doc.setCreatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.insertDoc(doc);
        return result;
    }

    @Override
    public void confirmImport(String docId, String kbId, int headingLevel, String markdownContent, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null) throw new RuntimeException("知识库不存在");
        if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");
        if (!kbId.equals(doc.getKbId())) throw new RuntimeException("文档不属于该知识库");
        if (headingLevel < 0 || headingLevel > 6) throw new RuntimeException("标题层级必须在 0-6 之间");

        int affected = knowledgeDao.tryLockDoc(docId);
        if (affected == 0) throw new RuntimeException("文档已被其他请求确认导入");

        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        if (markdownContent != null && !markdownContent.isBlank()) {
            String mdName = doc.getFileName().replaceFirst("\\.[^.]+$", "") + ".md";
            try { Files.writeString(dataDir.resolve(docId + "_" + mdName), markdownContent); }
            catch (IOException e) { throw new RuntimeException("保存 markdown 失败", e); }
        }

        self.processImportAsync(docId, kbId, headingLevel);
    }

    @Async
    public void processImportAsync(String docId, String kbId, int headingLevel) {
        try {
            KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
            if (doc == null) { log.warn("文档已删除: docId={}", docId); return; }
            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);

            Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
            String text;
            String mdName = doc.getFileName().replaceFirst("\\.[^.]+$", "") + ".md";
            Path mdPath = dataDir.resolve(docId + "_" + mdName);
            if (Files.exists(mdPath)) text = Files.readString(mdPath);
            else text = Files.readString(dataDir.resolve(docId + "_" + doc.getFileName()));

            text = textCleaner.clean(text, true);
            List<TextSplitter.Chunk> chunks = textSplitter.split(text, headingLevel);
            List<TextSplitter.Chunk> filtered = new ArrayList<>();
            for (TextSplitter.Chunk c : chunks)
                if (c.text.codePointCount(0, c.text.length()) >= MIN_CHUNK_CODE_POINTS) filtered.add(c);

            if (filtered.isEmpty()) { knowledgeDao.updateDocStatus(docId, STATUS_ERROR, 0, "文档内容为空"); return; }

            var embedModel = modelConfigDao.queryById(kb.getEmbedModelId());
            if (embedModel == null) { knowledgeDao.updateDocStatus(docId, STATUS_ERROR, 0, "Embedding 模型未配置"); return; }

            String collId = chromaClient.getOrCreateCollection("kb_" + kbId);
            int batchSize = CHROMA_BATCH_SIZE;
            for (int bs = 0; bs < filtered.size(); bs += batchSize) {
                int be = Math.min(bs + batchSize, filtered.size());
                List<String> batchIds = new ArrayList<>(); List<Map<String, String>> batchMetas = new ArrayList<>();
                List<String> batchTexts = new ArrayList<>();
                for (int i = bs; i < be; i++) {
                    TextSplitter.Chunk c = filtered.get(i);
                    batchIds.add(docId + "_" + i);
                    Map<String, String> meta = new HashMap<>();
                    meta.put("doc_id", docId); meta.put("file_name", doc.getFileName());
                    meta.put("chunk_index", String.valueOf(i));
                    meta.put(CHUNK_META_HEADING_PATH, c.headingPath != null ? c.headingPath : "");
                    meta.put(CHUNK_META_CHUNK_TYPE, c.chunkType != null ? c.chunkType : CHUNK_TYPE_PARAGRAPH);
                    batchMetas.add(meta); batchTexts.add(c.text);
                }
                chromaClient.add(collId, batchIds, embeddingClient.embed(batchTexts, embedModel), batchMetas, batchTexts);
            }
            keywordIndex.addChunks(kbId, docId, filtered.stream().map(c -> c.text).toList());
            knowledgeDao.updateDocStatus(docId, "ready", filtered.size(), null);

            if (Integer.valueOf(1).equals(kb.getGraphEnabled())) {
                try {
                    knowledgeDao.updateDocGraphStatus(docId, "indexing");
                    lightRagClient.index(kbId, docId, text);
                    knowledgeDao.updateDocGraphStatus(docId, "ready");
                } catch (Exception e) { log.warn("图谱索引失败: docId={}", docId, e); knowledgeDao.updateDocGraphStatus(docId, "error"); }
            }
        } catch (Exception e) { log.error("文档导入处理失败: docId={}", docId, e); knowledgeDao.updateDocStatus(docId, STATUS_ERROR, 0, e.getMessage()); }
    }

    @Override
    public Map<String, Object> reParseDoc(String docId, String kbId, String userName) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");
        if (DOC_STATUS_PENDING.equals(doc.getStatus()) || "processing".equals(doc.getStatus()))
            throw new RuntimeException("文档状态不允许重新解析");
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

        keywordIndex.removeDoc(kbId, docId);
        lightRagClient.deleteDoc(kbId, docId);
        try { chromaClient.deleteByMetadata("kb_" + kbId, Map.of("doc_id", docId)); }
        catch (Exception e) { log.warn("清理 Chroma embeddings 失败: docId={}", docId, e); }

        Path oldImgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);
        try {
            if (Files.exists(oldImgDir)) {
                try (var s = Files.walk(oldImgDir)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
        } catch (Exception e) { log.warn("清理旧图片失败: docId={}", docId, e); }

        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        Path mdPath = dataDir.resolve(docId + "_" + doc.getFileName().replaceFirst("\\.[^.]+$", "") + ".md");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId); result.put("fileType", doc.getFileType()); result.put("fileName", doc.getFileName());

        try {
            String text;
            Path origPath = dataDir.resolve(docId + "_" + doc.getFileName());
            if (Files.exists(mdPath)) text = Files.readString(mdPath);
            else if (Files.exists(origPath)) {
                if ("docx".equals(doc.getFileType()) || "pdf".equals(doc.getFileType())) {
                    Path imageDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);
                    ParseResult pr;
                    try (InputStream in = Files.newInputStream(origPath)) {
                        pr = switch (doc.getFileType()) {
                            case "docx" -> docxParser.parse(in, kbId, docId, imageDir);
                            case "pdf"  -> pdfParser.parse(in, kbId, docId, imageDir);
                            default     -> new ParseResult("", 0, PARSE_ERROR_CORRUPT);
                        };
                    }
                    if (!pr.isSuccess()) throw new RuntimeException("重新解析失败: " + pr.getErrorType());
                    text = pr.getMarkdown();
                } else text = Files.readString(origPath);
            } else throw new RuntimeException("文档文件不存在");

            result.put("headings", textSplitter.scanHeadings(text));
            if (!"md".equals(doc.getFileType())) result.put("markdownPreview", text);
            knowledgeDao.updateDocStatus(docId, DOC_STATUS_PENDING, 0, null);
        } catch (IOException e) { throw new RuntimeException("读取文件失败", e); }
        return result;
    }

    @Override
    public String createInlineDoc(String kbId, String title, String content, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null) throw new RuntimeException("知识库不存在");
        if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

        String docId = UUID.randomUUID().toString();
        String fileName = title + ".md";
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(docId + "_" + fileName), content);
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败", e);
        }

        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType("md");
        doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setContent(content);
        doc.setSourceType("inline");
        doc.setStatus("processing");
        doc.setChunkCount(0);
        doc.setCreatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.insertDoc(doc);

        self.processDocContentAsync(docId, kbId, content, fileName);
        return docId;
    }

    @Override
    public void updateInlineDoc(String docId, String title, String content, String userName) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");
        if (!"inline".equals(doc.getSourceType())) throw new RuntimeException("仅内联文档支持在线编辑");
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(doc.getKbId());
        if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

        String fileName = title + ".md";
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), doc.getKbId());
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(docId + "_" + fileName), content);
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败", e);
        }

        // 删除旧索引
        keywordIndex.removeDoc(doc.getKbId(), docId);
        lightRagClient.deleteDoc(doc.getKbId(), docId);

        doc.setFileName(fileName);
        doc.setContent(content);
        doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setStatus("processing");
        doc.setChunkCount(0);
        doc.setUpdatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.updateDoc(doc);

        self.processDocContentAsync(docId, doc.getKbId(), content, fileName);
    }

    @Override
    public List<SearchResult> search(String query, List<String> kbIds, int topK) {
        if (kbIds == null || kbIds.isEmpty()) return List.of();

        Map<String, List<String>> kbByModel = new LinkedHashMap<>();
        for (String kbId : kbIds) {
            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
            if (kb == null || kb.getEmbedModelId() == null) {
                log.warn("知识库 {} 未配置 Embedding 模型，跳过", kbId);
                continue;
            }
            kbByModel.computeIfAbsent(kb.getEmbedModelId(), k -> new ArrayList<>()).add(kbId);
        }
        if (kbByModel.isEmpty()) throw new RuntimeException("所选知识库均未配置 Embedding 模型");

        int fetchSize = topK * 2;
        List<ChromaClient.QueryResult> allVecResults = new ArrayList<>();

        String augmentedQuery = augmentQuery(query);

        for (Map.Entry<String, List<String>> entry : kbByModel.entrySet()) {
            ModelConfigEntity embedModel = modelConfigDao.queryById(entry.getKey());
            if (embedModel == null) {
                log.warn("Embedding 模型 {} 不存在，跳过", entry.getKey());
                continue;
            }
            List<float[]> embeddings = embeddingClient.embed(List.of(augmentedQuery), embedModel);
            if (embeddings.isEmpty()) continue;
            for (String kbId : entry.getValue()) {
                try {
                    String collId = chromaClient.getOrCreateCollection("kb_" + kbId);
                    allVecResults.addAll(chromaClient.query(collId, embeddings.get(0), fetchSize));
                } catch (Exception e) {
                    log.warn("知识库 {} 向量检索失败: {}", kbId, e.getMessage());
                }
            }
        }

        Map<String, Float> kwResults = keywordIndex.search(query, kbIds, fetchSize);

        allVecResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        RrfMerger merger = new RrfMerger(RRF_DEFAULT_K);
        List<String> mergedIds = merger.merge(allVecResults, kwResults, topK);

        Map<String, ChromaClient.QueryResult> vecMap = new HashMap<>();
        for (ChromaClient.QueryResult r : allVecResults) {
            vecMap.put(r.getId(), r);
        }

        List<SearchResult> results = new ArrayList<>();
        for (String chunkId : mergedIds) {
            ChromaClient.QueryResult vr = vecMap.get(chunkId);
            if (vr != null) {
                SearchResult sr = new SearchResult(vr.getDocument(),
                        vr.getMetadata() != null ? vr.getMetadata().getOrDefault("file_name", "") : "",
                        vr.getScore());
                sr.setVecScore(vr.getScore());
                sr.setKwScore(kwResults.getOrDefault(chunkId, 0f).doubleValue());
                int rank = mergedIds.indexOf(chunkId) + 1;
                sr.setRrfScore(1.0 / (RRF_DEFAULT_K + rank));
                results.add(sr);
            } else {
                String text = keywordIndex.getChunkText(chunkId);
                if (text != null) {
                    double kwScore = kwResults.getOrDefault(chunkId, 0f).doubleValue();
                    SearchResult sr = new SearchResult(text, "关键词匹配", kwScore);
                    sr.setVecScore(0);
                    sr.setKwScore(kwScore);
                    int rank = mergedIds.indexOf(chunkId) + 1;
                    sr.setRrfScore(1.0 / (RRF_DEFAULT_K + rank));
                    results.add(sr);
                }
            }
        }
        // 图谱检索增强 — 结果作为独立上下文区块（不参与 RRF 混排）
        // --- 上下文窗口扩展 ---
        final int WINDOW_SIZE = 2;
        final int MAX_TOTAL_CHARS = 8000;

        Set<String> expandedIds = new LinkedHashSet<>();
        for (String chunkId : mergedIds) {
            List<String> window = keywordIndex.expandWindow(chunkId, WINDOW_SIZE);
            expandedIds.addAll(window);
        }

        List<String> sortedExpanded = new ArrayList<>(expandedIds);
        sortedExpanded.sort(Comparator.comparing(id -> {
            int i = id.lastIndexOf('_');
            if (i < 0) return id;
            return id.substring(0, i) + String.format("%010d", Integer.parseInt(id.substring(i + 1)));
        }));

        StringBuilder expandedText = new StringBuilder();
        for (String id : sortedExpanded) {
            String chunkText = keywordIndex.getChunkText(id);
            if (chunkText == null) continue;
            if (expandedText.length() + chunkText.length() + 1 > MAX_TOTAL_CHARS) break;
            if (expandedText.length() > 0) expandedText.append("\n\n");
            expandedText.append(chunkText);
        }

        if (!results.isEmpty() && expandedText.length() > 0) {
            SearchResult first = results.get(0);
            results.add(0, new SearchResult(expandedText.toString(),
                    first.getSourceFile() + "（上下文窗口）", first.getScore()));
        }

        for (String kbId : kbIds) {
            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
            if (kb == null || !Integer.valueOf(1).equals(kb.getGraphEnabled())) continue;

            List<KnowledgeDocEntity> docs = knowledgeDao.queryDocsByKbId(kbId);
            boolean graphNotReady = docs.stream().anyMatch(d -> "indexing".equals(d.getGraphStatus()));
            if (graphNotReady) {
                results.add(new SearchResult(
                        "知识库\"" + kb.getName() + "\"的图谱仍在索引中，暂不支持图谱增强检索",
                        "系统提示", 0.0));
                continue;
            }

            List<LightRagClient.GraphSearchResult> graphResults =
                    lightRagClient.search(kbId, query, "hybrid", topK);
            for (LightRagClient.GraphSearchResult gr : graphResults) {
                SearchResult sr = new SearchResult(
                        gr.getContent() != null ? gr.getContent() : "",
                        "图谱",
                        0.0);
                results.add(sr);
            }
        }
        return results;
    }

    @Deprecated // v2 两阶段流程不再使用，保留供回退参考
    @Async
    public void processDocAsync(String docId, String kbId, Path filePath) {
        try {
            KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
            if (doc == null) { log.warn("文档已删除: docId={}", docId); return; }

            long maxSize = cfg.getKnowledge().getMaxFileSizeBytes();
            if (Files.size(filePath) > maxSize) {
                knowledgeDao.updateDocStatus(docId, "error", 0, "文件过大（超过" + (maxSize / 1024 / 1024) + "MB）");
                return;
            }

            String ft = fileType(doc.getFileName());
            Path imageDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);

            ParseResult pr;
            try (InputStream in = Files.newInputStream(filePath)) {
                pr = switch (ft) {
                    case "docx" -> docxParser.parse(in, kbId, docId, imageDir);
                    case "pdf"  -> pdfParser.parse(in, kbId, docId, imageDir);
                    default     -> new ParseResult(tikaParser.parse(in), 0, null);
                };
            }

            if (!pr.isSuccess()) {
                String msg = switch (pr.getErrorType()) {
                    case PARSE_ERROR_SCANNED   -> "扫描件不支持，请使用 OCR 后重新导入";
                    case PARSE_ERROR_ENCRYPTED -> "加密文件不支持，请解密后重新导入";
                    case PARSE_ERROR_CORRUPT   -> "文件损坏或格式不支持";
                    default                    -> "解析失败: " + pr.getErrorType();
                };
                knowledgeDao.updateDocStatus(docId, "error", 0, msg);
                return;
            }

            knowledgeDao.updateDocImageCount(docId, pr.getImageCount());
            self.processDocContentAsync(docId, kbId, pr.getMarkdown(),
                    filePath.getFileName().toString().replace(docId + "_", ""), true);
        } catch (Exception e) {
            log.error("文档处理失败: docId={}", docId, e);
            knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
        }
    }

    @Async
    public void processDocContentAsync(String docId, String kbId, String text, String displayName, boolean isMarkdown) {
        try {
            KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
            if (doc == null) { log.warn("文档已被删除，取消处理: docId={}", docId); return; }

            text = textCleaner.clean(text, isMarkdown);

            TextSplitter splitter = new TextSplitter();
            List<TextSplitter.Chunk> chunks = splitter.split(text);
            List<TextSplitter.Chunk> filtered = new ArrayList<>();
            for (TextSplitter.Chunk c : chunks) {
                if (c.text.codePointCount(0, c.text.length()) >= MIN_CHUNK_CODE_POINTS)
                    filtered.add(c);
            }
            if (filtered.isEmpty()) {
                knowledgeDao.updateDocStatus(docId, "error", 0, "文档内容为空");
                return;
            }

            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
            var embedModel = modelConfigDao.queryById(kb.getEmbedModelId());
            if (embedModel == null) {
                knowledgeDao.updateDocStatus(docId, "error", 0, "Embedding 模型未配置");
                return;
            }

            String collection = "kb_" + kbId;
            String collId = chromaClient.getOrCreateCollection(collection);

            int batchSize = CHROMA_BATCH_SIZE;
            for (int bs = 0; bs < filtered.size(); bs += batchSize) {
                int be = Math.min(bs + batchSize, filtered.size());
                List<String> batchIds = new ArrayList<>();
                List<Map<String, String>> batchMetas = new ArrayList<>();
                List<String> batchTexts = new ArrayList<>();
                for (int i = bs; i < be; i++) {
                    TextSplitter.Chunk c = filtered.get(i);
                    batchIds.add(docId + "_" + i);
                    Map<String, String> meta = new HashMap<>();
                    meta.put("doc_id", docId);
                    meta.put("file_name", displayName);
                    meta.put("chunk_index", String.valueOf(i));
                    meta.put(CHUNK_META_HEADING_PATH, c.headingPath != null ? c.headingPath : "");
                    meta.put(CHUNK_META_CHUNK_TYPE, c.chunkType != null ? c.chunkType : CHUNK_TYPE_PARAGRAPH);
                    batchMetas.add(meta);
                    batchTexts.add(c.text);
                }

                List<float[]> batchEmbeddings = embeddingClient.embed(batchTexts, embedModel);
                chromaClient.add(collId, batchIds, batchEmbeddings, batchMetas, batchTexts);
                log.info("文档处理进度: docId={}, {}/{} chunks", docId, be, filtered.size());
            }

            keywordIndex.addChunks(kbId, docId, filtered.stream().map(c -> c.text).toList());
            knowledgeDao.updateDocStatus(docId, "ready", filtered.size(), null);
            log.info("文档处理完成: docId={}, chunks={}", docId, filtered.size());

            // 图谱索引（ready 之后执行，追踪状态）
            KnowledgeBaseEntity kbRef = knowledgeDao.queryKbById(kbId);
            if (kbRef != null && Integer.valueOf(1).equals(kbRef.getGraphEnabled())) {
                try {
                    knowledgeDao.updateDocGraphStatus(docId, "indexing");
                    lightRagClient.index(kbId, docId, text);
                    knowledgeDao.updateDocGraphStatus(docId, "ready");
                } catch (Exception e) {
                    log.warn("图谱索引失败: docId={}", docId, e);
                    knowledgeDao.updateDocGraphStatus(docId, "error");
                }
            }
        } catch (Exception e) {
            log.error("文档处理失败: docId={}", docId, e);
            knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
        }
    }

    // 兼容旧签名（inline doc）
    @Async
    public void processDocContentAsync(String docId, String kbId, String text, String displayName) {
        processDocContentAsync(docId, kbId, text, displayName, false);
    }

    private void deleteKbDataDir(String kbId) {
        try {
            Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
            if (Files.exists(dataDir)) {
                try (var stream = Files.walk(dataDir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) { log.warn("删除文件失败: {}", p, e); }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("清理知识库文件目录失败: kbId={}", kbId, e);
        }
    }

    private String fileType(String fileName) {
        if (fileName == null) return "unknown";
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(i + 1).toLowerCase() : "unknown";
    }

    @Override
    @Transactional
    public void toggleKbScope(String id, String scope, String userName) {
        checkSharedManage();
        knowledgeDao.updateKbScope(id, scope);
    }

    @Override
    public void onApplicationEvent(ScriptInitialDoneEvent event) {
        self.rebuildKeywordIndexFromChroma();
    }

    @Async
    public void rebuildKeywordIndexFromChroma() {
        try {
            List<String> collNames = chromaClient.listCollections();
            for (String name : collNames) {
                if (!name.startsWith("kb_")) continue;
                String kbId = name.substring(3);
                String collId = chromaClient.getOrCreateCollection(name);
                Map<String, List<String>> docChunks = new LinkedHashMap<>();
                int offset = 0;
                int page = 200;
                while (true) {
                    List<ChromaClient.QueryResult> page_ = chromaClient.getByMetadata(collId, Map.of(), page, offset);
                    if (page_.isEmpty()) break;
                    for (ChromaClient.QueryResult r : page_) {
                        String cid = r.getId();
                        if (cid == null) continue;
                        int li = cid.lastIndexOf('_');
                        String docId = li > 0 ? cid.substring(0, li) : cid;
                        docChunks.computeIfAbsent(docId, k -> new ArrayList<>())
                                .add(r.getDocument() != null ? r.getDocument() : "");
                    }
                    offset += page_.size();
                }
                for (var e : docChunks.entrySet()) {
                    keywordIndex.addChunks(kbId, e.getKey(), e.getValue());
                }
                log.info("关键词索引重建完成: kbId={}, 文档数={}, collection={}", kbId, docChunks.size(), name);
            }
        } catch (Exception e) {
            log.warn("启动时重建关键词索引失败: {}", e.getMessage());
        }
    }
}
