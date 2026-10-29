package cn.diaojiahong.myyoutube.user.api.enums;

public enum UserRole {
    USER,
    ADMIN,
    SUPER_ADMIN;

    public boolean isAdmin() {
        return this == ADMIN || this == SUPER_ADMIN;
    }
}
