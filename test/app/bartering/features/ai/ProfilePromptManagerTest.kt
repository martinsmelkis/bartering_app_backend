package app.bartering.features.ai

import app.bartering.features.attributes.dao.AttributesDaoImpl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilePromptManagerTest {

    private val attributesDao = AttributesDaoImpl()

    @Test
    fun `buildProfileVectorSql should return null vector select for empty input`() {
        val sql = attributesDao.buildProfileVectorSql(emptyMap())
        assertEquals("SELECT NULL::vector AS embedding", sql)
    }

    @Test
    fun `buildProfileVectorSql should include weighted scalar_mult expressions`() {
        val sql = attributesDao.buildProfileVectorSql(
            mapOf(
                "Creative" to 0.9,
                "Logical" to 0.2,
                "Share Skills" to 0.1
            )
        )

        assertTrue(sql.contains("SELECT scalar_mult(("))
        assertTrue(sql.contains("ai.ollama_embed"))
        assertTrue(sql.contains("Creative"))
        assertTrue(sql.contains("Logical"))
        assertTrue(sql.contains("Share Skills"))
        assertTrue(sql.contains("AS embedding"))
    }

    @Test
    fun `buildHavesProfileVectorSql should return zero vector select for non-positive weights`() {
        val sql = attributesDao.buildHavesProfileVectorSql(
            mapOf(
                "hiking" to 0.0,
                "woodworking" to -0.2
            )
        )

        assertEquals("SELECT ARRAY_FILL(0, ARRAY[1024])::vector AS embedding", sql)
    }

    @Test
    fun `buildHavesProfileVectorSql should include attribute embedding lookups for positive weights`() {
        val sql = attributesDao.buildHavesProfileVectorSql(
            mapOf(
                "graphic_design" to 0.9,
                "business_mentorship" to 0.6
            )
        )

        assertTrue(sql.contains("SELECT scalar_mult(("))
        assertTrue(sql.contains("SELECT embedding FROM attributes WHERE attribute_key = 'graphic_design'"))
        assertTrue(sql.contains("SELECT embedding FROM attributes WHERE attribute_key = 'business_mentorship'"))
        assertTrue(sql.contains("AS embedding"))
    }
}
