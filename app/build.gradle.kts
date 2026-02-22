plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// IMPORTANT:
// Sceneform (LightEstimationKt) calls LightEstimate.acquireEnvironmentalHdrCubeMap().
// If an older ARCore client lib ends up in the APK, you get NoSuchMethodError at runtime.
// Pin ARCore to a version that definitely contains this API and force it across all configs.

val versionCodeValue = 1
val versionNameValue = "1.0"

android {
    namespace = "com.example.aibrain"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aibrain"
        minSdk = 27
        targetSdk = 34
        versionCode = versionCodeValue
        versionName = versionNameValue
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val backendBaseUrl = (project.findProperty("backendBaseUrl") as String?) ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "BACKEND_BASE_URL", "\"${backendBaseUrl}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { buildConfig = true }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }

    // Исключаем конфликтующие META-INF файлы из зависимостей AR-библиотек
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

// ВАЖНО: com.gorisse.thomas.sceneform вызывает Environmental HDR API
// (LightEstimate.acquireEnvironmentalHdrCubeMap).
// Если ARCore занижен/разъезжается по транзитивным зависимостям,
// получаем NoSuchMethodError и падение на старте камеры.
// Поэтому фиксируем одну (достаточно новую) версию ARCore для всего приложения.
val arCoreVersion = "1.39.0"
configurations.configureEach {
    resolutionStrategy.force("com.google.ar:core:$arCoreVersion")
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle (ViewModel + viewModelScope)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("com.google.ar:core:$arCoreVersion")

    // Sceneform (maintained continuation)
    implementation("com.gorisse.thomas.sceneform:sceneform:1.23.0")
    implementation("com.gorisse.thomas.sceneform:ux:1.23.0")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // OkHttp (timeouts/logging)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
