#!/bin/zsh
# Build the vendor-launcher stand-in, signed with the public AOSP test platform key (the key the MMB firmware's
# launcher and framework are signed with; fetched from AOSP into ~/.android/aosp-platform-key/ if missing).
set -euo pipefail
P=${0:A:h}
export JAVA_HOME=${JAVA_HOME:?set JAVA_HOME to a JDK 21}
export PATH=$JAVA_HOME/bin:$PATH
BT=~/Library/Android/sdk/build-tools/36.0.0
JAR=~/Library/Android/sdk/platforms/android-36/android.jar
K=~/.android/aosp-platform-key
if [[ ! -f $K/platform.pk8 ]]; then
  mkdir -p $K
  for f in platform.pk8 platform.x509.pem; do
    curl -sfL "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/$f?format=TEXT" | base64 -d > $K/$f
  done
fi
O=$P/out
rm -rf $O && mkdir -p $O/classes
$BT/aapt2 link -I $JAR --manifest $P/AndroidManifest.xml -o $O/base.apk
javac -nowarn --release 11 -classpath $JAR -d $O/classes $(find $P/src -name '*.java')
$BT/d8 --release --min-api 29 --lib $JAR --output $O $(find $O/classes -name '*.class')
(cd $O && zip -q -j base.apk classes.dex)
$BT/zipalign -f -p 4 $O/base.apk $O/aligned.apk
$BT/apksigner sign --key $K/platform.pk8 --cert $K/platform.x509.pem --out $O/launcher3-stub.apk $O/aligned.apk
$BT/apksigner verify --print-certs $O/launcher3-stub.apk | grep SHA-256
echo "built: $O/launcher3-stub.apk"
