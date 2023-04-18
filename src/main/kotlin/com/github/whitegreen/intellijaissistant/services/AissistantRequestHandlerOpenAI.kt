package com.github.whitegreen.intellijaissistant.services

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.util.TextRange
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.commons.io.IOUtils

class AissistantRequestHandlerOpenAI : AissistantRequestHandler {
    companion object {
        val mapper = ObjectMapper().apply {
        }
        val httpClient = HttpClients.createDefault() ?: throw Exception("HttpClient is null")
    }

    private fun simpleChatRequest(
        model: String,
        system: String,
        context: List<Pair<String, String>>,
        prompt: String,
        temperature: Double = 0.0,
    ): String {
        val request = HttpPost("https://api.openai.com/v1/chat/completions").apply {
            addHeader("Authorization", "Bearer ${service<OpenAIToken>().get()}")
            addHeader("Content-Type", "application/json")

            val jsonString = mapper.writeValueAsString(@Suppress("unused") object {
                val model = model
                val messages = listOf(
                    object {
                        val role = "system"
                        val content = system
                    },
                    *context.flatMap {
                        listOf(
                            object {
                                val role = "user"
                                val content = it.first
                            },
                            object {
                                val role = "assistant"
                                val content = it.second
                            }
                        )
                    }.toTypedArray(),
                    object {
                        val role = "user"
                        val content = prompt
                    })
                val temperature = temperature
            })
            thisLogger().warn(jsonString)
            entity = StringEntity(jsonString, "utf-8")
        }
        val response = httpClient.execute(request)
        val responseText = IOUtils.toString(response.entity.content, "utf-8")
        response.close()
        thisLogger().warn(responseText)
        val result = mapper.readValue(responseText, ChatCompletion::class.java)
        return result.choices[0].message.content
    }

    override fun request(request: AissistantRequest): AissistantResponse<StringProvider> {
        val context = mutableListOf<Pair<String, String>>()
        var prev = request.prev
        while (prev != null) {
            when (val response = prev.second) {
                is AissistantResponse.Error -> {}
                is AissistantResponse.Text -> {
                    context.add(Pair(prev.first.query, response.text))
                }

                else -> {
                    throw Exception("UNREACHABLE")
                }
            }
            prev = prev.first.prev
        }
        context.reverse()
        val indicator = ProgressIndicatorProvider.getGlobalProgressIndicator()
        val pair = request.activeEditor?.let { editor ->
            ApplicationManager.getApplication().runReadAction<_> {
                var selectionStart = Int.MAX_VALUE
                var selectionEnd = 0
                var selectionText = ""
                editor.caretModel.runForEachCaret { caret ->
                    val start = caret.selectionStart
                    val end = caret.selectionEnd
                    selectionStart = minOf(selectionStart, start)
                    selectionEnd = maxOf(selectionEnd, end)
                    val text = editor.document.getText(TextRange(start, end))
                    selectionText += text + "\n"
                }
                if (selectionStart >= selectionEnd) {
                    val focusDocumentStart = maxOf(editor.caretModel.primaryCaret.selectionStart - 1000, 0)
                    val focusDocument = editor.document.getText(
                        TextRange(
                            focusDocumentStart,
                            minOf(focusDocumentStart + 2000, editor.document.textLength)
                        )
                    )
                    return@runReadAction Pair(
                        focusDocument,
                        null,
                    )
                }
                val selectionLength = selectionEnd - selectionStart
                val focusDocumentStart = maxOf(0, selectionStart - maxOf(2000 - selectionLength, 0) / 2)
                val focusDocument = editor.document.getText(
                    TextRange(
                        focusDocumentStart,
                        minOf(focusDocumentStart + 2000, editor.document.textLength)
                    )
                )
                return@runReadAction Pair(
                    focusDocument,
                    selectionText.trim().apply { substring(0, minOf(length, 2000)) }.ifEmpty { null },
                )
            }
        }
        val focusDocument = pair?.first
        val selectionText = pair?.second
        val classifier = simpleChatRequest(
            service<AissistantLLMSelect>().messageClassifier(), """ユーザからのメッセージが以下のどれに分類されるのかを返答してください
返答は選択肢の後ろにある識別子(edit, question, talks)のうちどれかのみで行い、メッセージへの返答や識別子以外の発言は行わないでください
- ファイルを編集、置き換え、追記等を必要とする命令: edit
- ファイルの内容に関する質問であり、ファイルの編集や追記、置き換え等が必要とされていないもの: question
- 以上のどれとも関係ない雑談: talks${
                focusDocument?.let {
                    """

ただし、現在ユーザが注目しているファイルは以下のものです
```
$it
```
"""
                } ?: ""
            }${
                selectionText?.let {
                    """また、ユーザはファイルのうち以下の部分を選択しています
```
$it
```
"""
                } ?: ""
            }""", listOf(), """以下のメッセージがどれに分類されるのかを識別子のみで返答してください
```
${request.query}
```
""")
        indicator?.checkCanceled()
        indicator?.fraction = 0.5
        thisLogger().warn("request class: " + classifier.trim())
        when (classifier.trim()) {
            "edit" -> {
                if (focusDocument == null) return AissistantResponse.Error("no focus document")
                val systemPrompt = """ユーザからの命令によってファイルを編集、もしくはコードに対する質問に回答してください。
ユーザからの命令に編集や質問の対象が明示されていない場合、選択している箇所のみを対象にするものと解釈してください。

ただし、現在ユーザが注目しているファイルは以下のものです。

ただし、現在ユーザが注目しているファイルは以下のものです
```
$focusDocument
```
${
                    selectionText?.let {
                        """また、ユーザはファイルのうち以下の部分を選択しています
```
$it
```
"""
                    } ?: ""
                }"""
                val userPrompt = request.query
                val responder = object : AissistantCodeGenerator {
                    var temperature = 0.0
                    val candidate = mutableListOf<String>()

                    override fun candidates(): List<String> {
                        return candidate
                    }

                    override fun addCandidate() {
                        val result = simpleChatRequest(
                            service<AissistantLLMSelect>().codeGenerator(),
                            systemPrompt,
                            listOf(),
                            userPrompt,
                            temperature,
                        )
                        for (matchResult in "```.*\n((?:.|\n)+)\n```".toRegex().findAll(result)) {
                            candidate.add(matchResult.groupValues[1])
                        }
                    }
                }
                responder.addCandidate()
                responder.temperature = 0.5
                return AissistantResponse.EditCode(responder)
            }

            "question" -> {
                val response = simpleChatRequest(service<AissistantLLMSelect>().questionResponder(),
                    """ユーザからの質問に対して回答してください""", context, """${
                        focusDocument?.let {
                            """現在ユーザが注目しているファイルは以下のものです
```
$it
```
"""
                        } ?: ""
                    }
${
                        selectionText?.let {
                            """また、ユーザはファイルのうち以下の部分を選択しています
```
$it
```
"""
                        } ?: ""
                    }
${request.query}
""")
                return AissistantResponse.Text(ImmediateStringProvider(response))
            }

            "talks" -> {
                val response = simpleChatRequest(
                    service<AissistantLLMSelect>().messageResponder(),
                    """ユーザからの会話に返答してください""",
                    context,
                    request.query
                )
                return AissistantResponse.Text(ImmediateStringProvider(response))
            }

            else -> {
                return AissistantResponse.Error("Invalid classifier response: ${classifier.trim()}")
            }
        }
    }
}

@Suppress("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
class ChatCompletion {
    val id: String = ""
    val `object`: String = ""
    val created: Int = 0
    val choices: List<Choice> = listOf()
    val usage: Usage = Usage()
}

@Suppress("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
class Choice {
    val index: Int = 0
    val message: Message = Message()
    val finish_reason: String = ""
}

@Suppress("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
class Message {
    val role: String = ""
    val content: String = ""
}

@Suppress("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
class Usage {
    val prompt_tokens: Int = 0
    val completion_tokens: Int = 0
    val total_tokens: Int = 0
}