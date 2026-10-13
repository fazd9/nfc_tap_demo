plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.pri.nfctouchdemo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.pri.nfctouchdemo"
        // 融合后取两边的交集上限：动效层（Test）要求 27（GLES 2.0 + Canvas + 若干 API 分支），
        // NFC/传输层（nfc_tap_demo）原来写的是 23，用到的 NFC / WifiP2p / LocalOnlyHotspot API
        // 全部在 27 之前就有，所以统一到 27 不会丢能力。
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名：照 D:\A17\X\test\LongShotScreen 的写法 —— 工程根目录带一份 debug keystore，
    // release 也拿它签（省掉额外的 release 密钥，装真机时签名身份也稳定）。
    signingConfigs {
        // "debug" 这个配置 AGP 默认就会创建，所以这里只改取值，不能用 create（重名会报错）
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 同 LongShotScreen：release 直接走 debug 签名
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        // 混合语言：动效层是 Kotlin（Test），NFC/传输层是 Java（nfc_tap_demo）。
        // 两个源工程里都没有 kotlin 插件（AGP 9.3.1 自带 Kotlin 编译），保持这个配置；
        // Java 侧原来是 11，统一抬到 17 与 Kotlin 侧对齐，避免 jvmTarget 不一致。
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
