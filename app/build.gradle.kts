plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() && !releaseKeystorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()
val updateManifestUrl = (System.getenv("REMOTELINK_UPDATE_MANIFEST_URL") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "app.remotelink"
    compileSdk = 36
    defaultConfig { applicationId = "app.remotelink"; minSdk = 26; targetSdk = 36; versionCode = 26; versionName = "0.9.11-alpha" }
    buildFeatures { buildConfig = true }
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") { dimension = "distribution"; buildConfigField("boolean", "DIRECT_UPDATES", "true"); buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"") }
        create("play") { dimension = "distribution"; buildConfigField("boolean", "DIRECT_UPDATES", "false"); buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"") }
    }
    signingConfigs { if (hasReleaseSigning) { create("release") { storeFile=file(releaseKeystorePath!!);storePassword=releaseKeystorePassword;keyAlias=releaseKeyAlias;keyPassword=releaseKeyPassword;enableV1Signing=true;enableV2Signing=true;enableV3Signing=true;enableV4Signing=true } } }
    buildTypes { release { isMinifyEnabled=false;isShrinkResources=false;proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro");if(hasReleaseSigning)signingConfig=signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation("io.github.webrtc-sdk:android:144.7559.12");implementation("androidx.core:core:1.17.0");implementation("com.google.zxing:core:3.5.3") }
