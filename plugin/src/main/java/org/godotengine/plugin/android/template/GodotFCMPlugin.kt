package org.godot.plugins.fcm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import org.json.JSONObject

class GodotFCMPlugin(godot: Godot): GodotPlugin(godot) {
    private val TAG = "GodotFCMPlugin"
    private val NOTIFICATION_PERMISSION_REQ_CODE = 101
    override fun getPluginName() = "GodotFCM"

    override fun getPluginSignals(): Set<SignalInfo> {
        return setOf(
            SignalInfo("initialized"),
            SignalInfo("initialization_failed", String::class.javaObjectType),
            SignalInfo("token_received", String::class.javaObjectType),
            SignalInfo("token_fetch_failed", String::class.javaObjectType),
            SignalInfo("permission_request_completed", Boolean::class.javaObjectType)
        )
    }

    @UsedByGodot
    fun init(jsonConfig: String) {
        Log.d(TAG, "Initialization requested from Godot.")

        val context = activity?.applicationContext
        if (context == null) {
            Log.e(TAG, "Application context is null. Cannot initialize Firebase.")
            emitSignal("initialization_failed", "Application context is null")
            return
        }

        if (FirebaseApp.getApps(context).isNotEmpty()) {
            Log.i(TAG, "Firebase is already initialized.")
            emitSignal("initialized")
            return
        }

        try {
            val jsonObject = JSONObject(jsonConfig)
            val projectInfo = jsonObject.getJSONObject("project_info")

            val clients = jsonObject.getJSONArray("client")
            var clientObject: JSONObject? = null
            val packageName = context.packageName
            for (i in 0 until clients.length()) {
                val currentClient = clients.getJSONObject(i)
                val clientPackageName = currentClient.getJSONObject("client_info")
                    .getJSONObject("android_client_info")
                    .getString("package_name")
                if (clientPackageName == packageName) {
                    clientObject = currentClient
                    break
                }
            }

            if (clientObject == null) {
                val errorMsg = "Client config not found for package: $packageName"
                Log.e(TAG, "Could not find a client in google-services.json matching the package name: $packageName")
                emitSignal("initialization_failed", errorMsg)
                return
            }

            val clientInfo = clientObject.getJSONObject("client_info")
            val apiKey = clientObject.getJSONArray("api_key").getJSONObject(0)

            val optionsBuilder = FirebaseOptions.Builder()
                .setApiKey(apiKey.getString("current_key"))
                .setApplicationId(clientInfo.getString("mobilesdk_app_id"))
                .setProjectId(projectInfo.getString("project_id"))
                .setGcmSenderId(projectInfo.getString("project_number"))

            projectInfo.optString("firebase_url", null)?.let { optionsBuilder.setDatabaseUrl(it) }
            projectInfo.optString("storage_bucket", null)?.let { optionsBuilder.setStorageBucket(it) }

            FirebaseApp.initializeApp(context, optionsBuilder.build())

            Log.i(TAG, "Firebase initialized successfully from config string!")
            emitSignal("initialized")

        } catch (e: Exception) {
            val errorMsg = "Initialization failed: ${e.message}"
            Log.e(TAG, "Failed to parse google-services.json or initialize Firebase", e)
            emitSignal("initialization_failed", errorMsg)
        }
    }

    @UsedByGodot
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            activity?.let {
                if (it.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Notification permission already granted.")
                    emitSignal("permission_request_completed", true)
                } else {
                    Log.d(TAG, "Requesting notification permission...")
                    it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQ_CODE)
                }
            }
        } else {
            Log.i(TAG, "Running on Android < 13. Notification permission is granted by default.")
            emitSignal("permission_request_completed", true)
        }
    }

    @UsedByGodot
    fun getToken() {
        Log.d(TAG, "getToken() called from Godot.")
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.i(TAG, "FCM Token received successfully: $token")
                emitSignal("token_received", token)
            }
            .addOnFailureListener { exception ->
                val errorMsg = "Token fetch failed: ${exception.message}"
                Log.e(TAG, "Fetching FCM token failed!", exception)
                emitSignal("token_fetch_failed", errorMsg)
            }
    }

    override fun onMainRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onMainRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQ_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Permission request result: ${if (granted) "GRANTED" else "DENIED"}")
            emitSignal("permission_request_completed", granted)
        }
    }
}