package io.github.wuqiao309.clearapp

import android.app.Application
import android.content.pm.PackageInfo
import io.github.wuqiao309.clearapp.hooks.FanqieHooks
import io.github.wuqiao309.clearapp.hooks.HongguoHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 模块入口。libxposed 会为每个被作用域覆盖的进程各创建一个实例。
 *
 * 只在目标 app 的主进程里装 hook：番茄还会起 `:push`、`:sandbox` 之类的辅助进程，在那里挂 UI 相关的
 * hook 除了浪费开销没有意义。
 */
class ClearAppModule : XposedModule() {

    private var processName = UNKNOWN_PROCESS

    private val log: ModuleLog by lazy { ModuleLog(this, processName) }

    private var hooks: HookManager? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log.info(
            "模块加载 process=$processName api=$apiVersion framework=$frameworkName/$frameworkVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (packageName !in TARGET_PACKAGES) return
        if (!param.isFirstPackage) {
            log.debug("忽略非首个 classloader: $packageName")
            return
        }
        if (processName != packageName) {
            log.info("跳过非主进程 $processName（包名 $packageName）")
            return
        }

        val versionCode = readVersionCode(param)?.toString() ?: "未知"
        log.info("目标就绪 package=$packageName versionCode=$versionCode")

        val manager = HookManager(this, log)
        hooks = manager
        val resolver = ClassResolver(param.classLoader, log)
        when (packageName) {
            FANQIE -> FanqieHooks(manager, resolver, log).installAll()
            HONGGUO -> HongguoHooks(manager, resolver, log).installAll()
        }
        log.info("本轮共挂载 ${manager.installedIds.size} 条规则: ${manager.installedIds}")
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val manager = hooks
        log.info("热重载：卸载 ${manager?.installedIds?.size ?: 0} 条规则")
        manager?.unhookAll()
        hooks = null
        return true
    }

    /**
     * 读宿主 app 的 versionCode。
     *
     * 只用来写日志 —— 各条规则都靠「找不到就跳过」自己兜底，所以读不到不算错误，返回 null 即可。
     * 两条路径都可能在某个系统版本上被隐藏 API 限制挡掉，所以依次尝试。
     */
    private fun readVersionCode(param: PackageReadyParam): Long? =
        readFromApkFile(param) ?: readFromRunningApplication(param)

    private fun readFromApkFile(param: PackageReadyParam): Long? = runCatching {
        val packageManager = Class.forName("android.content.pm.PackageManager", false, param.classLoader)
        val getArchiveInfo = packageManager.getMethod(
            "getPackageArchiveInfo",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        (getArchiveInfo.invoke(null, param.applicationInfo.sourceDir, 0) as? PackageInfo)?.longVersionCode
    }.getOrNull()

    private fun readFromRunningApplication(param: PackageReadyParam): Long? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread", false, param.classLoader)
        val application = activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
        application?.packageManager?.getPackageInfo(param.packageName, 0)?.longVersionCode
    }.getOrNull()

    private companion object {
        const val UNKNOWN_PROCESS = "<unknown>"
        const val FANQIE = "com.dragon.read"
        const val HONGGUO = "com.phoenix.read"
        val TARGET_PACKAGES = setOf(FANQIE, HONGGUO)
    }
}
