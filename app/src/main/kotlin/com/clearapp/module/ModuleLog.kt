package com.clearapp.module

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 模块日志。
 *
 * LSPosed 的模块日志默认只有框架自己能读到，排查问题时不方便；这里同时写一份到
 * logcat，用 `adb logcat -s ClearApp` 就能直接看到。tag 带上进程名，因为番茄会起多个进程。
 */
class ModuleLog(private val module: XposedModule, processName: String) {

    private val tag = "ClearApp/$processName"

    fun debug(message: String) = write(Log.DEBUG, message, null)

    fun info(message: String) = write(Log.INFO, message, null)

    fun warn(message: String) = write(Log.WARN, message, null)

    fun error(message: String, cause: Throwable? = null) = write(Log.ERROR, message, cause)

    private fun write(priority: Int, message: String, cause: Throwable?) {
        if (cause == null) {
            module.log(priority, tag, message)
            Log.println(priority, tag, message)
        } else {
            module.log(priority, tag, message, cause)
            Log.println(priority, tag, "$message: ${Log.getStackTraceString(cause)}")
        }
    }
}
