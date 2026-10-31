package io.github.kurumi33.eropic.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v0.9：「保留本组」标记。
 *
 * 用户点「保留本组」后，这一组的所有成员 id 写进本表；之后重新扫描时这些图片
 * 不再参与重复 / 相似 / 差分分组（相当于「以后不要再来烦我」），图片本身不动。
 *
 * 之所以按成员而不是按组落库：组的成员会随文件增删变化（删掉一张、扫描重排都会变），
 * 按成员记录才稳定；某组过滤后不足 2 张自然就不显示了。
 */
@Entity(tableName = "ignored_member")
data class IgnoredMemberEntity(
    @PrimaryKey val mediaId: Long,
    val ignoredAtMs: Long,
)