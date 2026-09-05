plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pdfx.extractor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pdfx.extractor"
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "pdfx123456"
            keyAlias = "pdfx"
            keyPassword = "pdfx123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE.txt", "META-INF/LICENSE", "META-INF/NOTICE.txt",
            "META-INF/NOTICE", "META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    // PDF 解析使用内置纯 Kotlin 引擎 PdfEngine，无第三方依赖
    testImplementation("junit:junit:4.13.2")
}
