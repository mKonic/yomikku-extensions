plugins {
    id("yomikku.library")
}

// The app's own classes, as a compile-time view for extensions. Never packaged: extensions depend on this as
// compileOnly, and the app supplies the real implementation when it loads them.
