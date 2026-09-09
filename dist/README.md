# Signed distribution assets

This directory contains the stable-signature APK used for GitHub Releases.

For FloatClip 0.4.0:

- APK: `FloatClip-0.4.0-debug.apk`
- SHA-256: `4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182`
- Android signing certificate SHA-256: `9e476b6a60e08a14ec0d563ee1dde772145c70ea0463ce227b9b6e9e50275056`
- source commit: `effe2409b99cc124801ed5a5b2269a797c9cf380`

The GitHub-hosted CI build remains useful for compilation/lint verification, but its default ephemeral debug signing key must not be used as the installable release identity. Release publishing therefore uses this already-verified stable-signature APK until GitHub Actions is provisioned with a protected signing secret.
