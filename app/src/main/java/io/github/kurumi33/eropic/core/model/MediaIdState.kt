package io.github.kurumi33.eropic.core.model

/** 扫描增量比对用的轻量投影。 */
data class MediaIdState(
    val id: Long,
    val dateModifiedMs: Long,
)
