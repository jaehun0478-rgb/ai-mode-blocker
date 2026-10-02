# Stable APK signing

All production updates for `kr.school.aimodeblocker` must use the permanent stable signing certificate.

- Certificate SHA-256: `92EA78F691132205CAC50AC631D55E17C2A191FFC203A56F8180721FF980378F`
- Key alias: `ai-blocker`
- Encrypted keystore: `signing/ai-blocker-release.p12.enc.b64`
- Required GitHub Actions repository secret: `AI_BLOCKER_SIGNING_PASSWORD`

The encrypted keystore alone is not sufficient to sign an APK. Never commit the repository secret or a decrypted keystore.

The build workflow:
1. decrypts the stable keystore using the repository secret,
2. builds `assembleRelease`,
3. verifies the APK signing certificate fingerprint,
4. uploads the APK only when the fingerprint matches.

If the secret is lost, do not generate a new signing key for the same installed app. A new key would require uninstall/reinstall on every device.
