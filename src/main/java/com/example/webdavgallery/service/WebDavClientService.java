package com.example.webdavgallery.service;

import com.example.webdavgallery.dto.FileInfo;
import com.example.webdavgallery.dto.PageResult;
import com.example.webdavgallery.entity.WebDavResource;
import com.example.webdavgallery.entity.WebDavSource;
import com.example.webdavgallery.exception.BusinessException;
import com.example.webdavgallery.mapper.WebDavSourceMapper;
import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.SardineImpl;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebDAV 客户端 + 资源缓存服务。
 * 目录列表/搜索/封面全部读数据库缓存（webdav_resource），
 * 只在目录首次访问（懒同步）、手动全量同步和取文件内容时访问 WebDAV。
 */
@Service
public class WebDavClientService {

    private static final Logger log = LoggerFactory.getLogger(WebDavClientService.class);

    private static final Set<String> IMAGE_EXT =
            Set.of("jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp", "svg", "avif", "ico");
    private static final Set<String> VIDEO_EXT =
            Set.of("mp4", "webm", "m4v", "mov", "mkv", "avi", "wmv", "flv", "ts", "ogv", "3gp", "mpg", "mpeg", "m2ts", "mts");

    private static final int SEARCH_LIMIT = 300;
    /** 懒同步/封面递归的最大目录深度 */
    private static final int MAX_DEPTH = 8;
    /** 全量同步的最大目录深度 */
    private static final int MAX_SYNC_DEPTH = 12;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Sardine 客户端复用缓存（内含连接池），key = 源id|地址|账号|密码 */
    private final Map<String, Sardine> sardineCache = new ConcurrentHashMap<>();

    /** 正在全量同步中的源，防止重复触发 */
    private final Set<Long> syncingSources = ConcurrentHashMap.newKeySet();

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webdav-sync");
        t.setDaemon(true);
        return t;
    });

    private final WebDavResourceService resourceService;
    private final WebDavSourceMapper sourceMapper;
    private final WebDavCrypto crypto;

    public WebDavClientService(WebDavResourceService resourceService,
                               WebDavSourceMapper sourceMapper,
                               WebDavCrypto crypto) {
        this.resourceService = resourceService;
        this.sourceMapper = sourceMapper;
        this.crypto = crypto;
    }

    /* ---------------- 目录列表（读库 + 懒同步） ---------------- */

    /**
     * 列出目录（读库 + 懒同步），排序与分页在后端完成（前端无限滚动）。
     * sort: name_asc/name_desc/time_asc/time_desc；目录始终排在文件前。
     */
    public PageResult<FileInfo> list(WebDavSource source, String rawPath, String sort, int offset, int limit) {
        String dirPath = normalizePath(rawPath);
        ensureDirSynced(source, dirPath);
        List<WebDavResource> rows = resourceService.findByParent(source.getId(), dirPath);
        List<FileInfo> files = new ArrayList<>(rows.size());
        for (WebDavResource row : rows) {
            files.add(toFileInfo(row));
        }
        Comparator<FileInfo> cmp = comparatorOf(sort);
        files.sort(Comparator.comparing((FileInfo f) -> !f.isDir()).thenComparing(cmp));
        int from = Math.max(0, offset);
        int to = Math.min(files.size(), from + Math.max(1, limit));
        List<FileInfo> page = from < files.size() ? files.subList(from, to) : List.of();
        return new PageResult<>(page, to < files.size(), files.size());
    }

    private Comparator<FileInfo> comparatorOf(String sort) {
        boolean desc = sort != null && sort.endsWith("desc");
        Comparator<FileInfo> base = sort != null && sort.startsWith("time")
                ? Comparator.comparingLong(FileInfo::getLastModified)
                : Comparator.comparing(f -> f.getName().toLowerCase(), WebDavClientService::naturalCompare);
        return desc ? base.reversed() : base;
    }

    /** 名称自然排序比较：数字段按数值（1 < 2 < 10），其余按字符（忽略大小写） */
    static int naturalCompare(String a, String b) {
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia, jb = ib;
                while (ja < a.length() && Character.isDigit(a.charAt(ja))) ja++;
                while (jb < b.length() && Character.isDigit(b.charAt(jb))) jb++;
                java.math.BigInteger na = new java.math.BigInteger(a.substring(ia, ja));
                java.math.BigInteger nb = new java.math.BigInteger(b.substring(ib, jb));
                int c = na.compareTo(nb);
                if (c != 0) return c;
                ia = ja; ib = jb;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                ia++; ib++;
            }
        }
        return Integer.compare(a.length() - ia, b.length() - ib);
    }

    /** 目录未同步（或全量同步未覆盖到）时实时拉取一次该目录并入库 */
    private void ensureDirSynced(WebDavSource source, String dirPath) {
        // 全量同步进行中时只用现有缓存，避免与后台任务互相干扰
        if (source.getSyncStatus() != null && source.getSyncStatus() == 1) {
            return;
        }
        WebDavResource dirRow = resourceService.findByPath(source.getId(), dirPath);
        if (dirRow != null && Boolean.TRUE.equals(dirRow.getSynced())) {
            return;
        }
        String url = buildUrl(source.getServerUrl(), dirPath, true);
        String expectSelf = stripTrailingSlash(serverBasePath(source.getServerUrl()) + dirPath);
        Sardine sardine = beginSardine(source);
        try {
            List<DavResource> resources = sardine.list(url, 1);
            DavResource self = findSelf(resources, expectSelf);
            upsertDirRow(source.getId(), dirPath, self, true);
            resourceService.deleteByParent(source.getId(), dirPath);
            List<WebDavResource> children = new ArrayList<>();
            for (DavResource res : resources) {
                String hrefPath = hrefPathOf(res);
                if (hrefPath != null && hrefPath.equals(expectSelf)) {
                    continue;
                }
                String name = res.getName();
                if (name == null || name.isBlank()) {
                    name = lastSegment(hrefPath);
                }
                if (name == null || name.isBlank()) {
                    continue;
                }
                children.add(buildRow(source.getId(), dirPath, name, res.isDirectory(),
                        res.getContentLength(), res.getModified(), false));
            }
            if (!children.isEmpty()) {
                resourceService.saveBatch(children);
            }
            log.debug("懒同步目录: {} {}", source.getName(), dirPath);
        } catch (SardineException e) {
            log.warn("懒同步失败: {} {} -> HTTP {}", source.getName(), dirPath, e.getStatusCode());
            throw new BusinessException("列目录失败 (HTTP " + e.getStatusCode() + ")，请检查路径与账号权限");
        } catch (IOException e) {
            log.warn("懒同步连接失败: {} {}", source.getName(), e.getMessage());
            throw new BusinessException("连接 WebDAV 服务器失败: " + rootMessage(e));
        }
    }

    /* ---------------- 封面（读库递归） ---------------- */

    /**
     * 返回目录的封面图片完整路径：优先取目录内第一张图片（名称排序）；
     * 无直属图片时递归进入第一个子文件夹查找（深度限制），找不到返回 null。
     */
    public String firstImagePath(WebDavSource source, String rawPath) {
        return findFirstImage(source, rawPath, 0);
    }

    private String findFirstImage(WebDavSource source, String path, int depth) {
        if (depth > 4) {
            return null;
        }
        List<FileInfo> files;
        try {
            files = list(source, path, "name_asc", 0, Integer.MAX_VALUE).getItems();
        } catch (RuntimeException e) {
            return null;
        }
        for (FileInfo f : files) {
            if (!f.isDir() && "image".equals(f.getMediaType())) {
                return f.getPath();
            }
        }
        for (FileInfo f : files) {
            if (f.isDir()) {
                String found = findFirstImage(source, f.getPath(), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /* ---------------- 搜索（读库） ---------------- */

    /** 在 rootPath 子树内按文件名关键字搜索（不区分大小写），直接查数据库 */
    public List<FileInfo> search(WebDavSource source, String rootPath, String keyword) {
        String root = normalizePath(rootPath);
        String prefix = root.equals("/") ? "/" : root + "/";
        String escapedPrefix = escapeLike(root.equals("/") ? "/" : prefix);
        String escapedKw = escapeLike(keyword.toLowerCase());
        List<WebDavResource> rows = resourceService.lambdaQuery()
                .eq(WebDavResource::getSourceId, source.getId())
                .and(w -> w.eq(WebDavResource::getPath, root)
                        .or().apply("path LIKE {0} ESCAPE '|'", escapedPrefix + "%"))
                .apply("LOWER(name) LIKE {0} ESCAPE '|'", "%" + escapedKw + "%")
                .last("LIMIT " + SEARCH_LIMIT)
                .list();
        List<FileInfo> files = new ArrayList<>(rows.size());
        for (WebDavResource row : rows) {
            if (row.getPath().equals(root) && Boolean.TRUE.equals(row.getIsDir())) {
                continue; // 根目录自身不作为结果
            }
            files.add(toFileInfo(row));
        }
        files.sort(Comparator.comparing((FileInfo f) -> !f.isDir())
                .thenComparing(f -> f.getName().toLowerCase()));
        return files;
    }

    private String escapeLike(String s) {
        return s.replace("|", "||").replace("%", "|%").replace("_", "|_");
    }

    /* ---------------- 全量同步（后台任务） ---------------- */

    /** 触发全量同步：后台线程遍历整棵资源树入库。若已在同步中则忽略 */
    public void syncSourceAsync(Long sourceId) {
        if (!syncingSources.add(sourceId)) {
            return;
        }
        WebDavSource src = sourceMapper.selectById(sourceId);
        if (src == null) {
            syncingSources.remove(sourceId);
            throw new BusinessException("WebDAV 源不存在");
        }
        WebDavSource plain = new WebDavSource();
        plain.setId(src.getId());
        plain.setName(src.getName());
        plain.setServerUrl(src.getServerUrl());
        plain.setUsername(src.getUsername());
        plain.setPassword(crypto.decrypt(src.getPassword()));
        plain.setSyncStatus(src.getSyncStatus());

        updateSyncStatus(sourceId, 1, null);
        syncExecutor.submit(() -> {
            try {
                resourceService.deleteBySource(sourceId);
                walk(plain, "/", 0);
                updateSyncStatus(sourceId, 2, null);
                log.info("全量同步完成: {} ({})", plain.getName(), sourceId);
            } catch (Exception e) {
                log.error("全量同步失败: {} ({})", plain.getName(), sourceId, e);
                updateSyncStatus(sourceId, 3, rootMessage(e));
            } finally {
                syncingSources.remove(sourceId);
            }
        });
    }

    /** 深度优先遍历整棵资源树，逐目录入库 */
    private void walk(WebDavSource source, String dirPath, int depth) {
        if (depth > MAX_SYNC_DEPTH) {
            log.warn("超过最大同步深度，跳过: {}", dirPath);
            return;
        }
        String url = buildUrl(source.getServerUrl(), dirPath, true);
        String expectSelf = stripTrailingSlash(serverBasePath(source.getServerUrl()) + dirPath);
        Sardine sardine = beginSardine(source);
        try {
            List<DavResource> resources = sardine.list(url, 1);
            DavResource self = findSelf(resources, expectSelf);
            upsertDirRow(source.getId(), dirPath, self, true);
            resourceService.deleteByParent(source.getId(), dirPath);
            List<WebDavResource> children = new ArrayList<>();
            List<String> subDirs = new ArrayList<>();
            for (DavResource res : resources) {
                String hrefPath = hrefPathOf(res);
                if (hrefPath != null && hrefPath.equals(expectSelf)) {
                    continue;
                }
                String name = res.getName();
                if (name == null || name.isBlank()) {
                    name = lastSegment(hrefPath);
                }
                if (name == null || name.isBlank()) {
                    continue;
                }
                children.add(buildRow(source.getId(), dirPath, name, res.isDirectory(),
                        res.getContentLength(), res.getModified(), false));
                if (res.isDirectory()) {
                    subDirs.add(joinPath(dirPath, name));
                }
            }
            if (!children.isEmpty()) {
                resourceService.saveBatch(children);
            }
            for (String sub : subDirs) {
                walk(source, sub, depth + 1);
            }
        } catch (IOException e) {
            throw new BusinessException("同步目录失败 " + dirPath + ": " + rootMessage(e));
        }
    }

    private void updateSyncStatus(Long sourceId, int status, String message) {
        WebDavSource s = sourceMapper.selectById(sourceId);
        if (s == null) {
            return;
        }
        s.setSyncStatus(status);
        s.setSyncMessage(message);
        if (status == 2) {
            s.setSyncTime(LocalDateTime.now());
        }
        sourceMapper.updateById(s);
    }

    /* ---------------- 连接测试 ---------------- */

    /** 连接测试：PROPFIND 根目录 Depth 0。使用一次性客户端，不复用缓存（此时源可能尚未保存） */
    public void testConnection(WebDavSource source) {
        String base = normalizeServerUrl(source.getServerUrl());
        Sardine sardine = createSardine(source);
        try {
            try {
                sardine.list(base + "/", 0);
            } catch (SardineException e) {
                if (e.getStatusCode() == 404) {
                    // 部分服务器不接受尾斜杠，回退重试
                    sardine.list(base, 0);
                    return;
                }
                throw e;
            }
        } catch (SardineException e) {
            int code = e.getStatusCode();
            String hint = code == 401 ? "，账号或密码错误" : code == 404 ? "，地址不存在" : "";
            throw new BusinessException("连接失败 (HTTP " + code + hint + ")");
        } catch (IOException e) {
            throw new BusinessException("无法连接服务器: " + rootMessage(e));
        } finally {
            try {
                sardine.shutdown();
            } catch (IOException e) {
                log.debug("关闭测试客户端失败: {}", e.getMessage());
            }
        }
    }

    /* ---------------- 文件内容流式代理（实时） ---------------- */

    /**
     * 打开远端文件的流式响应（供 /raw、/preview 代理，按缓存中的路径直接取文件）。
     * 转发浏览器的 Range / If-Range 头以支持视频拖动；调用方透传响应状态与 Content-* 头，
     * 上游忽略 Range（对 Range 请求回 200）时由调用方本地切片返回 206。
     */
    public HttpResponse<InputStream> openStream(WebDavSource source, String rawPath, String rangeHeader, String ifRangeHeader)
            throws IOException, InterruptedException {
        String url = buildUrl(source.getServerUrl(), normalizePath(rawPath), false);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "webdav-gallery/1.0")
                .GET();
        String auth = basicAuth(source);
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            builder.header("Range", rangeHeader);
        }
        if (ifRangeHeader != null && !ifRangeHeader.isBlank()) {
            builder.header("If-Range", ifRangeHeader);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /* ---------------- 工具方法 ---------------- */

    public static String mediaTypeOf(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            return "other";
        }
        String ext = name.substring(idx + 1).toLowerCase();
        if (IMAGE_EXT.contains(ext)) {
            return "image";
        }
        if (VIDEO_EXT.contains(ext)) {
            return "video";
        }
        return "other";
    }

    private FileInfo toFileInfo(WebDavResource row) {
        FileInfo f = new FileInfo();
        f.setName(row.getName());
        f.setPath(row.getPath());
        f.setDir(Boolean.TRUE.equals(row.getIsDir()));
        f.setSize(f.isDir() || row.getSize() == null ? 0 : Math.max(0, row.getSize()));
        f.setLastModified(row.getLastModified() == null ? 0 : row.getLastModified());
        f.setMediaType(f.isDir() ? "dir" : mediaTypeOf(row.getName()));
        return f;
    }

    private WebDavResource buildRow(Long sourceId, String parentPath, String name, boolean isDir,
                                    Long contentLength, java.util.Date modified, boolean synced) {
        WebDavResource row = new WebDavResource();
        row.setSourceId(sourceId);
        row.setParentPath(parentPath);
        row.setName(name);
        row.setPath(joinPath(parentPath, name));
        row.setIsDir(isDir);
        row.setSize(isDir || contentLength == null ? 0L : Math.max(0, contentLength));
        row.setLastModified(modified == null ? 0L : modified.getTime());
        row.setSynced(synced);
        return row;
    }

    /** 插入或更新目录行，记录其子项是否已拉取 */
    private void upsertDirRow(Long sourceId, String dirPath, DavResource self, boolean synced) {
        WebDavResource row = resourceService.findByPath(sourceId, dirPath);
        boolean isRoot = "/".equals(dirPath);
        if (row == null) {
            row = new WebDavResource();
            row.setSourceId(sourceId);
            row.setPath(dirPath);
            row.setParentPath(isRoot ? "/" : dirOfPath(dirPath));
            row.setName(isRoot ? "/" : lastSegment(dirPath));
            row.setIsDir(true);
        }
        row.setIsDir(true);
        row.setSize(0L);
        row.setLastModified(self != null && self.getModified() != null ? self.getModified().getTime() : 0L);
        row.setSynced(synced);
        if (row.getId() != null) {
            resourceService.updateById(row);
        } else {
            resourceService.save(row);
        }
    }

    private DavResource findSelf(List<DavResource> resources, String expectSelf) {
        for (DavResource res : resources) {
            String p = hrefPathOf(res);
            if (p != null && p.equals(expectSelf)) {
                return res;
            }
        }
        return null;
    }

    private String joinPath(String parent, String name) {
        return "/".equals(parent) ? "/" + name : parent + "/" + name;
    }

    private String dirOfPath(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    /* ---------------- Sardine 客户端管理 ---------------- */

    private Sardine beginSardine(WebDavSource source) {
        String key = source.getId() + "|" + source.getServerUrl() + "|"
                + source.getUsername() + "|" + source.getPassword();
        return sardineCache.computeIfAbsent(key, k -> createSardine(source));
    }

    private Sardine createSardine(WebDavSource source) {
        // Sardine 5.13 不再提供接口级超时设置，通过底层 HttpClient 配置
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .setSocketTimeout(30_000)
                .build();
        Sardine sardine = new SardineImpl(HttpClientBuilder.create().setDefaultRequestConfig(requestConfig),
                source.getUsername(), source.getPassword());
        try {
            URI uri = URI.create(normalizeServerUrl(source.getServerUrl()));
            if ((source.getUsername() != null && !source.getUsername().isBlank())
                    || (source.getPassword() != null && !source.getPassword().isBlank())) {
                // 有账号时启用预认证，避免每个请求先收到 401 挑战
                sardine.enablePreemptiveAuthentication(
                        new URL(uri.getScheme(), uri.getHost(), uri.getPort(), ""));
            }
        } catch (Exception e) {
            log.debug("启用预认证失败（不影响正常鉴权）: {}", e.getMessage());
        }
        return sardine;
    }

    /** 源被修改/删除时清理其缓存的客户端 */
    public void evictClient(Long sourceId) {
        if (sourceId == null) {
            return;
        }
        String prefix = sourceId + "|";
        sardineCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private String basicAuth(WebDavSource source) {
        String u = source.getUsername() == null ? "" : source.getUsername().trim();
        String p = source.getPassword() == null ? "" : source.getPassword();
        if (u.isEmpty() && p.isEmpty()) {
            return null;
        }
        String token = Base64.getEncoder().encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    /** 规范化目录/文件路径：以 / 开头，去除尾斜杠（根目录保留 /） */
    private String normalizePath(String p) {
        if (p == null || p.isBlank()) {
            return "/";
        }
        p = p.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String normalizeServerUrl(String serverUrl) {
        String base = serverUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** 服务器 URL 中的路径部分（已解码，无尾斜杠），如 http://host/dav -> /dav */
    private String serverBasePath(String serverUrl) {
        String path = URI.create(normalizeServerUrl(serverUrl)).getPath();
        if (path == null || "/".equals(path)) {
            return "";
        }
        return stripTrailingSlash(path);
    }

    /** 服务器地址 + 路径（逐段 URL 编码）；asDir 时补尾斜杠 */
    private String buildUrl(String serverUrl, String path, boolean asDir) {
        StringBuilder sb = new StringBuilder(normalizeServerUrl(serverUrl));
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append('/').append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        String url = sb.toString();
        if (asDir && !url.endsWith("/")) {
            url = url + "/";
        }
        return url;
    }

    private String hrefPathOf(DavResource res) {
        try {
            URI href = res.getHref();
            return href == null ? null : stripTrailingSlash(href.getPath());
        } catch (Exception e) {
            return null;
        }
    }

    private String lastSegment(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = stripTrailingSlash(path);
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private String stripTrailingSlash(String p) {
        if (p == null) {
            return null;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        String msg = r.getMessage();
        return msg == null || msg.isBlank() ? r.getClass().getSimpleName() : msg;
    }
}
