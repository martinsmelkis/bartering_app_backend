package app.bartering.features.categories.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.ai.CategoryWithDescription
import app.bartering.features.attributes.model.CategoryLink
import app.bartering.features.categories.AttributeCategoriesLinkTable
import app.bartering.features.categories.CategoriesMasterTable
import app.bartering.features.categories.model.LinkedCategory
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import kotlin.Int

class CategoriesDaoImpl: CategoriesDao {

    override suspend fun findOrCreate(
        categoryKey: String,
        localizationKey: String,
        parentId: Int?,
    ): LinkedCategory {
        return dbQuery {
            // 1. Try to find the category by its unique key first.
            val existingCategory = CategoriesMasterTable
                .selectAll().where { CategoriesMasterTable.categoryNameKey eq categoryKey }
                .map(::toCategory)
                .singleOrNull()

            if (existingCategory != null) {
                return@dbQuery existingCategory
            }

            // 2. If it doesn't exist, create it.
            val newId = CategoriesMasterTable.insertAndGetId {
                it[CategoriesMasterTable.categoryNameKey] = categoryKey
                it[CategoriesMasterTable.localizationKey] = localizationKey
                it[CategoriesMasterTable.parentId] = parentId
            }

            // 3. Retrieve the newly created category and return it.
            return@dbQuery CategoriesMasterTable
                .selectAll().where { CategoriesMasterTable.id eq newId }
                .map(::toCategory)
                .first() // We know it exists, so .first() is safe.
        }
    }

    override suspend fun findMainCategoryForAttributes(attributeKeys: List<String>): List<CategoryLink> {
        if (attributeKeys.isEmpty()) return emptyList()

        return dbQuery {
            // This is a powerful SQL query that does the heavy lifting.
            // It uses a recursive CTE to traverse up the category tree.
            val sql = """
                WITH RECURSIVE category_parents AS (
                    -- Base case: select the direct categories of our target attributes
                    SELECT
                        l.attribute_id,
                        c.id as category_id,
                        c.parent_id,
                    FROM attribute_categories_link l
                    JOIN categories c ON l.category_id = c.id
                    WHERE l.attribute_id IN (SELECT id FROM attributes WHERE attribute_key = ANY(?))

                    UNION ALL

                    -- Recursive step: join with the parent category
                    SELECT
                        cp.attribute_id,
                        c.id as category_id,
                        c.parent_id,
                    FROM category_parents cp
                    JOIN categories c ON cp.parent_id = c.id
                    WHERE cp.parent_id IS NOT NULL
                )
                -- Final selection: get the attribute key and the UI hint from the top-level parent
                SELECT
                    a.attribute_key,
                    cp.category_id
                    cp.relevancy
                FROM category_parents cp
                JOIN attributes a ON cp.attribute_id = a.id
                WHERE cp.parent_id IS NULL AND cp.relevancy IS NOT NULL;
            """.trimIndent()

            val results = arrayListOf<CategoryLink>()

            TransactionManager.current().exec(sql) { rs ->
                while (rs.next()) {
                    val attributeKey = rs.getString(1)
                    val categoryKey = rs.getInt(2)
                    val relevancy = rs.getBigDecimal(3)
                    println("@@@ attributeKey: $attributeKey, categoryKey: $categoryKey, relevancy: $relevancy")
                    results.add(CategoryLink(categoryKey, relevancy))
                }
            }
            results
        }
    }

    override suspend fun findAllMainCategoriesWithDescriptions(): List<CategoryWithDescription> {
        return dbQuery {
            CategoriesMasterTable
                .selectAll().where { CategoriesMasterTable.parentId.isNull() and
                        CategoriesMasterTable.description.isNotNull() }
                .map { row ->
                    CategoryWithDescription(
                        id = row[CategoriesMasterTable.id].value,
                        categoryKey = row[CategoriesMasterTable.categoryNameKey],
                        description = row[CategoriesMasterTable.description]
                    )
                }
        }
    }

    private fun toCategory(row: ResultRow): LinkedCategory = LinkedCategory(
        categoryId = row[CategoriesMasterTable.id].value,
        categoryNameKey = row[CategoriesMasterTable.categoryNameKey],
        parentId = row[CategoriesMasterTable.parentId],
        relevancy = row[AttributeCategoriesLinkTable.relevancy]
    )

}