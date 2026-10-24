package com.example.webdavgallery.controller;

import com.example.webdavgallery.dto.ApiResponse;
import com.example.webdavgallery.dto.FileInfo;
import com.example.webdavgallery.entity.WebDavSource;
import com.example.webdavgallery.service.WebDavClientService;
import com.example.webdavgallery.service.WebDavSourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/list")
    public ApiResponse<List<FileInfo>> list(@PathVariable Long id, @RequestParam(defaultValue = "/") String path) {
        WebDavSource source = sourceService.getRequiredWithPlainPassword(id);
        return ApiResponse.ok(clientService.list(source, path));
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

    /**
     * 文件内容代理：转发 Range 头，透传 200/206 与 Content-* 头，
     * 流式拷贝不落盘，图片可直接显示，视频可拖动进度条。
     */
    @GetMapping("/raw")
    public void raw(@PathVariable Long id, @RequestParam String path,
                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebDavSource source = sourceService.getRequiredWithPlainPassword(id);
        String range = request.getHeader("Range");
        HttpResponse<InputStream> upstream;
        try {
            upstream = clientService.openStream(source, path, range);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "请求被中断");
            return;
        } catch (IOException e) {
            log.warn("取文件失败: {} {}", source.getName(), e.getMessage());
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "获取文件失败: " + e.getMessage());
            return;
        }
        writeProxy(upstream, response);
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
            upstream = clientService.openStream(source, imagePath, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "请求被中断");
            return;
        } catch (IOException e) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "获取文件失败: " + e.getMessage());
            return;
        }
        writeProxy(upstream, response);
    }

    private void writeProxy(HttpResponse<InputStream> upstream, HttpServletResponse response) throws IOException {
        try (InputStream in = upstream.body()) {
            response.setStatus(upstream.statusCode());
            response.setContentType(upstream.headers().firstValue("Content-Type")
                    .filter(v -> !v.isBlank()).orElse("application/octet-stream"));
            copyHeader(upstream, "Content-Length", v -> {
                try {
                    response.setContentLengthLong(Long.parseLong(v.trim()));
                } catch (NumberFormatException ignore) {
                    // 长度异常时交给容器按实际输出处理
                }
            });
            copyHeader(upstream, "Content-Range", v -> response.setHeader("Content-Range", v));
            copyHeader(upstream, "Accept-Ranges", v -> response.setHeader("Accept-Ranges", v));
            copyHeader(upstream, "ETag", v -> response.setHeader("ETag", v));
            copyHeader(upstream, "Last-Modified", v -> response.setHeader("Last-Modified", v));
            copyHeader(upstream, "Content-Disposition", v -> response.setHeader("Content-Disposition", v));
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
            out.flush();
        } catch (IOException e) {
            // 客户端中断或上游断流，响应已提交，仅记录
            log.debug("传输中断: {}", e.getMessage());
        }
    }

    private void copyHeader(HttpResponse<InputStream> from, String name, Consumer<String> setter) {
        Optional<String> v = from.headers().firstValue(name);
        if (v.isPresent() && !v.get().isBlank()) {
            setter.accept(v.get());
        }
    }
}
