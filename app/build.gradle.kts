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
        versionCode = 37
        versionName = "0.2.35"
    }
    // Only local debug builds may package developer credentials.
    sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())
    sourceSets.getByName("main").resources.srcDir("../third_party")
    sourceSets.getByName("debug").assets.srcDir("src/main/assets")
    signingConfigs {
        create("distribution") {
            System.getenv("OPENRUN_KEYSTORE")?.let { storeFile=file(it) }
            storePassword=System.getenv("OPENRUN_STORE_PASSWORD")
            keyAlias=System.getenv("OPENRUN_KEY_ALIAS")
            keyPassword=System.getenv("OPENRUN_KEY_PASSWORD")
        }
    }
    buildTypes.getByName("release") {
        if(System.getenv("OPENRUN_KEYSTORE")!=null) signingConfig=signingConfigs.getByName("distribution")
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
