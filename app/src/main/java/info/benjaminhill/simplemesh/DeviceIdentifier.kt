package info.benjaminhill.simplemesh

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.random.Random

object DeviceIdentifier {
    private const val PREFS_NAME = "SimpleMeshPrefs"
    private const val PREF_UNIQUE_ID = "UUID"
    private var uniqueID: String? = null

    private const val BASE58_ALPHABET = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"

    fun randomString(length: Int): String = buildString(length) {
        repeat(length) {
            val randomIndex = Random.nextInt(BASE58_ALPHABET.length)
            append(BASE58_ALPHABET[randomIndex])
        }
    }

    fun get(context: Context): String {
        if (uniqueID == null) {
            val sharedPrefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            uniqueID = sharedPrefs.getString(PREF_UNIQUE_ID, null)
            if (uniqueID == null) {
                uniqueID = "device-${randomString(6)}"
                sharedPrefs.edit {
                    putString(PREF_UNIQUE_ID, uniqueID)
                }
            }
        }
        return uniqueID!!
    }
}
