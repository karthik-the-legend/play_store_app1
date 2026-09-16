# Project-specific R8 rules.
#
# Hilt, Navigation (kotlinx.serialization routes), DataStore and MediaPipe ship their own
# consumer rules.

# PdfBox-Android (Milestone 5). The JPEG 2000 decoder is an optional extra PdfBox looks for, and
# BouncyCastle is excluded on purpose: it only serves certificate-encrypted PDFs.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# WorkManager, which the ads SDK pulls in, builds its Room database by looking the generated class
# up by name and calling its no-argument constructor. The rule Room ships keeps the class but not
# its members, so R8 removed that constructor and every release build crashed on startup with
# "Failed to create an instance of androidx.work.impl.WorkDatabase" (found in Milestone 8).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Type-safe navigation identifies a route by the fully qualified name of its class, and an enum
# argument is looked up with Class.forName. kotlinx.serialization's own rules keep the generated
# serializers but let R8 rename the classes, which left the app unable to open any tool in a
# release build (found in Milestone 8).
-keepnames @kotlinx.serialization.Serializable class app.formkit.navigation.**

# MediaPipe (background removal). Its tasks are wired up through JNI and protobuf-lite, both of
# which find classes and fields by name, so R8 left the segmenter unable to start and every
# passport photo came back as "Couldn't open this photo" (found in Milestone 8).
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# MediaPipe logs through Flogger, which finds the calling class by walking the stack and comparing
# it with its own class name. Renaming that class made the comparison fail ("no caller found on the
# stack"), which took the app down with it the moment a passport photo was opened.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
