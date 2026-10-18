plugins { id("com.android.application") }
android {
    namespace = "org.rv4a.captureprobe"
    compileSdk = 35
    defaultConfig { applicationId = providers.gradleProperty("probePackage").getOrElse("org.rv4a.captureprobe"); minSdk = 34; targetSdk = 34; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
