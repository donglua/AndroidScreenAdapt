import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    signing
}

group = "cn.jingzhuan.adapt"
version = providers.gradleProperty("releaseVersion")
    .map { it.trim() }
    .orElse(providers.environmentVariable("RELEASE_VERSION").map { it.trim() })
    .getOrElse("")
    .ifBlank { "0.1.0-alpha01" }

android {
    namespace = "cn.jingzhuan.adapt.autosize"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    compileOnly(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.fragment)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.fragment)
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "cn.jingzhuan.adapt"
            artifactId = "autosize"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("AndroidScreenAdapt AutoSize")
                description.set("Modern Kotlin-first Android screen adaptation library with current-Window DisplayMetrics engine.")
                url.set("https://github.com/donglua/AndroidScreenAdapt")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/donglua/AndroidScreenAdapt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/donglua/AndroidScreenAdapt.git")
                    url.set("https://github.com/donglua/AndroidScreenAdapt")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/donglua/AndroidScreenAdapt")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }

        val customRepoUrl = providers.gradleProperty("mavenRepoUrl")
            .orElse(providers.environmentVariable("MAVEN_REPO_URL"))
            .orNull
        if (!customRepoUrl.isNullOrBlank()) {
            maven {
                name = "CustomMaven"
                url = uri(customRepoUrl)
                isAllowInsecureProtocol = customRepoUrl.startsWith("http://")
                credentials {
                    username = providers.gradleProperty("mavenUsername")
                        .orElse(providers.environmentVariable("MAVEN_USERNAME"))
                        .orNull
                    password = providers.gradleProperty("mavenPassword")
                        .orElse(providers.environmentVariable("MAVEN_PASSWORD"))
                        .orNull
                }
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey")
        .orElse(providers.environmentVariable("SIGNING_KEY"))
        .orNull
    val signingPassword = providers.gradleProperty("signingPassword")
        .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
        .orNull
    isRequired = !signingKey.isNullOrBlank()
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword.orEmpty())
        sign(publishing.publications["release"])
    }
}

val apiBaselineFile = layout.projectDirectory.file("api/autosize.api")

fun collectReleasePublicApi(projectDirPath: java.io.File, javapBin: String): String {
    val classesJar = File(projectDirPath, "build/intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar")
    require(classesJar.exists()) { "Release classes.jar not found at $classesJar" }

    val tempDir = File(projectDirPath, "build/tmp/api-inspect").apply {
        deleteRecursively()
        mkdirs()
    }
    val classNames = mutableListOf<String>()
    ZipFile(classesJar).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.endsWith(".class")) {
                val outFile = File(tempDir, entry.name)
                outFile.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                val internalName = entry.name.removeSuffix(".class")
                val isPublicPackage = internalName.startsWith("cn/jingzhuan/adapt/autosize/") &&
                    !internalName.startsWith("cn/jingzhuan/adapt/autosize/core/") &&
                    !internalName.startsWith("cn/jingzhuan/adapt/autosize/engine/") &&
                    !internalName.matches(Regex(".*\\$\\d+$")) &&
                    !internalName.endsWith("/R") &&
                    !internalName.contains("/R$") &&
                    !internalName.endsWith("/BuildConfig")
                if (isPublicPackage) {
                    classNames += internalName.replace('/', '.')
                }
            }
        }
    }
    classNames.sort()
    val sb = StringBuilder()
    for (cls in classNames) {
        val out = ByteArrayOutputStream()
        val process = ProcessBuilder(javapBin, "-classpath", tempDir.absolutePath, "-protected", cls)
            .redirectErrorStream(true)
            .start()
        process.inputStream.copyTo(out)
        val exit = process.waitFor()
        require(exit == 0) { "javap failed for $cls: $out" }
        val text = out.toString(Charsets.UTF_8)
            .lineSequence()
            .filterNot { it.startsWith("Compiled from ") }
            .joinToString("\n")
            .trim()
        // Only include public or protected top-level/nested types
        if (text.contains("public ") || text.contains("protected ")) {
            sb.append(text).append("\n\n")
        }
    }
    return sb.toString().trimEnd() + "\n"
}

tasks.register("dumpPublicApi") {
    group = "verification"
    description = "Dumps the public/protected Java/Kotlin API baseline to api/autosize.api"
    dependsOn("syncReleaseLibJars")
    doLast {
        val javapBin = File(System.getProperty("java.home"), "bin/javap").absolutePath
        val dumped = collectReleasePublicApi(projectDir, javapBin)
        val target = apiBaselineFile.asFile
        target.parentFile.mkdirs()
        target.writeText(dumped, Charsets.UTF_8)
        logger.lifecycle("Updated API baseline: ${target.absolutePath}")
    }
}

tasks.register("checkPublicApi") {
    group = "verification"
    description = "Verifies that release public API matches api/autosize.api"
    dependsOn("syncReleaseLibJars")
    doLast {
        val baseline = apiBaselineFile.asFile
        require(baseline.exists()) {
            "API baseline missing: ${baseline.absolutePath}. Run ./gradlew :autosize:dumpPublicApi first."
        }
        val javapBin = File(System.getProperty("java.home"), "bin/javap").absolutePath
        val actual = collectReleasePublicApi(projectDir, javapBin)
        val expected = baseline.readText(Charsets.UTF_8)
        if (actual != expected) {
            throw GradleException(
                "Public API mismatch against ${baseline.name}! Run ./gradlew :autosize:dumpPublicApi and review the diff."
            )
        }
    }
}

tasks.register("verifyCoreIsolation") {
    group = "verification"
    description = "Ensures cn.jingzhuan.adapt.autosize.core does not reference android.* or androidx.*"
    doLast {
        val coreDir = file("src/main/java/cn/jingzhuan/adapt/autosize/core")
        if (coreDir.exists()) {
            coreDir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { src ->
                    val violations = src.readLines()
                        .withIndex()
                        .filter { (_, line) ->
                            val trimmed = line.trim()
                            trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")
                        }
                    if (violations.isNotEmpty()) {
                        throw GradleException(
                            "Forbidden android/androidx import in core package (${src.name}): ${violations.joinToString()}"
                        )
                    }
                }
        }
    }
}

tasks.register("verifyAarPackaging") {
    group = "verification"
    description = "Verifies release AAR excludes me.jessyan classes and InitProvider declarations"
    dependsOn("bundleReleaseAar")
    doLast {
        val aar = file("build/outputs/aar/autosize-release.aar")
        require(aar.exists()) { "Release AAR not found at $aar" }
        ZipFile(aar).use { aarZip ->
            val manifestEntry = aarZip.getEntry("AndroidManifest.xml")
                ?: throw GradleException("AndroidManifest.xml missing from release AAR")
            val manifest = aarZip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).readText()
            if (manifest.contains("<provider") || manifest.contains("InitProvider")) {
                throw GradleException("Release AAR AndroidManifest.xml must not declare any <provider> or InitProvider:\n$manifest")
            }
            val classesEntry = aarZip.getEntry("classes.jar")
                ?: throw GradleException("classes.jar missing from release AAR")
            val tmpJar = File(temporaryDir, "classes.jar")
            aarZip.getInputStream(classesEntry).use { input ->
                tmpJar.outputStream().use { output -> input.copyTo(output) }
            }
            ZipFile(tmpJar).use { jarZip ->
                val forbidden = jarZip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("me/jessyan/") || it.endsWith("/InitProvider.class") }
                    .toList()
                if (forbidden.isNotEmpty()) {
                    throw GradleException("Release AAR contains forbidden classes: $forbidden")
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyCoreIsolation", "checkPublicApi", "verifyAarPackaging")
}
