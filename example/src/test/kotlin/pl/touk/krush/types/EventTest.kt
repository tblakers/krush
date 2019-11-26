package pl.touk.krush.types

import org.assertj.core.api.Assertions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId.systemDefault
import java.time.ZonedDateTime
import java.util.UUID.randomUUID

class EventTest {

    @Before
    fun connect() {
        Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
    }

    @Test
    fun shouldHandleUUIDAndDateTypes() {
        transaction {
            SchemaUtils.create(EventTable)

            // given
            val clock = Clock.fixed(Instant.parse("2019-10-22T09:00:00.000Z"), systemDefault())

            val createTime = ZonedDateTime.now(clock)
            val event = EventTable.insert(Event(eventDate = LocalDate.now(clock), processTime = LocalDateTime.now(clock),
                    createTime = createTime, externalId = randomUUID()))

            //when
            val events = (EventTable)
                    .select { EventTable.createTime greater createTime.minusDays(1) }
                    .toEventList()

            //then
            Assertions.assertThat(events).containsOnly(event)
        }
    }
}