package com.clearapp.module

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 记录所有装上的 hook，方便热重载时统一卸载。
 *
 * 每次安装都会把“哪条规则挂到了哪个方法上”写进日志 —— 定位“没生效”时，第一个要回答的问题
 * 永远是目标方法到底找没找到。
 */
class HookManager(private val module: XposedModule, private val log: ModuleLog) {

    private val handles = mutableListOf<XposedInterface.HookHandle>()

    val installedIds: List<String> get() = handles.mapNotNull { it.id }

    /**
     * 拦截 [target]，由 [body] 决定返回值。
     *
     * 不调用 `chain.proceed()` 就等于吞掉原方法；需要走原逻辑时在 [body] 里自行调用。
     * [target] 为 null（方法没找到）时只记一条 warn 并跳过，不影响其它规则。
     */
    fun intercept(
        id: String,
        target: Method?,
        deoptimize: Boolean = false,
        body: (XposedInterface.Chain) -> Any?,
    ) {
        if (target == null) {
            log.warn("跳过 $id：目标方法不存在")
            return
        }
        install(id, target, deoptimize, XposedInterface.Hooker { chain -> body(chain) })
    }

    /**
     * 让 [target] 固定返回 [value]，即「这个方法永远说不是/永远是」。
     *
     * 每次命中都记一条日志：只挂上不等于真的拦到了，目标方法可能在这个版本的这条路径上压根没被调用。
     */
    fun replaceResult(id: String, target: Method?, value: Any?, deoptimize: Boolean = false) =
        intercept(id, target, deoptimize) { chain ->
            log.info("拦截 $id -> $value")
            value
        }

    /** 让返回 boolean 的 [target] 固定返回 [value]，并在类型不对时拒绝安装而不是运行期炸掉。 */
    fun replaceBoolean(id: String, target: Method?, value: Boolean, deoptimize: Boolean = false) {
        if (target != null && target.returnType != Boolean::class.javaPrimitiveType) {
            log.warn("跳过 $id：${target.name} 的返回类型不是 boolean，而是 ${target.returnType.simpleName}")
            return
        }
        replaceResult(id, target, value, deoptimize)
    }

    /** 在 [target] 上装一个只记日志、不改行为的探针，用来观察参数和调用时机。 */
    fun probe(id: String, target: Method?) = intercept(id, target) { chain ->
        log.debug("$id 被调用 args=${chain.args}")
        chain.proceed()
    }

    fun unhookAll() {
        handles.forEach { handle ->
            runCatching { handle.unhook() }.onFailure { log.warn("卸载 ${handle.id} 失败: ${it.message}") }
        }
        handles.clear()
    }

    private fun install(
        id: String,
        target: Method,
        deoptimize: Boolean,
        hooker: XposedInterface.Hooker,
    ) {
        try {
            if (deoptimize) {
                runCatching { module.deoptimize(target) }
                    .onFailure { log.warn("deoptimize $id 失败: ${it.javaClass.simpleName}") }
            }
            val handle = module.hook(target)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker)
            handles += handle
            log.info("已挂载 $id -> ${target.declaringClass.name}#${target.name}")
        } catch (e: Throwable) {
            log.error("挂载 $id 失败 (${target.declaringClass.name}#${target.name})", e)
        }
    }
}
