package me.kavishdevar.librepods.data

class XposedRemotePrefImpl: XposedRemotePref {
    override fun isAvailable(): Boolean { return false }

    override fun getBoolean(key: String, def: Boolean): Boolean {
        return false
    }

    override fun putBoolean(key: String, value: Boolean) { }
}
