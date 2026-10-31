# OpenJDK JSON backport

The relocated JSON API and implementation under `com/netflix/tools/json` are GPLv2 with the Classpath exception. The `internal/LazyValue.java` compatibility helper uses the same license and exception. The remaining Netflix application sources retain their stated licenses.

Upstream revision: `93bfd596427c2c40f0c1da09d4397e2aed18b4fe` in https://github.com/openjdk/jdk, source root `src/jdk.incubator.json/share/classes/jdk/incubator/json`.

Modifications: package relocation, replacement of the newer lazy-constant facility with a JDK 25 compatibility helper, and conversion of OpenJDK build-configured Javadoc tags to ordinary headings. No upstream tests or build infrastructure are included.

Matching sources accompany binary releases. The module has only JDK production dependencies; no annotation processor, native code, preview flag, or external JSON library is required.
