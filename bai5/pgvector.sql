-- 1. Bật extension pgvector
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. Tạo bảng vector_store tương thích với Spring AI PgVectorStore
CREATE TABLE IF NOT EXISTS vector_store (
                                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1536)
    );

-- 3. Tạo chỉ mục HNSW cho vector embedding sử dụng Cosine Distance
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_hnsw
    ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 4. Tạo chỉ mục GIN trên metadata JSONB để tăng tốc độ lọc tenant_id
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_gin
    ON vector_store
    USING gin (metadata);