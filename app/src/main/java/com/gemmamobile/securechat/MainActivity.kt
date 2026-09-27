package com.gemmamobile.securechat

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.gemmamobile.securechat.ui.AppRoot
import com.gemmamobile.securechat.ui.AppTheme
import com.gemmamobile.securechat.ui.ShieldActions
import com.gemmamobile.securechat.ui.ShieldStatus

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires it.
class MainActivity : FragmentActivity() {

    private val vm: ChatViewModel by viewModels()
    private var lockError by mutableStateOf<String?>(null)
    private var canLock by mutableStateOf(true)
    private var authInProgress = false
    private var pendingRecordingPicker = false

    private fun launchRecordingPicker() {
        pendingRecordingPicker = false
        vm.skipNextLock = true
        pickRecording.launch(arrayOf("audio/*", "application/ogg"))
    }

    private fun openUrl(url: String) {
        vm.skipNextLock = true
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private lateinit var voice: VoiceController
    /** Fallback meeting transcription (on-screen only) when Gemma audio isn't available. */
    private lateinit var liveTranscriber: LiveTranscriber

    private val requestMeetingPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            checkBlocked(result.keys)
            if (result[Manifest.permission.RECORD_AUDIO] == true) startMeetingNow()
        }

    private val permPrefs by lazy { getSharedPreferences("permissions", MODE_PRIVATE) }
    private var permissionHint by mutableStateOf<String?>(null)
    /** Set when we send the user to the notification-access page, to detect "Restricted setting" on return. */
    private var awaitingListener = false

    /** Used by the Permissions / Scam Shield screens. */
    private val requestCallerRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshShield() }

    private fun callerRoleHeld(): Boolean =
        getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            checkBlocked(result.keys)
            refreshShield()
        }

    /**
     * Asks for runtime permissions only when the user taps Allow. If Android won't show the popup
     * any more (denied twice, or "Restricted setting" for APK installs), go straight to App info.
     */
    private fun ask(vararg perms: String) {
        val missing = perms.filter { !granted(it) }
        if (missing.isEmpty()) return refreshShield()
        val blocked = missing.filter { askedBefore(it) && !shouldShowRequestPermissionRationale(it) }
        if (blocked.isNotEmpty() && blocked.size == missing.size) {
            showBlocked(blocked)
            return
        }
        missing.forEach { permPrefs.edit().putBoolean(it, true).apply() }
        requestPerms.launch(missing.toTypedArray())
    }

    private fun askedBefore(p: String) = permPrefs.getBoolean(p, false)

    private fun checkBlocked(perms: Collection<String>) {
        perms.forEach { permPrefs.edit().putBoolean(it, true).apply() }
        val blocked = perms.filter { !granted(it) && !shouldShowRequestPermissionRationale(it) }
        if (blocked.isNotEmpty()) showBlocked(blocked)
    }

    private fun showBlocked(perms: List<String>) {
        val names = perms.map { permName(it) }.distinct().joinToString(", ")
        permissionHint = "Android is blocking the popup for: $names. In App info, open Permissions and allow it. " +
            "If it says \"Restricted setting\", tap ⋮ (top-right) → Allow restricted settings first."
        refreshShield()
        openAppInfo()
    }

    private fun permName(p: String) = when (p) {
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.READ_PHONE_STATE -> "Phone"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> p.substringAfterLast('.')
    }

    private fun openAppInfo() {
        vm.skipNextLock = true
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun batteryUnrestricted() =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshShield()
    }

    private var shieldStatus by mutableStateOf(ShieldStatus())


    private val shieldActions by lazy {
        ShieldActions(
            onBack = { vm.showShield = false },
            onGrantPhone = {
                ask(Manifest.permission.READ_PHONE_STATE)
            },
            onOpenNotificationAccess = {
                awaitingListener = true
                vm.skipNextLock = true
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, CallGuardService::class.java).flattenToString(),
                )
                runCatching { startActivity(detail) }
                    .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
            onOpenAppInfo = ::openAppInfo,
            onGrantNotifications = {
                if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.POST_NOTIFICATIONS)
            },
            onOpenFullScreenSettings = {
                if (Build.VERSION.SDK_INT >= 34) {
                    vm.skipNextLock = true
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", packageName, null))
                    )
                }
            },
            onToggleOtp = { ScamShield.setOtpGuard(this, it); refreshShield() },
            onToggleCall = { ScamShield.setCallGuard(this, it); refreshShield() },
            onTestAlert = { ScamShield.show(this, ScamShield.Alert.TEST) },
            onScamCheck = { vm.startScamCheck() },
            onGrantMic = { ask(Manifest.permission.RECORD_AUDIO) },
            onOpenAutoStart = { vm.skipNextLock = true; BackgroundHelper.openAutoStart(this) },
            onBackgroundTest = {
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    ask(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    BackgroundHelper.scheduleSelfTest(this)
                    android.widget.Toast.makeText(
                        this, "Test started. Press Home and lock the screen — wait ~1 minute.", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onRequestCallerRole = {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) && !callerRoleHeld()) {
                    requestCallerRole.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
                }
            },
            onBatteryUnrestricted = {
                vm.skipNextLock = true
                runCatching {
                    @android.annotation.SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
                    startActivity(i)
                }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            },
            onAllowAll = {
                // One popup sequence for all runtime permissions; special ones keep their own Allow buttons.
                val runtime = buildList {
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.READ_PHONE_STATE)
                }
                ask(*runtime.toTypedArray())
            },
            onOpenPermissions = { vm.showShield = false; vm.showPermissions = true },
            onOpenUrl = ::openUrl,
            onOpenWhatsApp = {
                vm.skipNextLock = true
                val wa = packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                if (wa != null) startActivity(wa)
            },
            onOpenPhoneApp = {
                vm.skipNextLock = true
                runCatching { startActivity(Intent(Intent.ACTION_DIAL)) }
            },
            onCall1930 = {
                vm.skipNextLock = true
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")))
            },
            onPickRecording = {
                askNotificationPermission()
                vm.showShield = false
                launchRecordingPicker()
            },
        )
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshShield() {
        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        // Clear the hint once whatever it was about has been sorted out.
        val hint = permissionHint
        if (hint != null) {
            val listenerHint = hint.startsWith("Notification access")
            val solved = if (listenerHint) listenerOn else {
                listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE).all { granted(it) }
            }
            if (solved) permissionHint = null
        }
        shieldStatus = ShieldStatus(
            phoneStateAllowed = granted(Manifest.permission.READ_PHONE_STATE),
            listenerEnabled = listenerOn,
            notificationsAllowed = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            fullScreenAllowed = Build.VERSION.SDK_INT < 34 ||
                getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            otpGuardOn = ScamShield.otpGuardOn(this),
            callGuardOn = ScamShield.callGuardOn(this),
            micAllowed = granted(Manifest.permission.RECORD_AUDIO),
            callerRoleHeld = callerRoleHeld(),
            batteryUnrestricted = batteryUnrestricted(),
            hint = permissionHint,
        )
    }

    private val pickRecording = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.summarizeRecording(uri)
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.startListening() else checkBlocked(listOf(Manifest.permission.RECORD_AUDIO))
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Security.protectWindow(this)
        super.onCreate(savedInstanceState)

        if (Security.isCompromised(this)) {
            finishAndRemoveTask()
            return
        }
        enableEdgeToEdge()

        voice = VoiceController(this).apply {
            onFinalTranscript = { text -> vm.send(text) }
        }
        liveTranscriber = LiveTranscriber(this).apply {
            onSegment = { text -> vm.addMeetingSegment(text) }
        }
        if (savedInstanceState == null) handleShare(intent)
        refreshShield()

        setContent {
            AppTheme {
                AppRoot(
                    vm = vm,
                    canLock = canLock,
                    lockError = lockError,
                    onUnlock = ::promptUnlock,
                    onOpenSecuritySettings = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
                    voice = voice,
                    onMic = ::onMicPressed,
                    liveTranscriber = liveTranscriber,
                    onStartMeeting = ::onStartMeeting,
                    onStopMeeting = ::onStopMeeting,
                    onResumeMeeting = { liveTranscriber.start() },
                    onPickRecording = {
                        askNotificationPermission()
                        vm.skipNextLock = true
                        pickRecording.launch(arrayOf("audio/*", "application/ogg"))
                    },
                    shieldStatus = shieldStatus,
                    onPermissionsDone = {
                        permPrefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
                        vm.showPermissions = false
                    },
                    shieldActions = shieldActions,
                    onPickModel = {
                        vm.skipNextLock = true
                        pickModel.launch(arrayOf("*/*"))
                    },
                )
                LaunchedEffect(Unit) {
                    // Stream the latest reply into text-to-speech, sentence by sentence.
                    snapshotFlow { vm.messages.lastOrNull() }.collect { m ->
                        if (m != null && !m.fromUser && !m.isError) voice.onReplyProgress(m.id, m.text, !m.streaming)
                    }
                }
                LaunchedEffect(vm.modelState is ModelState.Ready) {
                    // Permissions are introduced only AFTER the model is installed and ready —
                    // never at first launch. Each popup appears only when the user taps Allow.
                    if (vm.modelState is ModelState.Ready && !permPrefs.getBoolean(KEY_ONBOARDED, false)) {
                        refreshShield()
                        vm.showPermissions = true
                    }
                }
                LaunchedEffect(vm.modelState) {
                    // Keep the screen on while a multi-GB download/import runs.
                    if (vm.modelState is ModelState.Installing) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Security.isCompromised(this)) {
            finishAndRemoveTask()
            return
        }
        canLock = Security.canLock(this)
        if (awaitingListener) {
            awaitingListener = false
            if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
                // Most likely Android 13+ "Restricted setting" (app installed from an APK file).
                permissionHint =
                    "Notification access is blocked by Android for apps installed from an APK. To unlock it:\n" +
                        "1. Tap Open App info below.\n" +
                        "2. Tap ⋮ (top-right) → Allow restricted settings, and confirm with your PIN/fingerprint.\n" +
                        "   (No ⋮ option? Android shows it only after one blocked try — you've just done that, so it should be there now.)\n" +
                        "3. Come back here, tap Allow on Notification access again and turn on \"Scam Shield call guard\"."
            }
        }
        refreshShield()
        if (!vm.unlocked && canLock && !authInProgress) promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        // Never keep the chat mic or speaker running when the app isn't visible.
        // (Background meeting recording is separate: it runs in SessionService with a notification.)
        if (::voice.isInitialized) voice.halt()
        if (::liveTranscriber.isInitialized && liveTranscriber.running) {
            liveTranscriber.stop()
            vm.session?.status = "Paused because the app left the screen — tap Resume."
        }
        // Re-lock whenever the app leaves the screen (except for our own file picker).
        if (!isChangingConfigurations) {
            if (vm.skipNextLock) vm.skipNextLock = false
            else if (!authInProgress) vm.unlocked = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A call recording shared from another app (Share → Gemma Secure Chat), or "Ask Gemma" from a scam alert. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == ACTION_SCAM_CHECK) {
            vm.startScamCheck()
            return
        }
        if (intent?.action == ACTION_CHECK_RECORDING) {
            // Open the file picker once the app is unlocked.
            if (vm.unlocked) launchRecordingPicker() else pendingRecordingPicker = true
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            askNotificationPermission()
            vm.summarizeRecording(uri)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onStartMeeting() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        when {
            granted(Manifest.permission.RECORD_AUDIO) -> startMeetingNow()
            askedBefore(Manifest.permission.RECORD_AUDIO) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                showBlocked(listOf(Manifest.permission.RECORD_AUDIO))
            else -> {
                perms.forEach { permPrefs.edit().putBoolean(it, true).apply() }
                requestMeetingPerms.launch(perms.toTypedArray())
            }
        }
    }

    private fun startMeetingNow() {
        voice.halt()
        val inBackground = vm.startMeeting()
        if (!inBackground) liveTranscriber.start()
    }

    private fun onStopMeeting() {
        if (vm.isRecordingInBackground) {
            vm.stopMeeting()
        } else {
            liveTranscriber.stop()
            vm.finishMeetingInput()
        }
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.release()
        if (::liveTranscriber.isInitialized) liveTranscriber.release()
        super.onDestroy()
    }

    private fun onMicPressed() {
        when {
            voice.phase == VoiceController.Phase.Listening -> voice.stopListening()
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> voice.startListening()
            askedBefore(Manifest.permission.RECORD_AUDIO) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                showBlocked(listOf(Manifest.permission.RECORD_AUDIO))
            else -> {
                permPrefs.edit().putBoolean(Manifest.permission.RECORD_AUDIO, true).apply()
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    companion object {
        const val ACTION_SCAM_CHECK = "com.gemmamobile.securechat.SCAM_CHECK"
        const val ACTION_CHECK_RECORDING = "com.gemmamobile.securechat.CHECK_RECORDING"
        private const val KEY_ONBOARDED = "onboarded"
    }

    private fun promptUnlock() {
        if (authInProgress) return
        authInProgress = true
        lockError = null
        Security.authenticate(
            this,
            onSuccess = {
                authInProgress = false
                vm.unlocked = true
                if (pendingRecordingPicker) launchRecordingPicker()
            },
            onFail = { authInProgress = false; lockError = it },
        )
    }
}
