package com.github.hbq969.ai.zephyr.knowledge.ctrl;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.constant.ZephyrConstants;
import com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Tag(name = "知识库管理")
@RestController
@RequestMapping(path = "/zephyr-ui/knowledge")
public class KnowledgeCtrl {

    @Resource
    private KnowledgeService knowledgeService;

    @Resource
    private KnowledgeDao knowledgeDao;

    @Resource
    private ZephyrConfigProperties cfg;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    private boolean isAdminInternal() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null && ui.isAdmin();
    }

    @Operation(summary = "知识库列表")
    @RequestMapping(path = "/kb/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_list", apiDesc = "知识库管理_知识库列表")
    public ReturnMessage<?> listKb() {
        return ReturnMessage.success(knowledgeService.listKb(userName()));
    }

    @Operation(summary = "新建知识库")
    @RequestMapping(path = "/kb/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_create", apiDesc = "知识库管理_新建知识库")
    public ReturnMessage<?> createKb(@RequestBody Map<String, Object> body) {
        return ReturnMessage.success(knowledgeService.createKb(body, userName()));
    }

    @Operation(summary = "修改知识库")
    @RequestMapping(path = "/kb/update", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_update", apiDesc = "知识库管理_修改知识库")
    public ReturnMessage<?> updateKb(@RequestBody Map<String, Object> body) {
        knowledgeService.updateKb(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除知识库")
    @RequestMapping(path = "/kb/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_delete", apiDesc = "知识库管理_删除知识库")
    public ReturnMessage<?> deleteKb(@RequestBody Map<String, String> body) {
        knowledgeService.deleteKb(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "文档列表")
    @RequestMapping(path = "/doc/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_list", apiDesc = "知识库管理_文档列表")
    public ReturnMessage<?> listDocs(@RequestParam String kbId) {
        return ReturnMessage.success(knowledgeService.listDocs(kbId));
    }

    @Operation(summary = "删除文档")
    @RequestMapping(path = "/doc/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_delete", apiDesc = "知识库管理_删除文档")
    public ReturnMessage<?> deleteDoc(@RequestBody Map<String, String> body) {
        knowledgeService.deleteDoc(body.get("id"));
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "对话关联知识库列表")
    @RequestMapping(path = "/conversation/kb/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_conversation_kb_list", apiDesc = "知识库管理_对话关联知识库列表")
    public ReturnMessage<?> getConversationKbs(@RequestParam String conversationId) {
        return ReturnMessage.success(knowledgeService.getConversationKbIds(conversationId));
    }

    @Operation(summary = "保存对话关联知识库")
    @RequestMapping(path = "/conversation/kb/save", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_conversation_kb_save", apiDesc = "知识库管理_保存对话关联知识库")
    public ReturnMessage<?> saveConversationKbs(@RequestBody Map<String, Object> body) {
        String conversationId = (String) body.get("conversationId");
        @SuppressWarnings("unchecked")
        List<String> kbIds = (List<String>) body.get("kbIds");
        knowledgeService.saveConversationKbIds(conversationId, kbIds);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "上传文档")
    @RequestMapping(path = "/doc/upload", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_upload", apiDesc = "知识库管理_上传文档")
    public ReturnMessage<?> uploadDoc(@RequestParam("file") MultipartFile file, @RequestParam String kbId) {
        return ReturnMessage.success(knowledgeService.uploadDoc(kbId, file, userName()));
    }

    @Operation(summary = "确认导入文档")
    @RequestMapping(path = "/doc/confirm-import", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_confirm_import", apiDesc = "知识库管理_确认导入文档")
    public ReturnMessage<?> confirmImport(@RequestBody Map<String, Object> body) {
        String docId = (String) body.get("docId");
        String kbId = (String) body.get("kbId");
        int headingLevel = body.containsKey("headingLevel") ? ((Number) body.get("headingLevel")).intValue() : 0;
        String markdownContent = (String) body.get("markdownContent");
        knowledgeService.confirmImport(docId, kbId, headingLevel, markdownContent, userName());
        return ReturnMessage.success(Map.of("docId", docId));
    }

    @Operation(summary = "下载转换后的 Markdown")
    @RequestMapping(path = "/doc/{docId}/markdown/download", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_markdown_download", apiDesc = "知识库管理_下载Markdown")
    public void downloadMarkdown(@PathVariable String docId, @RequestParam String kbId,
                                  HttpServletResponse response) throws IOException {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null || !kbId.equals(doc.getKbId())) { response.sendError(404); return; }
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null) { response.sendError(404); return; }
        if (!ZephyrConstants.SCOPE_SHARED.equals(kb.getScope()) && !isAdminInternal() && !userName().equals(kb.getUserName())) {
            response.sendError(403); return;
        }

        String mdName = doc.getFileName().replaceFirst("\\.[^.]+$", "") + ".md";
        Path kbDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId).normalize();
        Path mdPath = kbDir.resolve(docId + "_" + mdName).normalize();
        if (!mdPath.startsWith(kbDir)) { response.sendError(400); return; }
        if (!Files.exists(mdPath)) {
            mdPath = kbDir.resolve(docId + "_" + doc.getFileName()).normalize();
            if (!mdPath.startsWith(kbDir)) { response.sendError(400); return; }
        }
        if (!Files.exists(mdPath)) { response.sendError(404); return; }
        response.setContentType("text/markdown; charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" +
                java.net.URLEncoder.encode(mdName, "UTF-8").replace("+", "%20") + "\"");
        Files.copy(mdPath, response.getOutputStream());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ReturnMessage<?> handleRuntimeException(RuntimeException e) {
        return ReturnMessage.fail(e.getMessage());
    }

    @Operation(summary = "重新解析文档")
    @RequestMapping(path = "/doc/re-parse", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_reparse", apiDesc = "知识库管理_重新解析")
    public ReturnMessage<?> reParseDoc(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(knowledgeService.reParseDoc(body.get("id"), body.get("kbId"), userName()));
    }

    @Operation(summary = "创建内联文档")
    @RequestMapping(path = "/doc/create-inline", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_create_inline", apiDesc = "知识库管理_创建内联文档")
    public ReturnMessage<?> createInlineDoc(@RequestBody Map<String, String> body) {
        String docId = knowledgeService.createInlineDoc(
                body.get("kbId"), body.get("title"), body.get("content"), userName());
        return ReturnMessage.success(Map.of("docId", docId));
    }

    @Operation(summary = "更新内联文档")
    @RequestMapping(path = "/doc/update-inline", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_update_inline", apiDesc = "知识库管理_更新内联文档")
    public ReturnMessage<?> updateInlineDoc(@RequestBody Map<String, String> body) {
        knowledgeService.updateInlineDoc(body.get("id"), body.get("title"), body.get("content"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "召回测试")
    @RequestMapping(path = "/kb/{kbId}/recall-test", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_recall_test", apiDesc = "知识库管理_召回测试")
    public ReturnMessage<?> recallTest(@PathVariable String kbId, @RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;
        return ReturnMessage.success(knowledgeService.search(query, List.of(kbId), topK));
    }

    @Operation(summary = "切换知识库共享状态（仅admin）")
    @RequestMapping(path = "/kb/scope/toggle", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_toggleScope", apiDesc = "知识库管理_切换共享状态")
    public ReturnMessage<?> toggleKbScope(@RequestBody Map<String, String> body) {
        knowledgeService.toggleKbScope(body.get("id"), body.get("scope"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "获取知识库文档图片")
    @RequestMapping(path = "/image", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_image", apiDesc = "知识库管理_文档图片")
    public void image(@RequestParam String kbId, @RequestParam String docId,
                      @RequestParam String file, HttpServletResponse response) throws IOException {
        // 1. 校验 kbId 权限
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null) { response.sendError(404); return; }
        String un = userName();
        if (!ZephyrConstants.SCOPE_SHARED.equals(kb.getScope())) {
            if (!isAdminInternal() && !un.equals(kb.getUserName())) {
                response.sendError(403); return;
            }
        }

        // 2. 校验 docId 归属
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null || !kbId.equals(doc.getKbId())) { response.sendError(403); return; }

        // 3. 路径遍历防护
        String safeName = Path.of(file).getFileName().toString();
        if (!safeName.equals(file)) { response.sendError(400); return; }
        Path imgDir = Paths.get(cfg.getKnowledge().getImageBaseDir(), kbId, docId);
        Path resolved = imgDir.resolve(safeName).normalize();
        if (!resolved.startsWith(imgDir.normalize())) { response.sendError(400); return; }
        if (!Files.exists(resolved)) { response.sendError(404); return; }

        // 4. Content-Type 校验
        String mimeType = Files.probeContentType(resolved);
        if (mimeType == null || !mimeType.startsWith("image/")) { response.sendError(415); return; }
        response.setContentType(mimeType);
        Files.copy(resolved, response.getOutputStream());
    }
}
