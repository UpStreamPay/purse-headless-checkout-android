import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.validation.KotlinApiCompareTask
import kotlinx.validation.api.dump
import kotlinx.validation.api.extractAnnotatedPackages
import kotlinx.validation.api.filterOutAnnotated
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

// Root build: the plugins every module shares (applied per module) and the one-place configuration
// of the delivery chain — publishing coordinates, API docs, and the quality gates CI enforces
// (see docs/contributing/release.md and docs/contributing/ci.md).
buildscript {
    dependencies {
        // binary-compatibility-validator's signature loader reads Kotlin class metadata through
        // kotlin-metadata-jvm, which it expects the Kotlin Gradle plugin to have put on the
        // classpath — AGP's built-in Kotlin does not, so the apiDump/apiCheck tasks below need it.
        classpath("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")

        // Build-time only: these reach the plugin classpath through AGP (sdk-common, bundletool,
        // jetifier), never a published AAR. Raised past the versions GitHub's security advisories
        // flag, as in Secure Fields; drop each line once AGP ships the fixed version itself.
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.85")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85")
            classpath("org.bouncycastle:bcutil-jdk18on:1.85")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.commons:commons-lang3:3.18.0")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka)
    // Not applied: its Gradle integration keys on the `kotlin-android` plugin, which AGP's built-in
    // Kotlin does not apply. Its signature loader (`kotlinx.validation.api`) drives the apiDump /
    // apiCheck tasks below instead, so the dump format is the standard `.api` one.
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// ── Version ───────────────────────────────────────────────────────────────────────────────────────
// Not stored in any file: it lives in the git tag and reaches Gradle as SDK_VERSION, set by the
// staging/release workflows (see docs/contributing/release.md). Local builds are 0.0.0-SNAPSHOT.
val sdkVersion: String = System.getenv("SDK_VERSION") ?: "0.0.0-SNAPSHOT"

// ── Published artifacts ───────────────────────────────────────────────────────────────────────────
// Module → Maven artifactId under the `eu.purse` group (the same namespace as securefields-android).
// The SDK is one artifact; each partner that brings its own SDK is an optional one. `:app` is the
// demo and is never published.
val publishedArtifacts = mapOf(
    "headless-checkout" to "headless-checkout-android",
    "partner-googlepay" to "headless-checkout-googlepay",
    "partner-klarna" to "headless-checkout-klarna",
    "partner-paypal" to "headless-checkout-paypal",
)
val artifactDescriptions = mapOf(
    "headless-checkout" to "Purse headless checkout for Android: the payment engine (session, methods, split, /validate), " +
        "the payment elements, and the card and redirection payment methods.",
    "partner-googlepay" to "Purse headless checkout: the Google Pay plugin (Play Services Wallet).",
    "partner-klarna" to "Purse headless checkout: the Klarna installments plugin (Klarna Mobile SDK).",
    "partner-paypal" to "Purse headless checkout: the PayPal wallet plugin (PayPal Android SDK).",
)

// ── Public API surface tracking ───────────────────────────────────────────────────────────────────
// Every published module commits its public binary API to `<module>/api/<module>.api` (kotlinx
// binary-compatibility-validator's format). `./gradlew apiCheck` fails CI when the compiled release
// classes differ from the dump — a source- or binary-incompatible change is then a visible diff a
// reviewer approves by running `./gradlew apiDump`. Kotlin `internal` declarations and
// `@InternalPurseApi` ones (the seams the partner artifacts use) are left out.
val nonPublicMarkers = setOf("eu.purse.core.InternalPurseApi")

/** Dumps the public API of a library's release classes — what BCV's own build task does. */
abstract class ApiBuildTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ListProperty<RegularFile>

    @get:Input
    abstract val nonPublicMarkers: SetProperty<String>

    @get:OutputFile
    abstract val outputApiFile: RegularFileProperty

    @TaskAction
    fun build() {
        val classFiles = classesDirs.get().asSequence().flatMap { it.asFileTree.files.asSequence() } +
            jars.get().asSequence().flatMap { jar ->
                ZipFile(jar.asFile).use { zip -> zip.entries().asSequence().filter { it.name.endsWith(".class") }.map { zip.getInputStream(it).readBytes() }.toList() }
                    .map { it.inputStream() }
            }
        val streams = classFiles.mapNotNull { file ->
            when (file) {
                is java.io.File -> file.takeIf { it.isFile && it.name.endsWith(".class") }?.inputStream()
                is java.io.InputStream -> file
                else -> null
            }
        }
        val markers = nonPublicMarkers.get().map { it.replace('.', '/') }.toSet()
        val signatures = streams.loadApiFromJvmClasses()
        val hiddenPackages = signatures.extractAnnotatedPackages(markers)
        val filtered = signatures
            .filterOutNonPublic(nonPublicPackages = hiddenPackages, nonPublicClasses = emptyList())
            .filterOutAnnotated(markers)
        val dump = StringBuilder().also { filtered.dump(it) }.toString()
        // Compose generates one `ComposableSingletons$<File>Kt` holder per file whose lambda accessors
        // are named after a hash of the lambda body — internal in intent, public in bytecode, and
        // different after every edit. Not API: drop those class blocks from the dump.
        val blocks = dump.split("\n\n").filter { block -> !block.lineSequence().first().contains("ComposableSingletons\$") }
        outputApiFile.get().asFile.apply { parentFile.mkdirs() }.writeText(blocks.joinToString("\n\n").trimEnd() + "\n")
    }
}

val apiDump by tasks.registering { group = "verification"; description = "Refreshes every library's committed api/*.api dump." }
val apiCheck by tasks.registering { group = "verification"; description = "Fails when a library's public API differs from its committed api/*.api dump." }

subprojects {
    // ── Style + static analysis, every module including the demo ─────────────────────────────────
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<KtlintExtension> {
        android.set(true)
        filter { exclude { it.file.path.contains("/build/") } }
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        source.setFrom("src/main/kotlin", "src/test/kotlin")
    }

    // ── Publishing, API docs and API tracking — published modules only ──────────────────────────
    val artifactId = publishedArtifacts[name] ?: return@subprojects
    group = "eu.purse"
    version = sdkVersion

    // The module's own script applies the Android library plugin after this block runs.
    plugins.withId("com.android.library") {
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")

        configure<MavenPublishBaseExtension> {
            configure(
                AndroidSingleVariantLibrary(
                    variant = "release",
                    sourcesJar = SourcesJar.Sources(),
                    // The javadoc jar Maven Central requires is Dokka's HTML, generated from KDoc —
                    // the same output the docs workflow publishes, so the reference cannot drift.
                    javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                ),
            )
            // Staging (tag push) leaves the deployment pending in the Central Portal for
            // inspection; the manual release workflow passes -PautoRelease=true to promote it.
            val autoRelease = providers.gradleProperty("autoRelease").map { it.toBoolean() }.getOrElse(false)
            publishToMavenCentral(automaticRelease = autoRelease)
            // Signing is mandatory on Central; skipped locally when no key is configured so
            // `publishToMavenLocal` works on any machine.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            coordinates("eu.purse", artifactId, sdkVersion)

            pom {
                name.set("Purse Headless Checkout Android — $artifactId")
                description.set(artifactDescriptions.getValue(this@subprojects.name))
                url.set("https://github.com/UpStreamPay/purse-headless-checkout-android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/UpStreamPay/purse-headless-checkout-android/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("purse-eu")
                        name.set("Purse")
                        email.set("dev@purse.eu")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/UpStreamPay/purse-headless-checkout-android.git")
                    developerConnection.set("scm:git:github.com/UpStreamPay/purse-headless-checkout-android.git")
                    url.set("https://github.com/UpStreamPay/purse-headless-checkout-android")
                }
            }
        }

        // API dump / check on the release classes of this module only (dependencies excluded).
        val moduleName = this@subprojects.name
        val committedApi = layout.projectDirectory.file("api/$moduleName.api")
        val apiBuild = tasks.register<ApiBuildTask>("apiBuild") {
            group = "verification"
            description = "Dumps the public API of the release classes to build/api/$moduleName.api."
            @Suppress("UNCHECKED_CAST")
            nonPublicMarkers.set(this@subprojects.rootProject.extra.get("nonPublicMarkers") as Set<String>)
            outputApiFile.set(layout.buildDirectory.file("api/$moduleName.api"))
        }
        extensions.configure<LibraryAndroidComponentsExtension> {
            onVariants(selector().withBuildType("release")) { variant ->
                variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(apiBuild)
                    .toGet(ScopedArtifact.CLASSES, ApiBuildTask::jars, ApiBuildTask::classesDirs)
            }
        }
        val moduleApiDump = tasks.register<Copy>("apiDump") {
            group = "verification"
            description = "Overwrites api/$moduleName.api with the current public API."
            from(apiBuild.map { it.outputApiFile })
            into(layout.projectDirectory.dir("api"))
        }
        val moduleApiCheck = tasks.register<KotlinApiCompareTask>("apiCheck") {
            group = "verification"
            description = "Fails when the public API differs from api/$moduleName.api."
            projectApiFile.set(committedApi)
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
            dependsOn(apiBuild)
            // `./gradlew apiDump apiCheck` in one go: check the freshly written dump, not a stale one.
            mustRunAfter(moduleApiDump)
        }
        tasks.named("check") { dependsOn(moduleApiCheck) }
        apiDump { dependsOn(moduleApiDump) }
        apiCheck { dependsOn(moduleApiCheck) }
    }
}
extra["nonPublicMarkers"] = nonPublicMarkers

// ── API reference (Dokka) ─────────────────────────────────────────────────────────────────────────
// `./gradlew dokkaGenerate` writes one multi-module HTML reference (the SDK and the three partner
// artifacts) to build/dokka/html; the staging workflow attaches it to the GitHub release. Each
// module's own Dokka output is also its Maven Central javadoc jar.
dependencies {
    publishedArtifacts.keys.forEach { dokka(project(":$it")) }
}

dokka {
    moduleName.set("Purse Headless Checkout Android")
}

// ── 16 KB page-size alignment ─────────────────────────────────────────────────────────────────────
// Google Play requires apps targeting Android 15+ to support 16 KB memory pages: every native
// library (`.so`) on the app's release runtime classpath — the SDK's own, and each partner SDK's —
// must have its ELF LOAD segments aligned on 16 KB (p_align ≥ 0x4000). The SDK ships no native code
// itself; this task audits what the demo app (every partner artifact) pulls in, so a partner SDK
// upgrade that regresses is caught in CI. Findings live in docs/decisions/16kb-alignment.md.
abstract class Verify16kbAlignmentTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        runtimeClasspath.files.filter { it.name.endsWith(".aar") || it.name.endsWith(".jar") }.sortedBy { it.name }.forEach { archive ->
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".so") }.forEach { entry ->
                    val aligns = zip.getInputStream(entry).use { loadSegmentAlignments(it.readBytes()) }
                    val ok = aligns.isNotEmpty() && aligns.all { it >= 16384 }
                    val line = "${if (ok) "OK " else "BAD"} ${archive.name} ${entry.name} p_align=${aligns.joinToString(",") { "0x${it.toString(16)}" }}"
                    lines += line
                    if (!ok) failures += line
                }
            }
        }
        if (lines.isEmpty()) lines += "no native libraries on the release runtime classpath"
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
        lines.forEach { logger.lifecycle(it) }
        if (failures.isNotEmpty()) {
            throw GradleException("${failures.size} native librar${if (failures.size == 1) "y is" else "ies are"} not 16 KB aligned:\n${failures.joinToString("\n")}")
        }
    }

    /** `p_align` of every PT_LOAD program header of an ELF image (32- or 64-bit, either endianness). */
    private fun loadSegmentAlignments(elf: ByteArray): List<Long> {
        if (elf.size < 0x40 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte()) return emptyList()
        val is64 = elf[4].toInt() == 2
        val buffer = ByteBuffer.wrap(elf).order(if (elf[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val phoff = if (is64) buffer.getLong(0x20) else buffer.getInt(0x1c).toLong()
        val phentsize = buffer.getShort(if (is64) 0x36 else 0x2a).toInt() and 0xffff
        val phnum = buffer.getShort(if (is64) 0x38 else 0x2c).toInt() and 0xffff
        return (0 until phnum).mapNotNull { i ->
            val off = (phoff + i * phentsize).toInt()
            val type = buffer.getInt(off)
            if (type != 1) return@mapNotNull null // PT_LOAD
            if (is64) buffer.getLong(off + 0x30) else buffer.getInt(off + 0x1c).toLong()
        }
    }
}

tasks.register<Verify16kbAlignmentTask>("verify16kbAlignment") {
    group = "verification"
    description = "Fails when a native library on the demo app's release runtime classpath is not 16 KB page aligned."
    val app = project(":app")
    runtimeClasspath.from(app.configurations.named("releaseRuntimeClasspath").map { it.incoming.artifactView { lenient(true) }.files })
    report.set(layout.buildDirectory.file("reports/16kb-alignment.txt"))
}
