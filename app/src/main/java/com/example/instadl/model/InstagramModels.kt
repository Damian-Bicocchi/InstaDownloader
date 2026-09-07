package com.example.instadl.model

import com.google.gson.annotations.SerializedName

data class MediaCandidate(
    val width: Int? = null,
    val height: Int? = null,
    val url: String? = null
)

data class VideoVersion(
    val type: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val url: String? = null
)

data class ImageVersion2(
    @SerializedName("candidates") val candidates: List<MediaCandidate>? = null
)

data class StoryImageVersions(
    @SerializedName("candidates") val candidates: List<MediaCandidate>? = null
)

data class MediaItem(
    @SerializedName("id") val id: String? = null,
    @SerializedName("media_type") val mediaType: Int? = null,
    @SerializedName("image_versions2") val imageVersions2: ImageVersion2? = null,
    @SerializedName("video_versions") val videoVersions: List<VideoVersion>? = null,
    @SerializedName("carousel_media") val carouselMedia: List<MediaItem>? = null,
    @SerializedName("original_width") val originalWidth: Int? = null,
    @SerializedName("original_height") val originalHeight: Int? = null,
    @SerializedName("video_duration") val videoDuration: Double? = null,
    @SerializedName("taken_at") val takenAt: Long? = null
) {
    fun bestImageUrl(): String? =
        imageVersions2?.candidates?.maxByOrNull { it.width ?: 0 }?.url

    fun bestVideoUrl(): String? =
        videoVersions?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }?.url

    /** True for album posts (media_type 8) with children. */
    fun isCarousel(): Boolean = mediaType == 8 && !carouselMedia.isNullOrEmpty()

    /**
     * Flatten for download: carousel children (each has its own
     * image/video versions), otherwise just this item.
     */
    fun flatten(): List<MediaItem> =
        if (isCarousel()) carouselMedia!! else listOf(this)
}

data class UserInfo(
    @SerializedName("pk") val pk: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("full_name") val fullName: String? = null,
    @SerializedName("profile_pic_url") val profilePicUrl: String? = null,
    @SerializedName("is_private") val isPrivate: Boolean? = null
)

data class MediaFeed(
    @SerializedName("items") val items: List<MediaItem>? = null,
    @SerializedName("num_results") val numResults: Int? = null,
    @SerializedName("more_available") val moreAvailable: Boolean? = null,
    @SerializedName("next_max_id") val nextMaxId: String? = null,
    @SerializedName("status") val status: String? = null
)
