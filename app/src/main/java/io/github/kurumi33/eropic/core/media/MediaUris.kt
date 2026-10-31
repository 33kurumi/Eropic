package io.github.kurumi33.eropic.core.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

object MediaUris {
    fun of(mediaId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

    /** v0.8：视频行（video_item.id 就是 MediaStore.Video 的 _ID）。 */
    fun ofVideo(mediaId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
}
