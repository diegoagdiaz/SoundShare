plugins {
    id("com.android.application")
}

android {
    namespace = "com.soundshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.soundshare"
        // AudioTrack con modo de baja latencia existe desde Android 8.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            // El capturador se ejecuta con app_process por nombre de clase:
            // sin minificar para no perder ese punto de entrada.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes.add("META-INF/**")
        resources.excludes.add("kotlin/**")
        resources.excludes.add("DebugProbesKt.bin")
    }
}
