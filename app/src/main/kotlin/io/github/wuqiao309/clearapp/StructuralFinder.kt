package io.github.wuqiao309.clearapp

import android.os.Build
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

/**
 * 按**结构**去宿主 dex 里找方法。
 *
 * 番茄/红果是「类名可读、方法名混淆、混淆包号每次打包都变」—— `r32.o` 升个版本就成了 `y32.o`。
 * 但**形参表和返回类型是框架类型**（`android.view.ViewGroup`、`void`），跨版本不变，所以按签名
 * 能把目标重新捞回来，不用每次升级都人工重映射。
 *
 * **只在按名字找不到时才走这里**。DexKit 首次查询要扫一遍宿主 dex（番茄两百多 MB），是要花时间的；
 * 按名字找是零开销。它是兜底，不是主力 —— 构造函数里什么都不做，第一次真用到才建 bridge。
 */
class StructuralFinder(
    private val hostApkPath: String?,
    private val moduleApkPath: String?,
    private val hostDataDir: String?,
    private val classLoader: ClassLoader,
    private val log: ModuleLog,
) {

    private var bridge: DexKitBridge? = null
    private var unavailable = false

    /**
     * 按形参表和返回类型找方法，两边都用 `android.view.ViewGroup` / `void` 这种跨版本稳定的名字。
     *
     * [excluding] 是「这条特征还会命中、但不是我们要的」那些类 —— 别的规则已经管了的，等等。
     * 排除项都写可读名（框架包名、我们自己另一条规则的目标），跨版本稳定。
     *
     * **过滤完还不唯一就不挂。** 同签名的方法在这个 app 里通常有好几个，挂错一个比漏掉更糟。
     */
    fun findMethod(
        parameterTypeNames: List<String>,
        returnTypeName: String,
        excluding: List<String> = emptyList(),
    ): Method? {
        val dex = bridge() ?: return null
        val shape = "(${parameterTypeNames.joinToString()}) -> $returnTypeName"
        return try {
            val matcher = MethodMatcher().apply {
                paramTypes = parameterTypeNames
                returnType = returnTypeName
            }
            val raw = dex.findMethod(FindMethod.create().matcher(matcher))
            val candidates = raw
                .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                .filterNot { isNoise(it, excluding) }
            when (candidates.size) {
                0 -> {
                    log.warn("按结构找不到 $shape（原始命中 ${raw.size} 个，都被滤掉了：${raw.map { "${it.className}#${it.methodName}" }}）")
                    null
                }
                1 -> candidates.first().also {
                    log.info("按结构找回 $shape -> ${it.declaringClass.name}#${it.name}")
                }
                else -> {
                    log.warn("按结构找到 ${candidates.size} 个 $shape，不够独特，放弃：${candidates.map { "${it.declaringClass.name}#${it.name}" }}")
                    null
                }
            }
        } catch (e: Throwable) {
            log.warn("按结构查 $shape 失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun isNoise(method: Method, excluding: List<String>): Boolean {
        val owner = method.declaringClass.name
        // 接口上的声明不是实现，挂它没意义
        if (method.declaringClass.isInterface) return true
        if (FRAMEWORK_PREFIXES.any { owner.startsWith(it) }) return true
        return excluding.any { owner == it || owner.startsWith("$it\$") }
    }

    private companion object {
        /** 框架自己的同签名方法，永远不是我们要的。 */
        val FRAMEWORK_PREFIXES = listOf("android.", "androidx.", "java.", "kotlin.", "kotlinx.")
    }

    fun close() {
        runCatching { bridge?.close() }
        bridge = null
    }

    private fun bridge(): DexKitBridge? {
        bridge?.let { return it }
        if (unavailable) return null
        val apk = hostApkPath
        if (apk == null) {
            log.warn("拿不到宿主 APK 路径，结构查找不可用")
            return null.also { unavailable = true }
        }
        if (!loadNativeLibrary()) {
            unavailable = true
            return null
        }
        return try {
            DexKitBridge.create(apk).also {
                bridge = it
                log.info("DexKit 就绪")
            }
        } catch (e: Throwable) {
            log.warn("DexKit 初始化失败：${e.javaClass.simpleName}: ${e.message}")
            unavailable = true
            null
        }
    }

    /**
     * 加载 libdexkit.so。
     *
     * 模块跑在宿主进程里，`System.loadLibrary` 找的是**宿主的** lib 目录，当然没有我们的 so，
     * 所以得把 so 从模块 APK 里解出来，再按全路径 `System.load`。
     */
    private fun loadNativeLibrary(): Boolean {
        runCatching {
            System.loadLibrary("dexkit")
            log.info("libdexkit 由 System.loadLibrary 加载成功")
        }.onSuccess { return true }
            .onFailure { log.debug("System.loadLibrary(dexkit) 不可用（${it.javaClass.simpleName}），改从模块 APK 解") }

        val moduleApk = moduleApkPath
        val dataDir = hostDataDir
        if (moduleApk == null || dataDir == null) {
            log.warn("拿不到模块 APK 路径或宿主数据目录，libdexkit 加载不了")
            return false
        }

        return try {
            ZipFile(moduleApk).use { zip ->
                val abi = Build.SUPPORTED_ABIS.firstOrNull { zip.getEntry("lib/$it/libdexkit.so") != null }
                if (abi == null) {
                    log.warn("模块 APK 里没有适配 ${Build.SUPPORTED_ABIS.toList()} 的 libdexkit.so")
                    return false
                }
                val entry = zip.getEntry("lib/$abi/libdexkit.so")!!
                val target = File(dataDir, "cache/clearapp/libdexkit-$abi.so")
                target.parentFile?.mkdirs()
                // 之前解过、大小也对就不重复写
                if (target.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                System.load(target.absolutePath)
                log.info("libdexkit(abi=$abi) 从模块 APK 解出并加载：$target")
                true
            }
        } catch (e: Throwable) {
            log.warn("libdexkit 提取/加载失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
