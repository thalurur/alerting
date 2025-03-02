/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting

import org.junit.Assert
import org.opensearch.alerting.aggregation.bucketselectorext.BucketSelectorExtAggregationBuilder
import org.opensearch.alerting.alerts.AlertError
import org.opensearch.alerting.alerts.AlertIndices
import org.opensearch.alerting.core.model.IntervalSchedule
import org.opensearch.alerting.core.model.SearchInput
import org.opensearch.alerting.model.ActionExecutionResult
import org.opensearch.alerting.model.Alert
import org.opensearch.alerting.model.Alert.State.ACKNOWLEDGED
import org.opensearch.alerting.model.Alert.State.ACTIVE
import org.opensearch.alerting.model.Alert.State.COMPLETED
import org.opensearch.alerting.model.Alert.State.ERROR
import org.opensearch.alerting.model.Monitor
import org.opensearch.alerting.model.action.ActionExecutionPolicy
import org.opensearch.alerting.model.action.AlertCategory
import org.opensearch.alerting.model.action.PerAlertActionScope
import org.opensearch.alerting.model.action.PerExecutionActionScope
import org.opensearch.alerting.model.action.Throttle
import org.opensearch.alerting.model.destination.CustomWebhook
import org.opensearch.alerting.model.destination.Destination
import org.opensearch.alerting.model.destination.email.Email
import org.opensearch.alerting.model.destination.email.Recipient
import org.opensearch.alerting.util.DestinationType
import org.opensearch.alerting.util.getBucketKeysHash
import org.opensearch.client.ResponseException
import org.opensearch.client.WarningFailureException
import org.opensearch.common.settings.Settings
import org.opensearch.commons.authuser.User
import org.opensearch.index.query.QueryBuilders
import org.opensearch.rest.RestStatus
import org.opensearch.script.Script
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregationBuilder
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder
import org.opensearch.search.builder.SearchSourceBuilder
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.ChronoUnit.MILLIS
import java.time.temporal.ChronoUnit.MINUTES
import kotlin.collections.HashMap

class MonitorRunnerServiceIT : AlertingRestTestCase() {

    fun `test execute monitor with dryrun`() {
        val action = randomAction(template = randomTemplateScript("Hello {{ctx.monitor.name}}"), destinationId = createDestination().id)
        val monitor = randomQueryLevelMonitor(
            triggers = listOf(randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = listOf(action)))
        )

        val response = executeMonitor(monitor, params = DRYRUN_MONITOR)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (actionResult in triggerResult.objectMap("action_results").values) {
                @Suppress("UNCHECKED_CAST") val actionOutput = actionResult["output"] as Map<String, String>
                assertEquals("Hello ${monitor.name}", actionOutput["subject"])
                assertEquals("Hello ${monitor.name}", actionOutput["message"])
            }
        }

        val alerts = searchAlerts(monitor)
        assertEquals("Alert saved for test monitor", 0, alerts.size)
    }

    fun `test execute monitor returns search result`() {
        val testIndex = createTestIndex()
        val twoMinsAgo = ZonedDateTime.now().minus(2, MINUTES).truncatedTo(MILLIS)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(twoMinsAgo)
        val testDoc = """{ "test_strict_date_time" : "$testTime" }"""
        indexDoc(testIndex, "1", testDoc)

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().query(query))
        val triggerScript = """
            // make sure there is exactly one hit
            return ctx.results[0].hits.hits.size() == 1
        """.trimIndent()

        val trigger = randomQueryLevelTrigger(condition = Script(triggerScript))
        val monitor = randomQueryLevelMonitor(inputs = listOf(input), triggers = listOf(trigger))
        val response = executeMonitor(monitor, params = DRYRUN_MONITOR)

        val output = entityAsMap(response)

        assertEquals(monitor.name, output["monitor_name"])
        @Suppress("UNCHECKED_CAST")
        val searchResult = (output.objectMap("input_results")["results"] as List<Map<String, Any>>).first()
        @Suppress("UNCHECKED_CAST")
        val total = searchResult.stringMap("hits")?.get("total") as Map<String, String>
        assertEquals("Incorrect search result", 1, total["value"])
    }

    fun `test execute monitor not triggered`() {
        val monitor = randomQueryLevelMonitor(triggers = listOf(randomQueryLevelTrigger(condition = NEVER_RUN)))

        val response = executeMonitor(monitor)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            assertTrue("Unexpected trigger was run", triggerResult.objectMap("action_results").isEmpty())
        }

        val alerts = searchAlerts(monitor)
        assertEquals("Alert saved for test monitor", 0, alerts.size)
    }

    fun `test active alert is updated on each run`() {
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                triggers = listOf(randomQueryLevelTrigger(condition = ALWAYS_RUN, destinationId = createDestination().id))
            )
        )

        executeMonitor(monitor.id)
        val firstRunAlert = searchAlerts(monitor).single()
        verifyAlert(firstRunAlert, monitor)
        // Runner uses ThreadPool.CachedTimeThread thread which only updates once every 200 ms. Wait a bit to
        // see lastNotificationTime change.
        Thread.sleep(200)
        executeMonitor(monitor.id)
        val secondRunAlert = searchAlerts(monitor).single()
        verifyAlert(secondRunAlert, monitor)

        assertEquals("New alert was created, instead of updating existing alert.", firstRunAlert.id, secondRunAlert.id)
        assertEquals("Start time shouldn't change", firstRunAlert.startTime, secondRunAlert.startTime)
        assertNotEquals(
            "Last notification should be different.",
            firstRunAlert.lastNotificationTime, secondRunAlert.lastNotificationTime
        )
    }

    fun `test execute monitor input error`() {
        // use a non-existent index to trigger an input error
        createIndex("foo", Settings.EMPTY)
        val input = SearchInput(indices = listOf("foo"), query = SearchSourceBuilder().query(QueryBuilders.matchAllQuery()))
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                inputs = listOf(input),
                triggers = listOf(randomQueryLevelTrigger(condition = NEVER_RUN))
            )
        )

        deleteIndex("foo")
        val response = executeMonitor(monitor.id)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        @Suppress("UNCHECKED_CAST")
        val inputResults = output.stringMap("input_results")
        assertTrue("Missing monitor error message", (inputResults?.get("error") as String).isNotEmpty())

        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ERROR)
    }

    fun `test execute monitor wrong monitorid`() {
        // use a non-existent monitoid to trigger a 404.
        createIndex("foo", Settings.EMPTY)
        val input = SearchInput(indices = listOf("foo"), query = SearchSourceBuilder().query(QueryBuilders.matchAllQuery()))
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                inputs = listOf(input),
                triggers = listOf(randomQueryLevelTrigger(condition = NEVER_RUN))
            )
        )

        var exception: ResponseException? = null
        try {
            executeMonitor(monitor.id + "bad")
        } catch (ex: ResponseException) {
            exception = ex
        }
        Assert.assertEquals(404, exception?.response?.statusLine?.statusCode)
    }

    fun `test acknowledged alert does not suppress subsequent errors`() {
        val destinationId = createDestination().id

        createIndex("foo", Settings.EMPTY)
        val input = SearchInput(indices = listOf("foo"), query = SearchSourceBuilder().query(QueryBuilders.matchAllQuery()))
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                inputs = listOf(input),
                triggers = listOf(randomQueryLevelTrigger(condition = ALWAYS_RUN, destinationId = destinationId))
            )
        )

        var response = executeMonitor(monitor.id)

        var output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        assertTrue("Unexpected monitor error message", (output["error"] as String?).isNullOrEmpty())
        val activeAlert = searchAlerts(monitor).single()
        verifyAlert(activeAlert, monitor)

        // Now acknowledge the alert and delete the index to cause the next run of the monitor to fail...
        acknowledgeAlerts(monitor, activeAlert)
        deleteIndex("foo")
        response = executeMonitor(monitor.id)

        output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        val errorAlert = searchAlerts(monitor).single { it.state == ERROR }
        verifyAlert(errorAlert, monitor, ERROR)
    }

    fun `test acknowledged alert is not updated unnecessarily`() {
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                triggers = listOf(randomQueryLevelTrigger(condition = ALWAYS_RUN, destinationId = createDestination().id))
            )
        )
        executeMonitor(monitor.id)
        acknowledgeAlerts(monitor, searchAlerts(monitor).single())
        val acknowledgedAlert = searchAlerts(monitor).single()
        verifyAlert(acknowledgedAlert, monitor, ACKNOWLEDGED)

        // Runner uses ThreadPool.CachedTimeThread thread which only updates once every 200 ms. Wait a bit to
        // let lastNotificationTime change.  W/o this sleep the test can result in a false negative.
        Thread.sleep(200)
        val response = executeMonitor(monitor.id)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        val currentAlert = searchAlerts(monitor).single()
        assertEquals("Acknowledged alert was updated when nothing changed", currentAlert, acknowledgedAlert)
        for (triggerResult in output.objectMap("trigger_results").values) {
            assertTrue("Action run when alert is acknowledged.", triggerResult.objectMap("action_results").isEmpty())
        }
    }

    fun `test alert completion`() {
        val trigger = randomQueryLevelTrigger(condition = Script("ctx.alert == null"), destinationId = createDestination().id)
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))

        executeMonitor(monitor.id)
        val activeAlert = searchAlerts(monitor).single()
        verifyAlert(activeAlert, monitor)

        executeMonitor(monitor.id)
        assertTrue("There's still an active alert", searchAlerts(monitor, AlertIndices.ALERT_INDEX).isEmpty())
        val completedAlert = searchAlerts(monitor, AlertIndices.ALL_ALERT_INDEX_PATTERN).single()
        verifyAlert(completedAlert, monitor, COMPLETED)
    }

    fun `test execute monitor script error`() {
        // This painless script should cause a syntax error
        val trigger = randomQueryLevelTrigger(condition = Script("foo bar baz"))
        val monitor = randomQueryLevelMonitor(triggers = listOf(trigger))

        val response = executeMonitor(monitor)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            assertTrue("Missing trigger error message", (triggerResult["error"] as String).isNotEmpty())
        }

        val alerts = searchAlerts(monitor)
        assertEquals("Alert saved for test monitor", 0, alerts.size)
    }

    fun `test execute action template error`() {
        // Intentional syntax error in mustache template
        val action = randomAction(template = randomTemplateScript("Hello {{ctx.monitor.name"))
        val monitor = randomQueryLevelMonitor(
            triggers = listOf(randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = listOf(action)))
        )

        val response = executeMonitor(monitor)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (actionResult in triggerResult.objectMap("action_results").values) {
                assertTrue("Missing action error message", (actionResult["error"] as String).isNotEmpty())
            }
        }

        val alerts = searchAlerts(monitor)
        assertEquals("Alert saved for test monitor", 0, alerts.size)
    }

    fun `test execute monitor search with period`() {
        // We cant query .opendistro-alerting-config as its system index. Create a test index starting with "."
        val testIndex = createTestConfigIndex()
        val fiveDaysAgo = ZonedDateTime.now().minus(5, DAYS).truncatedTo(MILLIS)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(fiveDaysAgo)
        val testDoc = """{ "test_strict_date_time" : "$testTime" }"""
        indexDoc(testIndex, "1", testDoc)

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val input = SearchInput(indices = listOf(".*"), query = SearchSourceBuilder().query(query))
        val triggerScript = """
            // make sure there is at least one monitor
            return ctx.results[0].hits.hits.size() > 0
        """.trimIndent()
        val destinationId = createDestination().id
        val trigger = randomQueryLevelTrigger(condition = Script(triggerScript), destinationId = destinationId)
        val monitor = createMonitor(randomQueryLevelMonitor(inputs = listOf(input), triggers = listOf(trigger)))

        val response = executeMonitor(monitor.id)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        val triggerResult = output.objectMap("trigger_results").objectMap(trigger.id)
        assertEquals(true, triggerResult["triggered"].toString().toBoolean())
        assertTrue("Unexpected trigger error message", triggerResult["error"]?.toString().isNullOrEmpty())

        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor)
    }

    fun `test execute monitor search with period date math`() {
        val testIndex = createTestIndex()
        val fiveDaysAgo = ZonedDateTime.now().minus(5, DAYS).truncatedTo(MILLIS)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(fiveDaysAgo)
        val testDoc = """{ "test_strict_date_time" : "$testTime" }"""
        indexDoc(testIndex, "1", testDoc)

        // Queries that use period_start/end should expect these values to always be formatted as 'epoch_millis'. Either
        // the query should specify the format (like below) or the mapping for the index/field being queried should allow
        // epoch_millis as an alternative (OpenSearch's default mapping for date fields "strict_date_optional_time||epoch_millis")
        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().query(query))
        val triggerScript = """
            // make sure there is exactly one hit
            return ctx.results[0].hits.hits.size() == 1
        """.trimIndent()
        val trigger = randomQueryLevelTrigger(condition = Script(triggerScript))
        val monitor = randomQueryLevelMonitor(inputs = listOf(input), triggers = listOf(trigger))

        val response = executeMonitor(monitor)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        val triggerResult = output.objectMap("trigger_results").objectMap(trigger.id)
        assertEquals(true, triggerResult["triggered"].toString().toBoolean())
        assertTrue("Unexpected trigger error message", triggerResult["error"]?.toString().isNullOrEmpty())
        assertNotEquals("period incorrect", output["period_start"], output["period_end"])

        // Don't expect any alerts for this monitor as it has not been saved
        val alerts = searchAlerts(monitor)
        assertEquals("Alert saved for test monitor", 0, alerts.size)
    }

    fun `test execute monitor search with date math`() {
        // Give the index name in the date math format.
        val testIndex = "<my-index-{now/d{YYYY.MM.dd|+12:00}}>"
        // Add percent encoding for the http client to resolve the format.
        val encodedTestIndex = createTestIndex(
            URLEncoder.encode(testIndex, "utf-8")
        )

        val fiveDaysAgo = ZonedDateTime.now().minus(5, DAYS).truncatedTo(MILLIS)
        val testTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(fiveDaysAgo)
        val testDoc = """{ "test_strict_date_time" : "$testTime" }"""
        indexDoc(encodedTestIndex, "1", testDoc)

        // Queries that use period_start/end should expect these values to always be formatted as 'epoch_millis'. Either
        // the query should specify the format (like below) or the mapping for the index/field being queried should allow
        // epoch_millis as an alternative (OpenSearch's default mapping for date fields "strict_date_optional_time||epoch_millis")
        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().query(query))
        val triggerScript = """
            // make sure there is exactly one hit
            return ctx.results[0].hits.hits.size() == 1
        """.trimIndent()
        val trigger = randomQueryLevelTrigger(condition = Script(triggerScript))
        val monitor = randomQueryLevelMonitor(inputs = listOf(input), triggers = listOf(trigger))

        val response = executeMonitor(monitor, params = DRYRUN_MONITOR)

        val output = entityAsMap(response)

        assertEquals(monitor.name, output["monitor_name"])
        @Suppress("UNCHECKED_CAST")
        val searchResult = (output.objectMap("input_results")["results"] as List<Map<String, Any>>).first()
        @Suppress("UNCHECKED_CAST")
        val total = searchResult.stringMap("hits")?.get("total") as Map<String, String>
        assertEquals("Incorrect search result", 1, total["value"])
    }

    fun `test monitor with one bad action and one good action`() {
        val goodAction = randomAction(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id
        )
        val syntaxErrorAction = randomAction(
            name = "bad syntax",
            template = randomTemplateScript("{{foo"),
            destinationId = createDestination().id
        )
        val actions = listOf(goodAction, syntaxErrorAction)
        val monitor = createMonitor(
            randomQueryLevelMonitor(triggers = listOf(randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = actions)))
        )

        val output = entityAsMap(executeMonitor(monitor.id))

        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (actionResult in triggerResult.objectMap("action_results").values) {
                @Suppress("UNCHECKED_CAST") val actionOutput = actionResult["output"] as Map<String, String>
                if (actionResult["name"] == goodAction.name) {
                    assertEquals("Hello ${monitor.name}", actionOutput["message"])
                } else if (actionResult["name"] == syntaxErrorAction.name) {
                    assertTrue("Missing action error message", (actionResult["error"] as String).isNotEmpty())
                } else {
                    fail("Unknown action: ${actionResult["name"]}")
                }
            }
        }

        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ERROR)
    }

    fun `test execute monitor adds to alert error history`() {
        putAlertMappings() // Required as we do not have a create alert API.
        // This template script has a parsing error to purposefully create an errorMessage during runMonitor
        val action = randomAction(template = randomTemplateScript("Hello {{ctx.monitor.name"))
        val trigger = randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = listOf(action))
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))
        val listOfFiveErrorMessages = (1..5).map { i -> AlertError(timestamp = Instant.now(), message = "error message $i") }
        val activeAlert = createAlert(
            randomAlert(monitor).copy(
                state = ACTIVE, errorHistory = listOfFiveErrorMessages,
                triggerId = trigger.id, triggerName = trigger.name, severity = trigger.severity
            )
        )

        val response = executeMonitor(monitor.id)

        val updatedAlert = searchAlerts(monitor).single()
        assertEquals("Existing active alert was not updated", activeAlert.id, updatedAlert.id)
        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (actionResult in triggerResult.objectMap("action_results").values) {
                assertTrue("Missing action error message", (actionResult["error"] as String).isNotEmpty())
            }
        }
        assertEquals("Wrong number of error messages in history", 6, updatedAlert.errorHistory.size)
    }

    fun `test latest error is not lost when alert is completed`() {
        // Creates an active alert the first time it's run and completes it the second time the monitor is run.
        val trigger = randomQueryLevelTrigger(
            condition = Script(
                """
            if (ctx.alert == null) {
                throw new RuntimeException("foo");
            } else {
                return false;
            }
                """.trimIndent()
            )
        )
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))

        executeMonitor(monitor.id)
        val errorAlert = searchAlerts(monitor).single()
        verifyAlert(errorAlert, monitor, ERROR)
        executeMonitor(monitor.id)
        val completedAlert = searchAlerts(monitor, AlertIndices.ALL_ALERT_INDEX_PATTERN).single()
        verifyAlert(completedAlert, monitor, COMPLETED)

        assertNull("Completed alert still has error message.", completedAlert.errorMessage)
        assertTrue("Missing error history.", completedAlert.errorHistory.isNotEmpty())
        val latestError = completedAlert.errorHistory.single().message
        assertTrue("Latest error is missing from history.", latestError.contains("RuntimeException(\"foo\")"))
    }

    fun `test throw script exception`() {
        // Creates an active alert the first time it's run and completes it the second time the monitor is run.
        val trigger = randomQueryLevelTrigger(
            condition = Script(
                """
            param[0]; return true
                """.trimIndent()
            )
        )
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))

        executeMonitor(monitor.id)
        val errorAlert = searchAlerts(monitor).single()
        verifyAlert(errorAlert, monitor, ERROR)
        executeMonitor(monitor.id)
        assertEquals(
            "Error does not match",
            "Failed evaluating trigger:\nparam[0]; return true\n     ^---- HERE", errorAlert.errorMessage
        )
    }

    fun `test execute monitor limits alert error history to 10 error messages`() {
        putAlertMappings() // Required as we do not have a create alert API.
        // This template script has a parsing error to purposefully create an errorMessage during runMonitor
        val action = randomAction(template = randomTemplateScript("Hello {{ctx.monitor.name"))
        val trigger = randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = listOf(action))
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))
        val listOfTenErrorMessages = (1..10).map { i -> AlertError(timestamp = Instant.now(), message = "error message $i") }
        val activeAlert = createAlert(
            randomAlert(monitor).copy(
                state = ACTIVE, errorHistory = listOfTenErrorMessages,
                triggerId = trigger.id, triggerName = trigger.name, severity = trigger.severity
            )
        )

        val response = executeMonitor(monitor.id)

        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (actionResult in triggerResult.objectMap("action_results").values) {
                assertTrue("Missing action error message", (actionResult["error"] as String).isNotEmpty())
            }
        }
        val updatedAlert = searchAlerts(monitor).single()
        assertEquals("Existing active alert was not updated", activeAlert.id, updatedAlert.id)
        assertEquals("Wrong number of error messages in history", 10, updatedAlert.errorHistory.size)
    }

    fun `test execute monitor creates alert for trigger with no actions`() {
        putAlertMappings() // Required as we do not have a create alert API.

        val trigger = randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = emptyList(), destinationId = createDestination().id)
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))

        executeMonitor(monitor.id)

        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ACTIVE)
    }

    fun `test execute monitor non-dryrun`() {
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                triggers = listOf(
                    randomQueryLevelTrigger(
                        condition = ALWAYS_RUN,
                        actions = listOf(randomAction(destinationId = createDestination().id))
                    )
                )
            )
        )

        val response = executeMonitor(monitor.id, mapOf("dryrun" to "false"))

        assertEquals("failed dryrun", RestStatus.OK, response.restStatus())
        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ACTIVE)
    }

    fun `test execute monitor with already active alert`() {
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                triggers = listOf(
                    randomQueryLevelTrigger(
                        condition = ALWAYS_RUN,
                        actions = listOf(randomAction(destinationId = createDestination().id))
                    )
                )
            )
        )

        val firstExecuteResponse = executeMonitor(monitor.id, mapOf("dryrun" to "false"))

        assertEquals("failed dryrun", RestStatus.OK, firstExecuteResponse.restStatus())
        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ACTIVE)

        val secondExecuteResponse = executeMonitor(monitor.id, mapOf("dryrun" to "false"))

        assertEquals("failed dryrun", RestStatus.OK, secondExecuteResponse.restStatus())
        val newAlerts = searchAlerts(monitor)
        assertEquals("Second alert not saved", 1, newAlerts.size)
        verifyAlert(newAlerts.single(), monitor, ACTIVE)
    }

    fun `test delete monitor with no alerts after alert indices is initialized`() {
        putAlertMappings()

        val newMonitor = createMonitor(
            randomQueryLevelMonitor(
                triggers = listOf(randomQueryLevelTrigger(condition = NEVER_RUN, actions = listOf(randomAction())))
            )
        )
        val deleteNewMonitorResponse = client().makeRequest("DELETE", "$ALERTING_BASE_URI/${newMonitor.id}")

        assertEquals("Delete request not successful", RestStatus.OK, deleteNewMonitorResponse.restStatus())
    }

    fun `test update monitor stays on schedule`() {
        val monitor = createMonitor(randomQueryLevelMonitor(enabled = true))

        updateMonitor(monitor.copy(enabledTime = Instant.now()))

        val retrievedMonitor = getMonitor(monitorId = monitor.id)
        assertEquals("Monitor enabled time changed.", monitor.enabledTime, retrievedMonitor.enabledTime)
    }

    fun `test enabled time by disabling and re-enabling monitor`() {
        val monitor = createMonitor(randomQueryLevelMonitor(enabled = true))
        assertNotNull("Enabled time is null on a enabled monitor.", getMonitor(monitor.id).enabledTime)

        val disabledMonitor = updateMonitor(randomQueryLevelMonitor(enabled = false).copy(id = monitor.id))
        assertNull("Enabled time is not null on a disabled monitor.", disabledMonitor.enabledTime)

        val enabledMonitor = updateMonitor(randomQueryLevelMonitor(enabled = true).copy(id = monitor.id))
        assertNotNull("Enabled time is null on a enabled monitor.", enabledMonitor.enabledTime)
    }

    fun `test enabled time by providing enabled time`() {
        val enabledTime = Instant.ofEpochSecond(1538164858L) // This is 2018-09-27 20:00:58 GMT
        val monitor = createMonitor(randomQueryLevelMonitor(enabled = true, enabledTime = enabledTime))

        val retrievedMonitor = getMonitor(monitorId = monitor.id)
        assertTrue("Monitor is not enabled", retrievedMonitor.enabled)
        assertEquals("Enabled times do not match", monitor.enabledTime, retrievedMonitor.enabledTime)
    }

    fun `test monitor with throttled action for same alert`() {
        val actionThrottleEnabled = randomAction(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id,
            throttleEnabled = true, throttle = Throttle(value = 5, unit = MINUTES)
        )
        val actionThrottleNotEnabled = randomAction(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id,
            throttleEnabled = false, throttle = Throttle(value = 5, unit = MINUTES)
        )
        val actions = listOf(actionThrottleEnabled, actionThrottleNotEnabled)
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                triggers = listOf(randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = actions)),
                schedule = IntervalSchedule(interval = 1, unit = ChronoUnit.MINUTES)
            )
        )
        val monitorRunResultNotThrottled = entityAsMap(executeMonitor(monitor.id))
        verifyActionThrottleResults(
            monitorRunResultNotThrottled,
            mutableMapOf(
                Pair(actionThrottleEnabled.id, false),
                Pair(actionThrottleNotEnabled.id, false)
            )
        )

        val notThrottledAlert = searchAlerts(monitor)
        assertEquals("1 alert should be returned", 1, notThrottledAlert.size)
        verifyAlert(notThrottledAlert.single(), monitor, ACTIVE)
        val notThrottledActionResults = verifyActionExecutionResultInAlert(
            notThrottledAlert[0],
            mutableMapOf(Pair(actionThrottleEnabled.id, 0), Pair(actionThrottleNotEnabled.id, 0))
        )

        assertEquals(notThrottledActionResults.size, 2)
        val monitorRunResultThrottled = entityAsMap(executeMonitor(monitor.id))
        verifyActionThrottleResults(
            monitorRunResultThrottled,
            mutableMapOf(
                Pair(actionThrottleEnabled.id, true),
                Pair(actionThrottleNotEnabled.id, false)
            )
        )

        val throttledAlert = searchAlerts(monitor)
        assertEquals("1 alert should be returned", 1, throttledAlert.size)
        verifyAlert(throttledAlert.single(), monitor, ACTIVE)
        val throttledActionResults = verifyActionExecutionResultInAlert(
            throttledAlert[0],
            mutableMapOf(Pair(actionThrottleEnabled.id, 1), Pair(actionThrottleNotEnabled.id, 0))
        )

        assertEquals(notThrottledActionResults.size, 2)

        assertEquals(
            notThrottledActionResults[actionThrottleEnabled.id]!!.lastExecutionTime,
            throttledActionResults[actionThrottleEnabled.id]!!.lastExecutionTime
        )
    }

    fun `test monitor with throttled action for different alerts`() {
        val actionThrottleEnabled = randomAction(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id,
            throttleEnabled = true, throttle = Throttle(value = 5, unit = MINUTES)
        )
        val actions = listOf(actionThrottleEnabled)
        val trigger = randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = actions)
        val monitor = createMonitor(
            randomQueryLevelMonitor(
                triggers = listOf(trigger),
                schedule = IntervalSchedule(interval = 1, unit = ChronoUnit.MINUTES)
            )
        )
        val monitorRunResult1 = entityAsMap(executeMonitor(monitor.id))
        verifyActionThrottleResults(monitorRunResult1, mutableMapOf(Pair(actionThrottleEnabled.id, false)))

        val activeAlert1 = searchAlerts(monitor)
        assertEquals("1 alert should be returned", 1, activeAlert1.size)
        verifyAlert(activeAlert1.single(), monitor, ACTIVE)
        val actionResults1 = verifyActionExecutionResultInAlert(activeAlert1[0], mutableMapOf(Pair(actionThrottleEnabled.id, 0)))

        Thread.sleep(200)
        updateMonitor(monitor.copy(triggers = listOf(trigger.copy(condition = NEVER_RUN)), id = monitor.id))
        executeMonitor(monitor.id)
        val completedAlert = searchAlerts(monitor, AlertIndices.ALL_ALERT_INDEX_PATTERN).single()
        verifyAlert(completedAlert, monitor, COMPLETED)

        updateMonitor(monitor.copy(triggers = listOf(trigger.copy(condition = ALWAYS_RUN)), id = monitor.id))
        val monitorRunResult2 = entityAsMap(executeMonitor(monitor.id))
        verifyActionThrottleResults(monitorRunResult2, mutableMapOf(Pair(actionThrottleEnabled.id, false)))
        val activeAlert2 = searchAlerts(monitor)
        assertEquals("1 alert should be returned", 1, activeAlert2.size)
        assertNotEquals(activeAlert1[0].id, activeAlert2[0].id)

        val actionResults2 = verifyActionExecutionResultInAlert(activeAlert2[0], mutableMapOf(Pair(actionThrottleEnabled.id, 0)))
        assertNotEquals(
            actionResults1[actionThrottleEnabled.id]!!.lastExecutionTime,
            actionResults2[actionThrottleEnabled.id]!!.lastExecutionTime
        )
    }

    fun `test execute monitor with email destination creates alert in error state`() {
        putAlertMappings() // Required as we do not have a create alert API.

        val emailAccount = createRandomEmailAccount()
        val emailGroup = createRandomEmailGroup()
        val email = Email(
            emailAccountID = emailAccount.id,
            recipients = listOf(
                Recipient(type = Recipient.RecipientType.EMAIL, emailGroupID = null, email = "test@email.com"),
                Recipient(type = Recipient.RecipientType.EMAIL_GROUP, emailGroupID = emailGroup.id, email = null)
            )
        )

        val destination = createDestination(
            Destination(
                type = DestinationType.EMAIL,
                name = "testDesination",
                user = randomUser(),
                lastUpdateTime = Instant.now(),
                chime = null,
                slack = null,
                customWebhook = null,
                email = email
            )
        )
        val action = randomAction(destinationId = destination.id)
        val trigger = randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = listOf(action))
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))

        executeMonitor(monitor.id)

        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ERROR)
        Assert.assertTrue(alerts.single().errorMessage?.contains("Failed running action") as Boolean)
    }

    /*
    TODO: https://github.com/opensearch-project/alerting/issues/300
    fun `test execute monitor with custom webhook destination`() {
        val customWebhook = CustomWebhook("http://15.16.17.18", null, null, 80, null, "PUT", emptyMap(), emptyMap(), null, null)
        val destination = createDestination(
            Destination(
                type = DestinationType.CUSTOM_WEBHOOK,
                name = "testDesination",
                user = randomUser(),
                lastUpdateTime = Instant.now(),
                chime = null,
                slack = null,
                customWebhook = customWebhook,
                email = null
            )
        )
        val action = randomAction(destinationId = destination.id)
        val trigger = randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = listOf(action))
        val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))
        executeMonitor(adminClient(), monitor.id)

        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ERROR)
        Assert.assertTrue(alerts.single().errorMessage?.contains("Connect timed out") as Boolean)
    }
     */

    fun `test create ClusterMetricsInput monitor with ClusterHealth API`() {
        // GIVEN
        val path = "/_cluster/health"
        val input = randomClusterMetricsInput(path = path)
        val monitor = createMonitor(randomClusterMetricsMonitor(inputs = listOf(input)))

        // WHEN
        val response = executeMonitor(monitor.id)

        // THEN
        val output = entityAsMap(response)
        val inputResults = output.stringMap("input_results")
        val resultsContent = (inputResults?.get("results") as ArrayList<*>)[0]
        val errorMessage = inputResults["error"]

        assertEquals(monitor.name, output["monitor_name"])
        assertTrue(
            "Monitor results should contain cluster_name, but found: $resultsContent",
            resultsContent.toString().contains("cluster_name")
        )
        assertNull("There should not be an error message, but found: $errorMessage", errorMessage)
    }

    fun `test create ClusterMetricsInput monitor with ClusterStats API`() {
        // GIVEN
        val path = "/_cluster/stats"
        val input = randomClusterMetricsInput(path = path)
        val monitor = createMonitor(randomClusterMetricsMonitor(inputs = listOf(input)))

        // WHEN
        val response = executeMonitor(monitor.id)

        // THEN
        val output = entityAsMap(response)
        val inputResults = output.stringMap("input_results")
        val resultsContent = (inputResults?.get("results") as ArrayList<*>)[0]
        val errorMessage = inputResults["error"]

        assertEquals(monitor.name, output["monitor_name"])
        assertTrue(
            "Monitor results should contain monitor_name, but found: $resultsContent",
            resultsContent.toString().contains("memory_size_in_bytes")
        )
        assertNull("There should not be an error message, but found: $errorMessage", errorMessage)
    }

    fun `test create ClusterMetricsInput monitor with alert triggered`() {
        // GIVEN
        putAlertMappings()
        val trigger = randomQueryLevelTrigger(
            condition = Script(
                """
            return ctx.results[0].number_of_pending_tasks < 1
                """.trimIndent()
            ),
            destinationId = createDestination().id
        )
        val path = "/_cluster/health"
        val input = randomClusterMetricsInput(path = path)
        val monitor = createMonitor(randomClusterMetricsMonitor(inputs = listOf(input), triggers = listOf(trigger)))

        // WHEN
        val response = executeMonitor(monitor.id)

        // THEN
        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])

        val triggerResults = output.objectMap("trigger_results").values
        for (triggerResult in triggerResults) {
            assertTrue(
                "This triggerResult should be triggered: $triggerResult",
                triggerResult.objectMap("action_results").isNotEmpty()
            )
        }

        val alerts = searchAlerts(monitor)
        assertEquals("Alert not saved, $output", 1, alerts.size)
        verifyAlert(alerts.single(), monitor, ACTIVE)
    }

    fun `test create ClusterMetricsInput monitor with no alert triggered`() {
        // GIVEN
        putAlertMappings()
        val trigger = randomQueryLevelTrigger(
            condition = Script(
                """
            return ctx.results[0].status.equals("red")
                """.trimIndent()
            )
        )
        val path = "/_cluster/stats"
        val input = randomClusterMetricsInput(path = path)
        val monitor = createMonitor(randomClusterMetricsMonitor(inputs = listOf(input), triggers = listOf(trigger)))

        // WHEN
        val response = executeMonitor(monitor.id)

        // THEN
        val output = entityAsMap(response)
        assertEquals(monitor.name, output["monitor_name"])

        val triggerResults = output.objectMap("trigger_results").values
        for (triggerResult in triggerResults) {
            assertTrue(
                "This triggerResult should not be triggered: $triggerResult",
                triggerResult.objectMap("action_results").isEmpty()
            )
        }

        val alerts = searchAlerts(monitor)
        assertEquals("Alert saved for test monitor, output: $output", 0, alerts.size)
    }

    fun `test create ClusterMetricsInput monitor for ClusterHealth API with path parameters`() {
        // GIVEN
        val indices = (1..5).map { createTestIndex() }.toTypedArray()
        val pathParams = indices.joinToString(",")
        val path = "/_cluster/health/"
        val input = randomClusterMetricsInput(
            path = path,
            pathParams = pathParams
        )
        val monitor = createMonitor(randomClusterMetricsMonitor(inputs = listOf(input)))

        // WHEN
        val response = executeMonitor(monitor.id)

        // THEN
        val output = entityAsMap(response)
        val inputResults = output.stringMap("input_results")
        val resultsContent = (inputResults?.get("results") as ArrayList<*>)[0]
        val errorMessage = inputResults["error"]

        assertEquals(monitor.name, output["monitor_name"])
        assertTrue(
            "Monitor results should contain cluster_name, but found: $resultsContent",
            resultsContent.toString().contains("cluster_name")
        )
        assertNull("There should not be an error message, but found: $errorMessage", errorMessage)
    }

    // TODO: Once an API is implemented that supports adding/removing entries on the
    //  SupportedApiSettings::supportedApiList, create an test that simulates executing
    //  a preexisting ClusterMetricsInput monitor for an API that has been removed from the supportedApiList.
    //  This will likely involve adding an API to the list before creating the monitor, and then removing
    //  the API from the list before executing the monitor.

    fun `test execute monitor with custom webhook destination and denied host`() {

        listOf("http://10.1.1.1", "127.0.0.1").forEach {
            val customWebhook = CustomWebhook(it, null, null, 80, null, "PUT", emptyMap(), emptyMap(), null, null)
            val destination = createDestination(
                Destination(
                    type = DestinationType.CUSTOM_WEBHOOK,
                    name = "testDesination",
                    user = randomUser(),
                    lastUpdateTime = Instant.now(),
                    chime = null,
                    slack = null,
                    customWebhook = customWebhook,
                    email = null
                )
            )
            val action = randomAction(destinationId = destination.id)
            val trigger = randomQueryLevelTrigger(condition = ALWAYS_RUN, actions = listOf(action))
            val monitor = createMonitor(randomQueryLevelMonitor(triggers = listOf(trigger)))
            executeMonitor(adminClient(), monitor.id)

            val alerts = searchAlerts(monitor)
            assertEquals("Alert not saved", 1, alerts.size)
            verifyAlert(alerts.single(), monitor, ERROR)

            Assert.assertNotNull(alerts.single().errorMessage)
        }
    }

    fun `test execute AD monitor doesn't return search result without user`() {
        // TODO: change to REST API call to test security enabled case
        if (!securityEnabled()) {
            val user = randomUser()
            val detectorId = randomAlphaOfLength(5)
            prepareTestAnomalyResult(detectorId, user)
            // for old monitor before enable FGAC, the user field is empty
            val monitor = randomADMonitor(inputs = listOf(adSearchInput(detectorId)), triggers = listOf(adMonitorTrigger()), user = null)
            val response = executeMonitor(monitor, params = DRYRUN_MONITOR)
            val output = entityAsMap(response)
            @Suppress("UNCHECKED_CAST")
            (output["trigger_results"] as HashMap<String, Any>).forEach {
                    _, v ->
                assertTrue((v as HashMap<String, Boolean>)["triggered"] as Boolean)
            }
            assertEquals(monitor.name, output["monitor_name"])
            @Suppress("UNCHECKED_CAST")
            val searchResult = (output.objectMap("input_results")["results"] as List<Map<String, Any>>).first()
            @Suppress("UNCHECKED_CAST")
            val total = searchResult.stringMap("hits")?.get("total") as Map<String, String>
            assertEquals("Incorrect search result", 1, total["value"])
            @Suppress("UNCHECKED_CAST")
            val maxAnomalyGrade = searchResult.stringMap("aggregations")?.get("max_anomaly_grade") as Map<String, String>
            assertEquals("Incorrect search result", 0.75, maxAnomalyGrade["value"])
        }
    }

    fun `test execute AD monitor doesn't return search result with empty backend role`() {
        // TODO: change to REST API call to test security enabled case
        if (!securityEnabled()) {
            val user = randomUser()
            val detectorId = randomAlphaOfLength(5)
            prepareTestAnomalyResult(detectorId, user)
            // for old monitor before enable FGAC, the user field is empty
            val monitor = randomADMonitor(
                inputs = listOf(adSearchInput(detectorId)), triggers = listOf(adMonitorTrigger()),
                user = User(user.name, listOf(), user.roles, user.customAttNames)
            )
            val response = executeMonitor(monitor, params = DRYRUN_MONITOR)
            val output = entityAsMap(response)
            @Suppress("UNCHECKED_CAST")
            (output["trigger_results"] as HashMap<String, Any>).forEach {
                    _, v ->
                assertTrue((v as HashMap<String, Boolean>)["triggered"] as Boolean)
            }
            assertEquals(monitor.name, output["monitor_name"])
            @Suppress("UNCHECKED_CAST")
            val searchResult = (output.objectMap("input_results")["results"] as List<Map<String, Any>>).first()
            @Suppress("UNCHECKED_CAST")
            val total = searchResult.stringMap("hits")?.get("total") as Map<String, String>
            assertEquals("Incorrect search result", 1, total["value"])
            @Suppress("UNCHECKED_CAST")
            val maxAnomalyGrade = searchResult.stringMap("aggregations")?.get("max_anomaly_grade") as Map<String, String>
            assertEquals("Incorrect search result", 0.9, maxAnomalyGrade["value"])
        }
    }

    fun `test execute AD monitor returns search result with same backend role`() {
        // TODO: change to REST API call to test security enabled case
        if (!securityEnabled()) {
            val detectorId = randomAlphaOfLength(5)
            val user = randomUser()
            prepareTestAnomalyResult(detectorId, user)
            // Test monitor with same user
            val monitor = randomADMonitor(inputs = listOf(adSearchInput(detectorId)), triggers = listOf(adMonitorTrigger()), user = user)
            val response = executeMonitor(monitor, params = DRYRUN_MONITOR)
            val output = entityAsMap(response)
            @Suppress("UNCHECKED_CAST")
            (output["trigger_results"] as HashMap<String, Any>).forEach {
                    _, v ->
                assertTrue((v as HashMap<String, Boolean>)["triggered"] as Boolean)
            }
            @Suppress("UNCHECKED_CAST")
            val searchResult = (output.objectMap("input_results")["results"] as List<Map<String, Any>>).first()
            @Suppress("UNCHECKED_CAST")
            val total = searchResult.stringMap("hits")?.get("total") as Map<String, String>
            assertEquals("Incorrect search result", 3, total["value"])
            @Suppress("UNCHECKED_CAST")
            val maxAnomalyGrade = searchResult.stringMap("aggregations")?.get("max_anomaly_grade") as Map<String, String>
            assertEquals("Incorrect search result", 0.8, maxAnomalyGrade["value"])
        }
    }

    fun `test execute AD monitor returns no search result with different backend role`() {
        // TODO: change to REST API call to test security enabled case
        if (!securityEnabled()) {
            val detectorId = randomAlphaOfLength(5)
            val user = randomUser()
            prepareTestAnomalyResult(detectorId, user)
            // Test monitor with different user
            val monitor = randomADMonitor(
                inputs = listOf(adSearchInput(detectorId)),
                triggers = listOf(adMonitorTrigger()), user = randomUser()
            )
            val response = executeMonitor(monitor, params = DRYRUN_MONITOR)
            val output = entityAsMap(response)
            @Suppress("UNCHECKED_CAST")
            (output["trigger_results"] as HashMap<String, Any>).forEach {
                    _, v ->
                assertFalse((v as HashMap<String, Boolean>)["triggered"] as Boolean)
            }
            @Suppress("UNCHECKED_CAST")
            val searchResult = (output.objectMap("input_results")["results"] as List<Map<String, Any>>).first()
            @Suppress("UNCHECKED_CAST")
            val total = searchResult.stringMap("hits")?.get("total") as Map<String, String>
            assertEquals("Incorrect search result", 0, total["value"])
        }
    }

    fun `test execute bucket-level monitor returns search result`() {
        val testIndex = createTestIndex()
        insertSampleTimeSerializedData(
            testIndex,
            listOf(
                "test_value_1",
                "test_value_1", // adding duplicate to verify aggregation
                "test_value_2"
            )
        )

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val compositeSources = listOf(
            TermsValuesSourceBuilder("test_field").field("test_field")
        )
        val compositeAgg = CompositeAggregationBuilder("composite_agg", compositeSources)
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().size(0).query(query).aggregation(compositeAgg))
        val triggerScript = """
            params.docCount > 0
        """.trimIndent()

        var trigger = randomBucketLevelTrigger()
        trigger = trigger.copy(
            bucketSelector = BucketSelectorExtAggregationBuilder(
                name = trigger.id,
                bucketsPathsMap = mapOf("docCount" to "_count"),
                script = Script(triggerScript),
                parentBucketPath = "composite_agg",
                filter = null
            )
        )
        val monitor = createMonitor(randomBucketLevelMonitor(inputs = listOf(input), enabled = false, triggers = listOf(trigger)))
        val response = executeMonitor(monitor.id, params = DRYRUN_MONITOR)
        val output = entityAsMap(response)

        assertEquals(monitor.name, output["monitor_name"])
        @Suppress("UNCHECKED_CAST")
        val searchResult = (output.objectMap("input_results")["results"] as List<Map<String, Any>>).first()
        @Suppress("UNCHECKED_CAST")
        val buckets = searchResult.stringMap("aggregations")?.stringMap("composite_agg")?.get("buckets") as List<Map<String, Any>>
        assertEquals("Incorrect search result", 2, buckets.size)
    }

    fun `test bucket-level monitor alert creation and completion`() {
        val testIndex = createTestIndex()
        insertSampleTimeSerializedData(
            testIndex,
            listOf(
                "test_value_1",
                "test_value_1", // adding duplicate to verify aggregation
                "test_value_2"
            )
        )

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val compositeSources = listOf(
            TermsValuesSourceBuilder("test_field").field("test_field")
        )
        val compositeAgg = CompositeAggregationBuilder("composite_agg", compositeSources)
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().size(0).query(query).aggregation(compositeAgg))
        val triggerScript = """
            params.docCount > 0
        """.trimIndent()

        var trigger = randomBucketLevelTrigger()
        trigger = trigger.copy(
            bucketSelector = BucketSelectorExtAggregationBuilder(
                name = trigger.id,
                bucketsPathsMap = mapOf("docCount" to "_count"),
                script = Script(triggerScript),
                parentBucketPath = "composite_agg",
                filter = null
            )
        )
        val monitor = createMonitor(randomBucketLevelMonitor(inputs = listOf(input), enabled = false, triggers = listOf(trigger)))
        executeMonitor(monitor.id)

        // Check created alerts
        var alerts = searchAlerts(monitor)
        assertEquals("Alerts not saved", 2, alerts.size)
        alerts.forEach {
            // Given the random configuration of the Bucket-Level Trigger for the test, it's possible to get
            // an action configuration that leads to no notifications (meaning the field for the Alert is null).
            // Since testing action execution is not relevant to this test, verifyAlert is asked to ignore it.
            verifyAlert(it, monitor, ACTIVE, expectNotification = false)
        }

        // Delete documents of a particular value
        deleteDataWithDocIds(
            testIndex,
            listOf(
                "1", // test_value_1
                "2" // test_value_1
            )
        )

        // Execute monitor again
        executeMonitor(monitor.id)

        // Verify expected alert was completed
        alerts = searchAlerts(monitor, AlertIndices.ALL_ALERT_INDEX_PATTERN)
        val activeAlerts = alerts.filter { it.state == ACTIVE }
        val completedAlerts = alerts.filter { it.state == COMPLETED }
        assertEquals("Incorrect number of active alerts", 1, activeAlerts.size)
        assertEquals("Incorrect number of completed alerts", 1, completedAlerts.size)
    }

    fun `test bucket-level monitor with acknowledged alert`() {
        val testIndex = createTestIndex()
        insertSampleTimeSerializedData(
            testIndex,
            listOf(
                "test_value_1",
                "test_value_2"
            )
        )

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val compositeSources = listOf(
            TermsValuesSourceBuilder("test_field").field("test_field")
        )
        val compositeAgg = CompositeAggregationBuilder("composite_agg", compositeSources)
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().size(0).query(query).aggregation(compositeAgg))
        val triggerScript = """
            params.docCount > 0
        """.trimIndent()

        // For the Actions ensure that there is at least one and any PER_ALERT actions contain ACTIVE, DEDUPED and COMPLETED in its policy
        // so that the assertions done later in this test don't fail.
        // The config is being mutated this way to still maintain the randomness in configuration (like including other ActionExecutionScope).
        val actions = randomActionsForBucketLevelTrigger(min = 1).map {
            if (it.actionExecutionPolicy?.actionExecutionScope is PerAlertActionScope) {
                it.copy(
                    actionExecutionPolicy = ActionExecutionPolicy(
                        PerAlertActionScope(setOf(AlertCategory.NEW, AlertCategory.DEDUPED, AlertCategory.COMPLETED))
                    )
                )
            } else {
                it
            }
        }
        var trigger = randomBucketLevelTrigger(actions = actions)
        trigger = trigger.copy(
            bucketSelector = BucketSelectorExtAggregationBuilder(
                name = trigger.id,
                bucketsPathsMap = mapOf("docCount" to "_count"),
                script = Script(triggerScript),
                parentBucketPath = "composite_agg",
                filter = null
            )
        )
        val monitor = createMonitor(randomBucketLevelMonitor(inputs = listOf(input), enabled = false, triggers = listOf(trigger)))
        executeMonitor(monitor.id)

        // Check created Alerts
        var currentAlerts = searchAlerts(monitor)
        assertEquals("Alerts not saved", 2, currentAlerts.size)
        currentAlerts.forEach {
            verifyAlert(it, monitor, ACTIVE)
        }

        // Acknowledge one of the Alerts
        val alertToAcknowledge = currentAlerts.single { it.aggregationResultBucket?.getBucketKeysHash().equals("test_value_1") }
        acknowledgeAlerts(monitor, alertToAcknowledge)
        currentAlerts = searchAlerts(monitor)
        val acknowledgedAlert = currentAlerts.single { it.state == ACKNOWLEDGED }
        val activeAlert = currentAlerts.single { it.state == ACTIVE }

        // Runner uses ThreadPool.CachedTimeThread thread which only updates once every 200 ms. Wait a bit to
        // let lastNotificationTime change.  W/o this sleep the test can result in a false negative.
        Thread.sleep(200)
        executeMonitor(monitor.id)

        // Check that the lastNotification time of the acknowledged Alert wasn't updated and the active Alert's was
        currentAlerts = searchAlerts(monitor)
        val acknowledgedAlert2 = currentAlerts.single { it.state == ACKNOWLEDGED }
        val activeAlert2 = currentAlerts.single { it.state == ACTIVE }
        assertEquals("Acknowledged alert was updated", acknowledgedAlert.lastNotificationTime, acknowledgedAlert2.lastNotificationTime)
        assertTrue("Active alert was not updated", activeAlert2.lastNotificationTime!! > activeAlert.lastNotificationTime)

        // Remove data so that both Alerts are moved into completed
        deleteDataWithDocIds(
            testIndex,
            listOf(
                "1", // test_value_1
                "2" // test_value_2
            )
        )

        // Execute Monitor and check that both Alerts were updated
        Thread.sleep(200)
        executeMonitor(monitor.id)
        currentAlerts = searchAlerts(monitor, AlertIndices.ALL_ALERT_INDEX_PATTERN)
        val completedAlerts = currentAlerts.filter { it.state == COMPLETED }
        assertEquals("Incorrect number of completed alerts", 2, completedAlerts.size)
        val previouslyAcknowledgedAlert = completedAlerts.single { it.aggregationResultBucket?.getBucketKeysHash().equals("test_value_1") }
        val previouslyActiveAlert = completedAlerts.single { it.aggregationResultBucket?.getBucketKeysHash().equals("test_value_2") }
        assertTrue(
            "Previously acknowledged alert was not updated when it moved to completed",
            previouslyAcknowledgedAlert.lastNotificationTime!! > acknowledgedAlert2.lastNotificationTime
        )
        assertTrue(
            "Previously active alert was not updated when it moved to completed",
            previouslyActiveAlert.lastNotificationTime!! > activeAlert2.lastNotificationTime
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun `test bucket-level monitor with one good action and one bad action`() {
        val testIndex = createTestIndex()
        insertSampleTimeSerializedData(
            testIndex,
            listOf(
                "test_value_1",
                "test_value_1",
                "test_value_3",
                "test_value_2",
                "test_value_2"
            )
        )

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val compositeSources = listOf(
            TermsValuesSourceBuilder("test_field").field("test_field")
        )
        val compositeAgg = CompositeAggregationBuilder("composite_agg", compositeSources)
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().size(0).query(query).aggregation(compositeAgg))
        // Trigger script should only create Alerts for 'test_value_1' and 'test_value_2'
        val triggerScript = """
            params.docCount > 1
        """.trimIndent()

        val goodAction = randomAction(template = randomTemplateScript("Hello {{ctx.monitor.name}}"), destinationId = createDestination().id)
        val syntaxErrorAction = randomAction(
            name = "bad syntax",
            template = randomTemplateScript("{{foo"),
            destinationId = createDestination().id
        )
        val actions = listOf(goodAction, syntaxErrorAction)

        var trigger = randomBucketLevelTrigger(actions = actions)
        trigger = trigger.copy(
            bucketSelector = BucketSelectorExtAggregationBuilder(
                name = trigger.id,
                bucketsPathsMap = mapOf("docCount" to "_count"),
                script = Script(triggerScript),
                parentBucketPath = "composite_agg",
                filter = null
            )
        )
        val monitor = createMonitor(randomBucketLevelMonitor(inputs = listOf(input), enabled = false, triggers = listOf(trigger)))

        val output = entityAsMap(executeMonitor(monitor.id))
        // The 'events' in this case are the bucketKeys hashes representing the Alert events
        val expectedEvents = setOf("test_value_1", "test_value_2")

        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (alertEvent in triggerResult.objectMap("action_results")) {
                assertTrue(expectedEvents.contains(alertEvent.key))
                val actionResults = alertEvent.value.values as Collection<Map<String, Any>>
                for (actionResult in actionResults) {
                    val actionOutput = actionResult["output"] as Map<String, String>
                    if (actionResult["name"] == goodAction.name) {
                        assertEquals("Hello ${monitor.name}", actionOutput["message"])
                    } else if (actionResult["name"] == syntaxErrorAction.name) {
                        assertTrue("Missing action error message", (actionResult["error"] as String).isNotEmpty())
                    } else {
                        fail("Unknown action: ${actionResult["name"]}")
                    }
                }
            }
        }

        // Check created alerts
        val alerts = searchAlerts(monitor)
        assertEquals("Alerts not saved", 2, alerts.size)
        alerts.forEach {
            verifyAlert(it, monitor, ACTIVE)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun `test bucket-level monitor with per execution action scope`() {
        val testIndex = createTestIndex()
        insertSampleTimeSerializedData(
            testIndex,
            listOf(
                "test_value_1",
                "test_value_1",
                "test_value_3",
                "test_value_2",
                "test_value_2"
            )
        )

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val compositeSources = listOf(
            TermsValuesSourceBuilder("test_field").field("test_field")
        )
        val compositeAgg = CompositeAggregationBuilder("composite_agg", compositeSources)
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().size(0).query(query).aggregation(compositeAgg))
        // Trigger script should only create Alerts for 'test_value_1' and 'test_value_2'
        val triggerScript = """
            params.docCount > 1
        """.trimIndent()

        val action = randomActionWithPolicy(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id,
            actionExecutionPolicy = ActionExecutionPolicy(PerExecutionActionScope())
        )
        var trigger = randomBucketLevelTrigger(actions = listOf(action))
        trigger = trigger.copy(
            bucketSelector = BucketSelectorExtAggregationBuilder(
                name = trigger.id,
                bucketsPathsMap = mapOf("docCount" to "_count"),
                script = Script(triggerScript),
                parentBucketPath = "composite_agg",
                filter = null
            )
        )
        val monitor = createMonitor(randomBucketLevelMonitor(inputs = listOf(input), enabled = false, triggers = listOf(trigger)))

        val output = entityAsMap(executeMonitor(monitor.id))
        // The 'events' in this case are the bucketKeys hashes representing the Alert events
        val expectedEvents = setOf("test_value_1", "test_value_2")

        assertEquals(monitor.name, output["monitor_name"])
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (alertEvent in triggerResult.objectMap("action_results")) {
                assertTrue(expectedEvents.contains(alertEvent.key))
                val actionResults = alertEvent.value.values as Collection<Map<String, Any>>
                for (actionResult in actionResults) {
                    val actionOutput = actionResult["output"] as Map<String, String>
                    assertEquals("Unknown action: ${actionResult["name"]}", action.name, actionResult["name"])
                    assertEquals("Hello ${monitor.name}", actionOutput["message"])
                }
            }
        }

        // Check created alerts
        val alerts = searchAlerts(monitor)
        assertEquals("Alerts not saved", 2, alerts.size)
        alerts.forEach {
            verifyAlert(it, monitor, ACTIVE)
        }
    }

    fun `test bucket-level monitor with per alert action scope saves completed alerts even if not actionable`() {
        val testIndex = createTestIndex()
        insertSampleTimeSerializedData(
            testIndex,
            listOf(
                "test_value_1",
                "test_value_1",
                "test_value_2",
                "test_value_2"
            )
        )

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val compositeSources = listOf(
            TermsValuesSourceBuilder("test_field").field("test_field")
        )
        val compositeAgg = CompositeAggregationBuilder("composite_agg", compositeSources)
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().size(0).query(query).aggregation(compositeAgg))
        val triggerScript = """
            params.docCount > 1
        """.trimIndent()

        val action = randomActionWithPolicy(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id,
            actionExecutionPolicy = ActionExecutionPolicy(PerAlertActionScope(setOf(AlertCategory.DEDUPED, AlertCategory.NEW)))
        )
        var trigger = randomBucketLevelTrigger(actions = listOf(action))
        trigger = trigger.copy(
            bucketSelector = BucketSelectorExtAggregationBuilder(
                name = trigger.id,
                bucketsPathsMap = mapOf("docCount" to "_count"),
                script = Script(triggerScript),
                parentBucketPath = "composite_agg",
                filter = null
            )
        )
        val monitor = createMonitor(randomBucketLevelMonitor(inputs = listOf(input), enabled = false, triggers = listOf(trigger)))
        executeMonitor(monitor.id)

        // Check created Alerts
        var currentAlerts = searchAlerts(monitor)
        assertEquals("Alerts not saved", 2, currentAlerts.size)
        currentAlerts.forEach {
            verifyAlert(it, monitor, ACTIVE)
        }

        // Remove data so that both Alerts are moved into completed
        deleteDataWithDocIds(
            testIndex,
            listOf(
                "1", // test_value_1
                "2", // test_value_1
                "3", // test_value_2
                "4" // test_value_2
            )
        )

        // Execute Monitor and check that both Alerts were moved to COMPLETED
        executeMonitor(monitor.id)
        currentAlerts = searchAlerts(monitor, AlertIndices.ALL_ALERT_INDEX_PATTERN)
        val completedAlerts = currentAlerts.filter { it.state == COMPLETED }
        assertEquals("Incorrect number of completed alerts", 2, completedAlerts.size)
    }

    @Suppress("UNCHECKED_CAST")
    fun `test bucket-level monitor throttling with per alert action scope`() {
        val testIndex = createTestIndex()
        insertSampleTimeSerializedData(
            testIndex,
            listOf(
                "test_value_1",
                "test_value_2"
            )
        )

        val query = QueryBuilders.rangeQuery("test_strict_date_time")
            .gt("{{period_end}}||-10d")
            .lte("{{period_end}}")
            .format("epoch_millis")
        val compositeSources = listOf(
            TermsValuesSourceBuilder("test_field").field("test_field")
        )
        val compositeAgg = CompositeAggregationBuilder("composite_agg", compositeSources)
        val input = SearchInput(indices = listOf(testIndex), query = SearchSourceBuilder().size(0).query(query).aggregation(compositeAgg))
        val triggerScript = """
            params.docCount > 0
        """.trimIndent()

        val actionThrottleEnabled = randomActionWithPolicy(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id,
            throttleEnabled = true,
            throttle = Throttle(value = 5, unit = MINUTES),
            actionExecutionPolicy = ActionExecutionPolicy(
                actionExecutionScope = PerAlertActionScope(setOf(AlertCategory.DEDUPED, AlertCategory.NEW))
            )
        )
        val actionThrottleNotEnabled = randomActionWithPolicy(
            template = randomTemplateScript("Hello {{ctx.monitor.name}}"),
            destinationId = createDestination().id,
            throttleEnabled = false,
            throttle = Throttle(value = 5, unit = MINUTES),
            actionExecutionPolicy = ActionExecutionPolicy(
                actionExecutionScope = PerAlertActionScope(setOf(AlertCategory.DEDUPED, AlertCategory.NEW))
            )
        )
        val actions = listOf(actionThrottleEnabled, actionThrottleNotEnabled)
        var trigger = randomBucketLevelTrigger(actions = actions)
        trigger = trigger.copy(
            bucketSelector = BucketSelectorExtAggregationBuilder(
                name = trigger.id,
                bucketsPathsMap = mapOf("docCount" to "_count"),
                script = Script(triggerScript),
                parentBucketPath = "composite_agg",
                filter = null
            )
        )
        val monitor = createMonitor(randomBucketLevelMonitor(inputs = listOf(input), enabled = false, triggers = listOf(trigger)))

        val monitorRunResultNotThrottled = entityAsMap(executeMonitor(monitor.id))
        verifyActionThrottleResultsForBucketLevelMonitor(
            monitorRunResult = monitorRunResultNotThrottled,
            expectedEvents = setOf("test_value_1", "test_value_2"),
            expectedActionResults = mapOf(
                Pair(actionThrottleEnabled.id, false),
                Pair(actionThrottleNotEnabled.id, false)
            )
        )

        val notThrottledAlerts = searchAlerts(monitor)
        assertEquals("Alerts may not have been saved correctly", 2, notThrottledAlerts.size)
        val previousAlertExecutionTime: MutableMap<String, MutableMap<String, Instant?>> = mutableMapOf()
        notThrottledAlerts.forEach {
            verifyAlert(it, monitor, ACTIVE)
            val notThrottledActionResults = verifyActionExecutionResultInAlert(
                it,
                mutableMapOf(Pair(actionThrottleEnabled.id, 0), Pair(actionThrottleNotEnabled.id, 0))
            )
            assertEquals(notThrottledActionResults.size, 2)
            // Save the lastExecutionTimes of the actions for the Alert to be compared later against
            // the next Monitor execution run
            previousAlertExecutionTime[it.id] = mutableMapOf()
            previousAlertExecutionTime[it.id]!![actionThrottleEnabled.id] =
                notThrottledActionResults[actionThrottleEnabled.id]!!.lastExecutionTime
            previousAlertExecutionTime[it.id]!![actionThrottleNotEnabled.id] =
                notThrottledActionResults[actionThrottleNotEnabled.id]!!.lastExecutionTime
        }

        // Runner uses ThreadPool.CachedTimeThread thread which only updates once every 200 ms. Wait a bit to
        // let Action executionTime change.  W/o this sleep the test can result in a false negative.
        Thread.sleep(200)
        val monitorRunResultThrottled = entityAsMap(executeMonitor(monitor.id))
        verifyActionThrottleResultsForBucketLevelMonitor(
            monitorRunResult = monitorRunResultThrottled,
            expectedEvents = setOf("test_value_1", "test_value_2"),
            expectedActionResults = mapOf(
                Pair(actionThrottleEnabled.id, true),
                Pair(actionThrottleNotEnabled.id, false)
            )
        )

        val throttledAlerts = searchAlerts(monitor)
        assertEquals("Alerts may not have been saved correctly", 2, throttledAlerts.size)
        throttledAlerts.forEach {
            verifyAlert(it, monitor, ACTIVE)
            val throttledActionResults = verifyActionExecutionResultInAlert(
                it,
                mutableMapOf(Pair(actionThrottleEnabled.id, 1), Pair(actionThrottleNotEnabled.id, 0))
            )
            assertEquals(throttledActionResults.size, 2)

            val prevthrottledActionLastExecutionTime = previousAlertExecutionTime[it.id]!![actionThrottleEnabled.id]
            val prevNotThrottledActionLastExecutionTime = previousAlertExecutionTime[it.id]!![actionThrottleNotEnabled.id]
            assertEquals(
                "Last execution time of a throttled action was updated for one of the Alerts",
                prevthrottledActionLastExecutionTime,
                throttledActionResults[actionThrottleEnabled.id]!!.lastExecutionTime
            )
            assertTrue(
                "Last execution time of a non-throttled action was not updated for one of the Alerts",
                throttledActionResults[actionThrottleNotEnabled.id]!!.lastExecutionTime!! > prevNotThrottledActionLastExecutionTime
            )
        }
    }

    private fun prepareTestAnomalyResult(detectorId: String, user: User) {
        val adResultIndex = ".opendistro-anomaly-results-history-2020.10.17"
        try {
            createTestIndex(adResultIndex, anomalyResultIndexMapping())
        } catch (e: Exception) {
            // WarningFailureException is expected as we are creating system index start with dot
            assertTrue(e is WarningFailureException)
        }

        val twoMinsAgo = ZonedDateTime.now().minus(2, MINUTES).truncatedTo(MILLIS)
        val testTime = twoMinsAgo.toEpochSecond() * 1000
        val testResult1 = randomAnomalyResult(
            detectorId = detectorId, executionEndTime = testTime, user = user,
            anomalyGrade = 0.1
        )
        indexDoc(adResultIndex, "1", testResult1)
        val testResult2 = randomAnomalyResult(
            detectorId = detectorId, executionEndTime = testTime, user = user,
            anomalyGrade = 0.8
        )
        indexDoc(adResultIndex, "2", testResult2)
        val testResult3 = randomAnomalyResult(
            detectorId = detectorId, executionEndTime = testTime, user = user,
            anomalyGrade = 0.5
        )
        indexDoc(adResultIndex, "3", testResult3)
        val testResult4 = randomAnomalyResult(
            detectorId = detectorId, executionEndTime = testTime,
            user = User(user.name, listOf(), user.roles, user.customAttNames),
            anomalyGrade = 0.9
        )
        indexDoc(adResultIndex, "4", testResult4)
        // User is null
        val testResult5 = randomAnomalyResultWithoutUser(
            detectorId = detectorId, executionEndTime = testTime,
            anomalyGrade = 0.75
        )
        indexDoc(adResultIndex, "5", testResult5)
    }

    private fun verifyActionExecutionResultInAlert(alert: Alert, expectedResult: Map<String, Int>):
        MutableMap<String, ActionExecutionResult> {
        val actionResult = mutableMapOf<String, ActionExecutionResult>()
        for (result in alert.actionExecutionResults) {
            val expected = expectedResult[result.actionId]
            assertEquals(expected, result.throttledCount)
            actionResult.put(result.actionId, result)
        }
        return actionResult
    }

    private fun verifyActionThrottleResults(output: MutableMap<String, Any>, expectedResult: Map<String, Boolean>) {
        for (triggerResult in output.objectMap("trigger_results").values) {
            for (actionResult in triggerResult.objectMap("action_results").values) {
                val expected = expectedResult[actionResult["id"]]
                assertEquals(expected, actionResult["throttled"])
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyActionThrottleResultsForBucketLevelMonitor(
        monitorRunResult: MutableMap<String, Any>,
        expectedEvents: Set<String>,
        expectedActionResults: Map<String, Boolean>
    ) {
        for (triggerResult in monitorRunResult.objectMap("trigger_results").values) {
            for (alertEvent in triggerResult.objectMap("action_results")) {
                assertTrue(expectedEvents.contains(alertEvent.key))
                val actionResults = alertEvent.value.values as Collection<Map<String, Any>>
                for (actionResult in actionResults) {
                    val expected = expectedActionResults[actionResult["id"]]
                    assertEquals(expected, actionResult["throttled"])
                }
            }
        }
    }

    private fun verifyAlert(
        alert: Alert,
        monitor: Monitor,
        expectedState: Alert.State = ACTIVE,
        expectNotification: Boolean = true
    ) {
        assertNotNull(alert.id)
        assertNotNull(alert.startTime)
        if (expectNotification) {
            assertNotNull(alert.lastNotificationTime)
        }
        assertEquals("Alert in wrong state", expectedState, alert.state)
        if (expectedState == ERROR) {
            assertNotNull("Missing error message", alert.errorMessage)
        } else {
            assertNull("Unexpected error message", alert.errorMessage)
        }
        if (expectedState == COMPLETED) {
            assertNotNull("End time missing for completed alert.", alert.endTime)
        } else {
            assertNull("End time set for active alert", alert.endTime)
        }
        assertEquals(monitor.id, alert.monitorId)
        assertEquals(monitor.name, alert.monitorName)
        assertEquals(monitor.version, alert.monitorVersion)

        // assert trigger exists for alert
        val trigger = monitor.triggers.single { it.id == alert.triggerId }
        assertEquals(trigger.name, alert.triggerName)
    }

    @Suppress("UNCHECKED_CAST")
    /** helper that returns a field in a json map whose values are all json objects */
    private fun Map<String, Any>.objectMap(key: String): Map<String, Map<String, Any>> {
        return this[key] as Map<String, Map<String, Any>>
    }
}
