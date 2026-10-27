package io.github.kurumi33.eropic.core.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

object MediaUris {
    fun of(mediaId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
}
