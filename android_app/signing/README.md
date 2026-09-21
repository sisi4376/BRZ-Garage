# BRZ Garage debug signing key

Debug APKs must use the project-local `brz-debug.keystore`. It is intentionally
ignored by Git because it contains the private update key.

Expected signer certificate SHA-256:

`08bab8a9c07f8e5b0efcfb39f6c9ee56bea08021c7e5b231be7efff0aaa43f50`

The Gradle build fails when the keystore is absent instead of silently creating
or selecting a different per-user debug key. Restore the authorized keystore
from the local backup before building an installable APK.
