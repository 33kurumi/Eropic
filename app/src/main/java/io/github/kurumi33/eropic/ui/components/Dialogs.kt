package io.github.kurumi33.eropic.ui.components

import androidx.compose.material3.AlertDialog
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 通用确认对话框。
 *
 * Eropic 的所有写操作（移入回收站 / 永久删除 / 清空回收站 / 移动）都必须先经过它，
 * 保证"用户点下去之前明确知道会发生什么"。
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) {
                Text(
                    text = confirmText,
                    color = if (destructive) {
                        androidx.compose.material3.MaterialTheme.colorScheme.error
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
