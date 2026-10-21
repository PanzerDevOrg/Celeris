# tools

Helpers for building Celeris in a fresh cloud container (they are not part of the mod).

- `setup-env.sh`: installs JDK 25 (Gradle needs it; the project toolchains stay on 21) and links `../panzer-build-logic`. Use `source tools/setup-env.sh`.
- `gradle-retry.sh`: `./gradlew` with backoff when Maven Central answers 429. Real compile/test failures are not retried. `CELERIS_MIRROR=1` also routes Central through Google's mirror.
- `mirror.init.gradle`: the Gradle init script behind that option.

```bash
source tools/setup-env.sh
CELERIS_MIRROR=1 tools/gradle-retry.sh test -Ppanzer.loaders=neoforge
```
