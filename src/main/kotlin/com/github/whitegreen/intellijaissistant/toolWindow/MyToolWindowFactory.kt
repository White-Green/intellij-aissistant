package com.github.whitegreen.intellijaissistant.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.github.whitegreen.intellijaissistant.services.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.HorizontalAlign
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.JButton
import javax.swing.JLabel


class MyToolWindowFactory : ToolWindowFactory {

    init {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    private val contentFactory = ContentFactory.SERVICE.getInstance()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(toolWindow)
        val content = contentFactory.createContent(myToolWindow.content, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class MyToolWindow(toolWindow: ToolWindow) : /*JBPanel<MyToolWindow>(),*/ AissistantToolWindow {

        private val project = toolWindow.project
        private val activateOnClick = object : MouseListener {
            override fun mouseClicked(e: MouseEvent?) {}
            override fun mouseReleased(e: MouseEvent?) {}
            override fun mouseEntered(e: MouseEvent?) {}
            override fun mouseExited(e: MouseEvent?) {}
            override fun mousePressed(e: MouseEvent?) {
                service<ActiveResourceService>().activateProject(project)
            }
        }

        private var enabledMicrophone = false

        private lateinit var microphoneButton: Cell<JButton>
        private lateinit var textArea: Cell<JBTextArea>
        private lateinit var label: Cell<JBTextArea>
        private val textAreaRow = mutableListOf<Row>()
        private val candidateSelectRow = mutableListOf<Row>()
        private lateinit var candidateIndexLabel: Cell<JLabel>

        private var candidateIndex = 0
        private var candidates = listOf<String>()

        val content = JBScrollPane(panel {
            row {
                microphoneButton = button("Enable Microphone") {
                    service<ActiveResourceService>().activateProject(project)
                    microphoneButton.component.isEnabled = false
                    if (enabledMicrophone) service<AissistantUI>().stopRecording()
                    else service<AissistantUI>().startRecording()
                }
            }
            row {
                textArea = textArea()
                    .horizontalAlign(HorizontalAlign.FILL)
                    .applyToComponent {
                        lineWrap = true
                        addMouseListener(activateOnClick)
                    }
            }
            candidateSelectRow.add(row {
                button("<") {
                    service<ActiveResourceService>().activateProject(project)
                    candidateIndex--
                    if (candidateIndex < 0) candidateIndex = candidates.size - 1
                    updateCandidateSelect()
                }
                candidateIndexLabel = label("1/1")
                button(">") {
                    service<ActiveResourceService>().activateProject(project)
                    candidateIndex++
                    if (candidateIndex >= candidates.size) candidateIndex = 0
                    updateCandidateSelect()
                }
                visible(false)
            })
            candidateSelectRow.add(row {
                button("+") {
                    service<ActiveResourceService>().activateProject(project)
                    service<AissistantUI>().requestAdditionalCandidate()
                }
                button("Apply") {
                    service<ActiveResourceService>().activateProject(project)
                    service<AissistantUI>().applyEditCandidate(candidateIndex)
                }
                visible(false)
            })
            textAreaRow.add(row {
                label = textArea()
                    .horizontalAlign(HorizontalAlign.FILL)
                    .applyToComponent {
                        lineWrap = true
                        isOpaque = false
                        isEditable = false
                    }
                visible(false)
            })
            row {
                button("SEND") {
                    service<ActiveResourceService>().activateProject(project)
                    service<AissistantUI>().sendRequest(textArea.component.text)
                    println("SEND Clicked")
                }
            }
        }).apply {
            this.addMouseListener(activateOnClick)
        }

        init {
            service<AissistantUI>().registerToolWindow(this)
        }

        private fun updateCandidateSelect() {
            if (candidateIndex < candidates.size) {
                label.component.text = candidates[candidateIndex]
                candidateIndexLabel.component.text = "${candidateIndex + 1}/${candidates.size}"
            }
        }

        override fun getProject(): Project {
            return project
        }

        override fun onRecordingStarted() {
            ApplicationManager.getApplication().invokeLater {
                enabledMicrophone = true
                microphoneButton.applyToComponent {
                    text = "disable microphone"
                    isEnabled = true
                }
            }
        }

        override fun onRecordingStopped() {
            enabledMicrophone = false
            microphoneButton.applyToComponent {
                text = "enable microphone"
                isEnabled = true
            }
        }

        override fun addTextFromOtherSource(text: String) {
            println("addTextFromOtherSource: $text")
            ApplicationManager.getApplication().invokeLater {
                textArea.component.text += text
            }
        }

        override fun clearResponse() {
            ApplicationManager.getApplication().invokeLater {
                textAreaRow.forEach { it.visible(false) }
                candidateSelectRow.forEach { it.visible(false) }
                label.component.text = ""
                candidates = listOf()
                candidateIndex = 0
            }
        }

        override fun setTextResponse(text: StringProvider) {
            val textValue = text.collectString()
            ApplicationManager.getApplication().invokeLater {
                textAreaRow.forEach { it.visible(true) }
                candidateSelectRow.forEach { it.visible(false) }
                label.component.text = textValue
            }
        }

        override fun setEditCandidateResponse(candidate: List<String>) {
            ApplicationManager.getApplication().invokeLater {
                textAreaRow.forEach { it.visible(true) }
                candidateSelectRow.forEach { it.visible(true) }
                candidates = candidate
                candidateIndex = candidate.size - 1
                updateCandidateSelect()
            }
        }

        override fun errorResponse(message: String) {
            println("error: $message")
        }
    }
}
