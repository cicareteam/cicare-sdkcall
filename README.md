
# C-iCare SDK Call

A comprehensive Android SDK for implementing voice call functionality in your Android applications.  
The **C-iCare SDK Call** provides easy-to-use APIs for making and receiving calls with built-in notification management and event handling.

## Features
- **Outgoing Calls:** Initiate calls with customizable caller information
- **Incoming Calls:** Handle incoming calls with notification support
- **Permission Management:** Automatic permission handling for different Android versions
- **Custom Ringtones:** Set custom ringtones for incoming calls
- **Metadata Support:** Pass custom metadata with calls
- **Foreground Services:** Proper handling of background call operations

## Requirements
- Minimum Android API Level: **23 (Android 6.0)**
- Target Android API Level: **34+ (Android 14+)**
- Kotlin: **1.7+**
- Android Gradle Plugin: **7.0+**

## Installation
Add the dependency to your app's **build.gradle** file:
```gradle
dependencies {
    implementation 'com.github.cicareteam:cicare-sdkcall:1.2.0-alpha.1'
}
```

## Permissions
The SDK automatically handles different permission requirements based on Android API levels.

### API Level 23-27 (Android 6.0-8.1)
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

### API Level 28-32 (Android 9.0-12L)
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

### API Level 33 (Android 13)
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### API Level 34+ (Android 14+)
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
```

## Quick Start

### 1. Initialize the SDK
```kotlin
import cc.cicare.sdkcall.CiCareSdkCall

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CiCareSdkCall.init(this)
        CiCareSdkCall.setAPI("BASE_URL_API", "API_TOKEN")
    }
}
```

### 2. Request Permissions
```kotlin
import cc.cicare.sdkcall.CiCareSdkCall

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request permissions
        CiCareSdkCall.checkAndRequestPermissions(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                onPermissionsGranted()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }
}
```

## Usage Examples

### Making an Outgoing Call
```kotlin
import cc.cicare.sdkcall.CiCareSdkCall

fun makeOutgoingCall() {
    val metadata = mapOf(
        "call_id" to "12345",
        "room_id" to "room_abc",
        "custom_data" to "any_value"
    )

    CiCareSdkCall.makeCall(
        callerId = "user123",
        callerName = "John Doe",
        callerAvatar = "https://example.com/avatar/john.jpg",
        calleeId = "user456",
        calleeName = "Jane Smith",
        calleeAvatar = "https://example.com/avatar/jane.jpg",
        checkSum = "generated_checksum",
        metaData = metadata
    )
}
```

> **Note:** Ensure you have set `BASE_URL_API` and `API_TOKEN` before making calls.

### Handling Incoming Calls
```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    val callerName = data["caller_name"] ?: "Unknown"
    val callerId = data["caller_id"] ?: ""
    val callerAvatar = data["caller_avatar"] ?: ""
    val metadata = mutableMapOf<String, String>().apply {
            this["alert_data"] = data["alert_data"] ?: ""
        }
    CiCareSdkCall.init(this).showIncoming(
        callerId = callerId,
        callerName = callerName,
        callerAvatar = callerAvatar,
        calleeId = "",
        calleeName = "",
        calleeAvatar = "",
        checkSum = "",
        metaData = metadata,
        messageActionListener = {
            Toast.makeText(this, "Hello Message", Toast.LENGTH_LONG).show()
        }
    )
}
```

**alert_data in metaData is required

### Setting Custom Ringtone
```kotlin
import android.net.Uri
import cc.cicare.sdkcall.CiCareSdkCall

fun setCustomRingtone() {
    val ringtoneUri = Uri.parse("android.resource://your.package.name/raw/custom_ringtone")
    CiCareSdkCall.setRingTone(ringtoneUri)
}
```

## Troubleshooting

### Common Issues

#### 1. Permissions Not Granted
**Problem:** Call functionality doesn't work  
**Solution:** Ensure all required permissions are granted
```kotlin
if (!arePermissionsGranted()) {
    CiCareSdkCall.checkAndRequestPermissions(this)
    return
}
```

#### 2. SDK Not Initialized
**Problem:** NullPointerException when calling SDK methods  
**Solution:** Initialize the SDK before use
```kotlin
CiCareSdkCall.init(applicationContext)
```

#### 3. Foreground Service Issues on Android 8.0+
**Problem:** Service crashes  
**Solution:** Foreground service handling is automatic in SDK

#### 4. Notification Issues on Android 13+
**Problem:** Incoming call notifications not showing  
**Solution:** Request `POST_NOTIFICATIONS` permission (SDK handles this automatically)
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    CiCareSdkCall.checkAndRequestPermissions(this)
}
```
