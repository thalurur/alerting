/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.util

import org.apache.logging.log4j.LogManager
import org.opensearch.action.ActionListener
import org.opensearch.action.admin.indices.create.CreateIndexRequest
import org.opensearch.action.admin.indices.create.CreateIndexResponse
import org.opensearch.action.admin.indices.mapping.put.PutMappingRequest
import org.opensearch.action.bulk.BulkRequest
import org.opensearch.action.bulk.BulkResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.support.WriteRequest.RefreshPolicy
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.alerting.action.ExecuteMonitorResponse
import org.opensearch.alerting.action.IndexMonitorResponse
import org.opensearch.alerting.core.model.DocLevelMonitorInput
import org.opensearch.alerting.core.model.DocLevelQuery
import org.opensearch.alerting.core.model.ScheduledJob
import org.opensearch.alerting.model.Monitor
import org.opensearch.client.AdminClient
import org.opensearch.client.Client
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Settings
import org.opensearch.common.unit.TimeValue

private val log = LogManager.getLogger(DocLevelMonitorQueries::class.java)

class DocLevelMonitorQueries(private val client: AdminClient, private val clusterService: ClusterService) {
    companion object {
        @JvmStatic
        fun docLevelQueriesMappings(): String {
            return DocLevelMonitorQueries::class.java.classLoader.getResource("mappings/doc-level-queries.json").readText()
        }
    }

    fun initDocLevelQueryIndex(actionListener: ActionListener<CreateIndexResponse>) {
        if (!docLevelQueryIndexExists()) {
            var indexRequest = CreateIndexRequest(ScheduledJob.DOC_LEVEL_QUERIES_INDEX)
                .mapping(docLevelQueriesMappings())
                .settings(
                    Settings.builder().put("index.hidden", true)
                        .build()
                )
            client.indices().create(indexRequest, actionListener)
        }
    }

    fun docLevelQueryIndexExists(): Boolean {
        val clusterState = clusterService.state()
        return clusterState.routingTable.hasIndex(ScheduledJob.DOC_LEVEL_QUERIES_INDEX)
    }

    fun indexDocLevelQueries(
        queryClient: Client,
        monitor: Monitor,
        monitorId: String,
        refreshPolicy: RefreshPolicy,
        indexTimeout: TimeValue,
        indexMonitorActionListener: ActionListener<IndexMonitorResponse>?,
        executeMonitorActionListener: ActionListener<ExecuteMonitorResponse>?,
        docLevelQueryIndexListener: ActionListener<BulkResponse>
    ) {
        val docLevelMonitorInput = monitor.inputs[0] as DocLevelMonitorInput
        val index = docLevelMonitorInput.indices[0]
        val queries: List<DocLevelQuery> = docLevelMonitorInput.queries

        val clusterState = clusterService.state()
        if (clusterState.routingTable.hasIndex(index)) {
            val indexMetadata = clusterState.metadata.index(index)

            if (indexMetadata.mapping() != null) {
                val properties = ((indexMetadata.mapping()?.sourceAsMap?.get("properties")) as Map<String, Map<String, Any>>)
                val updatedProperties = properties.entries.associate { "${it.key}_$monitorId" to it.value }.toMutableMap()

                val updateMappingRequest = PutMappingRequest(ScheduledJob.DOC_LEVEL_QUERIES_INDEX)
                updateMappingRequest.source(mapOf<String, Any>("properties" to updatedProperties))

                queryClient.admin().indices().putMapping(
                    updateMappingRequest,
                    object : ActionListener<AcknowledgedResponse> {
                        override fun onResponse(response: AcknowledgedResponse) {
                            log.info("Percolation index ${ScheduledJob.DOC_LEVEL_QUERIES_INDEX} updated with new mappings")

                            val request = BulkRequest().setRefreshPolicy(refreshPolicy).timeout(indexTimeout)

                            queries.forEach {
                                var query = it.query

                                properties.forEach { prop ->
                                    query = query.replace("${prop.key}:", "${prop.key}_$monitorId:")
                                }
                                val indexRequest = IndexRequest(ScheduledJob.DOC_LEVEL_QUERIES_INDEX)
                                    .id(it.id + "_$monitorId")
                                    .source(mapOf("query" to mapOf("query_string" to mapOf("query" to query)), "monitor_id" to monitorId))
                                request.add(indexRequest)
                            }

                            queryClient.bulk(request, docLevelQueryIndexListener)
                        }

                        override fun onFailure(e: Exception) {
                            if (indexMonitorActionListener != null) {
                                indexMonitorActionListener.onFailure(AlertingException.wrap(e))
                            } else executeMonitorActionListener?.onFailure(AlertingException.wrap(e))
                        }
                    }
                )
            }
        }
    }
}
