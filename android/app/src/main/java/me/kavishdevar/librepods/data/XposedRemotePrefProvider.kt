package me.kavishdevar.librepods.data

object XposedRemotePrefProvider {
    fun create(): XposedRemotePref = XposedRemotePrefImpl()
}
