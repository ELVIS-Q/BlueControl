// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        classpath ("com.google.gms:google-services:4.4.0")// 👈 OBLIGATORIO para Firebase
    }
}

plugins {
    id ("com.android.application") version "8.9.1" apply false
}

