CREATE TABLE IF NOT EXISTS webdav_source (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    server_url   VARCHAR(500)  NOT NULL,
    username     VARCHAR(200),
    password     VARCHAR(500),
    enabled      BOOLEAN DEFAULT TRUE,
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 资源目录缓存：整棵资源树入库，浏览/搜索/封面直接查库
CREATE TABLE IF NOT EXISTS webdav_resource (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_id     BIGINT        NOT NULL,
    parent_path   VARCHAR(1000) NOT NULL,
    name          VARCHAR(500)  NOT NULL,
    path          VARCHAR(1500) NOT NULL,
    is_dir        BOOLEAN       NOT NULL DEFAULT FALSE,
    size          BIGINT        DEFAULT 0,
    last_modified BIGINT        DEFAULT 0,
    synced        BOOLEAN       DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_res_source_parent ON webdav_resource (source_id, parent_path);
CREATE INDEX IF NOT EXISTS idx_res_source_path ON webdav_resource (source_id, path);

-- 旧库迁移：源表补充同步状态字段（幂等）
ALTER TABLE webdav_source ADD COLUMN IF NOT EXISTS sync_status INT DEFAULT 0;
ALTER TABLE webdav_source ADD COLUMN IF NOT EXISTS sync_time DATETIME;
ALTER TABLE webdav_source ADD COLUMN IF NOT EXISTS sync_message VARCHAR(500);
