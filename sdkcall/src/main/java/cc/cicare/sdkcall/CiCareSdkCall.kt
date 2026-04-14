package cc.cicare.sdkcall

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.MessageActionListener
import cc.cicare.sdkcall.event.MessageListenerHolder
import cc.cicare.sdkcall.libs.AES256Decryptor
import cc.cicare.sdkcall.libs.ApiClient
import cc.cicare.sdkcall.libs.CallRepository
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.lang.ref.WeakReference

object CiCareSdkCall {

    private var contextRef: WeakReference<Context>? = null

    /**
     * Memory cache untuk encryption key.
     * @Volatile memastikan write dari IO thread langsung terlihat oleh thread lain
     * tanpa caching di CPU register — mencegah race condition baca/tulis antar thread.
     */
    @Volatile
    private var encryptionKeyCache: String? = null

    private const val PREFS_FILE = "cicare_sdk_prefs"
    private const val PREFS_KEY_ENCRYPT = "encryption_key"

    /**
     * Satu scope terpusat untuk semua coroutine SDK.
     * SupervisorJob: satu coroutine gagal tidak cancel yang lain.
     */
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * SharedPreferences instance — lazy, dibuat sekali, di-cache selamanya.
     * Diakses dari IO thread sehingga pakai SYNCHRONIZED.
     */
    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        contextRef?.get()?.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("Context null — call init() before setAPI()")
    }

    // ─────────────────────────────────────────────────────────────
    // Permission arrays per API level
    // ─────────────────────────────────────────────────────────────

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.P)
    private val requiredPermissions28 = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requiredPermissionsTiramisu = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private val requiredPermissionsUpsideDownCake = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        android.Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL,
    )

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Inisialisasi SDK. Wajib dipanggil pertama, biasanya di Application.onCreate().
     *
     * Setelah init, encryption key di-load dari SharedPreferences ke memory
     * agar siap dipakai tanpa IO saat showIncoming() dipanggil dari FCM.
     */
    fun init(context: Context): CiCareSdkCall {
        contextRef = WeakReference(context.applicationContext)
        sdkScope.launch {
            encryptionKeyCache = loadKeyFromPrefs()
            if (encryptionKeyCache != null) {
                Log.d("SDK CALL", "init: encryption key loaded from storage")
            }
        }
        return this
    }

    /**
     * Set base URL dan auth token untuk API, lalu fetch + simpan encryption key.
     *
     * Key di-fetch dari server, disimpan di SharedPreferences (persisten di disk),
     * dan di-cache di memory. Key tetap tersedia bahkan setelah app di-kill dan
     * restart — penting karena FCM bisa datang kapan saja tanpa setAPI() dipanggil ulang.
     *
     * Catatan: encryption key ini adalah operational key (bukan data user) yang
     * bisa di-refresh kapan saja via setAPI(). Disimpan di SharedPreferences biasa
     * karena tidak mengandung data sensitif pengguna.
     */
    fun setAPI(baseUrl: String, token: String) {
        ApiClient.BASE_URL = baseUrl
        ApiClient.AUTH_TOKEN = token

        sdkScope.launch {
            try {
                val key = CallRepository.getEncryptKey()
                if (!key.isNullOrBlank()) {
                    encryptionKeyCache = key
                    saveKeyToPrefs(key)
                    Log.d("SDK CALL", "setAPI: encryption key fetched and saved")
                } else {
                    Log.w("SDK CALL", "setAPI: server returned blank encryption key")
                }
            } catch (e: Exception) {
                Log.e("SDK CALL", "setAPI: failed to fetch encryption key: ${e.message}")
                // Tidak fatal — key lama dari storage (jika ada) akan tetap dipakai
            }
        }
    }

    fun setRingTone(ringTone: Uri) {
        CallNotificationManager.ringtoneUrl = ringTone
    }

    fun setEventListener(eventListener: CallEventListener) {
        MessageListenerHolder.callEventListener = eventListener
    }

    /**
     * Cek dan minta semua permission yang diperlukan SDK.
     *
     * Ini adalah early permission check di awal app lifecycle.
     * Permission mic saat proses call berjalan ditangani oleh
     * ScreenCallActivity dan IncomingCallService — bukan di sini.
     *
     * @return true jika semua permission sudah granted
     */
    fun checkAndRequestPermissions(activity: Activity): Boolean {
        val permissions = getRequiredPermissions()

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (notGranted.isEmpty()) {
            true
        } else {
            ActivityCompat.requestPermissions(activity, notGranted.toTypedArray(), 1001)
            false
        }
    }

    /**
     * Proses incoming call dari push notification (FCM).
     *
     * Flow:
     * 1. Validasi cipherText dari metaData["alert_data"]
     * 2. Resolve encryption key (memory → storage → server fetch)
     * 3. Decrypt AES-256 payload di IO thread
     * 4. Extract server + token dari JSON
     * 5. Start IncomingCallService dari Main thread
     *
     * Permission mic TIDAK dicek di sini — ditangani oleh:
     * - IncomingCallService: cek apakah device sedang call lain (BUSY)
     * - ScreenCallActivity: dialog permission sebelum user bisa answer
     */
    fun showIncoming(
        callerId: String,
        callerName: String? = null,
        callerAvatar: String? = "",
        calleeId: String,
        calleeName: String? = null,
        calleeAvatar: String? = "",
        checkSum: String,
        metaData: Map<String, String> = emptyMap(),
        messageActionListener: MessageActionListener,
    ) {
        val cipherText = metaData["alert_data"]
        if (cipherText.isNullOrBlank()) {
            Log.e("SDK CALL", "showIncoming: 'alert_data' missing or blank in metaData")
            MessageListenerHolder.callEventListener?.onError(400, "alert_data missing in payload")
            return
        }

        sdkScope.launch {
            try {
                val ctx = contextRef?.get() ?: run {
                    Log.e("SDK CALL", "showIncoming: context null — call init() first")
                    return@launch
                }

                // Resolve key: memory cache → SharedPreferences → server fetch
                val key = resolveEncryptionKey() ?: run {
                    Log.e("SDK CALL", "showIncoming: encryption key unavailable")
                    MessageListenerHolder.callEventListener?.onError(
                        500, "Encryption key unavailable — call setAPI() first"
                    )
                    return@launch
                }

                // Decrypt di IO thread — CPU-bound, jangan di Main thread
                val decryptedJson = AES256Decryptor.decrypt(cipherText, key)
                val jsonObject = JSONObject(decryptedJson)
                Log.d("SDK CALL", "showIncoming: payload decrypted successfully")

                // optString + takeIf lebih aman daripada getString():
                // getString() throw JSONException jika key tidak ada — ?: return tidak jalan
                val server = jsonObject.optString("server").takeIf { it.isNotBlank() }
                    ?: throw JSONException("'server' field missing or blank in payload")

                val token = jsonObject.optString("token").takeIf { it.isNotBlank() }
                    ?: throw JSONException("'token' field missing or blank in payload")

                val isFromPhone = jsonObject.optBoolean("isFromPhone", false)

                val caller = callerName?.takeIf { it.isNotBlank() } ?: "Caller"
                val callee = calleeName?.takeIf { it.isNotBlank() } ?: "Callee"

                // Set listener sebelum service distart agar tidak ada event yang terlewat
                MessageListenerHolder.listener = messageActionListener

                val serviceIntent = Intent(ctx, IncomingCallService::class.java).apply {
                    action = CiCareCallService.ACTION.INCOMING
                    putExtra("call_type", "incoming")
                    putExtra("caller_id", callerId)
                    putExtra("caller_name", caller)
                    putExtra("callee_id", calleeId)
                    putExtra("callee_name", callee)
                    putExtra("callee_avatar", calleeAvatar)
                    putExtra("caller_avatar", callerAvatar)
                    putExtra("meta_data", HashMap(metaData))
                    putExtra("checksum", checkSum)
                    putExtra("token", token)
                    putExtra("server", server)
                    putExtra("from_phone", isFromPhone)
                }

                // startForegroundService() harus dari Main thread
                withContext(Dispatchers.Main) {
                    startIncomingService(ctx, serviceIntent, caller, callerAvatar ?: "")
                }

            } catch (e: JSONException) {
                Log.e("SDK CALL", "showIncoming: JSON error: ${e.message}")
                MessageListenerHolder.callEventListener?.onError(400, "Invalid call payload: ${e.message}")
            } catch (e: Exception) {
                Log.e("SDK CALL", "showIncoming: unexpected error: ${e.message}", e)
                MessageListenerHolder.callEventListener?.onError(500, "Failed to process incoming call")
            }
        }
    }

    /**
     * Mulai panggilan keluar ke [calleeId].
     *
     * Permission mic TIDAK dicek di sini — sepenuhnya ditangani oleh
     * ScreenCallActivity (dialog permission sebelum CiCareCallService distart).
     */
    fun makeCall(
        activity: ComponentActivity,
        callerId: String,
        callerName: String? = null,
        callerAvatar: String? = "",
        calleeId: String,
        calleeName: String? = null,
        calleeAvatar: String = "",
        checkSum: String,
        metaData: Map<String, String> = emptyMap(),
    ) {
        val ctx = contextRef?.get() ?: run {
            Log.e("SDK CALL", "makeCall: context null — call init() first")
            MessageListenerHolder.callEventListener?.onError(500, "SDK not initialized")
            return
        }

        val caller = callerName?.takeIf { it.isNotBlank() } ?: "Caller"
        val callee = calleeName?.takeIf { it.isNotBlank() } ?: "Callee"

        val intent = Intent(ctx, ScreenCallActivity::class.java).apply {
            action = CiCareCallService.ACTION.OUTGOING
            // FLAG_ACTIVITY_NEW_TASK wajib karena ctx adalah applicationContext
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("call_type", "outgoing")
            putExtra("callee_id", calleeId)
            putExtra("callee_name", callee)
            putExtra("callee_avatar", calleeAvatar)
            putExtra("caller_id", callerId)
            putExtra("caller_name", caller)
            putExtra("caller_avatar", callerAvatar)
            putExtra("checksum", checkSum)
            putExtra("meta_data", HashMap(metaData))
        }

        ctx.startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Resolve encryption key dengan urutan prioritas:
     * 1. Memory cache (@Volatile) — paling cepat, tidak ada IO
     * 2. SharedPreferences — tersedia setelah app restart tanpa network
     * 3. Fetch dari server — fallback terakhir jika storage kosong
     */
    private suspend fun resolveEncryptionKey(): String? {
        encryptionKeyCache?.let { return it }

        val storedKey = loadKeyFromPrefs()
        if (!storedKey.isNullOrBlank()) {
            encryptionKeyCache = storedKey
            Log.d("SDK CALL", "resolveEncryptionKey: loaded from storage")
            return storedKey
        }

        return try {
            Log.d("SDK CALL", "resolveEncryptionKey: fetching from server")
            val fetchedKey = CallRepository.getEncryptKey()
            if (!fetchedKey.isNullOrBlank()) {
                encryptionKeyCache = fetchedKey
                saveKeyToPrefs(fetchedKey)
                Log.d("SDK CALL", "resolveEncryptionKey: key fetched and saved")
                fetchedKey
            } else {
                Log.e("SDK CALL", "resolveEncryptionKey: server returned blank key")
                null
            }
        } catch (e: Exception) {
            Log.e("SDK CALL", "resolveEncryptionKey: server fetch failed: ${e.message}")
            null
        }
    }

    private fun saveKeyToPrefs(key: String) {
        try {
            prefs.edit { putString(PREFS_KEY_ENCRYPT, key) }
        } catch (e: Exception) {
            Log.e("SDK CALL", "saveKeyToPrefs: failed: ${e.message}")
        }
    }

    private fun loadKeyFromPrefs(): String? {
        return try {
            prefs.getString(PREFS_KEY_ENCRYPT, null)
        } catch (e: Exception) {
            Log.e("SDK CALL", "loadKeyFromPrefs: failed: ${e.message}")
            null
        }
    }

    /**
     * Start IncomingCallService sebagai foreground service.
     * Harus dipanggil dari Main thread.
     *
     * Menangani ForegroundServiceStartNotAllowedException (Android 12+)
     * saat app di background dengan fallback ke notifikasi biasa.
     */
    private fun startIncomingService(
        ctx: Context,
        serviceIntent: Intent,
        callerName: String,
        callerAvatar: String,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.i("SDKCALL", "Start Foreground Incoming service")
                ctx.startForegroundService(serviceIntent)
            } else {
                ctx.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("SDK CALL", "startIncomingService failed: ${e.message}")

            val isFgsNotAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (e is android.app.ForegroundServiceStartNotAllowedException ||
                            e.message?.contains("ForegroundServiceStartNotAllowedException") == true)

            if (isFgsNotAllowed) {
                Log.w("SDK CALL", "FGS not allowed — showing fallback notification")
            } else {
                MessageListenerHolder.callEventListener?.onError(
                    500, "Failed to start incoming call service"
                )
            }
        }
    }

    /**
     * Kembalikan array permission yang tepat sesuai API level device.
     */
    private fun getRequiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> requiredPermissionsUpsideDownCake
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> requiredPermissionsTiramisu
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> requiredPermissions28
        else -> requiredPermissions
    }
}