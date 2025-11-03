package vision.salient.contacts

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.people.v1.PeopleService
import com.google.api.services.people.v1.model.Person
import com.google.api.services.people.v1.model.SearchResponse
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import org.slf4j.LoggerFactory
import vision.salient.config.ContactsConfig
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class GoogleContactsClient(
    private val config: ContactsConfig,
) {

    private val logger = LoggerFactory.getLogger(GoogleContactsClient::class.java)
    private val cache = ConcurrentHashMap<String, ContactInfo>()
    private val service: PeopleService? = initialiseService()

    fun lookupByPhone(phone: String): ContactInfo? {
        val normalized = normalizePhoneDigits(phone)
        if (normalized.isEmpty()) return null
        cache[normalized]?.let { return it }
        val people = service ?: return null
        return runCatching {
            val response: SearchResponse = people.people().searchContacts()
                .setQuery(normalized)
                .setReadMask("names,phoneNumbers,metadata")
                .execute()
            val match = response.results?.firstOrNull()?.person ?: return null
            val info = extractInfo(match, normalized)
            if (info != null) {
                cache[normalized] = info
            }
            info
        }.onFailure {
            logger.warn("Contacts lookup failed for {}: {}", normalized, it.message)
        }.getOrNull()
    }

    private fun extractInfo(person: Person, fallbackPhone: String): ContactInfo? {
        val resourceName = person.resourceName ?: return null
        val displayName = person.names?.firstOrNull()?.displayName ?: fallbackPhone
        val phoneEntry = person.phoneNumbers?.firstOrNull { !it.value.isNullOrBlank() }
        val number = phoneEntry?.value ?: fallbackPhone
        return ContactInfo(
            resourceName = resourceName,
            displayName = displayName,
            phoneNumber = normalizePhoneDigits(number)
        )
    }

    private fun initialiseService(): PeopleService? {
        val secretPath = config.clientSecretPath ?: return null
        val tokenPath = config.tokenPath ?: return null
        return runCatching {
            runCatching { Files.newInputStream(secretPath).use { /* existence check */ } }
                .onFailure { logger.warn("Unable to read Google Contacts client secret at {}: {}", secretPath, it.message) }
            Files.newInputStream(tokenPath).use { tokenStream ->
                val credentials = UserCredentials.fromStream(tokenStream)
                val adapter = HttpCredentialsAdapter(credentials)
                PeopleService.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    adapter
                )
                    .setApplicationName("WhatsLiberation")
                    .build()
            }
        }.onFailure {
            logger.error("Unable to initialise Google Contacts client: {}", it.message)
        }.getOrNull()
    }

}

private fun normalizePhoneDigits(value: String): String = value.filter { it.isDigit() }

data class ContactInfo(
    val resourceName: String,
    val displayName: String,
    val phoneNumber: String,
)
