package com.bytedance.android.live.base.model.user;

import com.bytedance.android.live.base.model.ImageModel;

/** The LIVE user model's fields a name alert and a held room's host bar read, by their real names and types. */
public class User {
    public long id;
    public String idStr;
    public String nickName;
    public String displayId;
    /** The @username; the real getDisplayId and getUsername both read this field. */
    public String username;
    public ImageModel avatarThumb;

    public User(long id, String nickName) {
        this.id = id;
        this.idStr = String.valueOf(id);
        this.nickName = nickName;
    }
}
