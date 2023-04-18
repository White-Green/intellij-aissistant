package com.github.whitegreen.intellijaissistant.services

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface ActiveResourceService {
    fun activateProject(project: Project)
    fun activateEditor(editor: Editor)
    fun activeProject(): Project?
    fun activeEditor(): Editor?
}

class ActiveResourceServiceImpl : ActiveResourceService {
    companion object {
        var activeProject: Project? = null
        var activeEditor: Editor? = null
        val lock = ReentrantLock(true)
    }

    override fun activateProject(project: Project) {
        lock.withLock {
            activeProject = project
            if (project != activeEditor?.project) activeEditor = null
        }
    }

    override fun activateEditor(editor: Editor) {
        lock.withLock {
            activeEditor = editor
            activeProject = editor.project
        }
    }

    override fun activeProject(): Project? {
        lock.withLock {
            return activeProject
        }
    }

    override fun activeEditor(): Editor? {
        lock.withLock {
            return activeEditor
        }
    }
}