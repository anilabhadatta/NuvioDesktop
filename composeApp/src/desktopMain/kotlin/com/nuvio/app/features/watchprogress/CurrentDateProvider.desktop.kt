package com.nuvio.app.features.watchprogress

import java.time.LocalDate

actual object CurrentDateProvider {
    actual fun todayIsoDate(): String = LocalDate.now().toString()

    actual fun localStartOfDayEpochMs(isoDate: String): Long? {
        return try {
            LocalDate.parse(isoDate)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
