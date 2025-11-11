package info.benjaminhill.simplemesh

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import androidx.core.content.edit

object DeviceIdentifier {
    private const val PREFS_NAME = "SimpleMeshPrefs"
    private const val PREF_UNIQUE_ID = "UUID"
    private var uniqueID: String? = null

    fun get(context: Context): String {
        if (uniqueID == null) {
            val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            uniqueID = sharedPrefs.getString(PREF_UNIQUE_ID, null)
            if (uniqueID == null) {
                uniqueID = "phone-${UUID.randomUUID().toString().substring(0, 5)}"
                sharedPrefs.edit {
                    putString(PREF_UNIQUE_ID, uniqueID)
                }
            }
        }
        return uniqueID!!
    }
}
