package cc.cicare.sdkcalltest

import android.content.Context

class UserSessionManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)

    fun saveUserId(userId: Int, name: String) {
        prefs.edit().putInt("user_id", userId).apply()
        prefs.edit().putString("name", name).apply()
    }

    fun getUserId(): Int? {
        val id = prefs.getInt("user_id", -1)
        return if (id != -1) id else null
    }

    fun getUserName(): String? {
        val name = prefs.getString("name", "")
        return if (name != "") name else null
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
