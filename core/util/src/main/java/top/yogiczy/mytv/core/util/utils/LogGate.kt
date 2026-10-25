package top.yogiczy.mytv.core.util.utils

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 调试日志总开关。默认关闭。
 * SharedPreferences 的键是 [PREF_KEY]，与设置项 DEBUG_LOG 相同。
 */
object LogGate {
    const val PREF_KEY = "DEBUG_LOG"

    @Volatile
    var enabled: Boolean = false
        private set

    private var applied = false
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun setEnabled(value: Boolean) {
        if (applied && enabled == value) return
        applied = true
        enabled = value
        for (listener in listeners) {
            listener(value)
        }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
