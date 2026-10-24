package com.example.webdavgallery.controller;

import com.example.webdavgallery.dto.ApiResponse;
import com.example.webdavgallery.dto.SourceRequest;
import com.example.webdavgallery.dto.SourceVO;
import com.example.webdavgallery.service.WebDavSourceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sources")
public class WebDavSourceController {

    private final WebDavSourceService sourceService;

    public WebDavSourceController(WebDavSourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    public ApiResponse<List<SourceVO>> list() {
        return ApiResponse.ok(sourceService.listAll().stream().map(SourceVO::from).toList());
    }

    /** 新增前会自动测试连接，失败则报错不保存 */
    @PostMapping
    public ApiResponse<SourceVO> create(@RequestBody SourceRequest req) {
        return ApiResponse.ok(SourceVO.from(sourceService.create(req)));
    }

    @PutMapping("/{id}")
    public ApiResponse<SourceVO> update(@PathVariable Long id, @RequestBody SourceRequest req) {
        return ApiResponse.ok(SourceVO.from(sourceService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        sourceService.delete(id);
        return ApiResponse.ok();
    }

    /** 保存前的连接测试 */
    @PostMapping("/test")
    public ApiResponse<Void> test(@RequestBody SourceRequest req) {
        sourceService.test(req);
        return ApiResponse.ok();
    }

    /** 手动全量同步资源树到数据库（后台执行，状态见源列表） */
    @PostMapping("/{id}/sync")
    public ApiResponse<Void> sync(@PathVariable Long id) {
        sourceService.sync(id);
        return ApiResponse.ok();
    }
}
