# Signed distribution assets

This directory contains the preverified APK used for GitHub Releases.

## Current release: FloatClip 0.5.0

- APK: `FloatClip-0.5.0-debug.apk`
- SHA-256: `6c1821d8ca2ce58b81005e832d37083e396c3b7ef0f9608b571797f3f3d5d71f`
- Android signing certificate SHA-256: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`
- source commit: `13b3102290d5f788f99f89f649d6dfb49b31f650`

0.5.0 intentionally starts a new persistent FloatClip signing identity. It therefore cannot overwrite-install an APK signed with the previous 0.4.0 certificate; the transition requires uninstalling the older package first. Future builds signed with the 0.5.0 identity can update 0.5.0 normally.

The private keystore and password are stored outside the Git repository in the controlled workspace and are never committed. GitHub-hosted CI APKs remain compile/lint evidence only because their default debug signer is runner-local. Tagged public releases consume the preverified asset committed under `dist/`.

## Previous release: FloatClip 0.4.0

- APK: `FloatClip-0.4.0-debug.apk`
- SHA-256: `4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182`
- Android signing certificate SHA-256: `9e476b6a60e08a14ec0d563ee1dde772145c70ea0463ce227b9b6e9e50275056`
- source commit: `effe2409b99cc124801ed5a5b2269a797c9cf380`
