# Native exports, constructor lookups, and callbacks invoked by name from Rust.
-keep class dev.rprobedesk.NativeCore { *; }
-keep class dev.rprobedesk.DiscoveryResult { *; }
-keep class dev.rprobedesk.ShellStreams { *; }
-keep class dev.rprobedesk.SyncStreams { *; }
-keep class dev.rprobedesk.MonitorReading { *; }
-keep class dev.rprobedesk.PeerRejected { *; }
-keep interface dev.rprobedesk.TransportListener { *; }
-keepclassmembers class * implements dev.rprobedesk.TransportListener { *; }
# The Android adapter inspects the established TLS socket on this exact transport.
-keep class io.github.muntashirakon.adb.AdbConnection { *; }
-keep class org.conscrypt.** { *; }
-keep class dev.rprobedesk.TransportFailure { *; }
