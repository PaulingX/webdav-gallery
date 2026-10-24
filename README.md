# WebDAV 媒体库 (webdav-gallery)

基于 Spring Boot 3 + MyBatis Plus + H2 的 WebDAV 媒体浏览器：后台可添加多个 WebDAV 源，网页端可浏览目录、预览图片、在线播放视频，页面自适应手机浏览，支持 Docker 一键部署。

## 功能

- **登录鉴权**：账号密码配置在 `application.yml`（`app.auth`），未登录无法访问任何页面和接口
- **WebDAV 源管理**：添加 / 编辑 / 删除 / 连接测试（保存前自动测试连接，密码 AES 加密存储）
- **资源数据库缓存**：添加源后自动全量同步资源树到 H2，目录浏览/搜索/封面直接查库；管理源里可「更新资源」手动刷新；未同步过的目录首次访问时自动按需拉取
- **目录浏览**：面包屑导航、悬浮返回按钮（返回后恢复原滚动位置）、文件夹封面（目录内第一张图片，无图时递归子目录）
- **图片预览**：缩略图网格；点击进入拼接长图查看器，上下滑动连续浏览，滚动到附近才加载（懒加载）
- **视频播放**：在线播放，支持拖动进度条（HTTP Range / 206 分段传输）；同目录同名图片自动作为视频封面且不再重复展示
- **搜索与排序**：根目录提供递归搜索与名称/时间正反排序；子目录固定按名称自然排序（1、2、10）
- **移动端**：响应式布局，手机浏览器直接访问使用

## Docker 部署

### 方式一：快速构建（推荐，约 20 秒）

本机有 JDK17 + Maven 时，先用本地 jar 构建（只拉取约 60MB 的 alpine JRE 镜像）：

```bash
mvn -B -DskipTests package
docker compose up -d --build
```

### 方式二：多阶段构建（无需本地 JDK/Maven）

会拉取约 500MB 的 maven 基础镜像并在容器内编译，首次较慢：

```bash
DOCKERFILE=Dockerfile docker compose up -d --build
```

### 自定义端口与登录账号密码

compose 支持环境变量（命令行传入，或复制 `.env.example` 为 `.env` 修改后直接 `docker compose up -d --build`）：

```bash
APP_PORT=9090 APP_AUTH_USERNAME=myuser APP_AUTH_PASSWORD=mypass docker compose up -d --build
```

| 变量 | 默认值 | 说明 |
|---|---|---|
| `APP_PORT` | `8080` | 宿主机访问端口 |
| `APP_AUTH_USERNAME` | `admin` | 登录用户名 |
| `APP_AUTH_PASSWORD` | `admin123` | 登录密码 |
| `DOCKERFILE` | `Dockerfile.fast` | 构建用 Dockerfile（`Dockerfile` 为多阶段） |

访问 `http://<服务器IP>:$APP_PORT`，手机浏览器访问同一地址即可。H2 数据持久化在宿主机 `./data/`；WebDAV 服务器若跑在宿主机上，源地址填 `http://host.docker.internal:端口/路径`。

## 本地开发

```bash
mvn spring-boot:run
```

访问 `http://localhost:8080`。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `H2_DATA_DIR` | `./data` | H2 数据文件目录 |
| `JAVA_OPTS` | 空 | JVM 参数（Docker 镜像内，如 `-Xmx512m`） |
| `APP_AUTH_USERNAME` | `admin` | 登录用户名（覆盖 yml） |
| `APP_AUTH_PASSWORD` | `admin123` | 登录密码（覆盖 yml） |

- 登录账号密码优先级：环境变量 `APP_AUTH_USERNAME/PASSWORD` > `application.yml` 的 `app.auth`
- WebDAV 密码的 AES 加密密钥在 `webdav.secret`，生产环境建议修改

## 已知边界

- 浏览器原生 `<video>` 仅支持 MP4/WebM 等格式，MKV/AVI 可列出但可能无法播放
- 接口未做登录鉴权，建议仅在可信局域网内使用
- 只读浏览，不提供上传 / 删除远端文件功能
