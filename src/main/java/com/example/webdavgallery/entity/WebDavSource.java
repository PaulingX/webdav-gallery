package com.example.webdavgallery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("webdav_source")
public class WebDavSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String serverUrl;

    private String username;

    /** AES 加密后的密码（数据库存储形态） */
    private String password;

    private Boolean enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 同步状态：0 未同步，1 同步中，2 已同步，3 同步失败 */
    private Integer syncStatus;

    private LocalDateTime syncTime;

    private String syncMessage;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getServerUrl() { return serverUrl; }

    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getUsername() { return username; }

    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }

    public void setPassword(String password) { this.password = password; }

    public Boolean getEnabled() { return enabled; }

    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreateTime() { return createTime; }

    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }

    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public Integer getSyncStatus() { return syncStatus; }

    public void setSyncStatus(Integer syncStatus) { this.syncStatus = syncStatus; }

    public LocalDateTime getSyncTime() { return syncTime; }

    public void setSyncTime(LocalDateTime syncTime) { this.syncTime = syncTime; }

    public String getSyncMessage() { return syncMessage; }

    public void setSyncMessage(String syncMessage) { this.syncMessage = syncMessage; }
}
