// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
}

tasks.register<Copy>("copyApkToOutputs") {
    dependsOn(":app:assembleDebug")
    from("app/build/outputs/apk/debug/app-debug.apk")
    into(".build-outputs")
}

tasks.register<Copy>("copyApkToDownload") {
    dependsOn(":app:assembleDebug")
    from("app/build/outputs/apk/debug/app-debug.apk")
    into("APK_DOWNLOAD")
}

tasks.register("verifyApk") {
    dependsOn("copyApkToOutputs", "copyApkToDownload")
    doLast {
        val path1 = ".build-outputs/app-debug.apk"
        val path2 = "APK_DOWNLOAD/app-debug.apk"
        for (path in listOf(path1, path2)) {
            val f = java.io.File(path)
            if (!f.exists()) {
                throw GradleException("Verification failed: $path does not exist!")
            }
            val size = f.length()
            println("[VERIFY] Path: $path, Size: $size bytes (${String.format("%.2f", size.toDouble() / (1024 * 1024))} MB)")
            if (size < 1024 * 1024) {
                throw GradleException("Verification failed: $path is too small ($size bytes), expected > 1 MB!")
            }
            try {
                java.util.zip.ZipFile(f).use { zip ->
                    val entries = zip.entries()
                    var count = 0
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(8192)
                            while (input.read(buffer) > 0) { /* fully read and verify data integrity */ }
                        }
                        count++
                    }
                    println("[VERIFY] APK is valid! Read $count files successfully without any data errors.")
                }
            } catch (e: Exception) {
                throw GradleException("Verification failed: $path is corrupt or has invalid ZIP headers: ${e.message}", e)
            }
        }
    }
}

tasks.register("prepareApk") {
    dependsOn("verifyApk")
}
