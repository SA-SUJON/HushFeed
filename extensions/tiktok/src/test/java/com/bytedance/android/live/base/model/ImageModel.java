package com.bytedance.android.live.base.model;

import java.util.List;

/** Test fixture for TikTok's LIVE picture model: the addresses one picture is served from. */
public class ImageModel {
    public List<String> mUrls;

    public ImageModel(List<String> urls) {
        mUrls = urls;
    }
}
