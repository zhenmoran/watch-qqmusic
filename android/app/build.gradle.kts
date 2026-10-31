import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * 服务器地址来源优先级：
 *   1. gradle 命令行 -PserverUrl=http://xxx:3000
 *   2. local.properties 里的 serverUrl=
 *   3. 下面这个占位值
 * 手表和服务器要在同一局域网（或服务器有公网地址）。
 */
val serverUrl: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    (project.findProperty("serverUrl") as String?)
        ?: props.getProperty("serverUrl")
        ?: "http://192.168.1.100:3000"
}

android {
    namespace = "com.henmoran.watchmusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.henmoran.watchmusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
}

dependencies {
    // 刻意不引 AppCompat / Compose：壳越薄，手表上内存越省、启动越快
}
