package io.github.wuqiao309.clearapp

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method

/**
 * 按名字找宿主 app 的类和方法。
 *
 * 番茄的类名基本没有被混淆（例如 `com.dragon.read.component.biz.impl.NsAdImpl`），所以直接按
 * 名字定位是最省事也最好排查的做法。找不到就返回 null 并记一条日志，让调用方决定是跳过还是放弃 ——
 * 版本对不上时模块应该降级成“部分生效”，而不是整体崩掉。
 */
class ClassResolver(private val classLoader: ClassLoader, private val log: ModuleLog) {

    /** 按全限定名加载类；失败返回 null。 */
    fun findClass(name: String): Class<*>? = try {
        Class.forName(name, false, classLoader)
    } catch (e: Throwable) {
        log.warn("找不到类 $name (${e.javaClass.simpleName})")
        null
    }

    /**
     * 找声明在 [className] 自身上的方法。[parameterTypeNames] 支持 `int`/`boolean` 等裸类型名和类全限定名。
     *
     * 番茄大量使用父类承载逻辑，找继承来的方法请用 [findMethodInHierarchy]。
     */
    fun findMethod(className: String, methodName: String, vararg parameterTypeNames: String): Method? {
        val owner = findClass(className) ?: return null
        val parameterTypes = resolveAll(className, methodName, parameterTypeNames) ?: return null
        return declaredMethod(owner, methodName, parameterTypes)
    }

    /**
     * 按 [methodNames] 里的候选名依次找方法，取第一个找到的。
     *
     * 番茄和红果出自同一套代码但混淆结果不同：同一个挂载方法在番茄叫 `s`、在红果叫 `t`。参数表是
     * 可读的类型（`android.view.ViewGroup` 这种），不会变，所以按参数表锁定、名字放宽。
     */
    fun findMethodAnyName(className: String, methodNames: List<String>, vararg parameterTypeNames: String): Method? {
        val owner = findClass(className) ?: return null
        val parameterTypes = parameterTypeNames.map { resolveType(it) }
        if (parameterTypes.any { it == null }) {
            log.warn("放弃查找 $className：参数类型 $parameterTypeNames 无法解析")
            return null
        }
        val types = parameterTypes.filterNotNull().toTypedArray<Class<*>>()
        methodNames.forEach { name ->
            declaredMethod(owner, name, types, quiet = true)?.let {
                if (name != methodNames.first()) log.info("$className 的方法叫 $name（不是 ${methodNames.first()}）")
                return it
            }
        }
        log.warn("找不到方法 $className 上的任何一个：$methodNames(${parameterTypeNames.joinToString()})")
        return null
    }

    /** 沿类继承链向上查找方法。 */
    fun findMethodInHierarchy(
        className: String,
        methodName: String,
        vararg parameterTypeNames: String,
    ): Method? {
        val parameterTypes = resolveAll(className, methodName, parameterTypeNames) ?: return null
        var owner: Class<*>? = findClass(className)
        while (owner != null) {
            declaredMethod(owner, methodName, parameterTypes, quiet = true)?.let { return it }
            owner = owner.superclass
        }
        log.warn("找不到方法 $className#$methodName（已查继承链）")
        return null
    }

    /**
     * 找返回类型为 [returnTypeName]、名字为 [methodName] 的方法，忽略具体参数表。
     *
     * 适用于参数表被混淆或重载太多、但方法名和语义还认得出的场景。
     */
    fun findMethodByReturnType(className: String, methodName: String, returnTypeName: String): Method? {
        val owner = findClass(className) ?: return null
        val expected = resolveType(returnTypeName)
        val match = owner.declaredMethods.firstOrNull { it.name == methodName && it.returnType == expected }
        if (match == null) {
            log.warn("找不到方法 $className#$methodName 返回 $returnTypeName")
            return null
        }
        return match.also { it.isAccessible = true }
    }

    /**
     * 读 [className] 上的静态字段。
     *
     * 用来拿到接口背后的运行时实现类（字节的 ServiceManager 会把实现挂在接口的 `IMPL` 静态字段上，
     * 直接 hook 接口是挂不上的），或者拿到宿主缓存的集合。
     */
    fun staticField(className: String, fieldName: String): Any? {
        val owner = findClass(className) ?: return null
        return try {
            owner.getDeclaredField(fieldName).also { it.isAccessible = true }.get(null)
        } catch (e: Throwable) {
            log.warn("读不到静态字段 $className.$fieldName（${e.javaClass.simpleName}），该字段有：${owner.declaredFields.joinToString { it.name }}")
            null
        }
    }

    /** 把接口上的 `IMPL` 静态字段解析成运行时实现类；不用这个方法就只能拿到接口本身。 */
    fun findServiceImplementation(interfaceName: String): Class<*>? =
        (staticField(interfaceName, "IMPL"))?.javaClass

    /** 列出 [className] 上名字含 [keyword] 的方法，用于人工定位目标方法。 */
    fun listMethods(className: String, keyword: String = ""): List<Method> {
        val owner = findClass(className) ?: return emptyList()
        return owner.declaredMethods.filter { keyword.isEmpty() || it.name.contains(keyword, ignoreCase = true) }
    }

    private fun resolveAll(owner: String, method: String, names: Array<out String>): Array<Class<*>>? {
        val resolved = names.map { resolveType(it) }
        val missing = names.filterIndexed { index, _ -> resolved[index] == null }
        if (missing.isNotEmpty()) {
            log.warn("放弃查找 $owner#$method：参数类型 $missing 无法解析")
            return null
        }
        return resolved.filterNotNull().toTypedArray<Class<*>>()
    }

    private fun declaredMethod(
        owner: Class<*>,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        quiet: Boolean = false,
    ): Method? = try {
        owner.getDeclaredMethod(methodName, *parameterTypes).also { it.isAccessible = true }
    } catch (e: Throwable) {
        if (!quiet) {
            log.warn("找不到方法 ${owner.name}#$methodName(${parameterTypes.joinToString { it.simpleName }})")
        }
        null
    }

    private fun resolveType(name: String): Class<*>? = when (name) {
        "void" -> Void.TYPE
        "boolean" -> Boolean::class.javaPrimitiveType
        "byte" -> Byte::class.javaPrimitiveType
        "char" -> Char::class.javaPrimitiveType
        "short" -> Short::class.javaPrimitiveType
        "int" -> Int::class.javaPrimitiveType
        "long" -> Long::class.javaPrimitiveType
        "float" -> Float::class.javaPrimitiveType
        "double" -> Double::class.javaPrimitiveType
        "Object" -> Any::class.java
        "String" -> String::class.java
        "CharSequence" -> CharSequence::class.java
        "Boolean" -> Boolean::class.javaObjectType
        "Integer" -> Integer::class.javaObjectType
        "Long" -> Long::class.javaObjectType
        "Float" -> Float::class.javaObjectType
        "Double" -> Double::class.javaObjectType
        else -> if (name.endsWith("[]")) {
            resolveType(name.removeSuffix("[]"))?.let { ReflectArray.newInstance(it, 0).javaClass }
        } else {
            findClass(name)
        }
    }
}
