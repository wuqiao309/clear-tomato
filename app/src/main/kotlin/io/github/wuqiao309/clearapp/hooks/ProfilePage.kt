package io.github.wuqiao309.clearapp.hooks

import io.github.wuqiao309.clearapp.ClassResolver
import io.github.wuqiao309.clearapp.HookManager
import io.github.wuqiao309.clearapp.ModuleLog
import io.github.wuqiao309.clearapp.readField

/** 「我的」主 tab：屏蔽金币/微信提现入口与推荐流。 */
internal class ProfilePage(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
) {

    fun install() {
        hideCoinCard()
        hideCoinEntrances()
        hideRecommendFeed()
    }

    /**
     * 金币/现金余额/微信提现/福利 是同一张卡片，走 Polaris 卡片工厂。
     *
     * 卡片分发器在建不出卡片时本来就会跳过（`f` 里对工厂返回 null 的情形已经判过），所以这里返回
     * null 等于「这张卡不存在」，比事后再去隐藏它的视图稳。
     */
    private fun hideCoinCard() {
        val dispatch = resolver.findMethod(CARD_DISPATCHER, "f", CARD_INFO) ?: return
        hooks.intercept(ID_COIN_CARD, dispatch) { chain ->
            val cardType = chain.getArg(0)?.let { cardTypeName(it) }
            if (cardType in Rules.Fanqie.MINE_CARDS_BLOCKED) {
                log.info("屏蔽我的页卡片：$cardType")
                null
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * 卡片内部还有一批「这一格要不要显示」的入口（提现按钮、金刚位里的金币入口等），逐个按编号拒绝。
     *
     * 判断方法声明在接口上，实现挂在接口的 `IMPL` 静态字段里，所以要按运行时实现类挂。
     */
    private fun hideCoinEntrances() {
        val owner = resolver.findServiceImplementation(CELL_SERVICE)
        val target = owner?.let { resolver.findMethod(it.name, "isCellTypeShowable", "int") }
        hooks.intercept(ID_COIN_ENTRANCE, target) { chain ->
            val cell = chain.getArg(0) as? Int
            if (cell in Rules.Fanqie.MINE_CELLS_BLOCKED) {
                log.info("屏蔽我的页入口 cell=$cell")
                false
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * 「我的」页的推荐流是页面自己按一个总开关决定要不要搭出来的。
     *
     * 关掉这个开关，页面压根不会去建推荐流的容器、也就不会发那次请求；页面上的头部、功能入口、
     * 订单那些都在这个开关之外，不受影响。
     */
    private fun hideRecommendFeed() {
        hooks.intercept(ID_FEED, resolver.findMethod(FEED_GATE, "d")) { chain ->
            log.info("关闭我的页推荐流")
            false
        }
    }

    /** `vv3.d` 只有一个 CardType 字段，取它来判断这是哪张卡；字段名被混淆过，按候选名依次试。 */
    private fun cardTypeName(cardInfo: Any): String? {
        val cardType = CARD_TYPE_FIELDS.firstNotNullOfOrNull { cardInfo.readField(it) }
        return (cardType as? Enum<*>)?.name ?: cardType?.toString()
    }

    private companion object {
        const val ID_COIN_CARD = "fanqie-mine-coin-card"
        const val CARD_DISPATCHER = "lc4.c"
        const val CARD_INFO = "vv3.d"

        const val ID_COIN_ENTRANCE = "fanqie-mine-coin-entrance"
        const val CELL_SERVICE = "com.dragon.read.component.biz.impl.mine.card.IBsMineTabCellService"

        const val ID_FEED = "fanqie-mine-recommend-feed"
        const val FEED_GATE = "com.dragon.read.base.ssconfig.template.d"

        val CARD_TYPE_FIELDS = listOf("a", "cardType", "f437455a")
    }
}
