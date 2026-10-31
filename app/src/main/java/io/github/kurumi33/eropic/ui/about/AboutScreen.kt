package io.github.kurumi33.eropic.ui.about

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import io.github.kurumi33.eropic.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kurumi33.eropic.BuildConfig
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.common.EropicTopBar

/**
 * 关于页（v0.6 重做）。
 *
 * 之前这一页什么都有：逐条权限表、系统与设备、本地数据目录、第三方许可、崩溃日志、设计原则。
 * 用户反馈「太长、找不到重点」，于是只留四块真正需要知道的：
 * 我是谁（应用身份）→ 去哪看源码 → 你怎么处理我的数据（隐私协议）→ 都改了什么（更新日志）→ 用了谁的轮子（许可）。
 *
 * viewModel 在这个版本里已经用不上了（崩溃日志那块被删掉），但保留参数是为了不动
 * EropicRoot.kt 里 AboutScreen(viewModel, onBack) 的调用签名。
 */
@Composable
fun AboutScreen(viewModel: EropicViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    // 版本号统一从 BuildConfig 读（由 app/build.gradle.kts 的 appVersionName 自动生成），
    // 不再单独向 PackageManager 取，保证和安装包信息一致。
    val versionName = BuildConfig.VERSION_NAME
    val appIcon = remember(context) { loadAppIcon(context) }

    Scaffold(
        topBar = {
            // 本页没有副标题，显式传 null（传空串会多占一行，标题看起来偏高）。
            EropicTopBar(title = "关于", subtitle = null, onBack = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IdentityCard(icon = appIcon, versionName = versionName)

            GitHubCard()

            ContactCard()

            AboutSection(title = "隐私协议") {
                PrivacyLine("所有照片与索引只存在这台手机上；索引删掉也能重建，不会动你的原图。")
                PrivacyLine("更新包会下载到 cacheDir/updates，安装完成后可以清理，不会长期占空间。")
                PrivacyLine("不上传、无账号、不采集统计，也没有任何第三方 SDK 在打点。")
                PrivacyLine("只有你主动点「联网识图」时才会联网，并且只把你选中的那一张图发给你选的服务商。")
                PrivacyLine(
                    "联网识图需要 INTERNET 权限，会把源图上传到你选择的服务商；" +
                        "而「检查更新」只访问 GitHub Releases 接口获取版本信息，不会上传任何本地图片或索引。",
                )
                PrivacyLine("「所有文件访问」只用于移动、重命名、复制和回收站，不用于上传或分析。")
                PrivacyLine("删除只是移入回收站，默认保留 30 天、随时可恢复。")
                PrivacyLine("卸载应用不会删除你的照片；只有回收站里的文件需要你先恢复或清空。")
            }

            AboutSection(title = "更新日志") {
                // 从新到旧排列；版本之间加一条分隔线，长列表里不容易看串行。
                changelog.forEachIndexed { index, entry ->
                    ChangelogRow(entry = entry)
                    if (index != changelog.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }

            AboutSection(title = "第三方与许可") {
                Text(
                    text = "基于 Kotlin / Jetpack Compose / Room / Paging / Coil / WorkManager / OkHttp / ExifInterface，" +
                        "均为 Apache 2.0 许可。交互设计参考了 Immich、PhotoPrism、Aves、Fossify Gallery 等开源项目，" +
                        "但没有复制它们的源码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 原来那整块「设计原则」压成一行：留个态度就够了，不再展开四条。
            Text(
                text = "Eropic · 只做检索与整理，删不删永远由你确认。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * 身份卡：图标、名字、一句话定位、开发者、版本与构建时间，居中展示。
 *
 * 版本号旁边带上构建时间：debug 包每次编译都不同，用户报问题时能直接说清装的是哪一版
 * （沿用 app/build.gradle.kts 里写好的 BuildConfig.BUILD_TIME_MS）。
 */
@Composable
private fun IdentityCard(icon: ImageBitmap?, versionName: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                // 图标是满幅的方形，这里套一层圆角，免得在卡片里显得像一块灰板。
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(text = "Eropic", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "个人图片与存储管理工具",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "开发者 33kurumi", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "版本 " + versionName + " · 构建 " + formatBuildTime(BuildConfig.BUILD_TIME_MS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 开源地址：整行可点，点了用浏览器打开 GitHub。
 */
@Composable
private fun GitHubCard() {
    val context = LocalContext.current
    val repoUrl = "https://github.com/33kurumi/Eropic"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openUrl(context, repoUrl) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = "GitHub 源码", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "github.com/33kurumi/Eropic",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = "用浏览器打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 联系方式：点整行用邮件应用给开发者写信。
 *
 * 用 ACTION_SENDTO + mailto：ACTION_VIEW 在部分机型上会被浏览器接走，
 * 选错应用后「发邮件」就变成了打开网页。
 */
@Composable
private fun ContactCard() {
    val context = LocalContext.current
    val email = "Aemeath33@163.com"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "联系开发者",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openMail(context = context, address = email) }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = "发邮件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = email, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "问题反馈、功能建议，或把设置页打包的日志发到这里都可以。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 用系统邮件应用写一封信；没装邮件应用时静默失败（不弹警告打断阅读）。
 */
private fun openMail(context: Context, address: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address)))
    }
}

/**
 * 分组外框，样式对齐设置页的 SettingsSection：
 * 底色 surfaceContainerLow、无阴影，标题在框内顶部，下面一条分隔线。
 */
@Composable
private fun AboutSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            content()
        }
    }
}

/**
 * 隐私协议的一条：前面一个点，正文自动换行。
 */
@Composable
private fun PrivacyLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 更新日志的一块：版本号 + 发布日期一行，下面逐条列出这一版的变化。
 *
 * 之前是「版本号 + 一整段话」，话一长就没有重点；1.0 起统一改成一条一句话，
 * 日期放在版本号右边，各版本之间靠调用方的分隔线区分。
 */
@Composable
private fun ChangelogRow(entry: ChangelogEntry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.version,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = entry.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.items.forEach { item ->
            // 复用隐私协议那条「· + 正文」的样式，全是小字、自动换行。
            PrivacyLine(item)
        }
    }
}

/** 构建时间戳 → 可读的本地时间；构建脚本没写值时就留空。 */
private fun formatBuildTime(ms: Long): String {
    if (ms <= 0L) return "未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        .format(java.util.Date(ms))
}

/** 用系统自带浏览器打开链接；没有可用浏览器时静默失败（不弹警告打断阅读）。 */
private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/**
 * 取应用图标并画成位图。
 *
 * 为什么不用 painterResource(R.mipmap.ic_launcher)：清单里的 ic_launcher 是 adaptive-icon 的 XML，
 * painterResource 只认矢量和位图，碰到自适应图标解不出来（空白或直接抛异常）。
 * 这里改成向 PackageManager 要系统已经算好的图标 Drawable，再画进一张位图，各版本都稳。
 */
private fun loadAppIcon(context: Context): ImageBitmap? {
    return runCatching {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        // 192px 够显示 76dp（按 2 倍多算），也不会为了一个图标占太多内存。
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
