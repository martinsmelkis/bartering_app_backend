package app.bartering.model

import com.pgvector.PGvector
import net.postgis.jdbc.PGgeometry
import net.postgis.jdbc.geometry.Point
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
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
        stmt.set(index, obj, this)
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

class PointColumnType(private val srid: Int = 4326) : ColumnType<Point>() {
    override fun sqlType(): String = "GEOMETRY(POINT, $srid)"

    override fun valueFromDB(value: Any): Point = when (value) {
        is Point -> value
        is PGgeometry -> value.geometry as Point
        else -> error("Unsupported point value: $value")
    }

    override fun notNullValueToDB(value: Point): Any {
        value.srid = srid
        val obj = PGobject()
        obj.type = "geometry"
        obj.value = "SRID=$srid;POINT(${value.x} ${value.y})"
        return obj
    }
}

fun Table.point(name: String, srid: Int = 4326): Column<Point> = registerColumn(name, PointColumnType(srid))

