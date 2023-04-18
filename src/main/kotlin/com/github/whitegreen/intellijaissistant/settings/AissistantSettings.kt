package com.github.whitegreen.intellijaissistant.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.annotations.Nls
import javax.swing.JComponent


enum class LLMSetting {
    ONLY_GPT3_5_TURBO,
    USE_GPT4_FOR_GENERATE_CODE,
}

@State(
    name = "com.github.whitegreen.intellijaissistant.settings.AissistantSettings",
    storages = [Storage("Aissistant.xml")]
)
class AissistantSettings : PersistentStateComponent<AissistantSettings> {
    var openAIApiKey: String = ""
    var llmSetting: LLMSetting = LLMSetting.ONLY_GPT3_5_TURBO

    override fun getState(): AissistantSettings {
        return this
    }

    override fun loadState(state: AissistantSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): AissistantSettings {
            return service()
        }
    }
}

class AissistantSettingsComponent {
    val panel: DialogPanel
    private val apiKeyField = JBPasswordField()
    private lateinit var llmSettingField: Cell<ComboBox<LLMSetting>>

    init {
        panel = panel {
            row("LLM Setting:") {
                llmSettingField = comboBox(listOf(LLMSetting.ONLY_GPT3_5_TURBO, LLMSetting.USE_GPT4_FOR_GENERATE_CODE))
            }
            row("OpenAI API Key:") {
                cell(apiKeyField)
            }
        }
    }

    var openAIApiKey: String
        get() = apiKeyField.password.joinToString("")
        set(value) {
            apiKeyField.text = value
        }

    var llmSetting: LLMSetting
        get() = llmSettingField.component.selectedItem as LLMSetting
        set(value) {
            llmSettingField.component.selectedItem = value
        }
}

class AppSettingsConfigurable : Configurable {
    private var mySettingsComponent: AissistantSettingsComponent? = null

    override fun getDisplayName(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "Aissistant Plugin Settings"
    }

    override fun createComponent(): JComponent {
        mySettingsComponent = AissistantSettingsComponent()
        return mySettingsComponent!!.panel
    }

    override fun isModified(): Boolean {
        val settings = AissistantSettings.getInstance()
        var modified = mySettingsComponent!!.openAIApiKey != settings.openAIApiKey
        modified = modified or (mySettingsComponent!!.llmSetting != settings.llmSetting)
        return modified
    }

    override fun apply() {
        val settings = AissistantSettings.getInstance()
        settings.openAIApiKey = mySettingsComponent!!.openAIApiKey
        settings.llmSetting = mySettingsComponent!!.llmSetting
    }

    override fun reset() {
        val settings = AissistantSettings.getInstance()
        mySettingsComponent!!.openAIApiKey = settings.openAIApiKey
        mySettingsComponent!!.llmSetting = settings.llmSetting
    }

    override fun disposeUIResources() {
        mySettingsComponent = null
    }
}