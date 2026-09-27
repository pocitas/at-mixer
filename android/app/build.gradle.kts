val bridgeSdk = 37

plugins {
    id("com.android.application")
}

android {
    namespace = "com.atmixer.softrfbridge"
    compileSdk = bridgeSdk

    defaultConfig {
        applicationId = "com.atmixer.softrfbridge"
        minSdk = 31
        targetSdk = bridgeSdk
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
}
