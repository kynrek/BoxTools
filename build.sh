#!/bin/zsh
# Build Box Tools without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner (debug key).
set -euo pipefail
P=${0:A:h}
export JAVA_HOME=${JAVA_HOME:?set JAVA_HOME to a JDK 21 (e.g. Temurin) before building}
export PATH=$JAVA_HOME/bin:$PATH
BT=~/Library/Android/sdk/build-tools/36.0.0
JAR=~/Library/Android/sdk/platforms/android-36/android.jar
O=$P/out
rm -rf $O && mkdir -p $O/flat $O/classes
$BT/aapt2 compile --dir $P/res -o $O/flat/res.zip
$BT/aapt2 link -I $JAR --manifest $P/AndroidManifest.xml -o $O/base.apk $O/flat/res.zip
javac -nowarn --release 11 -classpath $JAR -d $O/classes $(find $P/src -name '*.java') 2>&1 | grep -v -- '^warning' || true
$BT/d8 --release --min-api 29 --lib $JAR --output $O $(find $O/classes -name '*.class')
(cd $O && zip -q -j base.apk classes.dex)
$BT/zipalign -f -p 4 $O/base.apk $O/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out $O/boxtools.apk $O/aligned.apk
$BT/apksigner verify $O/boxtools.apk && echo "built: $O/boxtools.apk"
