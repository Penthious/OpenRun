plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.digitalducktape.openrun"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.digitalducktape.openrun"
        minSdk = 28
        targetSdk = 34
        versionCode = 25
        versionName = "0.2.23"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("com.garmin:fit:21.176.0")
    implementation("io.grpc:grpc-okhttp:1.60.1")
    implementation("io.grpc:grpc-stub:1.60.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
