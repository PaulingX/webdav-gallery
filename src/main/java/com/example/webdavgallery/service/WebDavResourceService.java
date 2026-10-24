package com.example.webdavgallery.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.webdavgallery.entity.WebDavResource;
import com.example.webdavgallery.mapper.WebDavResourceMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebDavResourceService extends ServiceImpl<WebDavResourceMapper, WebDavResource> {

    public List<WebDavResource> findByParent(Long sourceId, String parentPath) {
        return lambdaQuery()
                .eq(WebDavResource::getSourceId, sourceId)
                .eq(WebDavResource::getParentPath, parentPath)
                .list();
    }

    public WebDavResource findByPath(Long sourceId, String path) {
        return lambdaQuery()
                .eq(WebDavResource::getSourceId, sourceId)
                .eq(WebDavResource::getPath, path)
                .one();
    }

    public void deleteBySource(Long sourceId) {
        lambdaUpdate().eq(WebDavResource::getSourceId, sourceId).remove();
    }

    public void deleteByParent(Long sourceId, String parentPath) {
        lambdaUpdate()
                .eq(WebDavResource::getSourceId, sourceId)
                .eq(WebDavResource::getParentPath, parentPath)
                .remove();
    }
}
