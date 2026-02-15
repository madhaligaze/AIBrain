// Настройки плагинов для всего проекта
buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.8.10" apply false
}