package com.floatclip.app.integration

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.util.Log
import java.io.FileInputStream
import java.security.MessageDigest

enum class OriginOsRomLockState {
    MATCHED,
    FINGERPRINT_MISMATCH,
    PACKAGE_MISSING,
    VERSION_MISMATCH,
    APK_PATH_UNAVAILABLE,
    APK_HASH_MISMATCH,
    HASH_READ_FAILED,
}

data class OriginOsRomIdentity(
    val fingerprint: String,
    val floatingBallVersionName: String?,
    val floatingBallVersionCode: Long?,
    val floatingBallSourceDir: String?,
    val floatingBallSha256: String?,
)

data class OriginOsRomLockResult(
    val state: OriginOsRomLockState,
    val identity: OriginOsRomIdentity,
    val detail: String,
) {
    val matched: Boolean get() = state == OriginOsRomLockState.MATCHED
}

/**
 * Fail-closed lock for the single OriginOS build that was statically analysed for FloatClip.
 *
 * This deliberately does not call vivo's private AIDL or request signature permissions. The
 * public package resources are used only when the exact ROM identity and FloatingBall APK hash
 * match the analysed specimen. A mismatch keeps the standalone renderer active.
 */
object OriginOsRomLock {
    const val FLOATING_BALL_PACKAGE = "com.vivo.floatingball"
    const val SUPPORTED_FINGERPRINT =
        "vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys"
    const val SUPPORTED_FLOATING_BALL_VERSION_NAME = "2.5.32.0"
    const val SUPPORTED_FLOATING_BALL_VERSION_CODE = 253200L
    const val SUPPORTED_FLOATING_BALL_SHA256 =
        "e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e"

    fun evaluate(context: Context): OriginOsRomLockResult {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        if (fingerprint != SUPPORTED_FINGERPRINT) {
            return result(
                state = OriginOsRomLockState.FINGERPRINT_MISMATCH,
                fingerprint = fingerprint,
                detail = "ROM fingerprint is not the analysed PD2115 Android 11 build",
            )
        }

        val info = try {
            context.packageManager.getPackageInfo(FLOATING_BALL_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return result(
                state = OriginOsRomLockState.PACKAGE_MISSING,
                fingerprint = fingerprint,
                detail = "com.vivo.floatingball is not installed",
            )
        }

        val versionName = info.versionName
        val versionCode = info.longVersionCode
        val sourceDir = info.applicationInfo?.sourceDir
        if (versionName != SUPPORTED_FLOATING_BALL_VERSION_NAME ||
            versionCode != SUPPORTED_FLOATING_BALL_VERSION_CODE
        ) {
            return OriginOsRomLockResult(
                state = OriginOsRomLockState.VERSION_MISMATCH,
                identity = OriginOsRomIdentity(
                    fingerprint = fingerprint,
                    floatingBallVersionName = versionName,
                    floatingBallVersionCode = versionCode,
                    floatingBallSourceDir = sourceDir,
                    floatingBallSha256 = null,
                ),
                detail = "FloatingBall version does not match analysed 2.5.32.0 (253200)",
            )
        }

        if (sourceDir.isNullOrBlank()) {
            return OriginOsRomLockResult(
                state = OriginOsRomLockState.APK_PATH_UNAVAILABLE,
                identity = OriginOsRomIdentity(
                    fingerprint = fingerprint,
                    floatingBallVersionName = versionName,
                    floatingBallVersionCode = versionCode,
                    floatingBallSourceDir = sourceDir,
                    floatingBallSha256 = null,
                ),
                detail = "FloatingBall sourceDir is unavailable",
            )
        }

        val sha256 = try {
            sha256(sourceDir)
        } catch (e: Exception) {
            return OriginOsRomLockResult(
                state = OriginOsRomLockState.HASH_READ_FAILED,
                identity = OriginOsRomIdentity(
                    fingerprint = fingerprint,
                    floatingBallVersionName = versionName,
                    floatingBallVersionCode = versionCode,
                    floatingBallSourceDir = sourceDir,
                    floatingBallSha256 = null,
                ),
                detail = "Unable to hash FloatingBall APK: ${e.javaClass.simpleName}",
            )
        }

        val state = if (sha256 == SUPPORTED_FLOATING_BALL_SHA256) {
            OriginOsRomLockState.MATCHED
        } else {
            OriginOsRomLockState.APK_HASH_MISMATCH
        }
        return OriginOsRomLockResult(
            state = state,
            identity = OriginOsRomIdentity(
                fingerprint = fingerprint,
                floatingBallVersionName = versionName,
                floatingBallVersionCode = versionCode,
                floatingBallSourceDir = sourceDir,
                floatingBallSha256 = sha256,
            ),
            detail = if (state == OriginOsRomLockState.MATCHED) {
                "ROM lock matched; semantic FloatingBall resources may be read"
            } else {
                "FloatingBall APK hash differs from the analysed specimen"
            },
        )
    }

    private fun result(
        state: OriginOsRomLockState,
        fingerprint: String,
        detail: String,
    ) = OriginOsRomLockResult(
        state = state,
        identity = OriginOsRomIdentity(
            fingerprint = fingerprint,
            floatingBallVersionName = null,
            floatingBallVersionCode = null,
            floatingBallSourceDir = null,
            floatingBallSha256 = null,
        ),
        detail = detail,
    )

    private fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

class LockedOriginOsSystemBridge(context: Context) : OriginOsSystemBridge {
    private val appContext = context.applicationContext
    val lockResult: OriginOsRomLockResult by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OriginOsRomLock.evaluate(appContext).also { result ->
            Log.i(TAG, "ROM lock ${result.state}: ${result.detail}")
        }
    }

    private val floatingBallResources: Resources? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!lockResult.matched) return@lazy null
        runCatching {
            appContext.packageManager.getResourcesForApplication(OriginOsRomLock.FLOATING_BALL_PACKAGE)
        }.getOrNull()
    }

    override val protocolVersion: Int = OriginOsSystemBridge.PROTOCOL_VERSION

    override fun isAvailable(): Boolean = lockResult.matched && floatingBallResources != null

    override fun currentTheme(): OriginOsThemeSnapshot? {
        val lock = lockResult
        val resources = floatingBallResources ?: return null
        if (!lock.matched) return null

        val bubbleColor = resources.colorOrNull("floating_ball_circle_background_color")
        val panelColor = resources.colorOrNull("floating_ball_list_func_background_color_rom_9_0")
            ?: resources.colorOrNull("floating_ball_list_app_background_color_rom_9_0")
        val textColor = resources.colorOrNull("floating_ball_expanded_func_label_color")
        val cornerRadius = resources.dimenDpOrNull("floating_ball_expanded_outline_corner")

        if (bubbleColor == null && panelColor == null && textColor == null && cornerRadius == null) {
            return null
        }

        Log.i(TAG, "OriginOS semantic resource bridge active")
        return OriginOsThemeSnapshot(
            sourcePackage = OriginOsRomLock.FLOATING_BALL_PACKAGE,
            themeName = "rom-locked-floatingball",
            bubbleBackgroundArgb = bubbleColor,
            panelBackgroundArgb = panelColor,
            primaryTextArgb = textColor,
            secondaryTextArgb = textColor,
            cornerRadiusDp = cornerRadius,
            metadata = mapOf(
                "lockState" to lock.state.name,
                "fingerprint" to lock.identity.fingerprint,
                "floatingBallVersion" to (lock.identity.floatingBallVersionName ?: "unknown"),
                "floatingBallSha256" to (lock.identity.floatingBallSha256 ?: "unknown"),
                "bridgeMode" to "semantic-resources-only",
            ),
        )
    }

    override fun registerClipboardObserver(observer: (CapturedClipboardItem) -> Unit): AutoCloseable? = null

    @SuppressLint("DiscouragedApi")
    @Suppress("DEPRECATION")
    private fun Resources.colorOrNull(name: String): Int? {
        val id = getIdentifier(name, "color", OriginOsRomLock.FLOATING_BALL_PACKAGE)
        if (id == 0) return null
        return runCatching { getColor(id, null) }.getOrNull()
    }

    @SuppressLint("DiscouragedApi")
    private fun Resources.dimenDpOrNull(name: String): Float? {
        val id = getIdentifier(name, "dimen", OriginOsRomLock.FLOATING_BALL_PACKAGE)
        if (id == 0) return null
        return runCatching { getDimension(id) / displayMetrics.density }.getOrNull()
    }

    private companion object {
        const val TAG = "FloatClipOriginOS"
    }
}
