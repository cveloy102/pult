plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "ru.hl100.pult"
    compileSdk = 37
    defaultConfig {
        applicationId = "ru.hl100.pult"
        minSdk = 31
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 2000
        versionName = providers.gradleProperty("versionName").orNull ?: "0.2.0"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    val releaseStore = System.getenv("PULT_KEYSTORE")
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs { create("release") { storeFile = file(releaseStore); storePassword = System.getenv("PULT_STORE_PASSWORD"); keyAlias = System.getenv("PULT_KEY_ALIAS"); keyPassword = System.getenv("PULT_KEY_PASSWORD") } }
    }
    buildTypes { release { isMinifyEnabled = false; if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Google code scanner: QR pairing without a camera permission in the app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
