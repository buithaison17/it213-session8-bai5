package com.example.bai5;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class MultiTenantRAGService {
    private static final Logger log = LoggerFactory.getLogger(MultiTenantRAGService.class);
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public MultiTenantRAGService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Thực hiện truy vấn RAG có phân tách Tenant qua Metadata Filter
     */
    public String chatWithTenantDocs(String query, String tenantId) {
        log.info("[Multi-tenant RAG] Nhận truy vấn từ Tenant: [{}] | Query: '{}'", tenantId, query);

        // 1. Tạo Metadata Filter Expression bắt buộc theo tenant_id
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var tenantFilter = b.eq("tenant_id", tenantId).build();

        // 2. Tạo SearchRequest với filter và các tham số tối ưu
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(0.6)
                .filterExpression(tenantFilter)
                .build();

        // 3. Truy vấn các Documents tương đồng chỉ thuộc về Tenant hiện tại
        List<Document> matchedDocs = vectorStore.similaritySearch(searchRequest);

        log.info("[Multi-tenant RAG] Tìm thấy {} tài liệu phù hợp cho Tenant [{}]", matchedDocs.size(), tenantId);
        for (int i = 0; i < matchedDocs.size(); i++) {
            Document doc = matchedDocs.get(i);
            log.info(" --> Doc [{}]: id={}, tenant_id={}",
                    i + 1,
                    doc.getId(),
                    doc.getMetadata().get("tenant_id"));
        }

        if (matchedDocs.isEmpty()) {
            return "Không tìm thấy tài liệu phù hợp trong cơ sở dữ liệu của doanh nghiệp bạn.";
        }

        // 4. Đóng gói Context
        String context = matchedDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 5. Gửi Prompt tới LLM kèm Context đã được phân tách
        return chatClient.prompt()
                .system("Bạn là trợ lý AI nội bộ của doanh nghiệp. Chỉ sử dụng thông tin trong ngữ cảnh được cung cấp để trả lời. Không bịa đặt thông tin.")
                .user(u -> u.text("""
                                Ngữ cảnh tài liệu:
                                {context}
                                
                                Câu hỏi của nhân viên:
                                {question}
                                """)
                        .param("context", context)
                        .param("question", query))
                .call()
                .content();
    }
}