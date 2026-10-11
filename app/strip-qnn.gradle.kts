// KMK -->
// Strips the QNN context binaries (assets/qnn-contexts/*) from the non-arm64 split APKs
// after packaging when `-Pexclude-qnn-assets` is set: they only run on arm64-v8a devices,
// so carrying ~437 MB of them in the other splits is dead weight. Deleting entries breaks
// the APK signature, so the stripped splits are realigned with zipalign and re-signed with
// the debug keystore (CI signs the outputs again with the release keystore).
//
// Applied from app/build.gradle.kts. Kept in a separate script to keep the main build
// file small; every `package<Variant>` task of this module gets a matching strip task.
// KMK <--

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

val excludeQnnAssets = providers.gradleProperty("exclude-qnn-assets").isPresent

val localSdkProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun findBuildToolsDir(): File {
    val sdkDir = sequenceOf(
        localSdkProperties.getProperty("sdk.dir"),
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    ).firstOrNull { !it.isNullOrBlank() }
        ?: throw GradleException("Android SDK not found (sdk.dir, ANDROID_HOME or ANDROID_SDK_ROOT required for zipalign)")

    return File(sdkDir, "build-tools").listFiles()
        ?.filter { File(it, "zipalign").isFile || File(it, "zipalign.exe").isFile }
        ?.maxByOrNull { it.name.replace(".", "").toLongOrNull() ?: 0L }
        ?: throw GradleException("No build-tools providing zipalign found under $sdkDir")
}

abstract class StripQnnAssetsFromApkTask : DefaultTask() {

    @get:Internal
    abstract val apkDir: DirectoryProperty

    @get:Internal
    abstract val buildToolsDir: DirectoryProperty

    @TaskAction
    fun strip() {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val exeSuffix = if (isWindows) ".exe" else ""
        val javaBin = File(System.getProperty("java.home"), "bin/java$exeSuffix")
        val keytoolBin = File(System.getProperty("java.home"), "bin/keytool$exeSuffix")
        val zipalignBin = File(buildToolsDir.get().asFile, "zipalign$exeSuffix")
        val apksignerJar = File(buildToolsDir.get().asFile, "lib/apksigner.jar")
        val keystore = File(System.getProperty("user.home"), ".android/debug.keystore")

        listOf(zipalignBin, apksignerJar).forEach {
            if (!it.isFile) throw GradleException("Required build-tools file missing: $it")
        }
        if (!keystore.isFile) {
            keystore.parentFile.mkdirs()
            runCommand(
                keytoolBin.absolutePath, "-genkeypair",
                "-keystore", keystore.absolutePath,
                "-storepass", "android", "-keypass", "android",
                "-alias", "androiddebugkey",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                "-dname", "CN=Android Debug,O=Android,C=US",
            )
        }

        val qnnPrefix = "assets/qnn-contexts/"
        val apks = apkDir.get().asFile.listFiles { file ->
            file.isFile && file.name.startsWith("app-") && file.name.endsWith(".apk")
        }.orEmpty().filter { apk ->
            // The universal APK contains arm64-v8a libraries, so it needs the models too.
            !apk.name.contains("universal") && !apk.name.contains("arm64-v8a")
        }

        for (apk in apks) {
            val qnnEntries = ZipFile(apk).use { zip ->
                zip.entries().asSequence().count { it.name.startsWith(qnnPrefix) }
            }
            if (qnnEntries == 0) {
                logger.lifecycle("No QNN models in ${apk.name}, skipping.")
                continue
            }

            val stripped = File(apk.parentFile, "${apk.nameWithoutExtension}.stripped.apk")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(stripped))).use { out ->
                ZipFile(apk).use { zip ->
                    for (entry in zip.entries().asSequence().filterNot { it.name.startsWith(qnnPrefix) }) {
                        out.putNextEntry(ZipEntry(entry))
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }

            val aligned = File(apk.parentFile, "${apk.nameWithoutExtension}.aligned.apk")
            runCommand(zipalignBin.absolutePath, "-f", "4", stripped.absolutePath, aligned.absolutePath)
            runCommand(
                javaBin.absolutePath, "-jar", apksignerJar.absolutePath, "sign",
                "--ks", keystore.absolutePath,
                "--ks-pass", "pass:android",
                "--ks-key-alias", "androiddebugkey",
                "--key-pass", "pass:android",
                aligned.absolutePath,
            )
            runCommand(javaBin.absolutePath, "-jar", apksignerJar.absolutePath, "verify", aligned.absolutePath)

            Files.move(
                aligned.toPath(),
                apk.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            stripped.delete()
            logger.lifecycle("Stripped $qnnEntries QNN entries from ${apk.name}.")
        }
    }

    private fun runCommand(vararg command: String) {
        val process = ProcessBuilder(command.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("Command failed (${process.exitValue()}): ${command.joinToString(" ")}\n$output")
        }
    }
}

if (excludeQnnAssets) {
    // Keep in sync with the buildTypes in app/build.gradle.kts; a variant missing from
    // this list simply keeps its QNN assets.
    val buildTools = findBuildToolsDir()
    listOf("debug", "release", "releaseTest", "foss", "preview", "viel", "benchmark").forEach { variantName ->
        val capitalized = variantName.replaceFirstChar { it.uppercaseChar() }
        tasks.register("stripQnnModels$capitalized", StripQnnAssetsFromApkTask::class) {
            apkDir.set(layout.projectDirectory.dir("build/outputs/apk/$variantName"))
            buildToolsDir.set(buildTools)
        }
        tasks.matching { it.name == "package$capitalized" }.configureEach {
            finalizedBy("stripQnnModels$capitalized")
        }
    }
}
