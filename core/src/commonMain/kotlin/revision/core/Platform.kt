package revision.core

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A fresh random UUID string, used as the primary key for anything the user creates. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

/**
 * "What time is it?" as a function, so tests can supply a fake clock.
 * Everything in the app stores time as epoch milliseconds (UTC) and only converts
 * to local time for display.
 */
typealias Now = () -> Long

val systemNow: Now = { Clock.System.now().toEpochMilliseconds() }
