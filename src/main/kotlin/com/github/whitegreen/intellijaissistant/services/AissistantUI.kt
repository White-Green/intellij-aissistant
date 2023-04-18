package com.github.whitegreen.intellijaissistant.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface AissistantToolWindow {
    fun getProject(): Project
    fun onRecordingStarted()
    fun onRecordingStopped()
    fun addTextFromOtherSource(text: String)
    fun clearResponse()
    fun setTextResponse(text: StringProvider)
    fun setEditCandidateResponse(candidate: List<String>)
    fun errorResponse(message: String)
}

interface AissistantUI {
    fun registerToolWindow(toolWindow: AissistantToolWindow)
    fun unregisterToolWindow(toolWindow: AissistantToolWindow)
    fun addTextForActiveUI(text: String)
    fun startRecording()
    fun stopRecording()
    fun onRecordingStarted()
    fun onRecordingStopped()
    fun sendRequest(requestText: String)
    fun requestAdditionalCandidate()
    fun applyEditCandidate(index: Int)
}

interface AissistantMicrophoneInput {
    fun startRecording()
    fun stopRecording()
}

class AissistantUIImpl : AissistantUI, AissistantResponseHandler {
    companion object {
        private val allToolWindow = HashMap<Project, AissistantToolWindow>()
        private val codeGeneratorResponse = HashMap<Project, AissistantCodeGenerator>()
        private val lockToolWindow = ReentrantLock(true)
    }

    override fun addTextForActiveUI(text: String) {
        println("addTextForActiveUI: $text")
        lockToolWindow.withLock {
            service<ActiveResourceService>().activeProject()?.let {
                allToolWindow[it]?.addTextFromOtherSource(text)
            }
        }
    }

    override fun onRecordingStarted() {
        lockToolWindow.withLock {
            allToolWindow.values.forEach { it.onRecordingStarted() }
        }
    }

    override fun onRecordingStopped() {
        lockToolWindow.withLock {
            allToolWindow.values.forEach { it.onRecordingStopped() }
        }
    }

    override fun registerToolWindow(toolWindow: AissistantToolWindow) {
        lockToolWindow.withLock {
            allToolWindow[toolWindow.getProject()] = toolWindow
        }
    }

    override fun unregisterToolWindow(toolWindow: AissistantToolWindow) {
        lockToolWindow.withLock {
            allToolWindow.remove(toolWindow.getProject())
        }
    }

    override fun startRecording() {
        service<AissistantMicrophoneInput>().startRecording()
    }

    override fun stopRecording() {
        service<AissistantMicrophoneInput>().stopRecording()
    }

    override fun sendRequest(requestText: String) {
        allToolWindow[service<ActiveResourceService>().activeProject()]?.clearResponse()
        service<AissistantCore>().request(
            AissistantRequest(
                null,
                requestText,
                service<ActiveResourceService>().activeEditor(),
                service<ActiveResourceService>().activeProject()
            )
        )
    }

    override fun requestAdditionalCandidate() {
        lockToolWindow.withLock {
            service<ActiveResourceService>().activeProject()?.let { project ->
                val toolWindow = allToolWindow[project] ?: return@let
                codeGeneratorResponse[project]?.let {
                    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Aissistant request") {
                        override fun run(indicator: ProgressIndicator) {
                            it.addCandidate()
                            toolWindow.setEditCandidateResponse(it.candidates())
                        }
                    })
                }
            }
        }
    }

    override fun applyEditCandidate(index: Int) {
        val (candidate, editor) = lockToolWindow.withLock {
            Pair(
                codeGeneratorResponse[service<ActiveResourceService>().activeProject()] ?: return,
                service<ActiveResourceService>().activeEditor() ?: return,
            )
        }
        val codeForApply = candidate.candidates()[index]

        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.replaceString(
                editor.caretModel.primaryCaret.selectionStart,
                editor.caretModel.primaryCaret.selectionEnd,
                codeForApply
            )
        }
    }

    override fun response(request: AissistantRequest, response: AissistantResponse<StringProvider>) {
        when (response) {
            is AissistantResponse.Text -> {
                lockToolWindow.withLock {
                    allToolWindow[request.activeProject]?.setTextResponse(response.text)
                }
            }

            is AissistantResponse.EditCode -> {
                lockToolWindow.withLock {
                    request.activeProject?.let {
                        codeGeneratorResponse[it] = response.code
                    }
                    allToolWindow[request.activeProject]?.setEditCandidateResponse(response.code.candidates())
                }
            }

            is AissistantResponse.Error -> {
                lockToolWindow.withLock {
                    allToolWindow[request.activeProject]?.errorResponse(response.message)
                }
            }
        }
    }
}