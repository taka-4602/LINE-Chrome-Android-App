# -*- coding: utf-8 -*-
"""
Typed data classes for all LINE API responses.

All classes are NamedTuples so they are immutable, unpackable, and
have nice repr() for free.  The `from_dict` class method on each one
converts the raw integer-keyed dicts returned by the Thrift decoder.
"""
from typing import Dict, List, NamedTuple, Optional


# ---------------------------------------------------------------------------
# Enums (plain int constants — use isinstance checks against the raw value)
# ---------------------------------------------------------------------------

class OpType:
    END_OF_OPERATION            = 0
    UPDATE_PROFILE              = 1
    NOTIFIED_UPDATE_PROFILE     = 2
    ADD_CONTACT                 = 4
    NOTIFIED_ADD_CONTACT        = 5
    CREATE_GROUP                = 9
    UPDATE_GROUP                = 10
    NOTIFIED_UPDATE_GROUP       = 11
    INVITE_INTO_GROUP           = 12
    NOTIFIED_INVITE_INTO_GROUP  = 13
    LEAVE_GROUP                 = 14
    NOTIFIED_LEAVE_GROUP        = 15
    ACCEPT_GROUP_INVITATION     = 16
    KICKOUT_FROM_GROUP          = 18
    NOTIFIED_KICKOUT_FROM_GROUP = 19
    LEAVE_ROOM                  = 23
    NOTIFIED_LEAVE_ROOM         = 24
    SEND_MESSAGE                = 25
    RECEIVE_MESSAGE             = 26
    SEND_MESSAGE_RECEIPT        = 27
    RECEIVE_MESSAGE_RECEIPT     = 28
    SEND_CHAT_CHECKED           = 40
    NOTIFIED_READ_MESSAGE       = 55
    DESTROY_MESSAGE             = 64
    NOTIFIED_DESTROY_MESSAGE    = 65


class MIDType:
    USER        = 0
    ROOM        = 1
    GROUP       = 2
    SQUARE      = 3
    SQUARE_CHAT = 4
    BOT         = 6


class ContentType:
    NONE             = 0
    TEXT             = 0   # LINE sends plain text as NONE, with the body in field 10
    IMAGE            = 1
    VIDEO            = 2
    AUDIO            = 3
    CALL             = 6
    STICKER          = 7
    GIFT             = 9
    CONTACT          = 13
    FILE             = 14
    LOCATION         = 15
    FLEX             = 22

    @classmethod
    def name(cls, value: int) -> str:
        """Human label for a content type, e.g. 7 -> 'Sticker'."""
        for k, v in vars(cls).items():
            if k.isupper() and v == value and k != "NONE":
                return k.capitalize()
        return f"Type {value}"


class ChatType:
    GROUP = 0
    ROOM  = 1
    PEER  = 2   # 1:1 chat with a single user


class MessageRelationType:
    FORWARD     = 0
    AUTO_REPLY  = 1
    SUBORDINATE = 2
    REPLY       = 3


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

# picture_path is returned CDN-relative; the API never sends the host.
_PROFILE_CDN = "https://profile.line-scdn.net"


def _picture_url(path: Optional[str], preview: bool = False) -> Optional[str]:
    if not path:
        return None
    url = f"{_PROFILE_CDN}/{path.lstrip('/')}"
    return url + "/preview" if preview else url

class Location(NamedTuple):
    title:     Optional[str]
    address:   Optional[str]
    latitude:  Optional[float]
    longitude: Optional[float]
    phone:     Optional[str]

    @classmethod
    def from_dict(cls, d: dict) -> "Location":
        if not d:
            return cls(None, None, None, None, None)
        return cls(
            title=d.get(1),
            address=d.get(2),
            latitude=d.get(3),
            longitude=d.get(4),
            phone=d.get(5),
        )


class Message(NamedTuple):
    """A LINE message (sent or received)."""
    id:                  Optional[str]   # field 4
    sender:              Optional[str]   # field 1  (_from)
    to:                  Optional[str]   # field 2
    to_type:             Optional[int]   # field 3  (MIDType)
    created_time:        Optional[int]   # field 5  (ms epoch)
    text:                Optional[str]   # field 10
    content_type:        int             # field 15 (ContentType)
    content_metadata:    Dict[str, str]  # field 18
    has_content:         bool            # field 14
    location:            Optional[Location]  # field 11
    related_message_id:  Optional[str]   # field 21
    relation_type:       Optional[int]   # field 22 (MessageRelationType)
    chunks:              Optional[List[bytes]]  # field 20

    @classmethod
    def from_dict(cls, d: dict) -> "Message":
        loc = d.get(11)
        return cls(
            id=d.get(4),
            sender=d.get(1),
            to=d.get(2),
            to_type=d.get(3),
            created_time=d.get(5),
            text=d.get(10),
            content_type=d.get(15) or 0,
            content_metadata=d.get(18) or {},
            has_content=bool(d.get(14)),
            location=Location.from_dict(loc) if isinstance(loc, dict) else None,
            related_message_id=d.get(21),
            relation_type=d.get(22),
            chunks=d.get(20),
        )


class Operation(NamedTuple):
    """A LINE operation (event) received from long-polling."""
    revision:     int             # field 1
    created_time: Optional[int]   # field 2  (ms epoch)
    op_type:      int             # field 3  (OpType)
    req_seq:      Optional[int]   # field 4
    param1:       Optional[str]   # field 10
    param2:       Optional[str]   # field 11
    param3:       Optional[str]   # field 12
    message:      Optional[Message]  # field 20

    @classmethod
    def from_dict(cls, d: dict) -> "Operation":
        msg = d.get(20)
        return cls(
            revision=d.get(1) or 0,
            created_time=d.get(2),
            op_type=d.get(3) or 0,
            req_seq=d.get(4),
            param1=d.get(10),
            param2=d.get(11),
            param3=d.get(12),
            message=Message.from_dict(msg) if isinstance(msg, dict) else None,
        )


class Profile(NamedTuple):
    """Own account profile."""
    mid:            str            # field 1
    display_name:   Optional[str]  # field 20
    status_message: Optional[str]  # field 24
    picture_status: Optional[str]  # field 22
    picture_path:   Optional[str]  # field 33
    userid:         Optional[str]  # field 3
    phone:          Optional[str]  # field 10
    email:          Optional[str]  # field 11
    region_code:    Optional[str]  # field 12

    @classmethod
    def from_dict(cls, d: dict) -> "Profile":
        return cls(
            mid=d.get(1) or "",
            display_name=d.get(20),
            status_message=d.get(24),
            picture_status=d.get(22),
            picture_path=d.get(33),
            userid=d.get(3),
            phone=d.get(10),
            email=d.get(11),
            region_code=d.get(12),
        )

    def picture_url(self, preview: bool = False) -> Optional[str]:
        """Full CDN URL of the profile picture, or None if unset."""
        return _picture_url(self.picture_path, preview)


class Contact(NamedTuple):
    """A contact (friend) in your LINE friend list."""
    mid:                   str            # field 1
    display_name:          Optional[str]  # field 22
    status_message:        Optional[str]  # field 26
    display_name_override: Optional[str]  # field 27 (custom nickname you set)
    picture_status:        Optional[str]  # field 24
    picture_path:          Optional[str]  # field 37
    contact_type:          Optional[int]  # field 10 (MIDType)
    status:                Optional[int]  # field 11
    relation:              Optional[int]  # field 21
    created_time:          Optional[int]  # field 2

    @classmethod
    def from_dict(cls, d: dict) -> "Contact":
        return cls(
            mid=d.get(1) or "",
            display_name=d.get(22),
            status_message=d.get(26),
            display_name_override=d.get(27),
            picture_status=d.get(24),
            picture_path=d.get(37),
            contact_type=d.get(10),
            status=d.get(11),
            relation=d.get(21),
            created_time=d.get(2),
        )

    @property
    def name(self) -> str:
        """Best display name: your nickname for them, else their own."""
        return self.display_name_override or self.display_name or self.mid

    def picture_url(self, preview: bool = False) -> Optional[str]:
        """Full CDN URL of the profile picture, or None if unset."""
        return _picture_url(self.picture_path, preview)


class Chat(NamedTuple):
    """A chat (group or room)."""
    chat_mid:               str            # field 2
    chat_type:              Optional[int]  # field 1 (ChatType)
    chat_name:              Optional[str]  # field 6
    created_time:           Optional[int]  # field 3
    notification_disabled:  bool           # field 4
    picture_path:           Optional[str]  # field 7

    @classmethod
    def from_dict(cls, d: dict) -> "Chat":
        return cls(
            chat_mid=d.get(2) or "",
            chat_type=d.get(1),
            chat_name=d.get(6),
            created_time=d.get(3),
            notification_disabled=bool(d.get(4)),
            picture_path=d.get(7),
        )


class ChatSummary(NamedTuple):
    """One row of the LINE client's "Chats" tab.

    A chat's name lives in a different place depending on its kind: groups and
    rooms carry chat_name, while a 1:1 chat has none and is named after the
    contact.  ChatSummary flattens both into `name`.
    """
    chat_mid:     str
    chat_type:    Optional[int]           # ChatType
    name:         str                     # group name, or the contact's name
    last_message: Optional["Message"]     # None if the chat has no messages
    sender_name:  Optional[str]           # who sent last_message ("You" if self)

    @property
    def preview(self) -> str:
        """The one-line body text the official client shows."""
        if self.last_message is None:
            return ""
        m = self.last_message
        if m.content_type != ContentType.TEXT:
            return f"[{ContentType.name(m.content_type)}]"
        if m.text:
            return m.text
        # Letter-sealed: the plaintext never arrives, the ciphertext is in
        # chunks.  Say so rather than rendering a misleading empty row.
        if m.chunks:
            return "[Encrypted]"
        return ""

    @property
    def timestamp(self) -> int:
        """ms epoch of the last message; 0 sorts empty chats last."""
        if self.last_message is None or self.last_message.created_time is None:
            return 0
        return int(self.last_message.created_time)


class SendMessageResult(NamedTuple):
    """Result of send_message()."""
    message: Optional[Message]   # the created message (contains id, etc.)

    @classmethod
    def from_dict(cls, d: dict) -> "SendMessageResult":
        # sendMessage's Thrift result is a Message at success field 0, which the
        # decoder returns unwrapped — so `d` *is* the message, not a container.
        return cls(
            message=Message.from_dict(d) if d else None,
        )
