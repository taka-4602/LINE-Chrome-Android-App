package com.example.line_chrome.data

import com.example.line_chrome.line.Chat
import com.example.line_chrome.line.ChatType
import com.example.line_chrome.line.Contact
import com.example.line_chrome.line.ContentType
import com.example.line_chrome.line.Message
import com.example.line_chrome.line.Profile
import java.util.concurrent.atomic.AtomicLong

/**
 * The fictional account this build runs on.
 *
 * Pure data, deliberately: it produces the same [Message] and [Contact] objects
 * the Thrift decoder would have produced, so every screen renders a demo world
 * through exactly the same path as a real session.  Nothing here talks to a
 * network, and [LineRepository] never builds a [com.example.line_chrome.line.LineClient]
 * while [LineRepository.DEMO] is set.
 *
 * Timestamps are relative to the moment the app starts, so the conversations
 * always look current — "25 minutes ago" rather than a date in 2026.
 */
object DemoData {

    // MIDs keep LINE's shape — 'u' for a person, 'c' for a group, then 32 hex
    // digits — because the app reads that prefix to tell the two apart.
    const val SELF_MID = "ua1b2c3d4e5f60718293a4b5c6d7e8f90"

    private const val MINAMI = "u3f7c2e9a1b4d6081c5a3e7f9b2d4c608"
    private const val YUKI = "u9d4e1a7b3c5f8206e4b1d9a7c3f5e820"
    private const val AYANO = "u5c8f3b1d7e9a2064f8c1b3d5e7a9f204"
    private const val DAIKI = "u2e6a9c4f1b8d3750a6e2c8f4b1d7e903"
    private const val SAKURA = "u7b3d5f9e1a4c6082d7f3b9e5a1c4d608"
    private const val RYO = "u4a8c2e6b9d1f3705c8a4e2b6d9f1c307"
    private const val HIKARU = "u6d2f8b4a1c9e5073b2d6f8a4c1e9b507"

    /** In the family group but not in the friend list — a real and common case. */
    private const val HAHA = "u8f4b2d6a9c1e5073f4b8d2a6c9e1b407"
    private const val IMOUTO = "u1c9e5073b2d6f8a4c1e9b5072d6f8a44"

    private const val DEV = "c1e5a9d3b7f2c608a4e1d5b9f3c7a208"
    private const val UNI = "c8b4f2d6a9c1e507b3f8d2a6c9e1b407"
    private const val FAMILY = "c5a1c7e3b9d4f602a8c5e1b7d3f9a604"

    /** Everything [LineRepository] needs to stand a signed-in session up. */
    class World(
        val profile: Profile,
        /** Shown in the Friends tab. */
        val contacts: List<Contact>,
        val groups: List<Chat>,
        /** Named but not befriended — group senders the chat list must still label. */
        val others: List<Contact>,
        /** chatMid -> messages, oldest first, exactly as the repository stores them. */
        val messages: Map<String, List<Message>>,
        val unread: Map<String, Int>,
    )

    // -- people ------------------------------------------------------------

    /**
     * Avatars are generated per name by DiceBear, so nobody's real photograph
     * ends up in a demo.  A failed fetch falls through to the initial-on-tint
     * avatar the app already draws, so a bad venue Wi-Fi is survivable.
     */
    private fun avatar(seed: String, style: String = "adventurer") =
        "https://api.dicebear.com/9.x/$style/png?seed=$seed&size=240"

    private fun person(
        mid: String,
        name: String,
        status: String? = null,
        seed: String,
        nickname: String? = null,
    ) = Contact(
        mid = mid,
        displayName = name,
        statusMessage = status,
        displayNameOverride = nickname,
        pictureStatus = null,
        picturePath = avatar(seed),
        contactType = 0,
        status = 0,
        relation = 0,
        createdTime = 0L,
    )

    private fun group(mid: String, name: String, seed: String) = Chat(
        chatMid = mid,
        chatType = ChatType.GROUP,
        chatName = name,
        createdTime = 0L,
        notificationDisabled = false,
        picturePath = avatar(seed, style = "shapes"),
    )

    private val profile = Profile(
        mid = SELF_MID,
        displayName = "山田 たくみ",
        statusMessage = "よろしくお願いします🙌",
        pictureStatus = null,
        picturePath = avatar("Takumi", style = "notionists"),
        userid = "takumi_y",
        phone = null,
        email = "takumi@example.com",
        regionCode = "JP",
    )

    private val friends = listOf(
        person(MINAMI, "佐藤 みなみ", "カフェ巡りが趣味です☕", "Minami"),
        person(YUKI, "田中 ゆうき", "よろしくお願いします", "Yuki"),
        person(AYANO, "高橋 あやの", "ランニング再開しました🏃", "Ayano"),
        person(DAIKI, "鈴木 だいき", null, "Daiki"),
        person(SAKURA, "中村 さくら", "音楽と猫が好き🐈", "Sakura"),
        person(RYO, "小林 りょう", "返信おそめです🙏", "Ryo"),
        // A nickname you set yourself.  LINE shows this instead of the name they
        // chose, and the profile page distinguishes the two.
        person(HIKARU, "渡辺 ひかる", "🎧", "Hikaru", nickname = "ひかるん"),
    )

    private val strangers = listOf(
        person(HAHA, "母", null, "Mother"),
        person(IMOUTO, "妹", null, "Sister"),
    )

    private val chatGroups = listOf(
        group(DEV, "開発チーム", "devteam"),
        group(UNI, "大学の友達", "university"),
        group(FAMILY, "家族", "family"),
    )

    /** Who speaks in each group, so a scripted reply comes from a plausible member. */
    private val members = mapOf(
        DEV to listOf(AYANO, DAIKI, SAKURA),
        UNI to listOf(RYO, HIKARU),
        FAMILY to listOf(HAHA, IMOUTO),
    )

    // -- message construction ----------------------------------------------

    private val ids = AtomicLong(90_000_000L)

    private fun message(
        id: String,
        sender: String,
        chatMid: String,
        at: Long,
        text: String? = null,
        contentType: Int = ContentType.TEXT,
        meta: Map<String, String> = emptyMap(),
    ): Message {
        val peer = chatMid.startsWith("u")
        return Message(
            id = id,
            sender = sender,
            // In a 1:1 the recipient is whoever is not the sender; in a group
            // everything is addressed to the group itself.
            to = if (!peer) chatMid else if (sender == SELF_MID) chatMid else SELF_MID,
            toType = if (peer) ChatType.PEER else ChatType.GROUP,
            createdTime = at,
            text = text,
            contentType = contentType,
            contentMetadata = meta,
            hasContent = contentType == ContentType.IMAGE || contentType == ContentType.VIDEO,
            location = null,
            relatedMessageId = null,
            relationType = null,
            chunks = null,
        )
    }

    /** A message the user has just sent, timestamped now. */
    fun outgoing(
        chatMid: String,
        text: String?,
        at: Long,
        contentType: Int = ContentType.TEXT,
    ) = message(
        id = "demo-${ids.incrementAndGet()}",
        sender = SELF_MID,
        chatMid = chatMid,
        at = at,
        text = text,
        contentType = contentType,
    )

    /** A message arriving from someone else, timestamped now. */
    fun incoming(
        chatMid: String,
        sender: String,
        text: String?,
        at: Long,
        contentType: Int = ContentType.TEXT,
        meta: Map<String, String> = emptyMap(),
    ) = message(
        id = "demo-${ids.incrementAndGet()}",
        sender = sender,
        chatMid = chatMid,
        at = at,
        text = text,
        contentType = contentType,
        meta = meta,
    )

    // -- the conversations -------------------------------------------------

    fun build(now: Long): World {
        // Minutes back from now, so the transcript always reads as "today" and
        // the older entries fall behind a "Yesterday" separator on their own.
        fun ago(minutes: Long) = now - minutes * 60_000L

        var n = 0
        fun id() = "demo-seed-${n++}"

        fun say(sender: String, chat: String, minutes: Long, body: String) =
            message(id(), sender, chat, ago(minutes), body)

        fun photo(sender: String, chat: String, minutes: Long) =
            message(id(), sender, chat, ago(minutes), null, ContentType.IMAGE)

        val messages = linkedMapOf(
            MINAMI to listOf(
                say(MINAMI, MINAMI, 1_560, "明日の打ち合わせ、14時からで大丈夫ですか？"),
                say(SELF_MID, MINAMI, 1_550, "大丈夫です！資料は今日中に送りますね"),
                say(MINAMI, MINAMI, 1_540, "助かります🙏"),
                say(SELF_MID, MINAMI, 180, "資料できました！確認おねがいします"),
                photo(SELF_MID, MINAMI, 178),
                say(MINAMI, MINAMI, 150, "見ました！すごく分かりやすいです✨"),
                say(MINAMI, MINAMI, 25, "了解です！明日よろしく 👍"),
            ),

            DEV to listOf(
                say(AYANO, DEV, 300, "おはようございます。今日のスタンドアップは10時からです"),
                say(DAIKI, DEV, 295, "了解です"),
                say(SELF_MID, DEV, 290, "5分ほど遅れます、すみません"),
                say(SAKURA, DEV, 285, "大丈夫ですよ〜"),
                say(AYANO, DEV, 70, "リリース来週で確定です📝 テスト項目まとめておきます"),
            ),

            YUKI to listOf(
                say(YUKI, YUKI, 1_680, "先週の写真送るね！"),
                photo(YUKI, YUKI, 1_675),
                say(SELF_MID, YUKI, 1_670, "ありがとう！めっちゃいい感じ📸"),
                say(SELF_MID, YUKI, 200, "今度みんなで行こう〜"),
                say(YUKI, YUKI, 185, "いいね！日程きめよう😊"),
            ),

            FAMILY to listOf(
                say(HAHA, FAMILY, 540, "今日の夕飯なにがいい？"),
                say(SELF_MID, FAMILY, 530, "なんでも大丈夫！"),
                say(IMOUTO, FAMILY, 525, "カレーがいい🍛"),
                say(HAHA, FAMILY, 390, "じゃあカレーにします🍛"),
            ),

            SAKURA to listOf(
                say(SAKURA, SAKURA, 1_800, "ライブのチケット取れた！"),
                say(SELF_MID, SAKURA, 1_790, "まじで！？いいなー"),
                message(
                    id(), SAKURA, SAKURA, ago(460), null,
                    ContentType.STICKER, mapOf("STKID" to "52002734", "STKPKGID" to "11537"),
                ),
            ),

            UNI to listOf(
                say(RYO, UNI, 3_000, "今週末の同窓会、参加できる人〜"),
                say(SELF_MID, UNI, 2_990, "行けます！"),
                say(HIKARU, UNI, 2_880, "私も行く〜"),
                say(RYO, UNI, 1_560, "じゃあ土曜18時、駅前集合で！"),
            ),

            AYANO to listOf(
                say(AYANO, AYANO, 3_120, "例のドキュメント共有しました"),
                say(SELF_MID, AYANO, 3_060, "確認します、ありがとう"),
                // Files are labelled but never downloaded, so this renders the
                // same "[File]" bubble a real one would.
                message(id(), AYANO, AYANO, ago(1_800), null, ContentType.FILE),
            ),

            RYO to listOf(
                say(RYO, RYO, 4_320, "久しぶり！元気？"),
                say(SELF_MID, RYO, 4_260, "元気だよ〜そっちは？"),
            ),
        )

        return World(
            profile = profile,
            contacts = friends,
            groups = chatGroups,
            others = strangers,
            messages = messages,
            unread = mapOf(MINAMI to 2, DEV to 1),
        )
    }

    // -- scripted responses ------------------------------------------------

    /** How long a reply takes to "arrive" after the user sends something. */
    const val REPLY_DELAY_MS = 1_800L

    class Reply(val sender: String, val text: String)

    private val scripts = mapOf(
        MINAMI to listOf(
            "なるほど、了解です！",
            "ありがとうございます😊",
            "確認しますね🙏",
            "では明日よろしくお願いします🙌",
        ),
        YUKI to listOf("おっけー！", "いいね〜😄", "りょーかい👌"),
        SAKURA to listOf("ほんと！？", "たのしみ🎶", "わかった〜"),
        AYANO to listOf("ありがとうございます", "確認しました✅"),
        RYO to listOf("おお、いいね", "了解〜"),
        HIKARU to listOf("うんうん", "オッケー✨"),
        DAIKI to listOf("了解です", "確認します"),
        DEV to listOf("了解です", "確認します！", "ありがとうございます🙏", "こちらでも見てみますね"),
        UNI to listOf("了解です！", "いいね〜", "楽しみ🎉"),
        FAMILY to listOf("わかった〜", "はーい", "ありがとう😊"),
    )

    private val fallback = listOf("ありがとう！", "了解です👍", "なるほど〜")

    /**
     * Who answers, and with what.
     *
     * [turn] increments per sent message so a demo that goes back and forth
     * does not repeat the same line — and a group answers from a different
     * member each time, which is what makes it read as a group.
     */
    fun replyFor(chatMid: String, turn: Int): Reply {
        val lines = scripts[chatMid] ?: fallback
        val roster = members[chatMid]
        val sender = when {
            roster != null -> roster[turn % roster.size]
            chatMid.startsWith("u") -> chatMid          // a 1:1 answers as itself
            else -> SELF_MID
        }
        return Reply(sender, lines[turn % lines.size])
    }

    /** The answer to an image the user just sent. */
    fun mediaReplyFor(chatMid: String, turn: Int): Reply {
        val roster = members[chatMid]
        val sender = when {
            roster != null -> roster[turn % roster.size]
            chatMid.startsWith("u") -> chatMid
            else -> SELF_MID
        }
        val lines = listOf("いい写真！📸", "わ、ありがとう😊", "おお〜！いいね")
        return Reply(sender, lines[turn % lines.size])
    }

    // -- ambient traffic ---------------------------------------------------

    /**
     * Messages that arrive on their own while the app is open.
     *
     * Without these a demo is completely static until someone types, and the
     * unread badges, the chat-list reordering and the notifications — the parts
     * worth showing — never fire.  The list cycles.
     */
    class Ambient(
        val afterMs: Long,
        val chatMid: String,
        val sender: String,
        val text: String?,
        val contentType: Int = ContentType.TEXT,
        val meta: Map<String, String> = emptyMap(),
    )

    val ambient = listOf(
        Ambient(45_000, MINAMI, MINAMI, "あ、そうだ！会議室って予約しましたか？"),
        Ambient(60_000, DEV, DAIKI, "ビルド通りました✅"),
        Ambient(75_000, YUKI, YUKI, "今週末あいてる？"),
        Ambient(60_000, FAMILY, IMOUTO, "アイスも買ってきて〜🍨"),
        Ambient(
            90_000, SAKURA, SAKURA, null,
            ContentType.STICKER, mapOf("STKID" to "52002735", "STKPKGID" to "11537"),
        ),
        Ambient(70_000, DEV, SAKURA, "レビューお願いします🙏"),
        Ambient(80_000, UNI, RYO, "写真あげておいたよ〜"),
        Ambient(65_000, MINAMI, MINAMI, "ありがとうございます！助かりました🙏"),
    )
}
