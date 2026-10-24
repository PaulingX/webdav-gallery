package com.example.webdavgallery.dto;

public class FileInfo {

    private String name;
    /** 相对源根目录的完整路径，如 /photos/2024/a.jpg */
    private String path;
    private boolean dir;
    private long size;
    private long lastModified;
    /** image / video / other，目录为 dir */
    private String mediaType;

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }

    public void setPath(String path) { this.path = path; }

    public boolean isDir() { return dir; }

    public void setDir(boolean dir) { this.dir = dir; }

    public long getSize() { return size; }

    public void setSize(long size) { this.size = size; }

    public long getLastModified() { return lastModified; }

    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public String getMediaType() { return mediaType; }

    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
}
