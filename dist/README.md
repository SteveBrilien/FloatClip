# Signed distribution assets

This directory contains the preverified APK used for GitHub Releases.

## Current release candidate: FloatClip 0.5.2

- APK: `FloatClip-0.5.2-debug.apk`
- SHA-256: `19b529c9a3da9cff45a4334646e1d1bffc75cdf6043427350f8ad2f0a16a8d45`
- Android signing certificate SHA-256: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`
- source commit: `892e9cad6c769d36e7c641ef978964b5f42b9b65`
- package metadata: `com.floatclip.app`, `versionCode=7`, `versionName=0.5.2`, `minSdk=28`, `targetSdk=30`

0.5.2 reuses the persistent signing identity introduced by 0.5.0, so it can overwrite-install 0.5.1 normally. The release asset was produced from the clean source commit above, independently linted with `No issues found`, aligned, and verified with APK Signature Scheme v3 and one signer before tagging.

The private keystore and password are stored outside the Git repository in the controlled workspace and are never committed. GitHub-hosted CI APKs remain compile/lint evidence only because their default debug signer is runner-local. Tagged public releases consume the preverified asset committed under `dist/`.

## Previous release: FloatClip 0.5.1

- APK: `FloatClip-0.5.1-debug.apk`
- SHA-256: `a5bd45526903a15ebec12833fee68b49c0e6383af9e6659d1244057b11e8da58`
- Android signing certificate SHA-256: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`
- source commit: `a3a9db0e02a8c767c8f0c39f4a70ab7cfc8874e2`
- package metadata: `com.floatclip.app`, `versionCode=6`, `versionName=0.5.1`, `minSdk=28`, `targetSdk=30`

0.5.1 also uses the same persistent signing identity and remains directly upgradable to 0.5.2.

## Previous release: FloatClip 0.5.0

- APK: `FloatClip-0.5.0-debug.apk`
- SHA-256: `6c1821d8ca2ce58b81005e832d37083e396c3b7ef0f9608b571797f3f3d5d71f`
- Android signing certificate SHA-256: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`
- source commit: `13b3102290d5f788f99f89f649d6dfb49b31f650`

0.5.0 intentionally started the current persistent FloatClip signing identity. It therefore could not overwrite-install an APK signed with the previous 0.4.0 certificate; that one transition required uninstalling the older package first. 0.5.1 and later releases that retain this identity can update 0.5.0 normally.

## Previous release: FloatClip 0.4.0

- APK: `FloatClip-0.4.0-debug.apk`
- SHA-256: `4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182`
- Android signing certificate SHA-256: `9e476b6a60e08a14ec0d563ee1dde772145c70ea0463ce227b9b6e9e50275056`
- source commit: `effe2409b99cc124801ed5a5b2269a797c9cf380`
