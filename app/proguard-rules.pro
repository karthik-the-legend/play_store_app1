# Project-specific R8 rules.
#
# Hilt, Navigation (kotlinx.serialization routes), DataStore and MediaPipe ship their own
# consumer rules.

# PdfBox-Android (Milestone 5). The JPEG 2000 decoder is an optional extra PdfBox looks for, and
# BouncyCastle is excluded on purpose: it only serves certificate-encrypted PDFs.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
