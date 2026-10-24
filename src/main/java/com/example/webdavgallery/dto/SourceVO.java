package com.example.webdavgallery.dto;

import com.example.webdavgallery.entity.WebDavSource;

import java.time.LocalDateTime;

/** 返回给前端的源信息，不含密码明文/密文 */
public class SourceVO {

    private Long id;
    private String name;
    private String serverUrl;
    private String username;
    private Boolean enabled;
    private boolean hasPassword;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 0 未同步，1 同步中，2 已同步，3 同步失败 */
    private Integer syncStatus;
    private LocalDateTime syncTime;
    private String syncMessage;

    public static SourceVO from(WebDavSource s) {
        SourceVO vo = new SourceVO();
        vo.setId(s.getId());
        vo.setName(s.getName());
        vo.setServerUrl(s.getServerUrl());
        vo.setUsername(s.getUsername());
        vo.setEnabled(s.getEnabled());
        vo.setHasPassword(s.getPassword() != null && !s.getPassword().isBlank());
        vo.setCreateTime(s.getCreateTime());
        vo.setUpdateTime(s.getUpdateTime());
        vo.setSyncStatus(s.getSyncStatus());
        vo.setSyncTime(s.getSyncTime());
        vo.setSyncMessage(s.getSyncMessage());
        return vo;
    }

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getServerUrl() { return serverUrl; }

    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getUsername() { return username; }

    public void setUsername(String username) { this.username = username; }

    public Boolean getEnabled() { return enabled; }

    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public boolean isHasPassword() { return hasPassword; }

    public void setHasPassword(boolean hasPassword) { this.hasPassword = hasPassword; }

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
