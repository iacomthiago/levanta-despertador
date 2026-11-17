plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// O GitHub Actions passa o número da execução como versão, para que cada
// instalador novo possa ser instalado por cima do anterior.
val ciVersionCode = (project.findProperty("ciVersionCode") as String?)?.toIntOrNull() ?: 1

// Chave de assinatura fixa, vinda dos segredos do repositório. Sem ela o
// build usa a chave de depuração da máquina que compilar.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasKeystore = !keystorePath.isNullOrBlank()

android {
    namespace = "com.iacomthiago.levanta"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iacomthiago.levanta"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }

    signingConfigs {
        if (hasKeystore) {
            create("ci") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "levanta"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasKeystore) "ci" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.4.1")
}
