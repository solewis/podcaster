plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.solewis.podcaster"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.solewis.podcaster"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "com.solewis.podcaster.testing.PodcasterTestRunner"
    }

    sourceSets {
        // Bundles exported schema JSON as a test asset so MigrationTestHelper can load each past
        // version by number instead of needing the actual entity/@Database classes from that version.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Custom ExoPlayer caching and MediaSession artwork loading both require touching
        // Media3's @UnstableApi surface - there is no stable alternative for this functionality,
        // and every real Media3 app makes this same deliberate tradeoff (including Media3's own
        // sample apps, which disable this same check). The module-wide Kotlin compiler opt-in
        // above covers compilation; this covers AGP's separate lint pass for the same annotation.
        disable += "UnsafeOptInUsageError"
        // Fires because the app publishes a MediaBrowserService, which lint takes as a promise to
        // support "play <something> from search" by voice. It isn't one: there is no voice search
        // here, and adding an intent-filter for an action nothing handles would be worse than the
        // warning. Revisit if voice search is ever built.
        disable += "MissingIntentFilterForMediaSearch"
    }
}

/**
 * Unit tests run on the debug variant only.
 *
 * Release would otherwise run the exact same code a second time - minification is off and there are
 * no variant-specific sources - so it doubled the suite's runtime for no coverage. It also could not
 * work: the Compose test harness hosts its content in a ComponentActivity contributed to the
 * manifest by `ui-test-manifest`, which is a debug-only dependency, so every screen and navigation
 * test failed on release with "Unable to resolve activity".
 */
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as com.android.build.api.variant.HasHostTestsBuilder)
            .hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]
            ?.enable = false
    }
}

room {
    // Committed to git from here on - schema history is what makes a future non-destructive
    // Room migration possible instead of falling back to wiping the database.
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Almost all of Media3's cache/datasource surface (SimpleCache, CacheDataSource,
        // DataSourceBitmapLoader, ...) is marked @UnstableApi. Opting in module-wide is the
        // standard approach for Media3 apps rather than annotating every call site individually.
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric lets the repository and (later) ViewModel tests run a real Room database on the
    // JVM, so they land in the `test` source set and run in CI - which only executes `test`.
    // Instrumented tests stay for what genuinely needs a device: ExoPlayer, MediaSession, and
    // Android's own XML parser (see RssParserOnDeviceTest).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Compose UI tests under Robolectric, so screen and navigation behavior is covered by the
    // same `test` task CI already runs. ui-test-manifest supplies the activity the harness hosts.
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.arch.core.testing)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
