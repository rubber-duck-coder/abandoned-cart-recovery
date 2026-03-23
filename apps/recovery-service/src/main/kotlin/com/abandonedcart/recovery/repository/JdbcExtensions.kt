package com.abandonedcart.recovery.repository

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime

internal fun PreparedStatement.setNullableText(index: Int, value: String?) {
    if (value == null) {
        setNull(index, Types.VARCHAR)
    } else {
        setString(index, value)
    }
}

internal fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) {
        setNull(index, Types.INTEGER)
    } else {
        setInt(index, value)
    }
}

internal fun PreparedStatement.setNullableOffsetDateTime(index: Int, value: OffsetDateTime?) {
    if (value == null) {
        setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    } else {
        setObject(index, value)
    }
}

internal fun PreparedStatement.setOffsetDateTime(index: Int, value: OffsetDateTime) {
    setObject(index, value)
}

internal fun ResultSet.getNullableInt(columnLabel: String): Int? {
    val value = getInt(columnLabel)
    return if (wasNull()) null else value
}

internal fun ResultSet.getNullableOffsetDateTime(columnLabel: String): OffsetDateTime? {
    return getObject(columnLabel, OffsetDateTime::class.java)
}

