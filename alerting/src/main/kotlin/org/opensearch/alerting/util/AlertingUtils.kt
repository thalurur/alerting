/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.util

import inet.ipaddr.IPAddressString
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.index.IndexResponse
import org.opensearch.action.support.WriteRequest
import org.opensearch.alerting.core.model.ScheduledJob
import org.opensearch.alerting.destination.message.BaseMessage
import org.opensearch.alerting.model.AggregationResultBucket
import org.opensearch.alerting.model.BucketLevelTriggerRunResult
import org.opensearch.alerting.model.Monitor
import org.opensearch.alerting.model.action.Action
import org.opensearch.alerting.model.action.ActionExecutionPolicy
import org.opensearch.alerting.model.destination.Destination
import org.opensearch.alerting.opensearchapi.suspendUntil
import org.opensearch.alerting.settings.AlertingSettings
import org.opensearch.alerting.settings.DestinationSettings
import org.opensearch.client.Client
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.NamedXContentRegistry
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentFactory

/**
 * RFC 5322 compliant pattern matching: https://www.ietf.org/rfc/rfc5322.txt
 * Regex was based off of this post: https://stackoverflow.com/a/201378
 */
fun isValidEmail(email: String): Boolean {
    val validEmailPattern = Regex(
        "(?:[a-z0-9!#\$%&'*+\\/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+\\/=?^_`{|}~-]+)*" +
            "|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")" +
            "@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?" +
            "|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}" +
            "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:" +
            "(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])",
        RegexOption.IGNORE_CASE
    )

    return validEmailPattern.matches(email)
}

/** Allowed Destinations are ones that are specified in the [DestinationSettings.ALLOW_LIST] setting. */
fun Destination.isAllowed(allowList: List<String>): Boolean = allowList.contains(this.type.value)

fun Destination.isTestAction(): Boolean = this.type == DestinationType.TEST_ACTION

fun BaseMessage.isHostInDenylist(networks: List<String>): Boolean {
    if (this.url != null || this.uri.host != null) {
        val ipStr = IPAddressString(this.uri.host)
        for (network in networks) {
            val netStr = IPAddressString(network)
            if (netStr.contains(ipStr)) {
                return true
            }
        }
    }

    return false
}

fun Monitor.isBucketLevelMonitor(): Boolean = this.monitorType == Monitor.MonitorType.BUCKET_LEVEL_MONITOR

fun Monitor.isDocLevelMonitor(): Boolean = this.monitorType == Monitor.MonitorType.DOC_LEVEL_MONITOR

/**
 * Since buckets can have multi-value keys, this converts the bucket key values to a string that can be used
 * as the key for a HashMap to easily retrieve [AggregationResultBucket] based on the bucket key values.
 */
fun AggregationResultBucket.getBucketKeysHash(): String = this.bucketKeys.joinToString(separator = "#")

fun Action.getActionExecutionPolicy(monitor: Monitor): ActionExecutionPolicy? {
    // When the ActionExecutionPolicy is null for an Action, the default is resolved at runtime
    // so it can be chosen based on the Monitor type at that time.
    // The Action config is not aware of the Monitor type which is why the default was not stored during
    // the parse.
    return this.actionExecutionPolicy ?: if (monitor.isBucketLevelMonitor()) {
        ActionExecutionPolicy.getDefaultConfigurationForBucketLevelMonitor()
    } else {
        null
    }
}

fun BucketLevelTriggerRunResult.getCombinedTriggerRunResult(
    prevTriggerRunResult: BucketLevelTriggerRunResult?
): BucketLevelTriggerRunResult {
    if (prevTriggerRunResult == null) return this

    // The aggregation results and action results across to two trigger run results should not have overlapping keys
    // since they represent different pages of aggregations so a simple concatenation will combine them
    val mergedAggregationResultBuckets = prevTriggerRunResult.aggregationResultBuckets + this.aggregationResultBuckets
    val mergedActionResultsMap = (prevTriggerRunResult.actionResultsMap + this.actionResultsMap).toMutableMap()

    // Update to the most recent error if it's not null, otherwise keep the old one
    val error = this.error ?: prevTriggerRunResult.error

    return this.copy(aggregationResultBuckets = mergedAggregationResultBuckets, actionResultsMap = mergedActionResultsMap, error = error)
}

// TODO: Check if this can be more generic such that TransportIndexMonitorAction class can use this. Also see if this should be refactored
// to another class. Include tests for this as well.
suspend fun updateMonitor(client: Client, xContentRegistry: NamedXContentRegistry, settings: Settings, monitor: Monitor): IndexResponse {
    /*val currentMonitor = AlertingConfigAccessor.getMonitorInfo(client, xContentRegistry, monitor.id)

    var updateMonitor = monitor
    // If both are enabled, use the current existing monitor enabled time, otherwise the next execution will be
    // incorrect.
    if (monitor.enabled && currentMonitor.enabled)
        updateMonitor = monitor.copy(enabledTime = currentMonitor.enabledTime)*/

    val indexRequest = IndexRequest(ScheduledJob.SCHEDULED_JOBS_INDEX)
        .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
        .source(monitor.toXContentWithUser(XContentFactory.jsonBuilder(), ToXContent.MapParams(mapOf("with_type" to "true"))))
        .id(monitor.id)
        .timeout(AlertingSettings.INDEX_TIMEOUT.get(settings))

    return client.suspendUntil { client.index(indexRequest, it) }
}
