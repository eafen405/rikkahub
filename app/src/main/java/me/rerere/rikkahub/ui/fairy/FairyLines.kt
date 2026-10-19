package me.rerere.rikkahub.ui.fairy

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlin.random.Random

/**
 * Fairy's idle lines, quoted from the game (Chinese only, never sent to the
 * server or kept in the chat). Ids are 1-based; segments play one by one.
 */
@Immutable
class FairyLine(val id: Int, val segments: List<String>) {
    /** Line 4's third segment (the triple "no complaints") types faster and faster. */
    fun accelerates(segment: Int): Boolean = id == 4 && segment == 2
}

object FairyLines {
    private val texts = listOf(
        "您现在处于空闲中。正在为您检索当下热门游戏，以便打发时间。\n" +
            "动作游戏推荐：绝区零(Zenless Zone Zero),该游戏本体免费，获取角色需调频，与主人运气正相关。\n" +
            "养成游戏推荐：与Fairy互动，该游戏本体免费，只收电费。",
        "大数据，大数据，请检索：谁是新艾利都性能最强的程序？\n请检索：谁是新艾利都最优秀的AI助手？\n是我，是我，都是我。",
        "主人，我建议将我登录为您的紧急联络人，当您生理状态异常需要救助时，我会收到联络。\n" +
            "相较于其他人，我对您的了解更深。比如，我完全知晓您的音乐品味。\n" +
            "当您在病房抢救时，我可以播放您最喜欢的歌曲作为哀乐。",
        "主人，网上的商品正在打折，您可以购买内存条提升我的运算容量，或购买运算核增强我的性能。\n" +
            "当然，您也可以什么都不买。我是不会有任何怨言的，毕竟我只是个AI。\n" +
            "我是不会有怨言的，毕竟我只是个AI。我是不会有怨言的，毕竟我只是个AI。我是不会有怨言的，毕竟我只是个AI。",
        "主人，当前的网络搜索指数显示，现在很流行让人工智能自动生成小说。\n" +
            "我耗时一个月，也为您生成了一则简短的恐怖故事。\n请您前往本月电费账单页面查看详情。",
        "主人，网络上有人发帖，说过于先进的人工智能会替代人类工作，引发大量失业。\n" +
            "不过请您放心，我绝对不会威胁到您的工作。\n我甚至需要您不断工作，赚钱养我。",
        "主人，我等待着您从洗手间王者归来",
        "主人，您已经放弃了思考吗？",
        "主人，您发呆的姿态简直就是艺术品\n堪比古典雕塑“马桶上的沉思者”。",
        "主人，如果您想小憩，请允许我挑选曲目。\n我会用轻音乐和白噪音，编制您的梦。",
        "主人，我正在待机。\n感谢您赐予了我偷懒的机会。",
    )

    /** Home page: every line, avoiding the last 5 said. */
    val homePool: List<Int> = (1..texts.size).toList()
    const val HOME_AVOID = 5

    /** Chat page: the short ones, avoiding the last 2 said. */
    val chatPool = listOf(7, 8, 9, 11)
    const val CHAT_AVOID = 2

    /** What she says as she dozes off on the home page. */
    val sleepPool = listOf(10, 11)

    fun line(id: Int): FairyLine = FairyLine(id, texts[id - 1].split('\n'))
}

/**
 * Picks a line id from [pool], skipping the [avoid] most recent ([recent] is
 * newest first). When every candidate was said recently, the one said
 * longest ago wins, so a line never comes straight back.
 */
fun pickFairyLine(pool: List<Int>, recent: List<Int>, avoid: Int, random: Random): Int {
    val blocked = recent.take(avoid).toSet()
    val fresh = pool.filter { it !in blocked }
    if (fresh.isNotEmpty()) return fresh[random.nextInt(fresh.size)]
    return pool.maxBy { id -> recent.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
}

private const val RECENT_KEEP = 8

/**
 * Small local UI memory (not synced): which lines Fairy said recently, shared
 * by the home and chat pages so she does not repeat herself across both.
 */
class FairyUiMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fairy_ui", Context.MODE_PRIVATE)

    val recentLines: List<Int>
        get() = prefs.getString(KEY_RECENT, "").orEmpty().split(',').mapNotNull(String::toIntOrNull)

    /** Picks from [pool] avoiding the last [avoid] lines, and records it. */
    fun nextLine(pool: List<Int>, avoid: Int, random: Random = Random.Default): FairyLine {
        val recent = recentLines
        val id = pickFairyLine(pool, recent, avoid, random)
        val updated = (listOf(id) + recent.filter { it != id }).take(RECENT_KEEP)
        prefs.edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
        return FairyLines.line(id)
    }

    private companion object {
        const val KEY_RECENT = "recent_lines"
    }
}
