package dev.nocloud.ai.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores API keys in the IDE's password safe rather than in the plugin XML.
 *
 * Local servers usually do not need a key at all, but proxies in front of them often do.
 */
object Credentials {
    private const val SERVICE = "NoCloudAI"

    fun get(key: String): String? =
        PasswordSafe.instance.getPassword(CredentialAttributes(SERVICE, key))?.takeIf { it.isNotBlank() }

    fun set(key: String, value: String?) {
        PasswordSafe.instance.setPassword(
            CredentialAttributes(SERVICE, key),
            value?.takeIf { it.isNotBlank() },
        )
    }

    const val KEY_OLLAMA = "ollamaApiKey"
    const val KEY_LLAMA_CPP = "llamaCppApiKey"
}
