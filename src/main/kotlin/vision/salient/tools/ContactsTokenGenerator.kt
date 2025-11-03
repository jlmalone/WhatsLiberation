package vision.salient.tools

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

private val JSON_FACTORY = GsonFactory.getDefaultInstance()
private val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()
private val SCOPES = listOf(
    "https://www.googleapis.com/auth/contacts.readonly",
    "https://www.googleapis.com/auth/drive"
)

fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: runContactsAuth <path-to-client-secret.json> <output-token.json>")
        return
    }

    val clientSecretPath = Path.of(args[0]).toAbsolutePath()
    val outputPath = Path.of(args[1]).toAbsolutePath()

    require(Files.exists(clientSecretPath)) {
        "Client secret file not found at $clientSecretPath"
    }

    val clientSecrets = clientSecretPath.inputStream().use {
        GoogleClientSecrets.load(JSON_FACTORY, it.reader())
    }

    val dataStoreDir = outputPath.parent?.also { Files.createDirectories(it) }
        ?: error("Output path must reside within a directory")

    val flow = GoogleAuthorizationCodeFlow.Builder(
        HTTP_TRANSPORT,
        JSON_FACTORY,
        clientSecrets,
        SCOPES
    )
        .setDataStoreFactory(FileDataStoreFactory(dataStoreDir.toFile()))
        .setAccessType("offline")
        .build()

    val receiver = LocalServerReceiver.Builder().setPort(0).build()
    val authApp = AuthorizationCodeInstalledApp(flow, receiver)
    val credential: Credential = authApp.authorize("user")

    val refreshToken = credential.refreshToken ?: error("Authorization did not return a refresh token")
    val tokenJson = """
        {
          "type": "authorized_user",
          "client_id": "${escape(clientSecrets.details.clientId)}",
          "client_secret": "${escape(clientSecrets.details.clientSecret)}",
          "refresh_token": "${escape(refreshToken)}"
        }
    """.trimIndent()

    Files.writeString(outputPath, tokenJson)
    println("Token saved to $outputPath")
}

private fun escape(value: String?): String {
    if (value == null) return ""
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
