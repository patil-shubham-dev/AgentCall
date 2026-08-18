plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // Phase A (FCM push-to-wake): generates Firebase resource bindings from
    // google-services.json at build time.
    alias(libs.plugins.google.services) apply false
}
