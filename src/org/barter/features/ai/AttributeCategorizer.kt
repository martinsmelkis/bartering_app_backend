package org.barter.features.ai

import org.barter.config.AiConfig
import org.barter.extensions.DatabaseFactory.dbQuery
import org.barter.features.attributes.model.CategoryLink
import org.barter.features.categories.CategoriesMasterTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager

class AttributeCategorizer {

    /**
     * This should be called once on application startup.
     * It finds any main categories without an embedding and uses pgai to populate them.
     */
    suspend fun initialize() {
        dbQuery {
            val categoriesToUpdate = CategoriesMasterTable
                .select(CategoriesMasterTable.id, CategoriesMasterTable.description)
                .where { CategoriesMasterTable.parentId.isNull() and CategoriesMasterTable.embedding.isNull() and CategoriesMasterTable.description.isNotNull() }
                .map { it[CategoriesMasterTable.id].value to it[CategoriesMasterTable.description]!! }

            if (categoriesToUpdate.isEmpty()) {
                println("All category embeddings are already populated.")
                return@dbQuery
            }

            println("Found ${categoriesToUpdate.size} categories with missing embeddings. Populating now...")

            val updateSql = """
                UPDATE categories
                SET embedding = ai.ollama_embed(
                    '${AiConfig.embedModel}',
                    ?,
                    host => '${AiConfig.ollamaHost}'
                )
                WHERE id = ?
            """.trimIndent()

            TransactionManager.current().connection.prepareStatement(updateSql, false)
                .also { statement ->
                    for ((id, description) in categoriesToUpdate) {
                        statement.set(1, description)
                        statement.set(2, id)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            println("Finished populating category embeddings.")
        }
    }

    /**
     * Finds the most semantically similar main category for a new attribute text using pgai.
     */
    suspend fun findBestCategory(newAttributeText: String): List<CategoryLink> {
        return dbQuery {
            val findBestCategorySql = """
                SELECT
                    id,
                    category_key,
                    1 - (embedding <=> ai.ollama_embed('${AiConfig.embedModel}', ?, host => '${AiConfig.ollamaHost}')) AS similarity
                FROM
                    categories
                WHERE
                    parent_id IS NULL AND embedding IS NOT NULL
                ORDER BY
                    similarity DESC
                LIMIT 1;
            """.trimIndent()

            var bestMatch: Pair<Int, String>? = null

            TransactionManager.current().connection.prepareStatement(findBestCategorySql, false).also { statement ->
                statement[1] = newAttributeText
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        println("@@@@@@@@@@@@@ bestCategoryMatchResponse for '$newAttributeText': ${rs.getString("category_key")}")
                        bestMatch = rs.getInt("id") to rs.getString("category_key")
                    }
                }
            }

            val (categoryId, categoryKey) = bestMatch ?: findDefaultCategory()

            println("@@@@@@@@ bestCategoryMatchResponse for '$newAttributeText': $categoryKey ($categoryId)")
            listOf(CategoryLink(categoryId, 0.9.toBigDecimal()))
        }
    }

    private fun findDefaultCategory(): Pair<Int, String> {
        return CategoriesMasterTable
            .select(CategoriesMasterTable.id, CategoriesMasterTable.categoryNameKey).where {
                CategoriesMasterTable.categoryNameKey eq "main_yellow" }
            .map { it[CategoriesMasterTable.id].value to it[CategoriesMasterTable.categoryNameKey] }
            .firstOrNull() ?: Pair(-1, "main_yellow") // Absolute fallback
    }

}

data class CategoryWithDescription(val id: Int, val categoryKey: String, val description: String)
