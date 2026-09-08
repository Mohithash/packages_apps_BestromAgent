/*
 * Copyright (C) 2026 The BestROM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.bestrom.agent.functions

import android.app.PendingIntent
import android.app.appfunctions.AppFunctionException
import android.app.appfunctions.AppFunctionManager
import android.app.appfunctions.AppFunctionMetadata
import android.app.appfunctions.AppFunctionName
import android.app.appfunctions.AppFunctionSearchSpec
import android.app.appfunctions.AppFunctionState
import android.app.appfunctions.AppFunctionStaticMetadataHelper
import android.app.appsearch.AppSearchManager
import android.app.appsearch.GenericDocument
import android.app.appsearch.GlobalSearchSession
import android.app.appsearch.SearchResult
import android.app.appsearch.SearchResults
import android.app.appsearch.SearchSpec
import android.app.appfunctions.ExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * AppFunctions behind one interface.
 *
 * Discovery goes through AppFunctionManager.searchAppFunctions, which accepts
 * EXECUTE_APP_FUNCTIONS in its anyOf, so no separate discovery permission is
 * requested. When that path is unavailable the client falls back to the global
 * AppSearch query the platform indexer writes, and says which one answered.
 */
class AppFunctionsClient(
    private val context: Context,
    private val executor: Executor,
) {

    companion object {
        const val SOURCE_SEARCH = "searchAppFunctions"
        const val SOURCE_APPSEARCH = "appsearch"
        private const val CALL_TIMEOUT_MS = 15000L
    }

    class ExecuteOutcome(
        val result: JSONObject?,
        val extras: JSONObject,
        val durationMs: Long,
        val errorCode: Int?,
        val errorCategory: Int?,
        val errorMessage: String?,
        val timedOut: Boolean,
    )

    private fun manager(): AppFunctionManager? =
        context.getSystemService(Context.APP_FUNCTION_SERVICE) as? AppFunctionManager

    fun isInstalled(packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Lists the functions this app may see, newest metadata first. */
    fun list(packageFilter: String?, includeSchema: Boolean): JSONObject {
        val manager = manager()
        if (manager != null) {
            val fromSearch = searchViaManager(manager, packageFilter)
            if (fromSearch != null) {
                val states = statesFor(manager, fromSearch)
                return encode(SOURCE_SEARCH, fromSearch.map { encodeMetadata(it, states, includeSchema) })
            }
        }
        val documents = searchViaAppSearch(packageFilter)
        return encode(SOURCE_APPSEARCH, documents.map { encodeStaticDocument(it, includeSchema) })
    }

    private fun encode(source: String, functions: List<JSONObject>): JSONObject {
        val array = JSONArray()
        for (f in functions) array.put(f)
        return JSONObject()
            .put("source", source)
            .put("functions", array)
            .put("count", functions.size)
    }

    private fun searchViaManager(
        manager: AppFunctionManager,
        packageFilter: String?,
    ): List<AppFunctionMetadata>? {
        val spec =
            AppFunctionSearchSpec.Builder()
                .apply { if (packageFilter != null) setPackageNames(setOf(packageFilter)) }
                .build()
        val latch = CountDownLatch(1)
        val holder = AtomicReference<List<AppFunctionMetadata>?>(null)
        try {
            manager.searchAppFunctions(
                spec,
                executor,
                object : OutcomeReceiver<List<AppFunctionMetadata>, Exception> {
                    override fun onResult(result: List<AppFunctionMetadata>) {
                        holder.set(result)
                        latch.countDown()
                    }

                    override fun onError(error: Exception) {
                        latch.countDown()
                    }
                },
            )
        } catch (e: Exception) {
            return null
        }
        if (!latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return holder.get()
    }

    private fun statesFor(
        manager: AppFunctionManager,
        metadata: List<AppFunctionMetadata>,
    ): Map<String, Boolean> {
        if (metadata.isEmpty()) return emptyMap()
        val names = metadata.map { it.name }
        val latch = CountDownLatch(1)
        val holder = AtomicReference<List<AppFunctionState>?>(null)
        try {
            manager.getAppFunctionStates(
                names,
                executor,
                object : OutcomeReceiver<List<AppFunctionState>, Exception> {
                    override fun onResult(result: List<AppFunctionState>) {
                        holder.set(result)
                        latch.countDown()
                    }

                    override fun onError(error: Exception) {
                        latch.countDown()
                    }
                },
            )
        } catch (e: Exception) {
            return emptyMap()
        }
        if (!latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return emptyMap()
        val states = holder.get() ?: return emptyMap()
        val out = HashMap<String, Boolean>(states.size)
        for (s in states) out[s.functionName.qualifiedId] = s.isEnabled
        return out
    }

    private fun encodeMetadata(
        metadata: AppFunctionMetadata,
        states: Map<String, Boolean>,
        includeSchema: Boolean,
    ): JSONObject {
        val name: AppFunctionName = metadata.name
        val document = metadata.metadataDocument
        val out =
            JSONObject()
                .put("package", name.packageName)
                .put("function_id", name.functionIdentifier)
                .put("enabled", states[name.qualifiedId] ?: true)

        val schema = metadata.schemaMetadata
        out.put(
            "schema",
            JSONObject()
                .put("category", schema?.category ?: JSONObject.NULL)
                .put("name", schema?.name ?: JSONObject.NULL)
                .put("version", if (schema != null) schema.version else JSONObject.NULL),
        )

        val flattened = documentToJson(document)
        out.put("description", flattened.opt("description") ?: JSONObject.NULL)
        if (includeSchema) {
            out.put("parameters", flattened.opt("parameters") ?: JSONObject())
            out.put("response", flattened.opt("response") ?: JSONObject())
        }
        return out
    }

    private fun encodeStaticDocument(document: GenericDocument, includeSchema: Boolean): JSONObject {
        val flattened = documentToJson(document)
        val packageName = firstString(flattened, AppFunctionStaticMetadataHelper.PROPERTY_PACKAGE_NAME)
        val functionId = firstString(flattened, AppFunctionStaticMetadataHelper.PROPERTY_FUNCTION_ID)
        val out =
            JSONObject()
                .put("package", packageName ?: "")
                .put("function_id", functionId ?: document.id)
                .put(
                    "enabled",
                    flattened.opt(AppFunctionStaticMetadataHelper.STATIC_PROPERTY_ENABLED_BY_DEFAULT)
                        ?: true,
                )
                .put(
                    "schema",
                    JSONObject()
                        .put("category", flattened.opt("schemaCategory") ?: JSONObject.NULL)
                        .put("name", flattened.opt("schemaName") ?: JSONObject.NULL)
                        .put("version", flattened.opt("schemaVersion") ?: JSONObject.NULL),
                )
                .put("description", flattened.opt("description") ?: JSONObject.NULL)
        if (includeSchema) {
            out.put("parameters", flattened.opt("parameters") ?: JSONObject())
            out.put("response", flattened.opt("response") ?: JSONObject())
        }
        return out
    }

    private fun firstString(o: JSONObject, key: String): String? {
        val v = o.opt(key) ?: return null
        if (v is String) return v
        if (v is JSONArray && v.length() > 0) return v.optString(0)
        return null
    }

    /** The fallback: the global AppSearch index the platform's apps indexer writes. */
    private fun searchViaAppSearch(packageFilter: String?): List<GenericDocument> {
        val appSearch =
            context.getSystemService(AppSearchManager::class.java) ?: return emptyList()
        val sessionLatch = CountDownLatch(1)
        val sessionHolder = AtomicReference<GlobalSearchSession?>(null)
        try {
            appSearch.createGlobalSearchSession(executor) { result ->
                if (result.isSuccess) sessionHolder.set(result.resultValue)
                sessionLatch.countDown()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        if (!sessionLatch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return emptyList()
        val session = sessionHolder.get() ?: return emptyList()

        val out = ArrayList<GenericDocument>()
        try {
            val spec =
                SearchSpec.Builder()
                    .addFilterNamespaces(
                        AppFunctionStaticMetadataHelper.APP_FUNCTION_STATIC_NAMESPACE
                    )
                    .addFilterPackageNames(AppFunctionStaticMetadataHelper.APP_FUNCTION_INDEXER_PACKAGE)
                    .addFilterSchemas(AppFunctionStaticMetadataHelper.STATIC_SCHEMA_TYPE)
                    .setVerbatimSearchEnabled(true)
                    .setNumericSearchEnabled(true)
                    .setListFilterQueryLanguageEnabled(true)
                    .setResultCountPerPage(200)
                    .build()
            val results: SearchResults = session.search("", spec)
            while (true) {
                val page = nextPage(results) ?: break
                if (page.isEmpty()) break
                for (r in page) out.add(r.genericDocument)
                if (page.size < 200) break
            }
        } catch (e: Exception) {
            // Reported as an empty list; the caller still says which source ran.
        } finally {
            try {
                session.close()
            } catch (e: Exception) {
            }
        }
        if (packageFilter == null) return out
        return out.filter {
            firstString(documentToJson(it), AppFunctionStaticMetadataHelper.PROPERTY_PACKAGE_NAME) ==
                packageFilter
        }
    }

    private fun nextPage(results: SearchResults): List<SearchResult>? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<List<SearchResult>?>(null)
        results.getNextPage(executor) { result ->
            if (result.isSuccess) holder.set(result.resultValue)
            latch.countDown()
        }
        if (!latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        return holder.get()
    }

    /** Executes one function and blocks until it answers, fails or times out. */
    fun execute(
        packageName: String,
        functionId: String,
        params: JSONObject,
        timeoutMs: Long,
    ): ExecuteOutcome {
        val manager =
            manager()
                ?: return ExecuteOutcome(
                    null,
                    JSONObject(),
                    0,
                    AppFunctionException.ERROR_SYSTEM_ERROR,
                    AppFunctionException.ERROR_CATEGORY_SYSTEM,
                    "AppFunctionManager is unavailable",
                    false,
                )

        val request =
            ExecuteAppFunctionRequest.Builder(packageName, functionId)
                .setParameters(jsonToDocument(params))
                .build()

        val latch = CountDownLatch(1)
        val response = AtomicReference<ExecuteAppFunctionResponse?>(null)
        val failure = AtomicReference<AppFunctionException?>(null)
        val signal = CancellationSignal()
        val started = System.currentTimeMillis()

        manager.executeAppFunction(
            request,
            executor,
            signal,
            object : OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> {
                override fun onResult(result: ExecuteAppFunctionResponse) {
                    response.set(result)
                    latch.countDown()
                }

                override fun onError(error: AppFunctionException) {
                    failure.set(error)
                    latch.countDown()
                }
            },
        )

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            signal.cancel()
            return ExecuteOutcome(
                null,
                JSONObject(),
                System.currentTimeMillis() - started,
                null,
                null,
                null,
                true,
            )
        }
        val duration = System.currentTimeMillis() - started

        val error = failure.get()
        if (error != null) {
            return ExecuteOutcome(
                null,
                JSONObject(),
                duration,
                error.errorCode,
                error.errorCategory,
                error.errorMessage,
                false,
            )
        }
        val ok = response.get()
        if (ok == null) {
            return ExecuteOutcome(
                null,
                JSONObject(),
                duration,
                AppFunctionException.ERROR_SYSTEM_ERROR,
                AppFunctionException.ERROR_CATEGORY_SYSTEM,
                "no response",
                false,
            )
        }
        return ExecuteOutcome(
            returnValueToJson(ok.resultDocument),
            extrasToJson(ok.extras),
            duration,
            null,
            null,
            null,
            false,
        )
    }

    private fun returnValueToJson(document: GenericDocument): JSONObject {
        val value = document.getProperty(ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE)
        val out = JSONObject()
        if (value == null) return documentToJson(document)
        val encoded = propertyToJson(value)
        if (encoded is JSONObject) return encoded
        out.put("value", encoded)
        return out
    }

    /**
     * A PendingIntent in the extras is reported and never launched: the human on
     * the other end of the bridge decides what to do with it.
     */
    @Suppress("DEPRECATION")
    private fun extrasToJson(extras: Bundle?): JSONObject {
        val out = JSONObject()
        if (extras == null) return out
        var pendingIntent = false
        for (key in extras.keySet()) {
            val value =
                try {
                    extras.get(key)
                } catch (e: Exception) {
                    null
                }
            when (value) {
                null -> out.put(key, JSONObject.NULL)
                is PendingIntent -> {
                    pendingIntent = true
                    out.put(key, "pending_intent")
                }
                is String, is Int, is Long, is Double, is Float, is Boolean ->
                    out.put(key, value)
                else -> out.put(key, value.javaClass.simpleName)
            }
        }
        out.put("pending_intent", pendingIntent)
        return out
    }

    /** Flattens a GenericDocument into plain JSON. */
    fun documentToJson(document: GenericDocument): JSONObject {
        val out = JSONObject()
        for (name in document.propertyNames) {
            val value = document.getProperty(name) ?: continue
            out.put(name, propertyToJson(value))
        }
        return out
    }

    private fun propertyToJson(value: Any): Any {
        when (value) {
            is Array<*> -> {
                if (value.size == 1) {
                    val only = value[0]
                    return if (only is GenericDocument) documentToJson(only)
                    else only ?: JSONObject.NULL
                }
                val array = JSONArray()
                for (v in value) {
                    array.put(if (v is GenericDocument) documentToJson(v) else v)
                }
                return array
            }
            is LongArray -> {
                if (value.size == 1) return value[0]
                val array = JSONArray()
                for (v in value) array.put(v)
                return array
            }
            is DoubleArray -> {
                if (value.size == 1) return value[0]
                val array = JSONArray()
                for (v in value) array.put(v)
                return array
            }
            is BooleanArray -> {
                if (value.size == 1) return value[0]
                val array = JSONArray()
                for (v in value) array.put(v)
                return array
            }
            is ByteArray -> return value.size
            else -> return value
        }
    }

    /** Builds the request parameter document from the params object. */
    fun jsonToDocument(params: JSONObject): GenericDocument {
        val builder = GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = params.get(key)) {
                is String -> builder.setPropertyString(key, value)
                is Boolean -> builder.setPropertyBoolean(key, value)
                is Int -> builder.setPropertyLong(key, value.toLong())
                is Long -> builder.setPropertyLong(key, value)
                is Double -> builder.setPropertyDouble(key, value)
                is JSONObject -> builder.setPropertyDocument(key, jsonToDocument(value))
                is JSONArray -> setArrayProperty(builder, key, value)
                else -> builder.setPropertyString(key, value.toString())
            }
        }
        return builder.build()
    }

    private fun setArrayProperty(
        builder: GenericDocument.Builder<GenericDocument.Builder<*>>,
        key: String,
        array: JSONArray,
    ) {
        if (array.length() == 0) {
            builder.setPropertyString(key)
            return
        }
        when (array.get(0)) {
            is String -> {
                val values = Array(array.length()) { array.getString(it) }
                builder.setPropertyString(key, *values)
            }
            is Boolean -> {
                val values = BooleanArray(array.length()) { array.getBoolean(it) }
                builder.setPropertyBoolean(key, *values)
            }
            is Int, is Long -> {
                val values = LongArray(array.length()) { array.getLong(it) }
                builder.setPropertyLong(key, *values)
            }
            is Double -> {
                val values = DoubleArray(array.length()) { array.getDouble(it) }
                builder.setPropertyDouble(key, *values)
            }
            is JSONObject -> {
                val values =
                    Array(array.length()) { jsonToDocument(array.getJSONObject(it)) }
                builder.setPropertyDocument(key, *values)
            }
            else -> {
                val values = Array(array.length()) { array.get(it).toString() }
                builder.setPropertyString(key, *values)
            }
        }
    }
}
