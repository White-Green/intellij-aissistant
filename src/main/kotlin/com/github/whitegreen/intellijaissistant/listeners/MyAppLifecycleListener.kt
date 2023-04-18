package com.github.whitegreen.intellijaissistant.listeners

import com.github.whitegreen.intellijaissistant.services.ActiveResourceService
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.util.Disposer

class MyAppLifecycleListener : AppLifecycleListener {
    init {
        thisLogger().warn("initialization of MyRecentProjectsManager")
    }

    private val dispose: Disposable = Disposable { }

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        thisLogger().warn("appFrameCreated")
        EditorFactory.getInstance()
            .addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor
                    editor.caretModel.addCaretListener(object : CaretListener {
                        override fun caretPositionChanged(event: CaretEvent) {
                            service<ActiveResourceService>().activateEditor(event.editor)
                        }
                    })
                    editor.addEditorMouseListener(object : EditorMouseListener {
                        override fun mousePressed(event: EditorMouseEvent) {
                            service<ActiveResourceService>().activateEditor(event.editor)
                        }
                    })
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    println("editorReleased")
                }
            }, dispose)
    }

    override fun appClosing() {
        println("appClosing")
        Disposer.dispose(dispose)
    }
}