plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cn.dsr213.nasphoto"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.dsr213.nasphoto"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        // ---------------------------------------------------------------------------
        // ⚠️ 版本号规则（用户 2026-09-21 明确定下）：
        //    在作者**明确宣布「可正式分发」之前，版本号必须带 `-alpha` 后缀**。
        //    去掉后缀是一个需要用户明确拍板的动作，不是随手改的。
        // ---------------------------------------------------------------------------
        versionName = "0.1.0-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    /**
     * 打开 BuildConfig —— 让版本号有**单一来源**。
     *
     * 同一份版本号出现在三个地方（构建产物 / User-Agent / 主界面状态栏），
     * 各写各的字面量必然脱节（之前 UA 还停留在 `NasPhoto/0.1`）。
     * 现在三者都读 [BuildConfig.VERSION_NAME]，改 `versionName` 一处即可。
     */
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 传输层走 NAS 自带的 WebDAV（纯 HTTP），不再需要任何 SSH 库
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
