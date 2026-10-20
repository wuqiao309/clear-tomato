package io.github.wuqiao309.clearapp.hooks

import io.github.wuqiao309.clearapp.ClassResolver
import io.github.wuqiao309.clearapp.HookManager
import io.github.wuqiao309.clearapp.ModuleLog
import io.github.wuqiao309.clearapp.readField

/**
 * 智行火车票 (`com.yipiao`) 的界面精简规则。
 *
 * 当前是 **Phase 0：只装探针，不改任何行为**。
 *
 * 智行和番茄/红果不是一个架构：底部 tab、首页子 tab、「我的」是原生的，但抢票/订单/支付是
 * CRN（React Native）页面，首页内容又是 Compose 模块。所以在写正式规则前，先把下面这些入口的
 * 真实数据打出来 —— 尤其是：
 *
 * - 底栏每个 tab 的 `txtStr`/`action`/`classPath`
 * - 首页子 tab 的 `tag`/`tabName`（`home_hotel` = 酒店、`home_stay` = 民宿/客栈）
 * - 首页模块的 key 和顺序（找「执行·低价不怕比」及以下）
 * - 「我的」服务项的 `type`/`title`（`my_footprint` = 我的足迹）
 * - 订单/营销/支付弹窗的调用时机和模型
 * - 路由 URL（找「已预约」前的邀请好友页）
 *
 * 探针全部调用 `chain.proceed()`，不会改变宿主行为。
 */
class YipiaoHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    private var lastHomeTabs: String? = null
    private val loggedRoutes = LinkedHashSet<String>()
    private val lastPopupActionAt = mutableMapOf<String, Long>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 点完「否」后到这个时间点之前，拦截邀好友页的 CRN modal 路由，让它根本不打开。 */
    private var blockInviteUntil = 0L

    private var currentActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    fun installAll() {
        probeBottomTabs()
        filterHomeTabs()
        hideCenterActivityTab()
        hideMineModules()
        hideMineViewSections()
        hideHomeFloat()
        hideMineFloatBall()
        hideOrderAdBanner()
        hideHomeModulesBelow()
        probeHomeModules()
        filterConfigLists()
        probeMineServices()
        probeOrderPopup()
        probeMarketing()
        probePayment()
        probeRouter()
        hideOrderFloat()
        handleNativeAds()
        trackCurrentActivity()
        autoHandlePopups()
    }

    /** 底部主 tab：`ZTTabBarView.setData(List<ZTTabEntity>)`。 */
    private fun probeBottomTabs() {
        val setData = resolver.findMethod(TAB_BAR, "setData", "java.util.List")
        hooks.intercept("yipiao-tabbar", setData) { chain ->
            val tabs = chain.getArg(0) as? List<*>
            val detail = tabs.orEmpty().joinToString(" | ") {
                "${it.readField("txtStr")}[${it.readField("action")}] ${it.readField("classPath")}"
            }
            log.info("底栏 tab：$detail")
            chain.proceed()
        }
    }

    /**
     * 首页子 tab：`AzureSetupManager.k()` 返回这次要显示的 `HomeModule` 列表。
     *
     * 只保留火车票/机票，去掉 `home_stay`（民宿/客栈）和 `home_hotel`（酒店）。
     * `initializeModules()` 会基于返回的列表重算默认下标，所以返回过滤后的副本是安全的。
     */
    private fun filterHomeTabs() {
        val modules = resolver.findMethod(SETUP_MANAGER, "k")
        hooks.intercept("yipiao-home-tabs", modules) { chain ->
            val result = chain.proceed()
            val list = result as? List<*> ?: return@intercept result
            val kept = list.filterNot { (it.readField("tag") as? String) in HOME_TABS_BLOCKED }
            val detail = kept.joinToString(" | ") { "${it.readField("tag")}=${it.readField("tabName")}" }
            if (detail != lastHomeTabs) {
                lastHomeTabs = detail
                log.info("首页子 tab：$detail")
            }
            kept
        }
    }

    /** 底栏中间的「超级星期二」活动位：返回 false 时 `addLocalActivityTabView()` 不会创建它。 */
    private fun hideCenterActivityTab() {
        val valid = resolver.findMethod(ACTIVITY_TAB_DATA, "isInValidTimePeriod")
        hooks.intercept("yipiao-center-activity-tab", valid) { chain ->
            log.info("屏蔽底栏活动位")
            false
        }
    }

    /** 「我的」页的足迹、周边服务、右下悬浮球。都是 Compose，直接在函数入口跳过。 */
    private fun hideMineModules() {
        skipComposable("yipiao-mine-footprint", FOOTPRINT_COMPOSE, "e", 7)
        skipComposable("yipiao-mine-nearby", NEARBY_COMPOSE, "d", 5)
        skipComposable("yipiao-mine-float", HOME_FLOAT_COMPOSE, "f", 7)
    }

    private fun skipComposable(id: String, className: String, methodName: String, arity: Int) {
        val method = resolver.findMethodByArity(className, methodName, arity) ?: return
        hooks.intercept(id, method) { chain ->
            log.info("屏蔽我的页模块：$id")
            null
        }
    }

    /**
     * 当前版本实际使用的是 **View 版**「我的」页（Compose 版只在部分模式启用）：
     *
     * - 我的足迹：`PersonFootPrintViewA.setData(...)`
     * - 周边服务：布局里的独立 section，标题文案固定为「周边服务」
     */
    private fun hideMineViewSections() {
        val footprint = resolver.findMethod(FOOTPRINT_VIEW, "setData", FOOT_PRINT)
        hooks.intercept("yipiao-mine-footprint-view", footprint) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? android.view.View)?.visibility = android.view.View.GONE
            log.info("屏蔽我的页足迹")
            result
        }

        val owner = resolver.findClass("android.widget.TextView") ?: return
        owner.declaredMethods.filter { it.name == "setText" }.forEach { method ->
            hooks.intercept("yipiao-nearby-${method.parameterCount}", method) { chain ->
                if (chain.getArg(0)?.toString() == NEARBY_TITLE) {
                    (chain.thisObject as? android.view.View)?.let { hideAncestor(it, 2, "我的页周边服务") }
                }
                chain.proceed()
            }
        }
    }

    /**
     * 订单页下滑流里的推广。
     *
     * - 「加智行微信好友…」：往上两层是整条 banner
     * - 「本行程订酒店最高省…」：往上三层是整个酒店推广 item
     */
    private fun hideOrderAdBanner() {
        val owner = resolver.findClass("android.widget.TextView") ?: return
        owner.declaredMethods.filter { it.name == "setText" }.forEach { method ->
            hooks.intercept("yipiao-order-ad-${method.parameterCount}", method) { chain ->
                val text = chain.getArg(0)?.toString().orEmpty()
                val view = chain.thisObject as? android.view.View
                when {
                    text.contains(ORDER_AD_HINT) -> view?.let { hideAncestor(it, 2, "订单广告") }
                    text.contains(ORDER_HOTEL_HINT) -> mainHandler.postDelayed({ hideHotelPromoItem() }, 500L)
                }
                chain.proceed()
            }
        }
    }

    /**
     * 首页「智行·低价不怕比」及其以下所有模块。
     *
     * 首页是 CRN 渲染，模块是一组同级 View。找到标题所在的 section，把它和后面的兄弟节点一起隐藏。
     * 每次重新渲染标题都会再次触发，滚动后也能重新应用。
     */
    private fun hideHomeModulesBelow() {
        val owner = resolver.findClass("android.widget.TextView") ?: return
        owner.declaredMethods.filter { it.name == "setText" }.forEach { method ->
            hooks.intercept("yipiao-home-below-${method.parameterCount}", method) { chain ->
                if (chain.getArg(0)?.toString()?.contains(HOME_CUTOFF_TITLE) == true) {
                    // RN 可能重建节点，跑几次把最终布局压住；按位置裁剪，重复执行是幂等的
                    repeat(3) { attempt ->
                        mainHandler.postDelayed({ hideHomeBelowCutoff() }, 500L * (attempt + 1))
                    }
                }
                chain.proceed()
            }
        }
    }

    /**
     * 从当前页面找到可见的「低价不怕比」section，只隐藏 **top 在其之下** 的兄弟节点。
     *
     * 之前按子节点索引隐藏，RN 重建/预加载副本会让索引错位，把上方的工具模块也一起藏掉。
     */
    private fun hideHomeBelowCutoff() {
        val root = currentActivity?.get()?.window?.decorView ?: return
        val text = findVisibleText(root, HOME_CUTOFF_TITLE) ?: return
        val screenWidth = text.resources.displayMetrics.widthPixels

        var section: android.view.View = text
        while (section.width < screenWidth * SECTION_WIDTH_RATIO) {
            section = section.parent as? android.view.View ?: return
        }
        val container = section.parent as? android.view.ViewGroup ?: return
        val sectionTop = section.top
        var hidden = 0
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.top >= sectionTop) {
                child.visibility = android.view.View.GONE
                hidden++
            }
        }
        log.info("屏蔽首页模块：$HOME_CUTOFF_TITLE 及以下（$hidden 个）")
    }

    private fun findVisibleText(view: android.view.View, hint: String): android.view.View? {
        if (view is android.widget.TextView && view.isShown && view.text?.contains(hint) == true) {
            val visible = android.graphics.Rect()
            if (view.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0) return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findVisibleText(view.getChildAt(i), hint)?.let { return it }
            }
        }
        return null
    }

    /**
     * 订单页的酒店推广是一个独立的 RecyclerView item。
     *
     * `setText` 可能命中的是预加载副本，祖先链里没有 RecyclerView，所以从当前页面的
     * RecyclerView 直接遍历 item，找到包含推广文案的那个，隐藏并把高度置 0。
     */
    private fun hideHotelPromoItem() {
        val root = currentActivity?.get()?.window?.decorView ?: return
        val recycler = findRecyclerContaining(root, ORDER_HOTEL_HINT) ?: run {
            log.warn("屏蔽订单酒店推广：没找到推广 item")
            return
        }
        for (i in 0 until recycler.childCount) {
            val item = recycler.getChildAt(i)
            if (!containsText(item, ORDER_HOTEL_HINT)) continue
            item.visibility = android.view.View.GONE
            item.layoutParams?.let { params ->
                params.height = 0
                item.layoutParams = params
            }
            recycler.requestLayout()
            log.info("屏蔽订单酒店推广（item $i 高度置 0）")
            return
        }
        log.warn("屏蔽订单酒店推广：没找到推广 item")
    }

    /** 找到「直接子 item 里包含 [hint]」的那个 RecyclerView。 */
    private fun findRecyclerContaining(view: android.view.View, hint: String): android.view.ViewGroup? {
        val recyclerClass = resolver.findClass(RECYCLER_VIEW) ?: return null
        if (recyclerClass.isInstance(view) && view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsText(view.getChildAt(i), hint)) return view
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findRecyclerContaining(view.getChildAt(i), hint)?.let { return it }
            }
        }
        return null
    }

    private fun containsText(view: android.view.View, hint: String): Boolean {
        if (view is android.widget.TextView && view.text?.contains(hint) == true) return true
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsText(view.getChildAt(i), hint)) return true
            }
        }
        return false
    }

    /** 等视图挂到父容器后，隐藏第 [levels] 层祖先。 */
    private fun hideAncestor(view: android.view.View, levels: Int, label: String, attempt: Int = 0) {
        view.post {
            var target: android.view.View? = view
            repeat(levels) { target = target?.parent as? android.view.View }
            when {
                target != null -> {
                    target.visibility = android.view.View.GONE
                    // RecyclerView 对外部 GONE 不一定立即重排，显式请求一次，避免留下空白
                    (target.parent as? android.view.View)?.requestLayout()
                    log.info("屏蔽$label")
                }
                attempt < 8 -> view.postDelayed({ hideAncestor(view, levels, label, attempt + 1) }, 150L)
            }
        }
    }

    /**
     * 「我的」页右下悬浮球。
     *
     * 它是 RN 底部区块里的一张 `ReactImageView`（实测 169x146，容器是它自己的 ReactViewGroup），
     * 文案树里没有可识别文本，所以按 **尺寸 + 屏幕位置** 定位：宽度 >= 屏幕 10%、高度 >= 100、
     * 位于右下角。标题「添加智行福利官」出现后延时压几次。
     */
    private fun hideMineFloatBall() {
        val owner = resolver.findClass("android.widget.TextView") ?: return
        owner.declaredMethods.filter { it.name == "setText" }.forEach { method ->
            hooks.intercept("yipiao-mine-float-ball-${method.parameterCount}", method) { chain ->
                if (chain.getArg(0)?.toString()?.contains(MINE_FLOAT_PROBE) == true) {
                    val view = chain.thisObject as? android.view.View
                    repeat(4) { attempt ->
                        // rootView 要等挂窗后再取，setText 当下可能还没 attach
                        mainHandler.postDelayed({ hideMineFloatBallNow(view?.rootView) }, 800L * (attempt + 1))
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun hideMineFloatBallNow(root: android.view.View?) {
        if (root == null) return

        // 福利官横幅本身：文案往上三层就是整条 banner
        findVisibleText(root, MINE_FLOAT_PROBE)?.let { hideAncestor(it, 3, "我的福利官横幅") }

        val screenWidth = root.resources.displayMetrics.widthPixels
        val screenHeight = root.resources.displayMetrics.heightPixels
        val location = IntArray(2)
        var images = 0
        var hidden = 0
        fun walk(view: android.view.View) {
            if (view.javaClass.name.contains("ReactImageView")) {
                images++
                if (view.width >= screenWidth * 0.10f && view.height >= 100) {
                    view.getLocationOnScreen(location)
                    if (location[0] in 0 until screenWidth && location[1] in 0 until screenHeight &&
                        location[0] > screenWidth * 0.7f && location[1] > 2200
                    ) {
                        ((view.parent as? android.view.View) ?: view).visibility = android.view.View.GONE
                        hidden++
                        log.info("屏蔽我的页悬浮球：${view.width}x${view.height} @${location[0]},${location[1]}")
                    }
                }
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        if (hidden == 0) log.warn("我的页悬浮球：没找到（ReactImageView=$images）")
    }

    /** 右下悬浮球（`HomeFloatView`）：不拉配置，也永远不认为自己该显示。 */
    private fun hideHomeFloat() {
        hooks.intercept("yipiao-home-float-show", resolver.findMethod(HOME_FLOAT_VIEW, "getIsShow")) { chain ->
            false
        }
        hooks.intercept("yipiao-home-float-fetch", resolver.findMethod(HOME_FLOAT_VIEW, "fetchData")) { chain ->
            log.info("屏蔽悬浮球")
            null
        }
    }

    /** 首页内容模块：`ModuleManager.dispatchRequest(key, request)`。 */
    private fun probeHomeModules() {
        val dispatch = resolver.findMethod(MODULE_MANAGER, "dispatchRequest", "String", MODULE_REQUEST)
        hooks.intercept("yipiao-home-module", dispatch) { chain ->
            val key = chain.getArg(0)
            val data = chain.getArg(1)?.readField("data")
            val summary = data?.let { "${it.javaClass.name} ${it.toString().take(300)}" } ?: "null"
            log.info("首页模块：$key data=$summary")
            chain.proceed()
        }
    }

    /**
     * 首页 tab 的配置来源：`getConfigList("azure_home_config", "aTabModulesV2", HomeModule.class)`。
     *
     * CRN 版首页直接读这份配置，不经过 `AzureSetupManager.k()`，所以在这里过滤才能影响真机上的 tab 栏。
     * 其它配置列表只记日志。
     */
    private fun filterConfigLists() {
        val owner = resolver.findClass(CONFIG_MANAGER) ?: return
        owner.declaredMethods.filter { it.name == "getConfigList" }.forEach { method ->
            hooks.intercept("yipiao-config-${method.parameterCount}", method) { chain ->
                val result = chain.proceed()
                val list = result as? List<*> ?: return@intercept result
                val args = chain.args.orEmpty()
                val isHomeTabs = args.getOrNull(0)?.toString() == HOME_TAB_CONFIG &&
                    args.any { (it as? Class<*>)?.name == HOME_MODULE }
                val kept = if (isHomeTabs) {
                    list.filterNot { (it.readField("tag") as? String) in HOME_TABS_BLOCKED }
                } else {
                    list
                }
                val detail = kept.joinToString(" | ") { "${it.readField("tag")}=${it.readField("tabName")}" }
                log.info("配置列表：${args.joinToString(" | ") { it?.toString().orEmpty().take(40) }} -> $detail")
                kept
            }
        }
    }

    /** 「我的」服务项：`ServiceModuleViewB.loadView(List<PersonalCenterService>)`。 */
    private fun probeMineServices() {
        val loadView = resolver.findMethod(MINE_SERVICE_VIEW, "loadView", "java.util.List")
        hooks.intercept("yipiao-mine-services", loadView) { chain ->
            val list = chain.getArg(0) as? List<*>
            val detail = list.orEmpty().joinToString(" | ") {
                "${it.readField("type")}=${it.readField("title")}"
            }
            log.info("我的服务项：$detail")
            chain.proceed()
        }
    }

    /** 订单页的「临近出发」弹窗：`OrderGlossary.B(TravelOrderPopConfig)`。 */
    private fun probeOrderPopup() {
        val showPop = resolver.findMethod(ORDER_GLOSSARY, "B", ORDER_POP_CONFIG)
        hooks.intercept("yipiao-order-pop", showPop) { chain ->
            val items = chain.getArg(0)?.readField("popItems")
            log.info("订单弹窗配置：${items?.toString()?.take(500) ?: "null"}")
            chain.proceed()
        }
    }

    /** 营销弹窗/悬浮层：`HomeMarketingManager.addDisplay(...)` 与 `processPopList(...)`。 */
    private fun probeMarketing() {
        listOf(
            "yipiao-market-2" to arrayOf("android.content.Context", MARKET_POPUP_ITEM),
            "yipiao-market-3" to arrayOf("android.content.Context", MARKET_POPUP_ITEM, "String"),
            "yipiao-market-4" to
                arrayOf("android.content.Context", MARKET_POPUP_ITEM, "String", "kotlin.jvm.functions.Function0"),
        ).forEach { (id, params) ->
            val method = resolver.findMethod(MARKETING_MANAGER, "addDisplay", *params)
            hooks.intercept(id, method) { chain ->
                val source = chain.getArg(2)
                log.info("营销弹窗：${describeMarketItem(chain.getArg(1))} source=$source")
                chain.proceed()
            }
        }

        val process = resolver.findMethod(MARKETING_MANAGER, "processPopList", "android.content.Context", "java.util.List")
        hooks.intercept("yipiao-market-list", process) { chain ->
            val list = chain.getArg(1) as? List<*>
            val detail = list.orEmpty().joinToString(" | ") { describeMarketItem(it) }
            log.info("营销弹窗列表：$detail")
            chain.proceed()
        }
    }

    /** 去支付页的销售信息弹窗：`SaleInfoPayManager` 的四个公开入口。 */
    private fun probePayment() {
        listOf(
            "h" to arrayOf("android.content.Context", SALE_INFO, "kotlin.jvm.functions.Function1"),
            "i" to arrayOf("android.content.Context", "String", "String", "kotlin.jvm.functions.Function1"),
            "k" to arrayOf("android.content.Context", "String", "String", "kotlin.jvm.functions.Function1"),
            "m" to arrayOf("android.content.Context", SALE_INFO, "kotlin.jvm.functions.Function3"),
        ).forEach { (name, params) ->
            val method = resolver.findMethod(SALE_PAY_MANAGER, name, *params)
            hooks.intercept("yipiao-pay-$name", method) { chain ->
                log.info("支付弹窗入口：SaleInfoPayManager#$name args=${chain.args?.joinToString()}")
                chain.proceed()
            }
        }
    }

    /**
     * 路由。
     *
     * 「抢票成功率」和「邀好友」共用同一个 CRN modal 容器。抢票成功率那条要在点「否」之后
     * 才允许流程继续，所以不能全局拦截；只在 **点完「否」之后的一小段时间内** 拦截同容器的
     * 下一次路由 —— 那次正好是邀好友页，直接不放行，页面根本不弹。
     */
    private fun probeRouter() {
        listOf(
            "yipiao-router" to arrayOf("android.content.Context", "String"),
            "yipiao-router-title" to arrayOf("android.content.Context", "String", "String"),
        ).forEach { (id, params) ->
            val method = resolver.findMethod(ROUTER, "openURI", *params)
            hooks.intercept(id, method) { chain ->
                val url = chain.getArg(1)?.toString()
                logRoute(url)
                val blockInvite = url != null &&
                    url.contains(INVITE_MODAL) &&
                    android.os.SystemClock.uptimeMillis() < blockInviteUntil
                if (blockInvite) {
                    log.info("屏蔽邀好友页（不弹出）")
                    true
                } else {
                    chain.proceed()
                }
            }
        }
    }

    /** 跟踪当前前台 Activity —— 弹窗那个 TextView 几秒后可能已经 detach，不能靠它拿根 View。 */
    private fun trackCurrentActivity() {
        val onResume = resolver.findMethodByArity("android.app.Activity", "onResume", 0) ?: return
        hooks.intercept("yipiao-current-activity", onResume) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? android.app.Activity)?.let {
                currentActivity = java.lang.ref.WeakReference(it)
            }
            result
        }
    }

    /** 订单页底部待支付浮球：不再拉取待支付订单，浮球就不会出现。 */
    private fun hideOrderFloat() {
        val fetch = resolver.findMethod(ORDER_FLOAT, "fetchWaitPayOrders")
        hooks.intercept("yipiao-order-float", fetch) { chain ->
            log.info("屏蔽订单待支付浮球")
            null
        }
    }

    /**
     * 原生广告：`TripAdManager`。
     *
     * `isShowTripAd()` 读的是配置 `CTRIP_AD_SWITCH/isCtripAD`，是原生广告的总开关；
     * banner 走 `loadBannerAd`，直接跳过加载即可。启动页/插屏广告涉及启动流程，暂不拦截。
     */
    private fun handleNativeAds() {
        hooks.intercept("yipiao-ad-off", resolver.findMethod(AD_MANAGER, "isShowTripAd")) { chain ->
            log.info("关闭原生广告开关")
            false
        }
        listOf(3, 4, 5).forEach { arity ->
            hooks.intercept("yipiao-ad-banner-off-$arity", resolver.findMethodByArity(AD_MANAGER, "loadBannerAd", arity)) { chain ->
                log.info("屏蔽 banner 广告/$arity")
                null
            }
        }
        hooks.intercept("yipiao-ad-enable", resolver.findMethod(AD_MANAGER, "canShowAD", "boolean")) { chain ->
            log.info("广告开关 canShowAD(${chain.getArg(0)})")
            chain.proceed()
        }
        hooks.intercept(
            "yipiao-ad-check",
            resolver.findMethod(
                AD_MANAGER,
                "checkAd",
                INIT_CALLBACK,
                "androidx.fragment.app.FragmentActivity",
                "int",
                "kotlin.jvm.functions.Function0",
                "kotlin.jvm.functions.Function1",
            ),
        ) { chain ->
            log.info("广告：checkAd")
            chain.proceed()
        }
        hooks.intercept("yipiao-ad-splash", resolver.findMethodByArity(AD_MANAGER, "showTimelySplashAd", 3)) { chain ->
            log.info("广告：showTimelySplashAd")
            chain.proceed()
        }
    }

    /**
     * 支付/预约流程里会挡路的弹窗。
     *
     * 都由 CRN 渲染（文案走 `ReactTextView#setText`），而且**必须做出选择流程才会继续**，
     * 所以不能只隐藏，必须替用户完成那个动作：
     *
     * | 弹窗 | 继续流程的动作 |
     * | --- | --- |
     * | 我是全能保障专员 | 点「否」 |
     * | 邀好友免费升级 | 返回（实测返回后直接进入已预约页） |
     * | 高峰期余票监控 | 点「暂不开启」 |
     *
     * 点击走根 View 的 DOWN/UP 派发，不需要 `INJECT_EVENTS` 权限。
     */
    private fun autoHandlePopups() {
        val owner = resolver.findClass("android.widget.TextView") ?: return
        owner.declaredMethods.filter { it.name == "setText" }.forEach { method ->
            hooks.intercept("yipiao-popup-${method.parameterCount}", method) { chain ->
                val text = chain.getArg(0)?.toString().orEmpty()
                val view = chain.thisObject as? android.view.View
                if (view != null && text.isNotEmpty()) {
                    when {
                        text.contains(POPUP_GRAB_RATE) -> {
                            if (schedulePopupAction(view, "grab-rate", DECLINE_TEXT)) {
                                // 点完「否」之后的短时间内，拦掉邀好友页的路由，让它不弹出
                                blockInviteUntil =
                                    android.os.SystemClock.uptimeMillis() + INVITE_BLOCK_WINDOW_MS
                            }
                        }
                        text.contains(POPUP_MONITOR) -> schedulePopupAction(view, "monitor", MONITOR_DECLINE_TEXT)
                    }
                }
                chain.proceed()
            }
        }
    }

    /** 同一个弹窗会重复 setText，按 [key] 去抖，避免连点。 */
    private fun schedulePopupAction(view: android.view.View, key: String, buttonText: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - (lastPopupActionAt[key] ?: 0L) < POPUP_DEBOUNCE_MS) return false
        lastPopupActionAt[key] = now
        log.info("自动处理弹窗：$key → 点击「$buttonText」")
        view.postDelayed({ findAndClick(view.rootView, buttonText, 0) }, POPUP_DELAY_MS)
        return true
    }

    /** 布局完成后在视图树里找 [text]，找到就派发一次点击；没找到就短间隔重试。 */
    private fun findAndClick(root: android.view.View?, text: String, attempt: Int) {
        if (root == null) return
        val target = findText(root, text)
        if (target == null) {
            if (attempt < DECLINE_RETRIES) {
                root.postDelayed({ findAndClick(root, text, attempt + 1) }, DECLINE_RETRY_MS)
            } else {
                log.warn("抢票成功率弹窗：没找到「$text」按钮")
            }
            return
        }
        log.info("抢票成功率弹窗：点击「$text」 ${target.width}x${target.height}")
        tapView(target)
    }

    private fun findText(view: android.view.View, text: String): android.view.View? {
        if (view is android.widget.TextView && view.text?.toString() == text) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    /** 在根 View 上派发一次真实触摸，走宿主的 RN 触摸链路。 */
    private fun tapView(target: android.view.View) {
        val root = target.rootView ?: return
        val targetLocation = IntArray(2)
        val rootLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation)
        root.getLocationOnScreen(rootLocation)
        val x = (targetLocation[0] - rootLocation[0]) + target.width / 2f
        val y = (targetLocation[1] - rootLocation[1]) + target.height / 2f

        val downTime = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(
            downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0,
        )
        root.dispatchTouchEvent(down)
        down.recycle()

        val up = android.view.MotionEvent.obtain(
            downTime, downTime + TAP_UP_DELAY_MS, android.view.MotionEvent.ACTION_UP, x, y, 0,
        )
        root.postDelayed({
            root.dispatchTouchEvent(up)
            up.recycle()
        }, TAP_UP_DELAY_MS)
    }

    private fun describeMarketItem(item: Any?): String {
        if (item == null) return "null"
        return "type=${item.readField("type")} key=${item.readField("key")} " +
            "marketId=${item.readField("marketId")} float=${item.readField("floatingLayerResult") != null} " +
            "jump=${item.readField("jumpUrl")}"
    }

    /** 路由调用很频繁，只记每个 URL 一次，避免刷屏。 */
    private fun logRoute(url: String?) {
        if (url.isNullOrBlank() || !loggedRoutes.add(url)) return
        if (loggedRoutes.size > MAX_LOGGED_ROUTES) loggedRoutes.clear()
        log.info("路由：$url")
    }

    private companion object {
        const val TAB_BAR = "com.app.base.widget.tab.ZTTabBarView"

        const val SETUP_MANAGER = "com.app.common.home.helper.AzureSetupManager"
        const val CONFIG_MANAGER = "com.app.base.config.ZTConfigManager"
        const val HOME_TAB_CONFIG = "azure_home_config"
        const val HOME_MODULE = "com.app.common.home.data.HomeModule"
        const val ACTIVITY_TAB_DATA = "com.app.base.loacltab.model.HomePageActivityTabData"

        /** 首页要去掉的子 tab：民宿/客栈、酒店。 */
        val HOME_TABS_BLOCKED = setOf("home_stay", "home_hotel")

        const val FOOTPRINT_COMPOSE = "com.app.train.main.personal.compose.module.FootprintComposeKt"
        const val NEARBY_COMPOSE = "com.app.train.main.personal.compose.module.MyNearbyComposeKt"
        const val HOME_FLOAT_COMPOSE = "com.app.train.main.personal.compose.header.HomeFloatComposeKt"

        const val FOOTPRINT_VIEW = "com.app.train.main.personal.view.PersonFootPrintViewA"
        const val FOOT_PRINT = "com.app.common.order.model.FootPrint"
        const val HOME_FLOAT_VIEW = "com.app.train.home.widget.HomeFloatView"
        const val NEARBY_TITLE = "周边服务"
        const val ORDER_AD_HINT = "加智行微信好友"
        const val ORDER_HOTEL_HINT = "本行程订酒店最高省"
        const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
        const val HOME_CUTOFF_TITLE = "低价不怕比"

        /** section 容器宽度至少占屏幕这个比例，用来区分整块 section 和里面的卡片。 */
        const val SECTION_WIDTH_RATIO = 0.85f
        const val MINE_FLOAT_PROBE = "添加智行福利官"

        const val MODULE_MANAGER = "com.app.base.home.module.ModuleManager"
        const val MODULE_REQUEST = "com.app.base.home.module.ModuleRequest"

        const val MINE_SERVICE_VIEW = "com.app.majia.personal.view.ServiceModuleViewB"

        const val ORDER_GLOSSARY = "com.app.common.order.experimentc.OrderGlossary"
        const val ORDER_POP_CONFIG = "com.app.common.order.experimentc.model.TravelOrderPopConfig"
        const val ORDER_FLOAT = "com.app.common.order.widget.entrance.WaitPayFloatViewModel"

        const val MARKETING_MANAGER = "com.app.base.dialog.manager.HomeMarketingManager"
        const val MARKET_POPUP_ITEM = "com.app.base.model.market.HomeMarketPopupItem"

        const val SALE_PAY_MANAGER = "com.app.majia.order.dialog.manager.SaleInfoPayManager"
        const val SALE_INFO = "com.app.majia.order.model.BannerSaleInfo"

        const val ROUTER = "com.app.base.router.ZTRouter"

        const val AD_MANAGER = "com.app.base.tripad.TripAdManager"
        const val INIT_CALLBACK = "com.app.base.interfaces.InitCallback"

        const val MAX_LOGGED_ROUTES = 200

        const val POPUP_GRAB_RATE = "全能保障专员"
        const val POPUP_MONITOR = "当前票源紧张"

        /** 抢票成功率弹窗和邀好友页共用的 CRN modal 容器。 */
        const val INVITE_MODAL = "xtaro-component-train-grab-home-ticket-dialog-crn"

        /** 点「否」之后拦截邀好友路由的时间窗口。 */
        const val INVITE_BLOCK_WINDOW_MS = 10_000L

        const val DECLINE_TEXT = "否"
        const val MONITOR_DECLINE_TEXT = "暂不开启"

        const val POPUP_DELAY_MS = 400L
        const val POPUP_DEBOUNCE_MS = 2_000L
        const val DECLINE_RETRY_MS = 150L
        const val DECLINE_RETRIES = 8
        const val TAP_UP_DELAY_MS = 60L
    }
}
