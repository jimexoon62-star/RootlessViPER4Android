plugins { id("com.android.application") }
android {
    namespace = "org.rv4a.captureprobe"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig { applicationId = providers.gradleProperty("probePackage").getOrElse("org.rv4a.captureprobe"); minSdk = 34; targetSdk = 34; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
