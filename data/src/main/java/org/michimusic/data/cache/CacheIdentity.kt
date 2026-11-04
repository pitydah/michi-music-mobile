package org.michimusic.data.cache

/**
 * Owner of cache rows written by this device itself.
 *
 * The track and playlist cache became source-scoped in schema version 8 (K16-01 /
 * K16-02) because IDs are only unique inside the library that produced them. Rows
 * created by a paired server are stored under that server's identifier instead, so
 * an empty value always means "authored here", which is also what the 7 -> 8
 * migration wrote for every pre-existing row.
 */
const val LOCAL_SOURCE_ID: String = ""
