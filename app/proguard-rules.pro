# Demo app R8 rules. The SDK's own needs travel in each library's consumer-rules.pro; the demo adds
# nothing on top — a merchant's release build should need nothing either. Keep line numbers so a
# stack trace from a minified demo build stays readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
