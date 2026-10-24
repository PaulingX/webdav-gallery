package com.example.webdavgallery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.webdavgallery.dto.SourceRequest;
import com.example.webdavgallery.entity.WebDavSource;
import com.example.webdavgallery.exception.BusinessException;
import com.example.webdavgallery.mapper.WebDavSourceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WebDavSourceService {

    private final WebDavSourceMapper mapper;
    private final WebDavClientService clientService;
    private final WebDavCrypto crypto;
    private final WebDavResourceService resourceService;

    public WebDavSourceService(WebDavSourceMapper mapper, WebDavClientService clientService,
                               WebDavCrypto crypto, WebDavResourceService resourceService) {
        this.mapper = mapper;
        this.clientService = clientService;
        this.crypto = crypto;
        this.resourceService = resourceService;
    }

    public List<WebDavSource> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<WebDavSource>().orderByDesc(WebDavSource::getId));
    }

    public WebDavSource getRequired(Long id) {
        WebDavSource source = mapper.selectById(id);
        if (source == null) {
            throw new BusinessException("WebDAV 源不存在");
        }
        return source;
    }

    /** 取实体并把密码解密为明文，供连接远端服务器使用 */
    public WebDavSource getRequiredWithPlainPassword(Long id) {
        WebDavSource source = getRequired(id);
        source.setPassword(crypto.decrypt(source.getPassword()));
        return source;
    }

    public WebDavSource create(SourceRequest req) {
        validate(req);
        // 保存前自动测试连接，失败则不保存
        WebDavSource probe = new WebDavSource();
        probe.setServerUrl(req.getServerUrl().trim());
        probe.setUsername(blankToNull(req.getUsername()));
        probe.setPassword(req.getPassword() == null ? "" : req.getPassword());
        clientService.testConnection(probe);

        WebDavSource entity = new WebDavSource();
        copyRequest(entity, req);
        entity.setPassword(crypto.encrypt(req.getPassword()));
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setSyncStatus(0);
        mapper.insert(entity);
        // 首次添加后自动全量同步资源树到数据库（后台执行）
        clientService.syncSourceAsync(entity.getId());
        return entity;
    }

    public WebDavSource update(Long id, SourceRequest req) {
        validate(req);
        WebDavSource entity = getRequired(id);
        copyRequest(entity, req);
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            entity.setPassword(crypto.encrypt(req.getPassword()));
        }
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
        clientService.evictClient(id);
        // 配置可能变化，重新全量同步
        clientService.syncSourceAsync(id);
        return getRequired(id);
    }

    public void delete(Long id) {
        getRequired(id);
        mapper.deleteById(id);
        clientService.evictClient(id);
        resourceService.deleteBySource(id);
    }

    /** 不落库的连接测试 */
    public void test(SourceRequest req) {
        if (req.getServerUrl() == null || req.getServerUrl().isBlank()) {
            throw new BusinessException("服务器地址不能为空");
        }
        WebDavSource probe = new WebDavSource();
        probe.setServerUrl(req.getServerUrl().trim());
        probe.setUsername(blankToNull(req.getUsername()));
        probe.setPassword(req.getPassword() == null ? "" : req.getPassword());
        clientService.testConnection(probe);
    }

    /** 手动触发全量同步 */
    public void sync(Long id) {
        getRequired(id);
        clientService.syncSourceAsync(id);
    }

    private void copyRequest(WebDavSource entity, SourceRequest req) {
        entity.setName(req.getName().trim());
        entity.setServerUrl(req.getServerUrl().trim());
        entity.setUsername(blankToNull(req.getUsername()));
        entity.setEnabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
    }

    private void validate(SourceRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException("名称不能为空");
        }
        if (req.getServerUrl() == null || req.getServerUrl().isBlank()) {
            throw new BusinessException("服务器地址不能为空");
        }
        String url = req.getServerUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException("服务器地址必须以 http:// 或 https:// 开头");
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
