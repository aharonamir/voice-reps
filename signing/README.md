# Persistent Android signing key

`voice-reps.keystore.enc` is the encrypted PKCS#12 signing keystore. The decryption passphrase is stored in Amir's vault item `voice-reps signing keystore passphrase`; do not put it in Git, shell history, logs, chat, or build artifacts. The keystore alias is `voice-reps`, and its store and key passwords are the same vault-generated passphrase.

For a future authorized build agent:

1. Retrieve the passphrase from the approved vault item using the vault's sandbox-use flow. Never print it.
2. Decrypt into a private temporary directory (mode 700) without putting the passphrase in command-line arguments or shell history. For example, use a short script that reads the passphrase from the vault's private file and supplies it to OpenSSL on stdin:

   ```sh
   umask 077
   mkdir -p "$HOME/.config/voice-reps"
   openssl enc -d -aes-256-cbc -pbkdf2 -in signing/voice-reps.keystore.enc \
     -out "$HOME/.config/voice-reps/signing.p12" -pass file:/path/to/private-vault-file
   ```

3. Set the following environment variables from the vault value and local path, without echoing secret values:
   - `VOICE_REPS_KEYSTORE_FILE=$HOME/.config/voice-reps/signing.p12`
   - `VOICE_REPS_STORE_PASSWORD` = vault passphrase
   - `VOICE_REPS_KEY_ALIAS=voice-reps`
   - `VOICE_REPS_KEY_PASSWORD` = vault passphrase
4. Run `./gradlew assembleDebug` or `./gradlew assembleRelease` with JDK 17. Delete the decrypted keystore after the build. Never commit or attach it.

The Gradle build fails if any signing variable is missing. This avoids silently creating a different debug signature in a fresh sandbox. All APKs signed with this key can update one another, but cannot update existing APKs signed with the old sandbox debug keys. Those older installs require uninstalling first; Android removes app-private files on uninstall, including downloaded models and saved voice profiles.
