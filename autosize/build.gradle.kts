import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    signing
}

fun configuredValue(propertyName: String, envName: String, fallback: String): String {
    val prop = providers.gradleProperty(propertyName).map { it.trim() }.orNull
    if (!prop.isNullOrBlank()) return prop
    val env = providers.environmentVariable(envName).map { it.trim() }.orNull
    if (!env.isNullOrBlank()) return env
    return fallback
}

val publishedGroupId = configuredValue("publishedGroupId", "PUBLISHED_GROUP_ID", "io.github.donglua.autosize")
val publishedArtifactId = configuredValue("publishedArtifactId", "PUBLISHED_ARTIFACT_ID", "autosize")
val publishedVersion = configuredValue(
    "publishedVersion",
    "PUBLISHED_VERSION",
    configuredValue("releaseVersion", "RELEASE_VERSION", "0.1.0-alpha01")
)
val publishedName = configuredValue("publishedName", "PUBLISHED_NAME", "AndroidScreenAdapt AutoSize")
val publishedDescription = configuredValue(
    "publishedDescription",
    "PUBLISHED_DESCRIPTION",
    "Modern Kotlin-first Android screen adaptation library with current-Window DisplayMetrics engine."
)
val publishedUrl = configuredValue("publishedUrl", "PUBLISHED_URL", "https://github.com/donglua/AndroidScreenAdapt")
val publishedLicenseName = configuredValue("publishedLicenseName", "PUBLISHED_LICENSE_NAME", "The Apache License, Version 2.0")
val publishedLicenseUrl = configuredValue("publishedLicenseUrl", "PUBLISHED_LICENSE_URL", "https://www.apache.org/licenses/LICENSE-2.0.txt")
val publishedDeveloperId = configuredValue("publishedDeveloperId", "PUBLISHED_DEVELOPER_ID", "donglua")
val publishedDeveloperName = configuredValue("publishedDeveloperName", "PUBLISHED_DEVELOPER_NAME", "donglua")
val publishedDeveloperEmail = configuredValue("publishedDeveloperEmail", "PUBLISHED_DEVELOPER_EMAIL", "donglua@users.noreply.github.com")
val sonatypeNamespace = configuredValue("sonatypeNamespace", "SONATYPE_NAMESPACE", "io.github.donglua")
val sonatypeUsername = configuredValue("sonatypeUsername", "SONATYPE_USERNAME", "")
val sonatypePassword = configuredValue("sonatypePassword", "SONATYPE_PASSWORD", "")
val sonatypePublishingType = providers.gradleProperty("sonatypePublishingType").getOrElse("user_managed")
val signingKey = configuredValue("signingKey", "SIGNING_KEY", "")
val signingPassword = configuredValue("signingPassword", "SIGNING_PASSWORD", "")

group = publishedGroupId
version = publishedVersion

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
            withJavadocJar()
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
            groupId = publishedGroupId
            artifactId = publishedArtifactId
            version = publishedVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set(publishedName)
                description.set(publishedDescription)
                url.set(publishedUrl)
                licenses {
                    license {
                        name.set(publishedLicenseName)
                        url.set(publishedLicenseUrl)
                    }
                }
                developers {
                    developer {
                        id.set(publishedDeveloperId)
                        name.set(publishedDeveloperName)
                        email.set(publishedDeveloperEmail)
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/donglua/AndroidScreenAdapt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/donglua/AndroidScreenAdapt.git")
                    url.set(publishedUrl)
                }
            }
        }
    }

    repositories {
        maven {
            name = "ReleaseCheck"
            url = uri(layout.buildDirectory.dir("release-repository"))
        }

        maven {
            name = "Sonatype"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = sonatypeUsername
                password = sonatypePassword
            }
        }

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
    }
}

val hasInMemorySigningKey = signingKey.isNotBlank() && signingPassword.isNotBlank()
val hasFileSigning = findProperty("signing.secretKeyRingFile") != null && findProperty("signing.password") != null

signing {
    isRequired = hasInMemorySigningKey || hasFileSigning
    if (hasInMemorySigningKey) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["release"])
}

tasks.withType<Sign>().configureEach {
    onlyIf { hasInMemorySigningKey || hasFileSigning }
}

tasks.register("prepareRelease") {
    group = "publishing"
    description = "Publishes release AAR to local ReleaseCheck repository and verifies artifacts and POM coordinates."
    dependsOn("publishReleasePublicationToReleaseCheckRepository")
    doLast {
        val repository = layout.buildDirectory.dir("release-repository").get().asFile
        val directory = File(repository, "${publishedGroupId.replace('.', '/')}/$publishedArtifactId/$publishedVersion")
        val base = "$publishedArtifactId-$publishedVersion"
        listOf(".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module").forEach { suffix ->
            val output = File(directory, "$base$suffix")
            if (!output.isFile || output.length() == 0L) {
                throw GradleException("Missing publication artifact: $output")
            }
        }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(directory, "$base.pom"))
        fun pomTag(tag: String): String = doc.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
        val pomGroup = pomTag("groupId")
        val pomArtifact = pomTag("artifactId")
        val pomVersion = pomTag("version")
        val pomPackaging = pomTag("packaging")
        if (pomGroup != publishedGroupId || pomArtifact != publishedArtifactId ||
            pomVersion != publishedVersion || pomPackaging != "aar"
        ) {
            throw GradleException("Incorrect coordinates in $base.pom: $pomGroup:$pomArtifact:$pomVersion ($pomPackaging)")
        }
        val metadata = JsonSlurper().parse(File(directory, "$base.module")) as Map<*, *>
        val component = metadata["component"] as Map<*, *>
        if (component["group"] != publishedGroupId || component["module"] != publishedArtifactId ||
            component["version"] != publishedVersion
        ) {
            throw GradleException("Incorrect coordinates in $base.module")
        }
        listOf("-sources.jar", "-javadoc.jar").forEach { suffix ->
            ZipFile(File(directory, "$base$suffix")).use { archive ->
                if (archive.size() == 0) throw GradleException("Empty $base$suffix")
            }
        }
        logger.lifecycle("Verified $publishedGroupId:$publishedArtifactId:$publishedVersion")
    }
}

tasks.register("validateSonatypePublication") {
    group = "publishing"
    description = "Validates coordinates, POM metadata, Sonatype credentials, and GPG signing before Central upload."
    doLast {
        if (sonatypePublishingType !in listOf("user_managed", "automatic")) {
            throw GradleException("sonatypePublishingType must be user_managed or automatic")
        }
        val semverRegex = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$")
        if (!publishedVersion.matches(semverRegex)) {
            throw GradleException("A valid publishedVersion is required, got: $publishedVersion")
        }
        val requiredValues = mapOf(
            "publishedGroupId" to publishedGroupId,
            "publishedArtifactId" to publishedArtifactId,
            "publishedVersion" to publishedVersion,
            "publishedName" to publishedName,
            "publishedDescription" to publishedDescription,
            "publishedUrl" to publishedUrl,
            "publishedLicenseName" to publishedLicenseName,
            "publishedLicenseUrl" to publishedLicenseUrl,
            "publishedDeveloperName" to publishedDeveloperName,
            "publishedDeveloperEmail" to publishedDeveloperEmail,
            "sonatypeNamespace" to sonatypeNamespace,
            "sonatypeUsername" to sonatypeUsername,
            "sonatypePassword" to sonatypePassword
        )
        val missing = requiredValues.filterValues { it.isBlank() }.keys.toMutableList()
        if (!hasInMemorySigningKey && !hasFileSigning) {
            missing += "GPG signing configuration (SIGNING_KEY / SIGNING_PASSWORD)"
        }
        if (missing.isNotEmpty()) {
            throw GradleException("Sonatype publication is missing: ${missing.joinToString(", ")}")
        }
    }
}

tasks.register("publishToSonatype") {
    group = "publishing"
    description = "Uploads signed AAR to Sonatype OSSRH staging and submits deployment to central.sonatype.org."
    dependsOn("validateSonatypePublication", "prepareRelease", "publishReleasePublicationToSonatypeRepository")
    doLast {
        val token = Base64.getEncoder().encodeToString("$sonatypeUsername:$sonatypePassword".toByteArray(Charsets.UTF_8))
        val endpoint = "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/$sonatypeNamespace?publishing_type=$sonatypePublishingType"
        val connection = (URI.create(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode < 400) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode >= 300) {
                throw GradleException("Sonatype Portal handoff failed with HTTP $responseCode: $body")
            }
            logger.lifecycle("Submitted $publishedGroupId:$publishedArtifactId:$publishedVersion ($sonatypePublishingType). Check https://central.sonatype.com/publishing")
        } finally {
            connection.disconnect()
        }
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
