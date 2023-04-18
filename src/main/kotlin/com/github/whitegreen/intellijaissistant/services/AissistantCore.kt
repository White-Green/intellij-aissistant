package com.github.whitegreen.intellijaissistant.services

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

interface StringProvider {
    // null => Termination
    fun addConsumer(consumer: (String?) -> Unit)
    fun collectString(): String
}

class ImmediateStringProvider(private val value: String) : StringProvider {
    override fun addConsumer(consumer: (String?) -> Unit) {
        consumer(value)
        consumer(null)
    }

    override fun collectString(): String {
        return value
    }
}

interface AissistantCodeGenerator {
    fun candidates(): List<String>
    fun addCandidate()
}

data class AissistantRequest(
    val prev: Pair<AissistantRequest, AissistantResponse<String>>?,
    val query: String,
    val activeEditor: Editor?,
    val activeProject: Project?
)

sealed class AissistantResponse<S> private constructor(val terminateConversation: Boolean) {
    data class Text<S>(val text: S) : AissistantResponse<S>(false)
    data class EditCode<S>(val code: AissistantCodeGenerator) : AissistantResponse<S>(true)
    data class Error<S>(val message: String) : AissistantResponse<S>(false)
}

interface AissistantCore {
    fun request(request: AissistantRequest)
}

interface AissistantRequestHandler {
    fun request(request: AissistantRequest): AissistantResponse<StringProvider>
}

interface AissistantResponseHandler {
    fun response(request: AissistantRequest, response: AissistantResponse<StringProvider>)
}

class AissistantCoreImpl : AissistantCore {
    override fun request(request: AissistantRequest) {
        ProgressManager.getInstance().run(object: Task.Backgroundable(request.activeProject, "Aissistant request") {
            override fun run(indicator: ProgressIndicator) {
                val response = service<AissistantRequestHandler>().request(request)
                service<AissistantResponseHandler>().response(request, response)
            }
        })
    }
}
