# osmdroid does not need special rules; silence optional-dependency warnings.
-dontwarn org.osmdroid.**
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
-dontwarn java.awt.**
-dontwarn javax.**
