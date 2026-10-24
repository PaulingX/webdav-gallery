package com.example.webdavgallery.controller;

import com.example.webdavgallery.dto.ApiResponse;
import com.example.webdavgallery.dto.FileInfo;
import com.example.webdavgallery.dto.PageResult;
import com.example.webdavgallery.entity.WebDavSource;
import com.example.webdavgallery.service.WebDavClientService;
import com.example.webdavgallery.service.WebDavSourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/sources/{id}")
public class WebDavBrowseController {

    private static final Logger log = LoggerFactory.getLogger(WebDavBrowseController.class);

    private final WebDavSourceService sourceService;
    private final WebDavClientService clientService;

    public WebDavBrowseController(WebDavSourceService sourceService, WebDavClientService clientService) {
        this.sourceService = sourceService;
        this.clientService = clientService;
    }

    /** 分页列目录：sort 为 name_asc/name_desc/time_asc/time_desc，默认每页 100 条（前端无限滚动） */
    @GetMapping("/list")
    public ApiResponse<PageResult<FileInfo>> list(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "/") String path,
                                                  @RequestParam(defaultValue = "name_asc") String sort,
                                                  @RequestParam(defaultValue = "0") int offset,
                                                  @RequestParam(defaultValue = "100") int limit) {
        WebDavSource source = sourceService.getRequiredWithPlainPassword(id);
        int safeLimit = Math.min(Math.max(limit, 1), 300);
        return ApiResponse.ok(clientService.list(source, path, sort, offset, safeLimit));
    }

    /** 递归搜索 path 目录及子目录中文件名包含关键字的条目 */
    @GetMapping("/search")
    public ApiResponse<List<FileInfo>> search(@PathVariable Long id,
                                              @RequestParam(defaultValue = "/") String path,
                                              @RequestParam String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return ApiResponse.ok(List.of());
        }
        WebDavSource source = sourceService.getRequiredWithPlainPassword(id);
        return ApiResponse.ok(clientService.search(source, path, keyword.trim()));
    }

    private static final long[] RANGE_UNSATISFIABLE = new long[0];

    /**
     * 文件内容代理：转发 Range / If-Range 头，透传 200/206 与 Content-* 头，
     * 流式拷贝不落盘，图片可直接显示，视频可拖动进度条。
     * 上游忽略 Range（对 Range 请求回 200 全量）时本地切片补回 206，
     * 保证 moov 在文件尾部的视频也能先取尾部元数据秒开、随意拖动。
     */
    @GetMapping("/raw")
    public void raw(@PathVariable Long id, @RequestParam String path,
                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebDavSource source = sourceService.getRequiredWithPlainPassword(id);
        String range = request.getHeader("Range");
        HttpResponse<InputStream> upstream;
        try {
            upstream = clientService.openStream(source, path, range, request.getHeader("If-Range"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "请求被中断");
            return;
        } catch (IOException e) {
            log.warn("取文件失败: {} {}", source.getName(), e.getMessage());
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "获取文件失败: " + e.getMessage());
            return;
        }
        writeProxy(upstream, response, range);
    }

    /**
     * 文件夹封面预览：取目录内第一张图片并以其内容响应，
     * 目录不存在或无图片时返回 404（前端回退为文件夹图标）。
     */
    @GetMapping("/preview")
    public void preview(@PathVariable Long id, @RequestParam String path, HttpServletResponse response) throws IOException {
        WebDavSource source = sourceService.getRequiredWithPlainPassword(id);
        String imagePath;
        try {
            imagePath = clientService.firstImagePath(source, path);
        } catch (RuntimeException e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (imagePath == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        HttpResponse<InputStream> upstream;
        try {
            upstream = clientService.openStream(source, imagePath, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "请求被中断");
            return;
        } catch (IOException e) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "获取文件失败: " + e.getMessage());
            return;
        }
        writeProxy(upstream, response, null);
    }

    /**
     * 把上游响应写回客户端：
     * - 客户端带 Range 且上游回 206：原样透传；
     * - 客户端带 Range 但上游回 200（不支持/忽略 Range）：本地按 Range 切片返回 206，
     *   越界返回 416；语法非法或无法确定总长时退回 200 全量透传；
     * - 客户端无 Range：全量透传。
     * Content-Disposition 的 attachment 统一改写为 inline，避免移动端浏览器当下载处理无法内联播放。
     */
    private void writeProxy(HttpResponse<InputStream> upstream, HttpServletResponse response,
                            String clientRange) throws IOException {
        boolean ranged = clientRange != null && !clientRange.isBlank();
        long total = upstream.headers().firstValueAsLong("Content-Length").orElse(-1L);
        boolean sliceable = ranged && upstream.statusCode() == 200
                && total > 0 && !clientRange.contains(",");
        if (sliceable) {
            long[] r = parseRange(clientRange, total);
            if (r == RANGE_UNSATISFIABLE) {
                closeQuietly(upstream.body());
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader("Content-Range", "bytes */" + total);
                response.setContentLength(0);
                return;
            }
            if (r != null) {
                streamSlice(upstream, response, r[0], r[1], total);
                return;
            }
            // Range 语法非法：按 RFC 应忽略该头，走 200 全量透传
        }
        passThrough(upstream, response, total);
    }

    /** 上游已按 Range 回 206（或无需分段）时原样透传 */
    private void passThrough(HttpResponse<InputStream> upstream, HttpServletResponse response, long total) throws IOException {
        try (InputStream in = upstream.body()) {
            response.setStatus(upstream.statusCode());
            response.setContentType(upstream.headers().firstValue("Content-Type")
                    .filter(v -> !v.isBlank()).orElse("application/octet-stream"));
            if (total > 0) {
                response.setContentLengthLong(total);
            } else {
                copyHeader(upstream, "Content-Length", v -> {
                    try {
                        response.setContentLengthLong(Long.parseLong(v.trim()));
                    } catch (NumberFormatException ignore) {
                        // 长度异常时交给容器按实际输出处理
                    }
                });
            }
            copyHeader(upstream, "Content-Range", v -> response.setHeader("Content-Range", v));
            if (total > 0) {
                // 代理可对下一次 Range 请求本地切片，主动声明支持
                response.setHeader("Accept-Ranges", "bytes");
            } else {
                copyHeader(upstream, "Accept-Ranges", v -> response.setHeader("Accept-Ranges", v));
            }
            copyHeader(upstream, "ETag", v -> response.setHeader("ETag", v));
            copyHeader(upstream, "Last-Modified", v -> response.setHeader("Last-Modified", v));
            copyHeader(upstream, "Content-Disposition", v -> response.setHeader("Content-Disposition", inlineDisposition(v)));
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
            out.flush();
        } catch (IOException e) {
            // 客户端中断或上游断流，响应已提交，仅记录
            log.debug("传输中断: {}", e.getMessage());
        }
    }

    /** 上游忽略 Range 时本地切片：跳过前 start 字节，仅输出请求长度，按 206 返回 */
    private void streamSlice(HttpResponse<InputStream> upstream, HttpServletResponse response,
                             long start, long end, long total) throws IOException {
        long length = end - start + 1;
        try (InputStream in = upstream.body()) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setContentType(upstream.headers().firstValue("Content-Type")
                    .filter(v -> !v.isBlank()).orElse("application/octet-stream"));
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
            response.setContentLengthLong(length);
            skipFully(in, start);
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break; // 上游提前断流，客户端拿到截断的 206 后会自行重试
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        } catch (IOException e) {
            log.debug("分段传输中断: {}", e.getMessage());
        }
    }

    /** Content-Disposition 的 attachment 改写为 inline（保留文件名参数），其余原样返回 */
    private String inlineDisposition(String value) {
        if (value != null && value.trim().toLowerCase().startsWith("attachment")) {
            return "inline" + value.trim().substring("attachment".length());
        }
        return value;
    }

    private void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long n = in.skip(remaining);
            if (n > 0) {
                remaining -= n;
            } else if (in.read() >= 0) { // skip 返回 0 时读 1 字节兜底推进
                remaining--;
            } else {
                break;
            }
        }
    }

    private void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignore) {
            // 关闭上游流失败无需处理
        }
    }

    /**
     * 解析单段 Range 头（bytes=start-end / bytes=start- / bytes=-suffix）。
     * 返回 {start, end}（含端点）；语法非法返回 null（应忽略该头回 200）；
     * 语法合法但越界返回 RANGE_UNSATISFIABLE（应回 416）。
     */
    private long[] parseRange(String header, long total) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^bytes=(\\d*)-(\\d*)$").matcher(header.trim());
        if (!m.matches()) {
            return null;
        }
        String s = m.group(1), e = m.group(2);
        if (s.isEmpty() && e.isEmpty()) {
            return null; // "bytes=-" 非法
        }
        long start, end;
        try {
            if (s.isEmpty()) { // 后缀 bytes=-N：最后 N 字节
                long suffix = Long.parseLong(e);
                if (suffix <= 0) {
                    return RANGE_UNSATISFIABLE;
                }
                start = Math.max(total - suffix, 0);
                end = total - 1;
            } else {
                start = Long.parseLong(s);
                end = e.isEmpty() ? total - 1 : Math.min(Long.parseLong(e), total - 1);
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        if (start >= total) {
            return RANGE_UNSATISFIABLE;
        }
        if (start > end) {
            return null; // 起点大于终点视为非法，忽略该头
        }
        return new long[]{start, end};
    }

    private void copyHeader(HttpResponse<InputStream> from, String name, Consumer<String> setter) {
        Optional<String> v = from.headers().firstValue(name);
        if (v.isPresent() && !v.get().isBlank()) {
            setter.accept(v.get());
        }
    }
}
