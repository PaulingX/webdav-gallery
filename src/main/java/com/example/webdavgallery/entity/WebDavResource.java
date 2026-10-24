package com.example.webdavgallery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("webdav_resource")
public class WebDavResource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceId;

    /** 父目录完整路径，根目录的子项为 "/" */
    private String parentPath;

    private String name;

    /** 完整路径，如 /photos/a.jpg，根目录本身为 "/" */
    private String path;

    private Boolean isDir;

    private Long size;

    /** epoch 毫秒 */
    private Long lastModified;

    /** 目录子项是否已拉取过（用于按目录懒同步） */
    private Boolean synced;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getSourceId() { return sourceId; }

    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public String getParentPath() { return parentPath; }

    public void setParentPath(String parentPath) { this.parentPath = parentPath; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }

    public void setPath(String path) { this.path = path; }

    public Boolean getIsDir() { return isDir; }

    public void setIsDir(Boolean isDir) { this.isDir = isDir; }

    public Long getSize() { return size; }

    public void setSize(Long size) { this.size = size; }

    public Long getLastModified() { return lastModified; }

    public void setLastModified(Long lastModified) { this.lastModified = lastModified; }

    public Boolean getSynced() { return synced; }

    public void setSynced(Boolean synced) { this.synced = synced; }
}
