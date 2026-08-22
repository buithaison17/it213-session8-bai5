# Kiến Trúc RAG Đa Doanh Nghiệp (Multi-Tenant) & Tối Ưu Chỉ Mục HNSW

## 1. Tổng Quan & Bài Toán Đặt Ra

Trong các hệ thống phần mềm dạng dịch vụ (**SaaS - Software as a Service**), nền tảng CRM thường phục vụ hàng trăm đến hàng nghìn doanh nghiệp (Tenants) trên cùng một hạ tầng cơ sở dữ liệu và ứng dụng chia sẻ (Shared Infrastructure / Multi-tenant Architecture).

Khi tích hợp công nghệ **RAG (Retrieval-Augmented Generation)** để giải đáp thắc mắc nội bộ dựa trên tài liệu doanh nghiệp, hai thách thức cốt lõi nảy sinh:
1. **Bảo mật & Phân tách dữ liệu tuyệt đối (Data Isolation):** Tuyệt đối không để nhân viên của Tenant A đọc hoặc nhận được câu trả lời từ tài liệu thuộc Tenant B (tránh rò rỉ bí mật kinh doanh, thông tin nhạy cảm).
2. **Hiệu năng truy vấn quy mô lớn (Scalability & Low Latency):** Khi số lượng vector chunks đạt hàng triệu bản ghi, việc quét tuần tự (Sequential Scan) hoặc tìm kiếm vector không có chỉ mục tối ưu sẽ gây thắt cổ chai hiệu năng, tăng thời gian phản hồi (TTFT) của mô hình LLM.

---

## 2. Sơ Đồ Luồng Truy Vấn RAG Đa Tenant (ASCII Flowchart)

```text
+-----------------------------------------------------------------------------------------+
|                                    CLIENT / FRONTEND                                    |
|                                                                                         |
|   +---------------------------------------+   +---------------------------------------+ |
|   |         Tenant A Client Request       |   |         Tenant B Client Request       | |
|   |  - Header: X-Tenant-ID: "tenant-alpha"|   |  - Header: X-Tenant-ID: "tenant-beta" | |
|   |  - Body: {"message": "..."}           |   |  - Body: {"message": "..."}           | |
|   +-------------------+-------------------+   +-------------------+-------------------+ |
+-----------------------|-------------------------------------------|---------------------+
                        |                                           |
                        +---------------------+---------------------+
                                              | HTTP POST /api/v1/crm/tenant-chat
                                              v
+-----------------------------------------------------------------------------------------+
|                                  SPRING BOOT APPLICATION                                |
|                                                                                         |
|   1. API Controller / Security Filter:                                                  |
|      - Trích xuất & Xác thực `X-Tenant-ID` từ Request Header.                            |
|      - Gán Security Context (Tránh giả mạo Header).                                     |
|                                                                                         |
|   2. MultiTenantRAGService:                                                             |
|      - Xây dựng `FilterExpression`: metadata['tenant_id'] == current_tenant_id           |
|      - Khởi tạo `SearchRequest` kèm Query Vector + Hard Metadata Filter.                |
|                                                                                         |
|   3. Spring AI PgVectorStore Client:                                                    |
|      - Sinh Embedding vector từ câu hỏi (OpenAI text-embedding-3-small).                |
|      - Đóng gói truy vấn SQL kết hợp Cosine Distance + JSONB Filter.                    |
+---------------------------------------------|-------------------------------------------+
                                              | SQL Query + Vector + Filter
                                              v
+-----------------------------------------------------------------------------------------+
|                           SUPABASE / POSTGRESQL (pgvector)                              |
|                                                                                         |
|   +---------------------------------------------------------------------------------+   |
|   |  Bảng `vector_store`:                                                           |   |
|   |  - content: Nội dung chunk văn bản                                              |   |
|   |  - embedding: vector(1536)                                                      |   |
|   |  - metadata: {"tenant_id": "tenant-alpha", "doc_type": "crm_policy", ...}       |   |
|   +---------------------------------------------------------------------------------+   |
|                                             |                                           |
|       +-------------------------------------+---------------------------------+         |
|       |                                                                       |         |
|       v                                                                       v         |
|   [ HNSW Index Lookup ]                                            [ GIN Metadata Filter ]|
|   - Thuật toán đồ thị ANN                                          - metadata->>'tenant_id'|
|   - Toán tử `vector_cosine_ops`                                    - Chỉ quét chunks     |
|   - Độ phức tạp O(log N)                                             thuộc đúng Tenant  |
|       |                                                                       |         |
|       +-------------------------------------+---------------------------------+         |
|                                             |                                           |
|                                             v                                           |
|                   [ Filtered Relevant Context Chunks (Top-K) ]                          |
+---------------------------------------------|-------------------------------------------+
                                              | Top-K Chunks chỉ thuộc Tenant hiện tại
                                              v
+-----------------------------------------------------------------------------------------+
|                                     LLM GENERATION                                      |
|                                                                                         |
|   Grounding Prompt Builder:                                                             |
|   - System: "Bạn là trợ lý AI nội bộ. Chỉ sử dụng thông tin trong context được cấp..."  |
|   - User: Context (Chunks Tenant) + User Question                                       |
|                                                                                         |
|   LLM (OpenAI GPT-4o / gpt-4o-mini):                                                    |
|   - Sinh câu trả lời bảo mật, độc lập dữ liệu giữa các bên.                             |
+-----------------------------------------------------------------------------------------+
```

---

## 3. Chi Tiết Giải Pháp Phân Tách Tenant (Multi-Tenant Isolation)

### 3.1. Mô Hình Phân Vùng Dữ Liệu: Logical Isolation (Metadata-based)
Hệ thống sử dụng mô hình **Shared Database, Shared Schema** kết hợp **Metadata Tagging**:
* **Lúc Ingestion (Nạp dữ liệu):** Khi tài liệu CRM của doanh nghiệp được chunking và nạp vào cơ sở dữ liệu vector, toàn bộ chunks được gắn cứng metadata:
  ```json
  {
    "tenant_id": "tenant-alpha",
    "document_id": "crm_policy_2026_v1",
    "department": "sales"
  }
  ```
* **Lúc Retrieval (Truy vấn):** API Service ép buộc áp dụng `FilterExpressionBuilder` ở tầng backend code, không phụ thuộc vào input từ client:
  ```java
  FilterExpressionBuilder b = new FilterExpressionBuilder();
  Filter.Expression tenantFilter = b.eq("tenant_id", tenantId).build();
  ```
* **Ưu điểm:**
  - Tối ưu chi phí hạ tầng (không phải tạo database/schema riêng cho từng tenant).
  - Dễ dàng quản lý pool kết nối (Connection Pooling) và cập nhật migration tự động.
  - Tích hợp tự nhiên với các cơ chế lọc metadata của Spring AI `VectorStore`.

### 3.2. Phòng Chống Rò Rỉ Dữ Liệu Chéo (Cross-Tenant Leakage Prevention)
1. **Xác thực Header ở tầng Gateway/Filter:** Request header `X-Tenant-ID` phải được trích xuất từ JWT Token / Session của người dùng đăng nhập thay vì tin tưởng trực tiếp giá trị client truyền lên.
2. **Server-Enforced Filtering:** Điều kiện `metadata['tenant_id'] = tenantId` là bắt buộc trong mọi lời gọi similarity search. Nếu `tenantId` rỗng hoặc không hợp lệ, hệ thống từ chối thực hiện truy vấn ngay từ đầu.
3. **Prompt Grounding Nghiêm Ngặt:** System Prompt yêu cầu LLM không tự ý suy đoán nếu Context trả về rỗng, đảm bảo khi không tìm thấy tài liệu phù hợp của tenant đó, AI sẽ báo không có thông tin thay vì lấy tri thức chung ngoài phạm vi.

---

## 4. Giải Pháp Tối Ưu Chỉ Mục HNSW Trên Supabase pgvector

### 4.1. HNSW (Hierarchical Navigable Small World) là gì?
HNSW là thuật toán tìm kiếm láng giềng gần nhất xấp xỉ (**Approximate Nearest Neighbor - ANN**) dựa trên đồ thị đa tầng (multi-layer graph). So với chỉ mục truyền thống `IVFFlat` hoặc quét tuần tự (`Seq Scan`):
* **Tốc độ truy vấn ($O(\log N)$):** Nhanh hơn gấp nhiều lần khi số lượng vector đạt hàng triệu chunks.
* **Không cần Train trước:** HNSW có thể thêm mới vector liên tục mà không cần tính toán lại các cụm (clusters) như IVFFlat.
* **Độ chính xác (Recall):** Đạt trên 95-99% với các tham số cấu hình phù hợp.

### 4.2. Các Tham Số Cấu Hình Chuẩn
* `vector_cosine_ops`: Phép đo khoảng cách góc Cosine Distance ($1 - \cos(	heta)$), phù hợp nhất với các embedding chuẩn hóa đơn vị như OpenAI `text-embedding-3-small`.
* `m = 16`: Số lượng cạnh kết nối tối đa cho mỗi node trên đồ thị. Giá trị từ 16 đến 32 là lý tưởng cho hệ thống CRM cân bằng giữa bộ nhớ RAM và độ chính xác.
* `ef_construction = 64`: Kích thước danh sách ứng viên được đánh giá trong quá trình xây dựng chỉ mục. Giá trị này đảm bảo chất lượng đồ thị cao mà không làm chậm quá mức tốc độ index tài liệu mới.
* `GIN Index trên metadata`: Đẩy nhanh tốc độ lọc điều kiện JSONB `metadata->>'tenant_id'` song song với tìm kiếm vector.

---

## 5. Kết Luận

Giải pháp kết hợp **Metadata Filtering** của Spring AI với **HNSW Index** trên Supabase pgvector mang lại:
- **Bảo mật cấp doanh nghiệp:** Dữ liệu từng tenant được cô lập logic hoàn toàn, không có nguy cơ lẫn lộn ngữ cảnh.
- **Hiệu năng cao & Chi phí tối ưu:** Tận dụng triệt để sức mạnh index đồ thị đa tầng và hạ tầng cơ sở dữ liệu dùng chung.