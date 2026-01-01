package org.barter.features.attributes.model

import java.math.BigDecimal

data class CategoryLink(val categoryId: Int,
                        val relevancy: BigDecimal)