/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.transport

import org.apache.logging.log4j.LogManager
import org.opensearch.OpenSearchSecurityException
import org.opensearch.OpenSearchStatusException
import org.opensearch.action.ActionListener
import org.opensearch.action.admin.indices.create.CreateIndexResponse
import org.opensearch.action.bulk.BulkResponse
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.index.IndexResponse
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.action.support.WriteRequest.RefreshPolicy
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.alerting.DocumentReturningMonitorRunner
import org.opensearch.alerting.action.IndexMonitorAction
import org.opensearch.alerting.action.IndexMonitorRequest
import org.opensearch.alerting.action.IndexMonitorResponse
import org.opensearch.alerting.core.ScheduledJobIndices
import org.opensearch.alerting.core.model.DocLevelMonitorInput
import org.opensearch.alerting.core.model.ScheduledJob
import org.opensearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOBS_INDEX
import org.opensearch.alerting.core.model.SearchInput
import org.opensearch.alerting.model.Monitor
import org.opensearch.alerting.settings.AlertingSettings
import org.opensearch.alerting.settings.AlertingSettings.Companion.ALERTING_MAX_MONITORS
import org.opensearch.alerting.settings.AlertingSettings.Companion.INDEX_TIMEOUT
import org.opensearch.alerting.settings.AlertingSettings.Companion.MAX_ACTION_THROTTLE_VALUE
import org.opensearch.alerting.settings.AlertingSettings.Companion.REQUEST_TIMEOUT
import org.opensearch.alerting.settings.DestinationSettings.Companion.ALLOW_LIST
import org.opensearch.alerting.util.AlertingException
import org.opensearch.alerting.util.DocLevelMonitorQueries
import org.opensearch.alerting.util.IndexUtils
import org.opensearch.alerting.util.addUserBackendRolesFilter
import org.opensearch.alerting.util.isADMonitor
import org.opensearch.client.Client
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.inject.Inject
import org.opensearch.common.settings.Settings
import org.opensearch.common.unit.TimeValue
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.NamedXContentRegistry
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentFactory.jsonBuilder
import org.opensearch.common.xcontent.XContentHelper
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.authuser.User
import org.opensearch.index.query.QueryBuilders
import org.opensearch.index.reindex.BulkByScrollResponse
import org.opensearch.index.reindex.DeleteByQueryAction
import org.opensearch.index.reindex.DeleteByQueryRequestBuilder
import org.opensearch.rest.RestRequest
import org.opensearch.rest.RestStatus
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService
import java.io.IOException
import java.time.Duration

private val log = LogManager.getLogger(TransportIndexMonitorAction::class.java)

class TransportIndexMonitorAction @Inject constructor(
    transportService: TransportService,
    val client: Client,
    actionFilters: ActionFilters,
    val scheduledJobIndices: ScheduledJobIndices,
    val docLevelMonitorQueries: DocLevelMonitorQueries,
    val clusterService: ClusterService,
    val settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<IndexMonitorRequest, IndexMonitorResponse>(
    IndexMonitorAction.NAME, transportService, actionFilters, ::IndexMonitorRequest
),
    SecureTransportAction {

    @Volatile private var maxMonitors = ALERTING_MAX_MONITORS.get(settings)
    @Volatile private var requestTimeout = REQUEST_TIMEOUT.get(settings)
    @Volatile private var indexTimeout = INDEX_TIMEOUT.get(settings)
    @Volatile private var maxActionThrottle = MAX_ACTION_THROTTLE_VALUE.get(settings)
    @Volatile private var allowList = ALLOW_LIST.get(settings)
    @Volatile override var filterByEnabled = AlertingSettings.FILTER_BY_BACKEND_ROLES.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALERTING_MAX_MONITORS) { maxMonitors = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(REQUEST_TIMEOUT) { requestTimeout = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(INDEX_TIMEOUT) { indexTimeout = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(MAX_ACTION_THROTTLE_VALUE) { maxActionThrottle = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALLOW_LIST) { allowList = it }
        listenFilterBySettingChange(clusterService)
    }

    override fun doExecute(task: Task, request: IndexMonitorRequest, actionListener: ActionListener<IndexMonitorResponse>) {
        val user = readUserFromThreadContext(client)

        if (!validateUserBackendRoles(user, actionListener)) {
            return
        }

        if (!isADMonitor(request.monitor)) {
            checkIndicesAndExecute(client, actionListener, request, user)
        } else {
            // check if user has access to any anomaly detector for AD monitor
            checkAnomalyDetectorAndExecute(client, actionListener, request, user)
        }
    }

    /**
     *  Check if user has permissions to read the configured indices on the monitor and
     *  then create monitor.
     */
    fun checkIndicesAndExecute(
        client: Client,
        actionListener: ActionListener<IndexMonitorResponse>,
        request: IndexMonitorRequest,
        user: User?
    ) {
        val indices = mutableListOf<String>()
        // todo: for doc level alerting: check if index is present before monitor is created.
        val searchInputs = request.monitor.inputs.filter { it.name() == SearchInput.SEARCH_FIELD }
        searchInputs.forEach {
            val searchInput = it as SearchInput
            indices.addAll(searchInput.indices)
        }
        val searchRequest = SearchRequest().indices(*indices.toTypedArray())
            .source(SearchSourceBuilder.searchSource().size(1).query(QueryBuilders.matchAllQuery()))
        client.search(
            searchRequest,
            object : ActionListener<SearchResponse> {
                override fun onResponse(searchResponse: SearchResponse) {
                    // User has read access to configured indices in the monitor, now create monitor with out user context.
                    client.threadPool().threadContext.stashContext().use {
                        IndexMonitorHandler(client, actionListener, request, user).resolveUserAndStart()
                    }
                }

                //  Due to below issue with security plugin, we get security_exception when invalid index name is mentioned.
                //  https://github.com/opendistro-for-elasticsearch/security/issues/718
                override fun onFailure(t: Exception) {
                    actionListener.onFailure(
                        AlertingException.wrap(
                            when (t is OpenSearchSecurityException) {
                                true -> OpenSearchStatusException(
                                    "User doesn't have read permissions for one or more configured index " +
                                        "$indices",
                                    RestStatus.FORBIDDEN
                                )
                                false -> t
                            }
                        )
                    )
                }
            }
        )
    }

    /**
     * It's no reasonable to create AD monitor if the user has no access to any detector. Otherwise
     * the monitor will not get any anomaly result. So we will check user has access to at least 1
     * anomaly detector if they need to create AD monitor.
     * As anomaly detector index is system index, common user has no permission to query. So we need
     * to send REST API call to AD REST API.
     */
    fun checkAnomalyDetectorAndExecute(
        client: Client,
        actionListener: ActionListener<IndexMonitorResponse>,
        request: IndexMonitorRequest,
        user: User?
    ) {
        client.threadPool().threadContext.stashContext().use {
            IndexMonitorHandler(client, actionListener, request, user).resolveUserAndStartForAD()
        }
    }

    inner class IndexMonitorHandler(
        private val client: Client,
        private val actionListener: ActionListener<IndexMonitorResponse>,
        private val request: IndexMonitorRequest,
        private val user: User?
    ) {

        fun resolveUserAndStart() {
            if (user == null) {
                // Security is disabled, add empty user to Monitor. user is null for older versions.
                request.monitor = request.monitor
                    .copy(user = User("", listOf(), listOf(), listOf()))
                start()
            } else {
                request.monitor = request.monitor
                    .copy(user = User(user.name, user.backendRoles, user.roles, user.customAttNames))
                start()
            }
        }

        fun resolveUserAndStartForAD() {
            if (user == null) {
                // Security is disabled, add empty user to Monitor. user is null for older versions.
                request.monitor = request.monitor
                    .copy(user = User("", listOf(), listOf(), listOf()))
                start()
            } else {
                try {
                    request.monitor = request.monitor
                        .copy(user = User(user.name, user.backendRoles, user.roles, user.customAttNames))
                    val searchSourceBuilder = SearchSourceBuilder().size(0)
                    addUserBackendRolesFilter(user, searchSourceBuilder)
                    val searchRequest = SearchRequest().indices(".opendistro-anomaly-detectors").source(searchSourceBuilder)
                    client.search(
                        searchRequest,
                        object : ActionListener<SearchResponse> {
                            override fun onResponse(response: SearchResponse?) {
                                val totalHits = response?.hits?.totalHits?.value
                                if (totalHits != null && totalHits > 0L) {
                                    start()
                                } else {
                                    actionListener.onFailure(
                                        AlertingException.wrap(
                                            OpenSearchStatusException("User has no available detectors", RestStatus.NOT_FOUND)
                                        )
                                    )
                                }
                            }

                            override fun onFailure(t: Exception) {
                                actionListener.onFailure(AlertingException.wrap(t))
                            }
                        }
                    )
                } catch (ex: IOException) {
                    actionListener.onFailure(AlertingException.wrap(ex))
                }
            }
        }

        fun start() {
            if (!scheduledJobIndices.scheduledJobIndexExists()) {
                scheduledJobIndices.initScheduledJobIndex(object : ActionListener<CreateIndexResponse> {
                    override fun onResponse(response: CreateIndexResponse) {
                        onCreateMappingsResponse(response)
                    }
                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                })
            } else if (!IndexUtils.scheduledJobIndexUpdated) {
                IndexUtils.updateIndexMapping(
                    SCHEDULED_JOBS_INDEX,
                    ScheduledJobIndices.scheduledJobMappings(), clusterService.state(), client.admin().indices(),
                    object : ActionListener<AcknowledgedResponse> {
                        override fun onResponse(response: AcknowledgedResponse) {
                            onUpdateMappingsResponse(response)
                        }
                        override fun onFailure(t: Exception) {
                            actionListener.onFailure(AlertingException.wrap(t))
                        }
                    }
                )
            } else {
                prepareMonitorIndexing()
            }
        }

        /**
         * This function prepares for indexing a new monitor.
         * If this is an update request we can simply update the monitor. Otherwise we first check to see how many monitors already exist,
         * and compare this to the [maxMonitorCount]. Requests that breach this threshold will be rejected.
         */
        private fun prepareMonitorIndexing() {

            // Below check needs to be async operations and needs to be refactored issue#269
            // checkForDisallowedDestinations(allowList)

            try {
                validateActionThrottle(request.monitor, maxActionThrottle, TimeValue.timeValueMinutes(1))
            } catch (e: RuntimeException) {
                actionListener.onFailure(AlertingException.wrap(e))
                return
            }

            if (request.method == RestRequest.Method.PUT) return updateMonitor()

            val query = QueryBuilders.boolQuery().filter(QueryBuilders.termQuery("${Monitor.MONITOR_TYPE}.type", Monitor.MONITOR_TYPE))
            val searchSource = SearchSourceBuilder().query(query).timeout(requestTimeout)
            val searchRequest = SearchRequest(SCHEDULED_JOBS_INDEX).source(searchSource)
            client.search(
                searchRequest,
                object : ActionListener<SearchResponse> {
                    override fun onResponse(searchResponse: SearchResponse) {
                        onSearchResponse(searchResponse)
                    }

                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                }
            )
        }

        private fun validateActionThrottle(monitor: Monitor, maxValue: TimeValue, minValue: TimeValue) {
            monitor.triggers.forEach { trigger ->
                trigger.actions.forEach { action ->
                    if (action.throttle != null) {
                        require(
                            TimeValue(Duration.of(action.throttle.value.toLong(), action.throttle.unit).toMillis())
                                .compareTo(maxValue) <= 0,
                            { "Can only set throttle period less than or equal to $maxValue" }
                        )
                        require(
                            TimeValue(Duration.of(action.throttle.value.toLong(), action.throttle.unit).toMillis())
                                .compareTo(minValue) >= 0,
                            { "Can only set throttle period greater than or equal to $minValue" }
                        )
                    }
                }
            }
        }

        /**
         * After searching for all existing monitors we validate the system can support another monitor to be created.
         */
        private fun onSearchResponse(response: SearchResponse) {
            val totalHits = response.hits.totalHits?.value
            if (totalHits != null && totalHits >= maxMonitors) {
                log.error("This request would create more than the allowed monitors [$maxMonitors].")
                actionListener.onFailure(
                    AlertingException.wrap(
                        IllegalArgumentException(
                            "This request would create more than the allowed monitors [$maxMonitors]."
                        )
                    )
                )
            } else {

                indexMonitor()
            }
        }

        private fun onCreateMappingsResponse(response: CreateIndexResponse) {
            if (response.isAcknowledged) {
                log.info("Created $SCHEDULED_JOBS_INDEX with mappings.")
                prepareMonitorIndexing()
                IndexUtils.scheduledJobIndexUpdated()
            } else {
                log.error("Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged.")
                actionListener.onFailure(
                    AlertingException.wrap(
                        OpenSearchStatusException(
                            "Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged", RestStatus.INTERNAL_SERVER_ERROR
                        )
                    )
                )
            }
        }

        private fun onUpdateMappingsResponse(response: AcknowledgedResponse) {
            if (response.isAcknowledged) {
                log.info("Updated  ${ScheduledJob.SCHEDULED_JOBS_INDEX} with mappings.")
                IndexUtils.scheduledJobIndexUpdated()
                prepareMonitorIndexing()
            } else {
                log.error("Update ${ScheduledJob.SCHEDULED_JOBS_INDEX} mappings call not acknowledged.")
                actionListener.onFailure(
                    AlertingException.wrap(
                        OpenSearchStatusException(
                            "Updated ${ScheduledJob.SCHEDULED_JOBS_INDEX} mappings call not acknowledged.",
                            RestStatus.INTERNAL_SERVER_ERROR
                        )
                    )
                )
            }
        }

        private fun indexMonitor() {
            if (request.monitor.monitorType == Monitor.MonitorType.DOC_LEVEL_MONITOR) {
                val monitorIndex = (request.monitor.inputs[0] as DocLevelMonitorInput).indices[0]
                val lastRunContext = DocumentReturningMonitorRunner.createRunContext(clusterService, client, monitorIndex).toMutableMap()
                request.monitor = request.monitor.copy(lastRunContext = lastRunContext)
            }
            request.monitor = request.monitor.copy(schemaVersion = IndexUtils.scheduledJobIndexSchemaVersion)
            val indexRequest = IndexRequest(SCHEDULED_JOBS_INDEX)
                .setRefreshPolicy(request.refreshPolicy)
                .source(request.monitor.toXContentWithUser(jsonBuilder(), ToXContent.MapParams(mapOf("with_type" to "true"))))
                .setIfSeqNo(request.seqNo)
                .setIfPrimaryTerm(request.primaryTerm)
                .timeout(indexTimeout)
            client.index(
                indexRequest,
                object : ActionListener<IndexResponse> {
                    override fun onResponse(response: IndexResponse) {
                        val failureReasons = checkShardsFailure(response)
                        if (failureReasons != null) {
                            actionListener.onFailure(
                                AlertingException.wrap(OpenSearchStatusException(failureReasons.toString(), response.status()))
                            )
                            return
                        }

                        if (request.monitor.monitorType == Monitor.MonitorType.DOC_LEVEL_MONITOR) {
                            indexDocLevelMonitorQueries(request.monitor, response.id, request.refreshPolicy)
                        }

                        actionListener.onResponse(
                            IndexMonitorResponse(
                                response.id, response.version, response.seqNo,
                                response.primaryTerm, RestStatus.CREATED, request.monitor
                            )
                        )
                    }
                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                }
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun indexDocLevelMonitorQueries(monitor: Monitor, monitorId: String, refreshPolicy: RefreshPolicy) {
            if (!docLevelMonitorQueries.docLevelQueryIndexExists()) {
                docLevelMonitorQueries.initDocLevelQueryIndex(object : ActionListener<CreateIndexResponse> {
                    override fun onResponse(response: CreateIndexResponse) {
                        log.info("Central Percolation index ${ScheduledJob.DOC_LEVEL_QUERIES_INDEX} created")
                        docLevelMonitorQueries.indexDocLevelQueries(
                            client,
                            monitor,
                            monitorId,
                            refreshPolicy,
                            indexTimeout,
                            actionListener,
                            null,
                            object : ActionListener<BulkResponse> {
                                override fun onResponse(response: BulkResponse) {
                                    log.info("Queries inserted into Percolate index ${ScheduledJob.DOC_LEVEL_QUERIES_INDEX}")
                                }

                                override fun onFailure(t: Exception) {
                                    actionListener.onFailure(AlertingException.wrap(t))
                                }
                            }
                        )
                    }

                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                })
            } else {
                docLevelMonitorQueries.indexDocLevelQueries(
                    client,
                    monitor,
                    monitorId,
                    refreshPolicy,
                    indexTimeout,
                    actionListener,
                    null,
                    object : ActionListener<BulkResponse> {
                        override fun onResponse(response: BulkResponse) {
                            log.info("Queries inserted into Percolate index ${ScheduledJob.DOC_LEVEL_QUERIES_INDEX}")
                        }

                        override fun onFailure(t: Exception) {
                            actionListener.onFailure(AlertingException.wrap(t))
                        }
                    }
                )
            }
        }

        private fun updateMonitor() {
            val getRequest = GetRequest(SCHEDULED_JOBS_INDEX, request.monitorId)
            client.get(
                getRequest,
                object : ActionListener<GetResponse> {
                    override fun onResponse(response: GetResponse) {
                        if (!response.isExists) {
                            actionListener.onFailure(
                                AlertingException.wrap(
                                    OpenSearchStatusException("Monitor with ${request.monitorId} is not found", RestStatus.NOT_FOUND)
                                )
                            )
                            return
                        }
                        val xcp = XContentHelper.createParser(
                            xContentRegistry, LoggingDeprecationHandler.INSTANCE,
                            response.sourceAsBytesRef, XContentType.JSON
                        )
                        val monitor = ScheduledJob.parse(xcp, response.id, response.version) as Monitor
                        onGetResponse(monitor)
                    }
                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                }
            )
        }

        private fun onGetResponse(currentMonitor: Monitor) {
            if (!checkUserPermissionsWithResource(user, currentMonitor.user, actionListener, "monitor", request.monitorId)) {
                return
            }

            if (
                request.monitor.monitorType == Monitor.MonitorType.DOC_LEVEL_MONITOR &&
                request.monitor.lastRunContext.toMutableMap().isNullOrEmpty()
            ) {
                val monitorIndex = (request.monitor.inputs[0] as DocLevelMonitorInput).indices[0]
                val lastRunContext = DocumentReturningMonitorRunner.createRunContext(clusterService, client, monitorIndex).toMutableMap()
                request.monitor = request.monitor.copy(lastRunContext = lastRunContext)
            }

            // If both are enabled, use the current existing monitor enabled time, otherwise the next execution will be
            // incorrect.
            if (request.monitor.enabled && currentMonitor.enabled)
                request.monitor = request.monitor.copy(enabledTime = currentMonitor.enabledTime)

            request.monitor = request.monitor.copy(schemaVersion = IndexUtils.scheduledJobIndexSchemaVersion)
            val indexRequest = IndexRequest(SCHEDULED_JOBS_INDEX)
                .setRefreshPolicy(request.refreshPolicy)
                .source(request.monitor.toXContentWithUser(jsonBuilder(), ToXContent.MapParams(mapOf("with_type" to "true"))))
                .id(request.monitorId)
                .setIfSeqNo(request.seqNo)
                .setIfPrimaryTerm(request.primaryTerm)
                .timeout(indexTimeout)

            client.index(
                indexRequest,
                object : ActionListener<IndexResponse> {
                    override fun onResponse(response: IndexResponse) {
                        val failureReasons = checkShardsFailure(response)
                        if (failureReasons != null) {
                            actionListener.onFailure(
                                AlertingException.wrap(OpenSearchStatusException(failureReasons.toString(), response.status()))
                            )
                            return
                        }

                        if (currentMonitor.monitorType == Monitor.MonitorType.DOC_LEVEL_MONITOR) {
                            DeleteByQueryRequestBuilder(client, DeleteByQueryAction.INSTANCE)
                                .source(ScheduledJob.DOC_LEVEL_QUERIES_INDEX)
                                .filter(QueryBuilders.matchQuery("monitor_id", currentMonitor.id))
                                .execute(
                                    object : ActionListener<BulkByScrollResponse> {
                                        override fun onResponse(response: BulkByScrollResponse) {
                                            indexDocLevelMonitorQueries(request.monitor, currentMonitor.id, request.refreshPolicy)
                                        }

                                        override fun onFailure(t: Exception) {
                                        }
                                    }
                                )
                        }
                        actionListener.onResponse(
                            IndexMonitorResponse(
                                response.id, response.version, response.seqNo,
                                response.primaryTerm, RestStatus.CREATED, request.monitor
                            )
                        )
                    }
                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                }
            )
        }

        private fun checkShardsFailure(response: IndexResponse): String? {
            val failureReasons = StringBuilder()
            if (response.shardInfo.failed > 0) {
                response.shardInfo.failures.forEach {
                        entry ->
                    failureReasons.append(entry.reason())
                }
                return failureReasons.toString()
            }
            return null
        }
    }
}
