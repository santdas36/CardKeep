# SQLCipher is called from native code via JNI.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# JavaMail loads providers and handlers by name from META-INF.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
-dontwarn org.apache.harmony.**

# PDFBox resolves filters, fonts and security handlers reflectively.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.**
