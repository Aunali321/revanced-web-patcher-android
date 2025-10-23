# Keep Ktor
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }

# Keep ReVanced
-keep class app.revanced.** { *; }

# Keep Jackson
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }

# Keep Kotlin
-dontwarn kotlin.**
-keep class kotlin.** { *; }
