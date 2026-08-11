plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xa.sharebox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xa.sharebox"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // AndroidX & Compose
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // FTP Client (Apache Commons Net)
    implementation("commons-net:commons-net:3.11.1")

    // SMB Client (smbj) — SMB2/3
    implementation("com.hierynomus:smbj:0.13.0")

    // SMB1 RAP (jcifs) — for share enumeration on old routers
    implementation("org.codelibs:jcifs:1.3.18.2")

    // FTP Server (Apache FtpServer)
    implementation("org.apache.ftpserver:ftpserver-core:1.1.4") {
        exclude(group = "org.slf4j")
    }
    implementation("org.slf4j:slf4j-simple:2.0.16")
}
