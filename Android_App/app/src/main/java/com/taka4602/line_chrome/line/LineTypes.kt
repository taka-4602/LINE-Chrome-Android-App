package com.taka4602.line_chrome.line

/**
 * Typed views over the integer-keyed maps the Thrift decoder returns.
 * Port of `line_chrome/types.py`.
 */

object OpType {
    const val END_OF_OPERATION = 0
    const val UPDATE_PROFILE = 1
    const val NOTIFIED_UPDATE_PROFILE = 2
    const val ADD_CONTACT = 4
    const val NOTIFIED_ADD_CONTACT = 5
    const val CREATE_GROUP = 9
    const val UPDATE_GROUP = 10
    const val NOTIFIED_UPDATE_GROUP = 11
    const val INVITE_INTO_GROUP = 12
    const val NOTIFIED_INVITE_INTO_GROUP = 13
    const val LEAVE_GROUP = 14
    const val NOTIFIED_LEAVE_GROUP = 15
    const val ACCEPT_GROUP_INVITATION = 16
    const val KICKOUT_FROM_GROUP = 18
    const val NOTIFIED_KICKOUT_FROM_GROUP = 19
    const val LEAVE_ROOM = 23
    const val NOTIFIED_LEAVE_ROOM = 24
    const val SEND_MESSAGE = 25
    const val RECEIVE_MESSAGE = 26
    const val SEND_MESSAGE_RECEIPT = 27
    const val RECEIVE_MESSAGE_RECEIPT = 28
    const val SEND_CHAT_CHECKED = 40
    const val NOTIFIED_READ_MESSAGE = 55
    const val DESTROY_MESSAGE = 64
    const val NOTIFIED_DESTROY_MESSAGE = 65
}

object ContentType {
    const val TEXT = 0          // LINE sends plain text as NONE, body in field 10
    const val IMAGE = 1
    const val VIDEO = 2
    const val AUDIO = 3
    const val CALL = 6
    const val STICKER = 7
    const val GIFT = 9
    const val CONTACT = 13
    const val FILE = 14
    const val LOCATION = 15
    const val FLEX = 22

    fun label(value: Int): String = when (value) {
        IMAGE -> "Image"
        VIDEO -> "Video"
        AUDIO -> "Audio"
        CALL -> "Call"
        STICKER -> "Sticker"
        GIFT -> "Gift"
        CONTACT -> "Contact"
        FILE -> "File"
        LOCATION -> "Location"
        FLEX -> "Flex"
        else -> "Type $value"
    }
}

object ChatType {
    const val GROUP = 0
    const val ROOM = 1
    const val PEER = 2          // 1:1 chat with a single user
}

object MessageRelationType {
    const val FORWARD = 0
    const val AUTO_REPLY = 1
    const val SUBORDINATE = 2
    const val REPLY = 3
}

data class LineLocation(
    val title: String?, val address: String?,
    val latitude: Double?, val longitude: Double?, val phone: String?,
) {
    companion object {
        fun from(d: Any?) = LineLocation(
            tStr(d, 1), tStr(d, 2),
            (tGet(d, 3) as? Number)?.toDouble(), (tGet(d, 4) as? Number)?.toDouble(),
            tStr(d, 5),
        )
    }
}

data class Message(
    val id: String?,                      // field 4
    val sender: String?,                  // field 1
    val to: String?,                      // field 2
    val toType: Int?,                     // field 3
    val createdTime: Long?,               // field 5, ms epoch
    val text: String?,                    // field 10
    val contentType: Int,                 // field 15
    val contentMetadata: Map<String, String>,  // field 18
    val hasContent: Boolean,              // field 14
    val location: LineLocation?,          // field 11
    val relatedMessageId: String?,        // field 21
    val relationType: Int?,               // field 22
    val chunks: List<Any?>?,              // field 20 — E2EE ciphertext parts
) {
    /**
     * True when the body of this message lives in object storage rather than
     * in the message, and can be pulled down by message id.
     */
    val isDownloadableMedia: Boolean
        get() = contentType == ContentType.IMAGE || contentType == ContentType.VIDEO

    /** Sticker id, carried in contentMetadata on contentType 7 messages. */
    val stickerId: String? get() = contentMetadata["STKID"]?.takeIf { it.isNotBlank() }

    /**
     * The sticker image on LINE's sticker CDN.
     *
     * Animated and effect stickers have richer resource types, but the static
     * PNG exists for every sticker and is what the web client falls back to.
     */
    val stickerUrl: String?
        get() = if (contentType == ContentType.STICKER) {
            stickerId?.let { "https://stickershop.line-scdn.net/stickershop/v1/sticker/$it/android/sticker.png" }
        } else {
            null
        }

    /** The one-line body the official client shows in a chat list row. */
    val preview: String
        get() = when {
            contentType != ContentType.TEXT -> "[${ContentType.label(contentType)}]"
            !text.isNullOrEmpty() -> text
            // Letter-sealed and undecryptable: the plaintext never arrived, so
            // say so rather than rendering a misleading empty row.
            !chunks.isNullOrEmpty() -> "[Encrypted]"
            else -> ""
        }

    companion object {
        fun from(d: Any?): Message {
            val loc = tGet(d, 11)
            @Suppress("UNCHECKED_CAST")
            val meta = (tGet(d, 18) as? Map<*, *>)
                ?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
            return Message(
                id = tStr(d, 4),
                sender = tStr(d, 1),
                to = tStr(d, 2),
                toType = tInt(d, 3),
                createdTime = tLong(d, 5),
                text = tStr(d, 10),
                contentType = tInt(d, 15) ?: 0,
                contentMetadata = meta,
                hasContent = tBool(d, 14),
                location = if (loc is Map<*, *>) LineLocation.from(loc) else null,
                relatedMessageId = tStr(d, 21),
                relationType = tInt(d, 22),
                chunks = tGet(d, 20) as? List<Any?>,
            )
        }
    }
}

data class Operation(
    val revision: Long,          // field 1
    val createdTime: Long?,      // field 2
    val opType: Int,             // field 3
    val reqSeq: Int?,            // field 4
    val param1: String?,         // field 10
    val param2: String?,         // field 11
    val param3: String?,         // field 12
    val message: Message?,       // field 20
) {
    companion object {
        fun from(d: Any?) = Operation(
            revision = tLong(d, 1) ?: 0L,
            createdTime = tLong(d, 2),
            opType = tInt(d, 3) ?: 0,
            reqSeq = tInt(d, 4),
            param1 = tStr(d, 10),
            param2 = tStr(d, 11),
            param3 = tStr(d, 12),
            message = (tGet(d, 20) as? Map<*, *>)?.let { Message.from(it) },
        )
    }
}

data class Profile(
    val mid: String,
    val displayName: String?,
    val statusMessage: String?,
    val pictureStatus: String?,
    val picturePath: String?,
    val userid: String?,
    val phone: String?,
    val email: String?,
    val regionCode: String?,
) {
    companion object {
        fun from(d: Any?) = Profile(
            mid = tStr(d, 1) ?: "",
            displayName = tStr(d, 20),
            statusMessage = tStr(d, 24),
            pictureStatus = tStr(d, 22),
            picturePath = tStr(d, 33),
            userid = tStr(d, 3),
            phone = tStr(d, 10),
            email = tStr(d, 11),
            regionCode = tStr(d, 12),
        )
    }
}

data class Contact(
    val mid: String,
    val displayName: String?,
    val statusMessage: String?,
    val displayNameOverride: String?,   // custom nickname you set
    val pictureStatus: String?,
    val picturePath: String?,
    val contactType: Int?,
    val status: Int?,
    val relation: Int?,
    val createdTime: Long?,
) {
    val name: String get() = displayNameOverride ?: displayName ?: mid

    companion object {
        fun from(d: Any?) = Contact(
            mid = tStr(d, 1) ?: "",
            displayName = tStr(d, 22),
            statusMessage = tStr(d, 26),
            displayNameOverride = tStr(d, 27),
            pictureStatus = tStr(d, 24),
            picturePath = tStr(d, 37),
            contactType = tInt(d, 10),
            status = tInt(d, 11),
            relation = tInt(d, 21),
            createdTime = tLong(d, 2),
        )
    }
}

data class Chat(
    val chatMid: String,
    val chatType: Int?,
    val chatName: String?,
    val createdTime: Long?,
    val notificationDisabled: Boolean,
    val picturePath: String?,
) {
    companion object {
        fun from(d: Any?) = Chat(
            chatMid = tStr(d, 2) ?: "",
            chatType = tInt(d, 1),
            chatName = tStr(d, 6),
            createdTime = tLong(d, 3),
            notificationDisabled = tBool(d, 4),
            picturePath = tStr(d, 7),
        )
    }
}

/**
 * One row of the "Chats" tab.
 *
 * A chat's name lives in a different place depending on its kind: groups and
 * rooms carry [Chat.chatName], while a 1:1 chat has none and is named after the
 * contact.  This flattens both into [name].
 */
data class ChatSummary(
    val chatMid: String,
    val chatType: Int?,
    val name: String,
    val picturePath: String?,
    val lastMessage: Message?,
    val senderName: String?,
    val unreadCount: Int = 0,
) {
    val preview: String get() = lastMessage?.preview ?: ""

    /** ms epoch of the last message; 0 sorts empty chats last. */
    val timestamp: Long get() = lastMessage?.createdTime ?: 0L

    val isGroup: Boolean get() = chatType == ChatType.GROUP || chatType == ChatType.ROOM
}
