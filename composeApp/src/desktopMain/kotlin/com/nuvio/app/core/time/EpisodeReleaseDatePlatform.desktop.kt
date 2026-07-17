package com.nuvio.app.core.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual object EpisodeReleaseDatePlatform {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun localIsoDateAtEpochMs(epochMs: Long): String? {
        return try {
            Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: Exception) {
            null
        }
    }

    actual fun localDateTimeToEpochMs(normalizedIsoDateTime: String): Long? {
        return try {
            LocalDateTime.parse(normalizedIsoDateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
