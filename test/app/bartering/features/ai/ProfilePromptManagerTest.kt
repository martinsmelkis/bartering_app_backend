package app.bartering.features.ai

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import app.bartering.features.attributes.dao.AttributesDaoImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.inject
import org.nirmato.ollama.api.EmbedRequest
import org.nirmato.ollama.api.EmbedResponse
import org.nirmato.ollama.api.EmbeddedInput
import org.nirmato.ollama.client.ktor.OllamaClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfilePromptManagerTest : KoinTest {

    private val ollamaClient: OllamaClient = mockk()
    private val attributesDao: AttributesDaoImpl = mockk()

    // Inject the class under test, Koin will provide the mocked dependencies
    private val profilePromptManager: ProfilePromptManager by inject()

    @BeforeEach
    fun setUp() {
        startKoin {
            modules(module {
                single { ollamaClient }
                single { attributesDao }
                single { ProfilePromptManager() }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `generateInterestsFromOnboardingData should return weighted map of semantically matched interests`() = runBlocking {
        // --- 1. ARRANGE ---

        // Mock the DAO to return a subset of attributes derived from ExpandedInterests.kt
        val attributesFromDb = hashMapOf(
            "graphic_design" to "Graphic design",
            "business_mentorship" to "Business Mentorship",
            "hiking" to "Hiking",
            "woodworking" to "Woodworking")
        coEvery { attributesDao.getAllAttributeKeysToStrings() } returns attributesFromDb

        // Mock the Ollama client to return specific vectors for each keyword.
        coEvery { ollamaClient.generateEmbed(any()) } answers { invocation ->
            val request = invocation.invocation.args[0] as EmbedRequest
            // Correctly unpack keywords from the request DTO
            val keywords = (request.input as EmbeddedInput.EmbeddedList).texts.map { it.text }

            val embeddings = keywords.map { keyword ->
                when (keyword) {
                    // --- Onboarding Keywords (Profile) ---
                    "Creative" -> listOf(1.0, 0.1, 0.1, 0.2)       // High in Creative dimension
                    "Professional" -> listOf(0.2, 1.0, 0.1, 0.1)   // High in Professional dimension
                    "Share Skills" -> listOf(0.1, 0.2, 1.0, 0.1)   // High in Sharing dimension
                    "Logical" -> listOf(0.1, 0.1, 0.1, 1.0)        // High in Logical dimension
                    "Physical work" -> listOf(0.0, 0.0, 0.0, 0.0)
                    "Higher Education" -> listOf(0.0, 0.0, 0.0, 0.0)
                    "Extroverted" -> listOf(0.0, 0.0, 0.0, 0.0)
                    "Paid Work" -> listOf(0.0, 0.0, 0.0, 0.0)
                    "Feminine" -> listOf(0.0, 0.0, 0.0, 0.0)
                    "Outdoors" -> listOf(0.0, 0.0, 0.0, 0.0)

                    // --- Attribute Keywords (from ExpandedInterests) ---
                    "Graphic design" -> listOf(0.9, 0.7, 0.1, 0.3)      // Strong match for Creative, good for Professional
                    "Business Mentorship" -> listOf(0.2, 0.9, 0.9, 0.5) // Strong match for Professional and Share Skills
                    "Woodworking" -> listOf(0.6, 0.1, 0.1, 0.8)        // Decent match for Creative and Logical
                    "Hiking" -> listOf(0.0, 0.0, 0.0, 0.0)              // No match
                    else -> listOf(0.0, 0.0, 0.0, 0.0)
                }
            }
            EmbedResponse(embeddings = embeddings)
        }

        // The user's onboarding profile, weighted from -1.0 to 1.0
        val onboardingKeyWeights = mapOf(
            "Creative" to 0.9,         // Very creative
            "Professional" to 0.1,     // Quite professional
            "Share Skills" to 0.1,     // Likes to share skills
            "Logical" to 0.2,          // Somewhat logical
            "Physical work" to 0.1,
            "Higher Education" to 0.9,
            "Extroverted" to 0.8,
            "Paid Work" to 0.8,
            "Feminine" to -0.9,
            "Outdoors" to 1.0
        )
        //@@@@@@@ Matching interests: {woodworking=0.6503833212352435, hiking=0.0, business_mentorship=0.9803870465624583, graphic_design=0.0}
        //@@@@@@@ Matching interests: {woodworking=0.8480446757403237, hiking=0.0, business_mentorship=0.7544711580050311, graphic_design=0.0}

        // onboardingKeyWeights Creative 0.9
        //@@@@@@@ Matching interests: {woodworking=0.8480446757403237, hiking=0.0, business_mentorship=0.7544711580050311, graphic_design=0.9577673766404848}

        // onboardingKeyWeights Creative -0.9
        //@@@@@@@ Matching interests: {woodworking=0.08504213095314485, hiking=0.0, business_mentorship=0.6009578470459078, graphic_design=0.08565489940659209}

        // 0.9, 0.1, 0.1, 0.2
        //@@@@@@@ Matching interests: {woodworking=0.85816806393674, hiking=0.0, business_mentorship=0.5332259401179675, graphic_design=0.9072048537403975}

        // --- 2. ACT ---
        val result = profilePromptManager.generateInterestsFromOnboardingData(onboardingKeyWeights)
        println("@@@@@@@@@@@ result: $result")

        // --- 3. ASSERT ---

        // Based on the vectors and weights, we expect graphic_design and business_mentorship to score highest.
        val expectedSize = 3 // It should return the top matches.
        assertEquals(expectedSize, result.size, "Should return the top 3 matching interests")

        val resultKeys = result.keys.toList()
        assertEquals("graphic_design", resultKeys[0], "The first interest should be graphic_design")
        assertEquals("business_mentorship", resultKeys[1], "The second interest should be business_mentorship")
        assertEquals("woodworking", resultKeys[2], "The third interest should be woodworking")

        // Assert the relative order of the calculated likelihoods (weights)
        assertTrue(result["graphic_design"]!! > result["business_mentorship"]!!, "Graphic Design should have a higher likelihood than Business Mentorship")
        assertTrue(result["business_mentorship"]!! > result["woodworking"]!!, "Business Mentorship should have a higher likelihood than Woodworking")
    }
}
