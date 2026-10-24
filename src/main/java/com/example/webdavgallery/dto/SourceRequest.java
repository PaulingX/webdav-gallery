package com.example.webdavgallery.dto;

public class SourceRequest {

    private String name;
    private String serverUrl;
    private String username;
    /** 明文密码；编辑时为空表示不修改 */
    private String password;
    private Boolean enabled;

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
}
