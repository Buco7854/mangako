// Top-level build file.
//
// The buildscript block pins javapoet 1.13.0 on the Gradle plugin classpath —
// Hilt 2.52's `hiltAggregateDepsXxx` task calls ClassName.canonicalName()
// which is a 1.13+ method. Without this pin some transitive resolves an older
// javapoet 1.x and the build dies with NoSuchMethodError. See:
// https://github.com/google/dagger/issues/3751
buildscript {
    dependencies {
        classpath("com.squareup:javapoet:1.13.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
