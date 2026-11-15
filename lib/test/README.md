# lib/test — test-only libraries (not included)

`ant test` needs JUnit 4 and Hamcrest Core. They are third-party binaries and are **not included** in this
repository or in the release zip. To run the tests, place these two files in this folder (`lib/test/`) under
exactly these names:

| File name | Library and version | Maven Central coordinates | SHA-256 of the copy the tests were run with |
|---|---|---|---|
| `junit-4.12.jar` | JUnit 4.12 | `junit:junit:4.12` | `59721f0805e223d84b90677887d9ff567dc534d7c502ca903c0c2b17f05c116a` |
| `hamcrest-core-1.3.jar` | Hamcrest Core 1.3 | `org.hamcrest:hamcrest-core:1.3` | `66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9` |

Download locations on Maven Central (plain text):

    https://repo1.maven.org/maven2/junit/junit/4.12/junit-4.12.jar
    https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar

Check a downloaded file with `certutil -hashfile junit-4.12.jar SHA256` (Windows) and compare it with the table.
The jars keep their own licenses (JUnit 4: Eclipse Public License 1.0; Hamcrest Core 1.3: BSD License).
`.gitignore` keeps `lib/test/*.jar` out of git. `ant jar`, `ant smoke` and `ant dist` do not need them; `ant test`
stops early and names the missing files when they are absent.
