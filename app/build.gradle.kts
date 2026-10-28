// 构建时间戳：versionCode 直接用它，保证每次装包都被系统当成"新版本"，
// 同时写进 BuildConfig，供"关于"页显示具体构建时间。导出的 APK 文件名也带上它，
// 避免手机浏览器/文件管理器把新旧两个 APK 当成同一个文件（用户反馈过这个问题）。
// 注意：脚本里的 `java` 是 Gradle 的 JavaPluginExtension，所以这里必须用 import 而不是全限定名。
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val buildTimeMs = System.currentTimeMillis()
val buildStamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(buildTimeMs))
val appVersionName = "0.2.0"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.kurumi33.eropic"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.kurumi33.eropic"
        minSdk = 31
        targetSdk = 36
        // 秒级时间戳约 1.79e9，仍在 versionCode 上限（2100000000）以内，且每次构建都更大。
        versionCode = (buildTimeMs / 1000L).toInt()
        versionName = appVersionName

        buildConfigField("long", "BUILD_TIME_MS", "${buildTimeMs}L")
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// 直接改 AGP 生成的 APK 文件名，例如 app/build/outputs/apk/debug/Eropic-debug-0.2.0-20261028-0140.apk。
// 文件名里带构建时间，手机浏览器/文件管理器就不会把新旧两个 APK 当成同一个文件（用户反馈过这个问题）。
// 说明：AGP 8.x 的 VariantOutput 没有公开的 outputFileName setter，只有实现类有，
// 所以这里用反射取 Property 再 set；拿不到就静默跳过（仍会产出默认名，不影响构建）。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            runCatching {
                val getter = output.javaClass.methods.firstOrNull { it.name == "getOutputFileName" }
                val property = getter?.invoke(output) as? org.gradle.api.provider.Property<String>
                property?.set("Eropic-${variant.name}-$appVersionName-$buildStamp.apk")
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.kotlinx.coroutines.android)
    // M4：读取原始 EXIF（查看器详情页）
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // v0.2：联网识图（以图搜图上传 multipart）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}