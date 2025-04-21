import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlin.serialization)
}

group = "eu.anifantakis"
version = "1.0.0"

kotlin {
    androidTarget {
        publishLibraryVariants("release", "debug")

        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "reanimator"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Navigation Library enables SavedStateHandle inside commonMain
            implementation(libs.jetbrains.compose.navigation)

            // Serialization library to handle complex data types for SavedStateHandle
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "eu.anifantakis.lib.reanimator"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()
    coordinates(
        groupId =  group.toString(),
        artifactId = "reanimator",
        version = version.toString()
    )

    pom {
        name = "Reanimator MultiPlatform"
        description = "Library to enable process death persistence in MVI architectures"
        inceptionYear = "2025"
        url = "https://github.com/ioannisa/reanimator"
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "ioannis-anifantakis"
                name = "Ioannis Anifantakis"
                url = "https://anifantakis.eu"
                email = "ioannisanif@gmail.com"
            }
        }
        scm {
            url = "https://github.com/ioannisa/reanimator"
            connection = "scm:git:https://github.com/ioannisa/reanimator.git"
            developerConnection = "scm:git:ssh://git@github.com/ioannisa/reanimator.git"
        }
    }
}

task("testClasses") {}