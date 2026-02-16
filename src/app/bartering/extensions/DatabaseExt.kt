package app.bartering.extensions

import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * A singleton object to manage the application's main database connection and transactions.
 * This ensures that initialization happens only once.
 */
object DatabaseFactory {

    // Use an AtomicBoolean to make the initialization thread-safe and idempotent.
    private val isInitialized = AtomicBoolean(false)

    // The single HikariDataSource instance for the application.
    private lateinit var hikariDataSource: HikariDataSource

    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Initializes the database connection, connection pool (HikariCP), and runs migrations (Flyway).
     * This function is safe to call multiple times; it will only execute its logic once.
     */
    fun init() {
        // If already initialized, do nothing. This prevents re-creating connections.
        if (!isInitialized.compareAndSet(false, true)) {
            log.info("DatabaseFactory already initialized. Skipping.")
            return
        }

        log.info("Initializing DatabaseFactory...")

        // 1. Configure and create the HikariCP connection pool.
        hikariDataSource = createHikariDataSource()

        // 2. Run database migrations using Flyway.
        migrate(hikariDataSource)

        // 3. Connect Exposed to the HikariCP data source.
        Database.connect(hikariDataSource)

        log.info("DatabaseFactory initialization complete.")
    }

    private fun createHikariDataSource(): HikariDataSource {
        val conf = ConfigFactory.load()
        val config = HikariConfig().apply {
            jdbcUrl = System.getenv("POSTGRES_DB") ?: conf.getString("database.MainDatabaseUrl")
            username = System.getenv("POSTGRES_USER") ?: conf.getString("database.MainDatabaseUser")
            password = System.getenv("POSTGRES_PASSWORD") ?: conf.getString("database.MainDatabasePassword")
            driverClassName = conf.getString("database.MainDatabaseDriver")
            maximumPoolSize = 10
            isAutoCommit = false // Let Exposed manage commits.
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }

    private fun migrate(dataSource: DataSource) {
        try {
            log.info("Running Flyway migrations...")
            val flyway = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("public")
                .sqlMigrationSeparator("__")
                .validateMigrationNaming(true)
                .schemas("public")
                .locations("resources/db/migration", "db/migration/", "resources/db/migration/")
                .baselineOnMigrate(true) // Initialize schema history table for existing databases
                .load()
            
            // Repair checksums if migrations were modified
            try {
                flyway.repair()
                log.info("Flyway repair completed (checksums updated if needed)")
            } catch (e: Exception) {
                log.warn("Flyway repair attempt failed: {}", e.message)
            }
            
            flyway.migrate()
            log.info("Flyway migrations completed successfully.")
        } catch (e: Exception) {
            log.error("Failed to migrate database", e)
            // Better to re-throw here to prevent the app from starting with a broken schema.
            throw e
        }
    }

    /**
     * Executes a database query inside a suspended transaction, ensuring connections are
     * managed correctly and operations don't block the main threads.
     * This is the ONLY function that should be used for database access from DAOs.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) {
            // Add a logger here to see all SQL statements in dev.
            // addLogger(StdOutSqlLogger)
            block()
        }

}