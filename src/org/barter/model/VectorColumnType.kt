package org.barter.model

import com.pgvector.PGvector
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject

/**
 * A private helper class that tells Exposed how to handle the pgvector type.
 */
class VectorColumnType(val dimensions: Int) : IColumnType<PGvector> {
    override fun valueToDB(value: PGvector?): Any = value ?: PGvector()
    override var nullable: Boolean
        get() = true
        set(_) {}

    override fun sqlType(): String {
        return "vector($dimensions)"
    }

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = "vector"
        obj.value = value.toString()
        stmt[index] = obj
    }

    override fun valueFromDB(value: Any): PGvector {
        return when (value) {
            is PGvector -> value
            is String -> PGvector(value)
            is PGobject -> PGvector(value.value)
            else -> PGvector(floatArrayOf())
        }
    }

    override fun notNullValueToDB(value: PGvector): Any = value.toString()

    override fun nonNullValueToString(value: PGvector): String = value.toString()
}