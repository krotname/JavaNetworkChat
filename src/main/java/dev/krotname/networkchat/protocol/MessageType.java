package dev.krotname.networkchat.protocol;

/** Types of messages exchanged between client and server. */
public enum MessageType {
  NAME_REQUEST,
  USER_NAME,
  NAME_ACCEPTED,
  TEXT,
  ROOM_TEXT,
  PRIVATE_TEXT,
  USER_ADDED,
  USER_REMOVED,
  ROOM_JOIN,
  ROOM_LEAVE,
  ROOM_ADDED,
  ROOM_REMOVED,
  ROOM_JOINED,
  ROOM_LEFT,
  ERROR
}
