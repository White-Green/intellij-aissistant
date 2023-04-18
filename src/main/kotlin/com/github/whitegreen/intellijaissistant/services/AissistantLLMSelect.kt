package com.github.whitegreen.intellijaissistant.services

import com.github.whitegreen.intellijaissistant.settings.AissistantSettings
import com.github.whitegreen.intellijaissistant.settings.LLMSetting
import com.intellij.openapi.components.service

class AissistantLLMSelect {
    fun messageClassifier(): String {
        return "gpt-3.5-turbo"
    }

    fun messageResponder(): String {
        return "gpt-3.5-turbo"
    }

    fun questionResponder(): String {
        return "gpt-3.5-turbo"
    }

    fun codeGenerator(): String {
        return if (service<AissistantSettings>().llmSetting == LLMSetting.ONLY_GPT3_5_TURBO) "gpt-3.5-turbo" else "gpt-4"
    }
}