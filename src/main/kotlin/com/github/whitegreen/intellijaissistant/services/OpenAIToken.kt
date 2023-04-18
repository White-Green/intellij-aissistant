package com.github.whitegreen.intellijaissistant.services

import com.github.whitegreen.intellijaissistant.settings.AissistantSettings
import com.intellij.openapi.components.service

class OpenAIToken{
    fun get(): String {
        return service<AissistantSettings>().openAIApiKey
    }
}