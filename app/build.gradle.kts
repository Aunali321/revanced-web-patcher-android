plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "app.revanced.webpatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.revanced.webpatcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    
    packagingOptions {
        resources.excludes.addAll(
            listOf(
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/module-info.class"
            )
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Ktor server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-cors:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-server-status-pages:2.3.7")
    implementation("io.ktor:ktor-serialization-jackson:2.3.7")
    implementation("io.ktor:ktor-server-call-logging:2.3.7")
    implementation("io.ktor:ktor-server-default-headers:2.3.7")
    
    // ReVanced libraries
    implementation("app.revanced:revanced-patcher:21.0.0")
    implementation("app.revanced:revanced-library:3.1.0")
    
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    
    implementation("ch.qos.logback:logback-classic:1.4.14")
}
