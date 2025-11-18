package nl.joostd.passkeyapp

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialCustomException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import kotlinx.coroutines.launch
import nl.joostd.passkeyapp.ui.theme.PasskeyAppTheme
import org.json.JSONObject
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PasskeyAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PasskeyScreen()
                }
            }
        }
    }
}

@Composable
fun PasskeyScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Ready") }

    // IMPORTANT: Replace this with your actual Relying Party ID
    val relyingPartyDomain = "passkey-app.joostd.nl";
    Log.d("PasskeyDemo", "RP ID: $relyingPartyDomain")

    // Lazily create a client instance of  CredentialManager
    val credentialManager = remember { CredentialManager.create(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = statusText,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Button(
            onClick = {
                coroutineScope.launch {
                    registerPasskey(
                        context = context,
                        credentialManager = credentialManager,
                        relyingPartyDomain = relyingPartyDomain,
                        updateStatus = { statusText = it }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Register Passkey")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                coroutineScope.launch {
                    authenticateWithPasskey(
                        context = context,
                        credentialManager = credentialManager,
                        relyingPartyDomain = relyingPartyDomain,
                        updateStatus = { statusText = it }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Authenticate with Passkey")
        }
    }
}


@Preview(showBackground = true)
@Composable
fun PasskeyScreenPreview() {
    PasskeyAppTheme {
        PasskeyScreen()
    }
}

private suspend fun registerPasskey(
    context: Context,
    credentialManager: CredentialManager,
    relyingPartyDomain: String,
    updateStatus: (String) -> Unit
) {
    // TODO: retrieve challenge from server.
    val challenge = ByteArray(32).apply { SecureRandom().nextBytes(this) }
    val requestJson = """
    {
      "challenge": "${Base64.encodeToString(challenge, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)}",
      "rp": { "id": "$relyingPartyDomain", "name": "Demo App" },
      "user": {
        "id": "safeBASE64URLencoded", "name": "user@example.com", "displayName": "Test User"
      },
      "pubKeyCredParams": [ { "type": "public-key", "alg": -7 }, { "type": "public-key", "alg": -257 } ],
      "authenticatorSelection": { "authenticatorAttachment": "platform", "residentKey": "required" }
    }
    """.trimIndent()

    // https://developer.android.com/identity/sign-in/credential-manager#create-passkey
    val request = CreatePublicKeyCredentialRequest(requestJson)
    // TODO, add preferImmediatelyAvailableCredentials = true ?

    // TODO: coroutinescope?
    try {
        updateStatus("Registering...")
        // Note: createCredential() call is a suspend function, so this needs to be called through a coroutineScope/another suspend function.
        val result = credentialManager.createCredential(context, request)
        val responseJson =
            result.data.getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON")

        Log.d("PasskeyRegister", "Success: $responseJson")
        updateStatus("Registration Successful!")
        Toast.makeText(context, "Registration Success!", Toast.LENGTH_SHORT).show()
        // TODO: post responseJson to server for verification.
        val id = JSONObject(responseJson!!).getString("id")
        Log.d("PasskeyAuth", "id: $id")

    } catch (e: CreateCredentialException) {
        Log.e("PasskeyRegister", "Error: ${e.message}", e)
        updateStatus("Registration Failed: ${e.message?.substringBefore("\n")}")
        Toast.makeText(context, "Registration Failed", Toast.LENGTH_SHORT).show()
        when (e) {
            // https://developer.android.com/identity/sign-in/credential-manager-troubleshooting-guide
            is CreatePublicKeyCredentialDomException -> {
                Log.e("PasskeyRegister", "Error: ${e.message}", e)
                // Handle the passkey DOM errors thrown according to the
                // WebAuthn spec.
            }

            is CreateCredentialCancellationException -> {
                Log.e("PasskeyRegister", "User cancelled: ${e.message}", e)
                // The user intentionally canceled the operation and chose not
                // to register the credential.
            }

            is CreateCredentialInterruptedException -> {
                Log.e("PasskeyRegister", "Error (retry?): ${e.message}", e)
                // Retry-able error. Consider retrying the call.
            }

            is CreateCredentialProviderConfigurationException -> {
                Log.e("PasskeyRegister", "Error: ${e.message}", e)
                // Your app is missing the provider configuration dependency.
                // Most likely, you're missing the
                // "credentials-play-services-auth" module.
            }

            is CreateCredentialCustomException -> {
                Log.e("PasskeyRegister", "Error (unexpected custom cred?): ${e.message}", e)
                // You have encountered an error from a 3rd-party SDK. If you
                // make the API call with a request object that's a subclass of
                // CreateCustomCredentialRequest using a 3rd-party SDK, then you
                // should check for any custom exception type constants within
                // that SDK to match with e.type. Otherwise, drop or log the
                // exception.
            }
            else -> Log.w("PasskeyRegister", "Unexpected exception type ${e::class.java.name}")

        }
    }
}

private suspend fun authenticateWithPasskey(
    context: Context,
    credentialManager: CredentialManager,
    relyingPartyDomain: String,
    updateStatus: (String) -> Unit
) {
    // TODO: retrieve challenge from server.
    val challenge = ByteArray(32).apply { SecureRandom().nextBytes(this) }
    // TODO: preferImmediatelyAvailableCredentials = true (default false)?
    val requestJson = """
    {
      "challenge": "${Base64.encodeToString(challenge, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)}",
      "rpId": "$relyingPartyDomain",
      "userVerification": "required",
      "allowCredentials": []
    }
    """.trimIndent()

    // https://developer.android.com/identity/sign-in/credential-manager#sign-in
    val getPublicKeyCredentialOption = GetPublicKeyCredentialOption(requestJson)
    val request = GetCredentialRequest(listOf(getPublicKeyCredentialOption))

    // TODO: coroutineScope { ... } ?
    try {
        updateStatus("Authenticating...")
        val result : GetCredentialResponse = credentialManager.getCredential(context, request)
        val credential = result.credential
        when (credential) {
            is PublicKeyCredential -> {
                val responseJson = credential.authenticationResponseJson
                Log.d("PasskeyAuth", "Success: $responseJson")
                // TODO: send responseJson to server for verification.
                val id = JSONObject(responseJson).getString("id")
                Log.d("PasskeyAuth", "id: $id")
                updateStatus("Authentication Successful!")
                Toast.makeText(context, "Authentication Success!", Toast.LENGTH_SHORT).show()
                val userHandle = JSONObject(responseJson).getJSONObject("response").getString("userHandle")
                Log.d("PasskeyAuth", "userHandle: $userHandle")

            }
            else -> {
                // Catch any unrecognized credential type here.
                Log.e("PasskeyAuth", "Unexpected type of credential")
                // TODO: throw exception?
            }
        }

    } catch (e: GetCredentialException) {
        Log.e("PasskeyAuth", "Error: ${e.message}", e)
        updateStatus("Authentication Failed: ${e.message?.substringBefore("\n")}")
        Toast.makeText(context, "Authentication Failed", Toast.LENGTH_SHORT).show()
        when (e) {
            is NoCredentialException -> {
                Log.e("PasskeyAuth", "Error (no credential): ${e.message}", e)
            }
            is GetCredentialCustomException -> {
                Log.e("PasskeyAuth", "Error (unexpected custom cred?): ${e.message}", e)
            }
            else -> Log.w("PasskeyAuth", "Unexpected exception type ${e::class.java.name}")
        }
    }
}